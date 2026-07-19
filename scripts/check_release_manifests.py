#!/usr/bin/env python3
"""Validate a frozen ReleaseManifest or its post-signature EvidenceIndex."""

from __future__ import annotations

import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "release"))

from manifests import evidence_index_issues, release_manifest_issues  # noqa: E402
from release_json import canonical_bytes, load_json, schema_issues  # noqa: E402


def main(argv: list[str]) -> int:
    if len(argv) != 4 or argv[1] not in {"release", "index"}:
        print("usage: check_release_manifests.py {release|index} DOCUMENT.json SUBJECT.json", file=sys.stderr)
        return 2
    try:
        document_path = Path(argv[2])
        document = load_json(document_path)
        subject = load_json(Path(argv[3]))
        if not isinstance(document, dict) or not isinstance(subject, dict):
            raise ValueError("MANIFEST_OBJECT_REQUIRED")
        contract = "release-manifest" if argv[1] == "release" else "evidence-index"
        schema = load_json(PROJECT_ROOT / f"contracts/release/{contract}.schema.json")
        issues = schema_issues(document, schema)
        issues.extend(
            release_manifest_issues(document, subject)
            if argv[1] == "release"
            else evidence_index_issues(document, subject)
        )
        if document_path.read_bytes() != canonical_bytes(document):
            issues.append("MANIFEST_NOT_CANONICAL_BYTES")
    except (OSError, TypeError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    if issues:
        print("\n".join(sorted(set(issues))), file=sys.stderr)
        return 1
    print(f"check-{contract}: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
