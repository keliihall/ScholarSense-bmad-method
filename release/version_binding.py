#!/usr/bin/env python3
"""Create-only global releaseVersion -> ReleaseManifest digest binding."""

from __future__ import annotations

import base64
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Protocol, Sequence


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from release_json import canonical_bytes, parse_json_bytes  # noqa: E402


SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


class VersionBindingError(RuntimeError):
    pass


class ManifestVersionLedger(Protocol):
    def read(self, release_version: str) -> str | None: ...

    def create(self, release_version: str, manifest_sha256: str) -> bool: ...


class ManifestVersionBindingService:
    def __init__(self, ledger: ManifestVersionLedger) -> None:
        self.ledger = ledger

    def bind(self, release_version: str, manifest_sha256: str) -> str:
        if not SEMVER.fullmatch(release_version) or not HEX64.fullmatch(manifest_sha256):
            raise VersionBindingError("RELEASE_VERSION_BINDING_INPUT_INVALID")
        existing = self.ledger.read(release_version)
        if existing is not None:
            if existing != manifest_sha256:
                raise VersionBindingError("RELEASE_VERSION_MANIFEST_CONFLICT")
            return "replayed"
        if self.ledger.create(release_version, manifest_sha256):
            return "bound"
        winner = self.ledger.read(release_version)
        if winner != manifest_sha256:
            raise VersionBindingError("RELEASE_VERSION_MANIFEST_CONFLICT")
        return "replayed"


class InMemoryManifestVersionLedger:
    def __init__(self) -> None:
        self.values: dict[str, str] = {}

    def read(self, release_version: str) -> str | None:
        return self.values.get(release_version)

    def create(self, release_version: str, manifest_sha256: str) -> bool:
        if release_version in self.values:
            return False
        self.values[release_version] = manifest_sha256
        return True


class GitHubManifestVersionLedger:
    def __init__(self, repository: str, gh: str = "gh") -> None:
        if not REPOSITORY.fullmatch(repository):
            raise VersionBindingError("RELEASE_VERSION_BINDING_REPOSITORY_INVALID")
        self.repository = repository
        self.gh = gh

    def _run(self, arguments: Sequence[str], payload: Any | None = None) -> subprocess.CompletedProcess[str]:
        command = [self.gh, "api", *arguments]
        stdin = None
        if payload is not None:
            command.extend(["--input", "-"])
            stdin = json.dumps(payload, separators=(",", ":"))
        return subprocess.run(
            command,
            cwd=PROJECT_ROOT,
            input=stdin,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    @staticmethod
    def _json(result: subprocess.CompletedProcess[str], code: str) -> Any:
        if result.returncode != 0:
            raise VersionBindingError(code)
        try:
            return json.loads(result.stdout)
        except json.JSONDecodeError as error:
            raise VersionBindingError(code) from error

    def _ref(self, release_version: str) -> str:
        if not SEMVER.fullmatch(release_version):
            raise VersionBindingError("RELEASE_VERSION_BINDING_INPUT_INVALID")
        return f"refs/tags/release-manifest/{release_version}"

    def read(self, release_version: str) -> str | None:
        relative = self._ref(release_version).removeprefix("refs/")
        ref = self._run([f"repos/{self.repository}/git/ref/{relative}"])
        if ref.returncode != 0:
            if "HTTP 404" in ref.stderr:
                return None
            raise VersionBindingError("RELEASE_VERSION_BINDING_REF_READ_FAILED")
        try:
            commit_oid = self._json(ref, "RELEASE_VERSION_BINDING_REF_INVALID")["object"]["sha"]
            commit = self._json(
                self._run([f"repos/{self.repository}/git/commits/{commit_oid}"]),
                "RELEASE_VERSION_BINDING_COMMIT_INVALID",
            )
            tree = self._json(
                self._run([f"repos/{self.repository}/git/trees/{commit['tree']['sha']}"]),
                "RELEASE_VERSION_BINDING_TREE_INVALID",
            )
            entry = next(item for item in tree["tree"] if item.get("path") == "manifest-binding.json")
            blob = self._json(
                self._run([f"repos/{self.repository}/git/blobs/{entry['sha']}"]),
                "RELEASE_VERSION_BINDING_BLOB_INVALID",
            )
            raw = base64.b64decode(str(blob["content"]).replace("\n", ""), validate=True)
            document = parse_json_bytes(raw)
        except (KeyError, StopIteration, TypeError, ValueError) as error:
            raise VersionBindingError("RELEASE_VERSION_BINDING_RECORD_INVALID") from error
        if not isinstance(document, dict) or raw != canonical_bytes(document) + b"\n":
            raise VersionBindingError("RELEASE_VERSION_BINDING_RECORD_NONCANONICAL")
        if document.get("releaseVersion") != release_version or not HEX64.fullmatch(str(document.get("manifestSha256", ""))):
            raise VersionBindingError("RELEASE_VERSION_BINDING_RECORD_INVALID")
        return str(document["manifestSha256"])

    def create(self, release_version: str, manifest_sha256: str) -> bool:
        record = {"releaseVersion": release_version, "manifestSha256": manifest_sha256}
        payload = canonical_bytes(record) + b"\n"
        blob = self._json(
            self._run(
                ["--method", "POST", f"repos/{self.repository}/git/blobs"],
                {"content": base64.b64encode(payload).decode("ascii"), "encoding": "base64"},
            ),
            "RELEASE_VERSION_BINDING_BLOB_CREATE_FAILED",
        )
        tree = self._json(
            self._run(
                ["--method", "POST", f"repos/{self.repository}/git/trees"],
                {"tree": [{"path": "manifest-binding.json", "mode": "100644", "type": "blob", "sha": blob["sha"]}]},
            ),
            "RELEASE_VERSION_BINDING_TREE_CREATE_FAILED",
        )
        commit = self._json(
            self._run(
                ["--method", "POST", f"repos/{self.repository}/git/commits"],
                {"message": f"bind release manifest {release_version}", "tree": tree["sha"]},
            ),
            "RELEASE_VERSION_BINDING_COMMIT_CREATE_FAILED",
        )
        created = self._run(
            ["--method", "POST", f"repos/{self.repository}/git/refs"],
            {"ref": self._ref(release_version), "sha": commit["sha"]},
        )
        if created.returncode == 0:
            return True
        if self.read(release_version) is not None:
            return False
        raise VersionBindingError("RELEASE_VERSION_BINDING_CAS_FAILED")


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print("usage: version_binding.py RELEASE_VERSION MANIFEST_SHA256 OWNER/REPO", file=sys.stderr)
        return 2
    try:
        result = ManifestVersionBindingService(GitHubManifestVersionLedger(argv[3])).bind(argv[1], argv[2])
    except (OSError, VersionBindingError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print(f"release-version-binding: {result} ({argv[1]} -> {argv[2]})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
