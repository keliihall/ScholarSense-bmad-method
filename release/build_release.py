#!/usr/bin/env python3
"""Build the same release twice in clean Git roots and publish only matching bytes."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from pathlib import Path
from pathlib import PurePosixPath
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from check_release_source import build_git_inventory  # noqa: E402
from release_json import canonical_bytes, canonical_sha256, load_json, schema_issues  # noqa: E402
from archive import create_normalized_tar_gz  # noqa: E402


BACKEND_NAME = "scholarsense-backend.jar"
FRONTEND_NAME = "scholarsense-frontend.tar.gz"
FIXED_BUILD_ENVIRONMENT = {
    "LANG": "C",
    "LC_ALL": "C",
    "SOURCE_DATE_EPOCH": "1784419200",
    "TZ": "UTC",
}
FORBIDDEN_ARTIFACT_SEGMENTS = {
    ".git",
    ".vite",
    "__pycache__",
    "coverage",
    "node_modules",
    "playwright-report",
    "secrets",
    "surefire-reports",
    "test-results",
}


def artifact_set_sha256(artifacts: list[dict[str, Any]]) -> str:
    comparable = sorted(artifacts, key=lambda item: item["name"])
    return hashlib.sha256(canonical_bytes(comparable)).hexdigest()


def validate_build_manifest(manifest: dict[str, Any]) -> list[str]:
    issues: list[str] = []
    attempts = manifest.get("attempts", [])
    if not isinstance(attempts, list) or len(attempts) != 2:
        issues.append("BUILD_ATTEMPT_COUNT_INVALID")
    else:
        if [item.get("attempt") for item in attempts] != [1, 2]:
            issues.append("BUILD_ATTEMPT_ID_INVALID")
        if len({item.get("buildInputSha256") for item in attempts}) != 1:
            issues.append("BUILD_INPUT_DIGEST_DRIFT")
        if len({item.get("artifactSetSha256") for item in attempts}) != 1:
            issues.append("BUILD_ARTIFACT_SET_DRIFT")
    names = {item.get("name") for item in manifest.get("artifacts", []) if isinstance(item, dict)}
    if names != {BACKEND_NAME, FRONTEND_NAME}:
        issues.append("BUILD_ARTIFACT_NAME_INVALID")
    if any("SNAPSHOT" in str(name) for name in names):
        issues.append("BUILD_ARTIFACT_SNAPSHOT_NAME_FORBIDDEN")
    return sorted(set(issues))


def artifact_content_issues(backend: Path, frontend: Path) -> list[str]:
    issues: list[str] = []

    def check(name: str, artifact: str) -> None:
        path = PurePosixPath(name)
        lowered = {part.lower() for part in path.parts}
        filename = path.name.lower()
        if path.is_absolute() or ".." in path.parts or "\\" in name:
            issues.append(f"ARTIFACT_PATH_UNSAFE: {artifact}: {name}")
        if lowered & FORBIDDEN_ARTIFACT_SEGMENTS:
            issues.append(f"ARTIFACT_GENERATED_OR_SECRET_PATH: {artifact}: {name}")
        if filename == ".env" or filename.startswith(".env.") or filename.endswith((".key", ".pem")):
            issues.append(f"ARTIFACT_SECRET_FILE_FORBIDDEN: {artifact}: {name}")

    try:
        with zipfile.ZipFile(backend) as archive:
            for name in archive.namelist():
                check(name, BACKEND_NAME)
    except (OSError, zipfile.BadZipFile):
        issues.append("BACKEND_ARTIFACT_INVALID_ZIP")
    try:
        with tarfile.open(frontend, "r:gz") as archive:
            for member in archive.getmembers():
                check(member.name, FRONTEND_NAME)
                if member.issym() or member.islnk():
                    issues.append(f"FRONTEND_ARTIFACT_LINK_FORBIDDEN: {member.name}")
                elif not member.isfile() and not member.isdir():
                    issues.append(f"FRONTEND_ARTIFACT_TYPE_FORBIDDEN: {member.name}")
    except (OSError, tarfile.TarError):
        issues.append("FRONTEND_ARTIFACT_INVALID_TAR")
    return sorted(set(issues))


def _manifest(
    commit: str,
    source_manifest_sha256: str,
    toolchain_lock_sha256: str,
    backend_lock_sha256: str,
    frontend_lock_sha256: str,
    build_input_sha256: str,
    artifacts: list[dict[str, Any]],
) -> dict[str, Any]:
    artifact_digest = artifact_set_sha256(artifacts)
    return {
        "version": "BUILD-MANIFEST-1.0.0",
        "sourceCommit": commit,
        "sourceManifestSha256": source_manifest_sha256,
        "toolchainLockSha256": toolchain_lock_sha256,
        "backendLockSha256": backend_lock_sha256,
        "frontendLockSha256": frontend_lock_sha256,
        "attempts": [
            {"attempt": 1, "buildInputSha256": build_input_sha256, "artifactSetSha256": artifact_digest},
            {"attempt": 2, "buildInputSha256": build_input_sha256, "artifactSetSha256": artifact_digest},
        ],
        "artifacts": artifacts,
    }


def _run(arguments: list[str], cwd: Path, environment: dict[str, str] | None = None) -> str:
    result = subprocess.run(
        arguments,
        cwd=cwd,
        env=environment,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    sys.stdout.write(result.stdout)
    if result.returncode != 0:
        raise RuntimeError(f"RELEASE_COMMAND_FAILED: {' '.join(arguments)}")
    return result.stdout


def _git(root: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", *arguments],
        cwd=root,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        raise RuntimeError(f"RELEASE_GIT_FAILED: {' '.join(arguments)}")
    return result.stdout.strip()


def _digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _artifact(path: Path, name: str, media_type: str) -> dict[str, Any]:
    return {
        "name": name,
        "mediaType": media_type,
        "size": path.stat().st_size,
        "binarySha256": _digest(path),
    }


def build_release(root: Path, destination: Path) -> dict[str, Any]:
    project_root = root.resolve()
    output = destination.resolve()
    if output.exists():
        raise RuntimeError("RELEASE_OUTPUT_ALREADY_EXISTS")
    dirty = _git(project_root, "status", "--porcelain", "--untracked-files=all")
    if dirty:
        raise RuntimeError("SOURCE_WORKSPACE_DIRTY")
    commit = _git(project_root, "rev-parse", "HEAD")
    if len(commit) != 40:
        raise RuntimeError("SOURCE_COMMIT_INVALID")
    source_manifest_sha256 = build_git_inventory(project_root, commit)["normalizedManifestSha256"]
    toolchain_lock = load_json(project_root / "contracts/release/toolchain-lock-1.0.0.json")
    backend_lock = load_json(project_root / "contracts/release/backend-lock-1.0.0.json")
    toolchain_lock_sha256 = canonical_sha256(toolchain_lock)
    backend_lock_sha256 = canonical_sha256(backend_lock)
    frontend_lock_sha256 = _digest(project_root / "frontend/package-lock.json")
    build_input_sha256 = hashlib.sha256(
        (
            f"{commit}\n{source_manifest_sha256}\n{toolchain_lock_sha256}\n"
            f"{backend_lock_sha256}\n{frontend_lock_sha256}\n"
        ).encode("ascii")
    ).hexdigest()

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".release-build-", dir=output.parent) as temporary:
        temporary_root = Path(temporary)
        attempt_artifacts: list[list[dict[str, Any]]] = []
        attempt_paths: list[dict[str, Path]] = []
        candidate_manifests: list[dict[str, Any]] = []
        for number in (1, 2):
            attempt = temporary_root / f"attempt-{number}"
            _run(["git", "clone", "--quiet", "--shared", str(project_root), str(attempt)], project_root)
            _run(["git", "checkout", "--quiet", "--detach", commit], attempt)
            environment = os.environ.copy()
            environment.update(FIXED_BUILD_ENVIRONMENT)
            environment["FRONTEND_RELEASE_OUTPUT"] = str(attempt / "release-out/frontend-dist")
            environment["RELEASE_BUILD_ATTEMPT"] = str(number)
            _run([str(attempt / "scripts/verify_core.sh")], attempt, environment)
            backend = attempt / f"backend/target/{BACKEND_NAME}"
            frontend = attempt / f"release-out/{FRONTEND_NAME}"
            create_normalized_tar_gz(attempt / "release-out/frontend-dist", frontend)
            content_issues = artifact_content_issues(backend, frontend)
            if content_issues:
                raise RuntimeError(content_issues[0])
            if _git(attempt, "status", "--porcelain", "--untracked-files=all"):
                raise RuntimeError(f"BUILD_SOURCE_OR_LOCK_MUTATION: attempt-{number}")
            artifacts = [
                _artifact(backend, BACKEND_NAME, "application/java-archive"),
                _artifact(frontend, FRONTEND_NAME, "application/gzip"),
            ]
            attempt_artifacts.append(artifacts)
            attempt_paths.append({BACKEND_NAME: backend, FRONTEND_NAME: frontend})
            candidate_manifests.append(
                _manifest(
                    commit,
                    source_manifest_sha256,
                    toolchain_lock_sha256,
                    backend_lock_sha256,
                    frontend_lock_sha256,
                    build_input_sha256,
                    artifacts,
                )
            )
        if attempt_artifacts[0] != attempt_artifacts[1]:
            raise RuntimeError("REPRODUCIBLE_ARTIFACT_DIGEST_MISMATCH")
        if canonical_sha256(candidate_manifests[0]) != canonical_sha256(candidate_manifests[1]):
            raise RuntimeError("REPRODUCIBLE_BUILD_MANIFEST_DIGEST_MISMATCH")

        manifest = candidate_manifests[0]
        issues = validate_build_manifest(manifest)
        schema = load_json(project_root / "contracts/release/build-manifest.schema.json")
        issues.extend(schema_issues(manifest, schema))
        if issues:
            raise RuntimeError(issues[0])
        staging = temporary_root / "complete"
        staging.mkdir()
        for name, path in attempt_paths[0].items():
            shutil.copyfile(path, staging / name)
        (staging / "build-manifest.json").write_bytes(canonical_bytes(manifest) + b"\n")
        os.replace(staging, output)
        return manifest


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) >= 2 else PROJECT_ROOT
    destination = Path(argv[2]) if len(argv) >= 3 else root / "release-out/local-release"
    try:
        manifest = build_release(root, destination)
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as error:
        print(error, file=sys.stderr)
        return 1
    print(f"build-release: PASS source={manifest['sourceCommit']} artifactSet={manifest['attempts'][0]['artifactSetSha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
