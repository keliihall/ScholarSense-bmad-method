#!/usr/bin/env python3
"""Build and verify a release-source inventory from immutable Git objects."""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

from release_json import load_json


INVENTORY_VERSION = "RELEASE-SOURCE-INVENTORY-1.0.0"
INVENTORY_PATH = "contracts/release/release-source-inventory-1.0.0.json"
INCLUDED_PREFIXES = (
    ".github/",
    "backend/",
    "contracts/",
    "deploy/",
    "docs/architecture/adr/",
    "frontend/",
    "release/",
    "scripts/",
)
INCLUDED_FILES = {
    ".editorconfig",
    ".gitignore",
    "README.md",
    "_bmad/scripts/with_pab_toolchain.sh",
}
EXCLUDED_PARTS = {
    ".git",
    "__pycache__",
    "coverage",
    "dist",
    "node_modules",
    "playwright-report",
    "target",
    "test-results",
}
EXCLUDED_SUFFIXES = {".class", ".jar", ".pyc"}
EXCLUDED_PREFIXES = (
    "release-out/",
    "release/evidence/",
    "release/generated/",
)
TEXT_SUFFIXES = {
    ".cmd", ".css", ".csv", ".example", ".html", ".java", ".js", ".json",
    ".jsx", ".md", ".mjs", ".mts", ".properties", ".sh", ".ts", ".tsx",
    ".vue", ".xml", ".yaml", ".yml",
}
OID = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^[0-9a-f]{64}$")


def _git(root: Path, *arguments: str, text: bool = True) -> subprocess.CompletedProcess[Any]:
    return subprocess.run(
        ["git", *arguments],
        cwd=root,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=text,
    )


def _included(path: str) -> bool:
    candidate = Path(path)
    if path == INVENTORY_PATH:
        return False
    if any(path.startswith(prefix) for prefix in EXCLUDED_PREFIXES):
        return False
    if any(part in EXCLUDED_PARTS for part in candidate.parts):
        return False
    if candidate.suffix in EXCLUDED_SUFFIXES:
        return False
    return path in INCLUDED_FILES or any(path.startswith(prefix) for prefix in INCLUDED_PREFIXES)


def source_scope_issues(files: list[dict[str, str]]) -> list[str]:
    paths = {item.get("path") for item in files if isinstance(item, dict)}
    required = {
        ".github/CODEOWNERS",
        ".github/workflows/ci.yml",
        ".github/workflows/platform-probe.yml",
        ".github/workflows/release.yml",
        ".github/workflows/rollback.yml",
        "backend/.mvn/wrapper/maven-wrapper.properties",
        "backend/mvnw",
        "backend/mvnw.cmd",
        "backend/pom.xml",
        "contracts/release/release-manifest.schema.json",
        "contracts/release/evidence-index.schema.json",
        "docs/architecture/adr/ci-supply-chain-baseline-cisb-1.0.0.md",
        "frontend/package-lock.json",
        "frontend/package.json",
        "release/build_release.py",
        "release/manifests.py",
        "release/promotion.py",
        "release/verifier.py",
        "scripts/check_promotion.py",
        "scripts/check_release_manifests.py",
        "scripts/check_release_workflows.py",
        "scripts/install-release-tools.sh",
        "scripts/promote-release.sh",
        "scripts/read_promotion.py",
        "scripts/rollback-release.sh",
        "scripts/verify-release.sh",
        "scripts/verify_core.sh",
    }
    issues = [f"RELEASE_SOURCE_REQUIRED_PATH_MISSING: {path}" for path in sorted(required - paths)]
    if INVENTORY_PATH in paths:
        issues.append("RELEASE_SOURCE_INVENTORY_SELF_REFERENCE")
    dynamic = sorted(
        str(path) for path in paths
        if isinstance(path, str) and any(path.startswith(prefix) for prefix in EXCLUDED_PREFIXES)
    )
    issues.extend(f"RELEASE_SOURCE_DYNAMIC_OUTPUT_INCLUDED: {path}" for path in dynamic)
    return issues


