#!/usr/bin/env python3
"""CLI for the one-way ReleaseManifest -> signature -> EvidenceIndex lifecycle."""

from __future__ import annotations

import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from manifests import create_evidence_index, create_release_manifest, write_frozen_document  # noqa: E402
from release_json import load_json, schema_issues  # noqa: E402


def _schema_issues(name: str, document: dict) -> list[str]:
    schema = load_json(PROJECT_ROOT / f"contracts/release/{name}.schema.json")
    return schema_issues(document, schema)


def main(argv: list[str]) -> int:
    if len(argv) != 4 or argv[1] not in {"release", "index"}:
        print("usage: generate_manifests.py {release|index} INPUT.json OUTPUT.json", file=sys.stderr)
        return 2
    try:
        payload = load_json(Path(argv[2]))
        if not isinstance(payload, dict):
            raise ValueError("MANIFEST_INPUT_OBJECT_REQUIRED")
        if argv[1] == "release":
            document = create_release_manifest(payload)
            contract = "release-manifest"
        else:
            document = create_evidence_index(
                payload["releaseManifest"],
                payload["releaseManifestRef"],
                payload["manifestSignature"],
                payload["createdAt"],
            )
            contract = "evidence-index"
        issues = _schema_issues(contract, document)
        if issues:
            raise ValueError(issues[0])
        write_frozen_document(Path(argv[3]), document)
    except (KeyError, OSError, RuntimeError, TypeError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print(f"generate-{contract}: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
