#!/usr/bin/env python3
"""Materialize full executable Story 1.6a negative payloads and refresh its lock."""

from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "contracts/identity-authority"
OUTPUT = BASE / "fixtures/negative/payloads-1.0.0.json"
LOCK = BASE / "identity-authority-contract-lock-1.0.0.json"
SIGNATURE = "sandbox-signature-v1"


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def payload_digest(value: dict[str, Any]) -> str:
    canonical = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return "sha256:" + digest(canonical)


def finalize(batch: dict[str, Any], index: int) -> dict[str, Any]:
    batch["batchId"] = f"019c1234-0000-7000-8000-{index:012d}"
    batch["correlationId"] = f"019c1234-0000-7000-8001-{index:012d}"
    batch["signatureDigest"] = "sha256:" + digest(SIGNATURE.encode("utf-8"))
    for offset, record in enumerate(batch["records"], start=1):
        record["eventId"] = (
            f"019c1234-0000-7000-8002-{index * 100 + offset:012d}"
        )
        record["payloadDigest"] = payload_digest(record["payload"])
    return batch


def record(
    kind: str, payload: dict[str, Any], version: int = 7
) -> dict[str, Any]:
    return {
        "eventId": "019c1234-0000-7000-8002-000000000000",
        "recordKind": kind,
        "sourceVersion": version,
        "effectiveFrom": payload["effectiveFrom"],
        "effectiveTo": payload["effectiveTo"],
        "payloadDigest": payload_digest(payload),
        "payload": payload,
    }


def case(
    payload: dict[str, Any],
    stage: str,
    *,
    checkpoint_version: int = 6,
    checkpoint_watermark: int = 6,
    signature_verified: bool = True,
    current_record_digest: str | None = None,
) -> dict[str, Any]:
    value: dict[str, Any] = {
        "executionStage": stage,
        "afterWatermark": payload["fromWatermark"],
        "checkpoint": {
            "sourceVersion": checkpoint_version,
            "sourceWatermark": checkpoint_watermark,
            "aggregateVersion": 2,
        },
        "signatureVerified": signature_verified,
        "detachedSignature": SIGNATURE,
        "payload": payload,
    }
    if current_record_digest is not None:
        value["currentRecord"] = {
            "sourceVersion": payload["sourceVersion"],
            "payloadDigest": current_record_digest,
        }
    return value


def main() -> None:
    base = json.loads(
        (BASE / "fixtures/valid/incremental-batch.json").read_text(
            encoding="utf-8"
        )
    )
    cases: dict[str, dict[str, Any]] = {}

    unknown = copy.deepcopy(base)
    unknown["records"][2]["payload"]["sourceRoleCode"] = "UNAPPROVED_ROLE"
    cases["unknown-role"] = case(finalize(unknown, 1), "adapter")

    duplicate_binding = copy.deepcopy(base)
    account = copy.deepcopy(duplicate_binding["records"][1]["payload"])
    account["externalRef"] = "ACCOUNT-002"
    duplicate_binding["records"].append(record("account", account))
    cases["duplicate-binding"] = case(
        finalize(duplicate_binding, 2), "adapter"
    )

    duplicate_external = copy.deepcopy(base)
    account = copy.deepcopy(duplicate_external["records"][1]["payload"])
    account["subject"] = "subject-002"
    duplicate_external["records"].append(record("account", account))
    cases["duplicate-external-id"] = case(
        finalize(duplicate_external, 3), "adapter"
    )

    orphan = copy.deepcopy(base)
    orphan["records"][0]["payload"]["parentExternalRef"] = "ORG-MISSING"
    cases["orphan-organization"] = case(finalize(orphan, 4), "service")

    self_parent = copy.deepcopy(base)
    self_parent["records"][0]["payload"]["parentExternalRef"] = "ORG-SCHOOL"
    cases["self-parent-organization"] = case(
        finalize(self_parent, 5), "service"
    )

    cyclic = copy.deepcopy(base)
    cyclic["records"][0]["payload"]["externalRef"] = "ORG-A"
    cyclic["records"][0]["payload"]["parentExternalRef"] = "ORG-B"
    cyclic["records"][2]["payload"]["organizationExternalRef"] = "ORG-A"
    second_org = copy.deepcopy(cyclic["records"][0]["payload"])
    second_org["externalRef"] = "ORG-B"
    second_org["parentExternalRef"] = "ORG-A"
    second_org["displayName"] = "循环组织 B"
    cyclic["records"].insert(1, record("organization", second_org))
    cases["cyclic-organization"] = case(finalize(cyclic, 6), "service")

    invalid_window = copy.deepcopy(base)
    invalid_window["records"][1]["effectiveFrom"] = "2026-07-24T01:00:00Z"
    invalid_window["records"][1]["effectiveTo"] = "2026-07-24T00:59:59Z"
    invalid_window["records"][1]["payload"]["effectiveFrom"] = (
        "2026-07-24T01:00:00Z"
    )
    invalid_window["records"][1]["payload"]["effectiveTo"] = (
        "2026-07-24T00:59:59Z"
    )
    cases["invalid-effective-window"] = case(
        finalize(invalid_window, 7), "adapter"
    )

    stale = finalize(copy.deepcopy(base), 8)
    cases["stale-source-version"] = case(
        stale,
        "service",
        checkpoint_version=7,
        checkpoint_watermark=6,
    )

    cursor_gap = finalize(copy.deepcopy(base), 9)
    cases["cursor-gap"] = case(
        cursor_gap,
        "service",
        checkpoint_version=4,
        checkpoint_watermark=4,
    )

    conflict = finalize(copy.deepcopy(base), 10)
    cases["same-key-different-payload"] = case(
        conflict,
        "service",
        current_record_digest="a" * 64,
    )

    mapping = copy.deepcopy(base)
    mapping["mappingDigest"] = "sha256:" + "b" * 64
    cases["mapping-digest-mismatch"] = case(
        finalize(mapping, 11), "adapter"
    )

    invalid_signature = finalize(copy.deepcopy(base), 12)
    cases["source-signature-invalid"] = case(
        invalid_signature,
        "service",
        signature_verified=False,
    )

    document = {
        "version": "IDENTITY-AUTHORITY-NEGATIVE-PAYLOADS-1.0.0",
        "cases": cases,
    }
    OUTPUT.write_text(
        json.dumps(document, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    refresh_lock()


def refresh_lock() -> None:
    paths = sorted(
        (
            path
            for path in BASE.rglob("*")
            if path.is_file() and path != LOCK
        ),
        key=lambda path: str(path.relative_to(ROOT)),
    )
    value = {
        "version": "IDENTITY-AUTHORITY-CONTRACT-LOCK-1.0.0",
        "files": [
            {
                "path": str(path.relative_to(ROOT)),
                "sha256": digest(path.read_bytes()),
            }
            for path in paths
        ],
    }
    LOCK.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
