#!/usr/bin/env python3
"""Generate additive release/evidence v10 schemas without mutating v9."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / "contracts/release"


def _write(path: Path, value: dict) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def main() -> int:
    release = json.loads((
        CONTRACTS / "release-manifest-9.schema.json"
    ).read_text(encoding="utf-8"))
    release["$id"] = (
        "https://scholarsense.suda.edu.cn/contracts/release/"
        "release-manifest-10.schema.json"
    )
    release["properties"]["version"] = {"const": "RELEASE-MANIFEST-10.0.0"}
    release["properties"]["runtimeEvidence"].update({"minItems": 15, "maxItems": 15})
    release["properties"]["controlledInputs"].update({"minItems": 48, "maxItems": 48})
    claims = release["$defs"]["runtime"]["properties"]["runtimeEvidenceClaim"]["enum"]
    claims.append("story-2.6a-observability-closure")
    _write(CONTRACTS / "release-manifest-10.schema.json", release)

    evidence = json.loads((
        CONTRACTS / "evidence-index-9.schema.json"
    ).read_text(encoding="utf-8"))
    evidence["$id"] = (
        "https://scholarsense.suda.edu.cn/contracts/release/"
        "evidence-index-10.schema.json"
    )
    evidence["properties"]["version"] = {"const": "EVIDENCE-INDEX-10.0.0"}
    _write(CONTRACTS / "evidence-index-10.schema.json", evidence)
    print("release-v10-contracts: generated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
