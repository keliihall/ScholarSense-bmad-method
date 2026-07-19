#!/usr/bin/env python3
"""Materialize the locked Maven bootstrap plugin graph before executing it."""

from __future__ import annotations

import hashlib
import os
import sys
import tempfile
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "release"))
sys.path.insert(0, str(ROOT / "scripts"))

from backend_lock import CENTRAL, bootstrap_plugin_artifacts  # noqa: E402
from release_json import load_json  # noqa: E402


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _local_path(repository: Path, uri: str) -> Path:
    if not uri.startswith(CENTRAL):
        raise ValueError("BACKEND_LOCK_BOOTSTRAP_PLUGIN_SOURCE_INVALID")
    relative = Path(uri.removeprefix(CENTRAL))
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError("BACKEND_LOCK_BOOTSTRAP_PLUGIN_SOURCE_INVALID")
    return repository / relative


def _materialize(uri: str, expected_sha256: str, destination: Path) -> None:
    if destination.is_file():
        if _sha256(destination) != expected_sha256:
            raise ValueError(f"BACKEND_LOCK_BOOTSTRAP_PLUGIN_LOCAL_DIGEST_MISMATCH: {destination}")
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as output:
            temporary = Path(output.name)
            with urllib.request.urlopen(uri, timeout=60) as response:  # noqa: S310 - URI is Maven Central locked above
                if response.geturl() != uri:
                    raise ValueError("BACKEND_LOCK_BOOTSTRAP_PLUGIN_REDIRECT_FORBIDDEN")
                while block := response.read(1024 * 1024):
                    output.write(block)
        if _sha256(temporary) != expected_sha256:
            raise ValueError("BACKEND_LOCK_BOOTSTRAP_PLUGIN_DOWNLOAD_DIGEST_MISMATCH")
        os.replace(temporary, destination)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def prepare(project_root: Path, repository: Path) -> int:
    lock = load_json(project_root / "contracts/release/backend-lock-1.0.0.json")
    artifacts = bootstrap_plugin_artifacts(lock)
    for artifact in artifacts:
        _materialize(artifact["pomSourceUri"], artifact["pomSha256"], _local_path(repository, artifact["pomSourceUri"]))
        _materialize(artifact["sourceUri"], artifact["binarySha256"], _local_path(repository, artifact["sourceUri"]))
    return len(artifacts)


def main(argv: list[str]) -> int:
    project_root = Path(argv[1]).resolve() if len(argv) >= 2 else ROOT
    repository = Path(argv[2]).resolve() if len(argv) >= 3 else Path.home() / ".m2/repository"
    try:
        count = prepare(project_root, repository)
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print(f"maven-bootstrap-plugin: PASS ({count} locked artifacts)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
