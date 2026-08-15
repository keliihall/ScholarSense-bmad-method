#!/usr/bin/env python3
"""Generate the digest-bound observability runtime profile bundle."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "contracts/config/observability-runtime-bundle-1.0.0.json"


def _reference(environment: str) -> dict[str, str]:
    relative = Path(
        f"contracts/config/observability-runtime-{environment}-1.0.0.json"
    )
    raw = (ROOT / relative).read_bytes()
    profile = json.loads(raw)
    return {
        "environment": environment,
        "role": profile["role"],
        "version": profile["profileVersion"],
        "path": relative.as_posix(),
        "binarySha256": hashlib.sha256(raw).hexdigest(),
    }


def main() -> int:
    contract = Path(
        "contracts/observability/observability-contract-1.0.0.json"
    )
    document = {
        "$schema": "./observability-runtime-bundle.schema.json",
        "version": "OBSERVABILITY-RUNTIME-BUNDLE-1.0.0",
        "contract": {
            "version": "OBS-1.0.0",
            "path": contract.as_posix(),
            "binarySha256": hashlib.sha256((ROOT / contract).read_bytes()).hexdigest(),
        },
        "profiles": [
            _reference(environment)
            for environment in ("dev", "test", "stage", "prod")
        ],
        "supportedRoles": ["web-api", "worker"],
        "exportPolicy": {
            "businessOutcomeAuthority": "forbidden",
            "failureMode": "drop-telemetry-keep-business-truth",
            "maxQueueSize": 2048,
            "networkInsideOwnerTransaction": False,
        },
    }
    OUTPUT.write_text(
        json.dumps(document, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print("observability-runtime-bundle: generated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