def build_git_inventory(project_root: Path, revision: str) -> dict[str, Any]:
    root = project_root.resolve()
    commit_result = _git(root, "rev-parse", f"{revision}^{{commit}}")
    if commit_result.returncode != 0:
        raise ValueError("RELEASE_SOURCE_COMMIT_UNREADABLE")
    commit = commit_result.stdout.strip()
    tree_result = _git(root, "rev-parse", f"{commit}^{{tree}}")
    if tree_result.returncode != 0:
        raise ValueError("RELEASE_SOURCE_TREE_UNREADABLE")
    tree_oid = tree_result.stdout.strip()
    listing = _git(root, "ls-tree", "-r", "-z", "--full-tree", commit, text=False)
    if listing.returncode != 0:
        raise ValueError("RELEASE_SOURCE_TREE_UNREADABLE")

    files: list[dict[str, str]] = []
    combined = hashlib.sha256()
    for record in listing.stdout.split(b"\0"):
        if not record:
            continue
        metadata, raw_path = record.split(b"\t", 1)
        mode, kind, blob_oid = metadata.decode("ascii").split(" ")
        path = raw_path.decode("utf-8")
        if not _included(path):
            continue
        if mode == "120000" or kind != "blob":
            raise ValueError(f"RELEASE_SOURCE_SYMLINK_FORBIDDEN: {path}")
        blob = _git(root, "cat-file", "blob", blob_oid, text=False)
        if blob.returncode != 0:
            raise ValueError(f"RELEASE_SOURCE_BLOB_UNREADABLE: {path}")
        payload = blob.stdout
        candidate = Path(path)
        if candidate.suffix.lower() in TEXT_SUFFIXES or candidate.name in {
            ".editorconfig", ".gitignore", ".npmrc", "CODEOWNERS", "mvnw",
        }:
            payload = payload.replace(b"\r\n", b"\n")
        digest = hashlib.sha256(payload).hexdigest()
        files.append({"path": path, "sha256": digest})
        combined.update(path.encode("utf-8") + b"\0" + digest.encode("ascii") + b"\n")
    return {
        "sourceCommit": commit,
        "gitTreeOid": tree_oid,
        "fileCount": len(files),
        "normalizedManifestSha256": combined.hexdigest(),
        "files": files,
    }


def validate_release_source_inventory(document: dict[str, Any], project_root: Path) -> list[str]:
    issues: list[str] = []
    if document.get("version") != INVENTORY_VERSION:
        issues.append("RELEASE_SOURCE_INVENTORY_VERSION_INVALID")
    if document.get("repositoryUrl") != "https://github.com/keliihall/ScholarSense-bmad-method":
        issues.append("RELEASE_SOURCE_REPOSITORY_INVALID")
    commit = document.get("sourceCommit")
    if not isinstance(commit, str) or not OID.fullmatch(commit):
        return sorted(set(issues + ["RELEASE_SOURCE_COMMIT_INVALID"]))
    if not OID.fullmatch(str(document.get("gitTreeOid", ""))):
        issues.append("RELEASE_SOURCE_TREE_INVALID")
    if not DIGEST.fullmatch(str(document.get("normalizedManifestSha256", ""))):
        issues.append("RELEASE_SOURCE_MANIFEST_DIGEST_INVALID")
    if document.get("inventoryExclusion") != INVENTORY_PATH:
        issues.append("RELEASE_SOURCE_INVENTORY_SELF_EXCLUSION_INVALID")
    try:
        actual = build_git_inventory(project_root, commit)
    except ValueError as error:
        return sorted(set(issues + [str(error)]))
    for field in ("gitTreeOid", "fileCount", "normalizedManifestSha256"):
        if document.get(field) != actual[field]:
            issues.append(f"RELEASE_SOURCE_{field.upper()}_MISMATCH")
    contains = _git(project_root, "branch", "-r", "--contains", commit)
    if contains.returncode != 0 or "origin/main" not in contains.stdout:
        issues.append("RELEASE_SOURCE_NOT_ON_REMOTE_MAIN")
    return sorted(set(issues))


def main(argv: list[str]) -> int:
    root = Path(argv[1]).resolve() if len(argv) >= 2 else Path(".").resolve()
    path = Path(argv[2]) if len(argv) >= 3 else root / INVENTORY_PATH
    try:
        document = load_json(path)
        if not isinstance(document, dict):
            raise ValueError("JSON_OBJECT_REQUIRED")
        issues = validate_release_source_inventory(document, root)
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print(f"release-source-inventory: PASS ({document['sourceCommit']}, {document['fileCount']} files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
