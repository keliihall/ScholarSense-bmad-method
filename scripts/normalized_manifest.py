#!/usr/bin/env python3
"""Create a deterministic source/output-structure manifest without generated artifacts."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path


INCLUDED_ROOTS = ("backend", "frontend", "contracts", "deploy", "scripts")
INCLUDED_FILES = ("README.md", ".gitignore", ".editorconfig", "_bmad/scripts/with_pab_toolchain.sh")
EXCLUDED_PARTS = {
    "target",
    "__pycache__",
    ".git",
    "node_modules",
    "dist",
    "coverage",
    "playwright-report",
    "test-results",
}
EXCLUDED_SUFFIXES = {".pyc", ".class", ".jar"}
TEXT_SUFFIXES = {
    ".java", ".ts", ".mts", ".cts", ".mjs", ".cjs", ".vue", ".css", ".html",
    ".json", ".yml", ".yaml", ".properties", ".xml", ".md", ".csv", ".example",
    ".sh", ".cmd",
}


def build_manifest(project_root: Path) -> dict:
    root = project_root.resolve()
    paths: set[Path] = set()
    for relative_root in INCLUDED_ROOTS:
        candidate = root / relative_root
        _reject_symlink_ancestry(root, candidate, relative_root)
        if candidate.is_dir():
            for path in candidate.rglob("*"):
                relative = path.relative_to(root)
                if any(part in EXCLUDED_PARTS for part in relative.parts):
                    continue
                if path.is_symlink():
                    raise ValueError(
                        f"MANIFEST_SYMLINK_FORBIDDEN: {relative.as_posix()}"
                    )
                if path.is_file():
                    paths.add(path)
    for relative_file in INCLUDED_FILES:
        candidate = root / relative_file
        _reject_symlink_ancestry(root, candidate, relative_file)
        if candidate.is_file():
            paths.add(candidate)

    files: list[dict[str, str]] = []
    combined = hashlib.sha256()
    for path in sorted(paths, key=lambda value: value.relative_to(root).as_posix()):
        relative = path.relative_to(root)
        if any(part in EXCLUDED_PARTS for part in relative.parts) or path.suffix in EXCLUDED_SUFFIXES:
            continue
        payload = path.read_bytes()
        if path.suffix in TEXT_SUFFIXES or path.name in {
            "mvnw", ".gitignore", ".editorconfig", ".npmrc",
        }:
            payload = payload.replace(b"\r\n", b"\n")
        digest = hashlib.sha256(payload).hexdigest()
        name = relative.as_posix()
        files.append({"path": name, "sha256": digest})
        combined.update(name.encode("utf-8") + b"\0" + digest.encode("ascii") + b"\n")
    return {
        "schemaVersion": 1,
        "fileCount": len(files),
        "sha256": combined.hexdigest(),
        "files": files,
    }


def _reject_symlink_ancestry(root: Path, candidate: Path, display: str) -> None:
    current = root
    for part in candidate.relative_to(root).parts:
        current = current / part
        if current.is_symlink():
            raise ValueError(f"MANIFEST_SYMLINK_FORBIDDEN: {display}")


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) >= 2 else Path(".")
    try:
        manifest = build_manifest(root)
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1
    if "--summary" in argv:
        print(f"manifest-file-count={manifest['fileCount']}")
        print(f"manifest-sha256={manifest['sha256']}")
    else:
        print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
