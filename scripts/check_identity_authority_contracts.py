#!/usr/bin/env python3
"""Validate Story 1.6a authority contracts, sandbox mapping, fixtures, and lock."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from release_json import load_json, schema_definition_issues, schema_issues  # noqa: E402


BASE = Path("contracts/identity-authority")
LOCK = BASE / "identity-authority-contract-lock-1.0.0.json"
ROLE_IDS = {
    "R1-COUNSELOR",
    "R2-COLLEGE-MANAGER",
    "R3-STUDENT-AFFAIRS",
    "R4-SCHOOL-LEADER",
    "R5-COLLABORATOR",
    "R6-DATA-OWNER",
    "R7-PLATFORM-OPS",
}
SCHEMAS = {
    BASE / "fixtures/valid/incremental-batch.json": BASE / "incremental-batch.schema.json",
    BASE / "fixtures/valid/heartbeat.json": BASE / "incremental-batch.schema.json",
    BASE / "fixtures/valid/sync-job.json": BASE / "sync-job.schema.json",
    BASE / "fixtures/valid/reconciliation-result.json": BASE / "reconciliation-result.schema.json",
    BASE / "sandbox-role-mapping-1.0.0.json": BASE / "role-mapping.schema.json",
    BASE / "sandbox-runtime-profile-1.0.0.json": BASE / "runtime-profile.schema.json",
    BASE / "subject-binding-golden-vectors-1.0.0.json": BASE / "subject-binding-vectors.schema.json",
}
REQUIRED_NEGATIVE_CASES = {
    "unknown-role",
    "duplicate-binding",
    "duplicate-external-id",
    "orphan-organization",
    "self-parent-organization",
    "cyclic-organization",
    "invalid-effective-window",
    "stale-source-version",
    "cursor-gap",
    "same-key-different-payload",
    "mapping-digest-mismatch",
    "source-signature-invalid",
}


def validate(project_root: Path) -> list[str]:
    root = project_root.resolve()
    issues: list[str] = []
    schemas: dict[Path, Any] = {}
    for relative in sorted(set(SCHEMAS.values())):
        try:
            schema = load_json(root / relative)
        except (OSError, ValueError):
            issues.append(f"IDENTITY_AUTHORITY_SCHEMA_INVALID: {relative}")
            continue
        schemas[relative] = schema
        issues.extend(
            f"IDENTITY_AUTHORITY_SCHEMA_INVALID: {relative}: {issue}"
            for issue in schema_definition_issues(schema)
        )
    for instance_path, schema_path in SCHEMAS.items():
        try:
            instance = load_json(root / instance_path)
        except (OSError, ValueError):
            issues.append(f"IDENTITY_AUTHORITY_EXAMPLE_INVALID: {instance_path}")
            continue
        validation = schema_issues(instance, schemas.get(schema_path, {}))
        if validation:
            issues.append(
                f"IDENTITY_AUTHORITY_EXAMPLE_REJECTED: {instance_path}: {validation[0]}"
            )
    issues.extend(_mapping_issues(root))
    issues.extend(_profile_issues(root))
    issues.extend(_batch_issues(root))
    issues.extend(_heartbeat_issues(root))
    issues.extend(_negative_catalog_issues(root))
    issues.extend(_lock_issues(root))
    return sorted(set(issues))


def _mapping_issues(root: Path) -> list[str]:
    relative = BASE / "sandbox-role-mapping-1.0.0.json"
    try:
        mapping = load_json(root / relative)
        entries = mapping["entries"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["IDENTITY_AUTHORITY_MAPPING_INVALID"]
    roles = {entry.get("targetRoleId") for entry in entries if isinstance(entry, dict)}
    codes = [entry.get("sourceRoleCode") for entry in entries if isinstance(entry, dict)]
    canonical = json.dumps(
        entries, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    expected_digest = "sha256:" + hashlib.sha256(canonical).hexdigest()
    valid = (
        mapping.get("version") == "IDENTITY-ROLE-MAPPING-1.0.0"
        and mapping.get("sourceId") == "SRC-P0-RESPONSIBILITY-001"
        and mapping.get("environment") == "sandbox"
        and mapping.get("productionEligible") is False
        and mapping.get("providedBy")
        == "SRC-P0-RESPONSIBILITY-001 controlled sandbox source owner"
        and mapping.get("approvedBy") == "Hei"
        and isinstance(mapping.get("approvedAt"), str)
        and isinstance(mapping.get("effectiveAt"), str)
        and roles == ROLE_IDS
        and len(codes) == len(set(codes)) == 7
        and all(
            isinstance(code, str) and code.startswith("SANDBOX_")
            for code in codes
        )
        and mapping.get("digest") == expected_digest
    )
    return [] if valid else ["IDENTITY_AUTHORITY_MAPPING_INVALID"]


def _batch_issues(root: Path) -> list[str]:
    try:
        batch = load_json(root / BASE / "fixtures/valid/incremental-batch.json")
        records = batch["records"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["IDENTITY_AUTHORITY_BATCH_INVALID"]
    kinds = {record.get("recordKind") for record in records if isinstance(record, dict)}
    valid = (
        batch.get("sourceId") == "SRC-P0-RESPONSIBILITY-001"
        and batch.get("consumerProjection") == "identity-org"
        and batch.get("resultType") == "changes"
        and batch.get("mappingVersion") == "IDENTITY-ROLE-MAPPING-1.0.0"
        and kinds == {"account", "organization", "employment-role"}
        and all("payloadDigest" in record for record in records)
    )
    schema_for_kind = {
        "account": BASE / "account.schema.json",
        "organization": BASE / "organization.schema.json",
        "employment-role": BASE / "employment-role.schema.json",
    }
    for record in records:
        try:
            payload_schema = load_json(root / schema_for_kind[record["recordKind"]])
        except (OSError, ValueError, KeyError, TypeError):
            valid = False
            continue
        if schema_definition_issues(payload_schema) or schema_issues(
            record.get("payload"), payload_schema
        ):
            valid = False
    return [] if valid else ["IDENTITY_AUTHORITY_BATCH_INVALID"]


def _heartbeat_issues(root: Path) -> list[str]:
    try:
        heartbeat = load_json(root / BASE / "fixtures/valid/heartbeat.json")
    except (OSError, ValueError):
        return ["IDENTITY_AUTHORITY_HEARTBEAT_INVALID"]
    valid = (
        heartbeat.get("resultType") == "heartbeat"
        and heartbeat.get("sourceId") == "SRC-P0-RESPONSIBILITY-001"
        and heartbeat.get("consumerProjection") == "identity-org"
        and heartbeat.get("fromWatermark") == heartbeat.get("toWatermark")
        and heartbeat.get("records") == []
    )
    return [] if valid else ["IDENTITY_AUTHORITY_HEARTBEAT_INVALID"]


def _profile_issues(root: Path) -> list[str]:
    try:
        profile = load_json(root / BASE / "sandbox-runtime-profile-1.0.0.json")
        bindings = profile["bindings"]
        mapping = load_json(root / BASE / "sandbox-role-mapping-1.0.0.json")
    except (OSError, ValueError, KeyError, TypeError):
        return ["IDENTITY_AUTHORITY_PROFILE_INVALID"]
    canonical = json.dumps(
        bindings, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    expected_digest = "sha256:" + hashlib.sha256(canonical).hexdigest()
    valid = (
        profile.get("profileVersion") == "IDENTITY-AUTHORITY-PROFILE-1.0.0"
        and profile.get("sourceId") == "SRC-P0-RESPONSIBILITY-001"
        and profile.get("environment") == "sandbox"
        and profile.get("productionEligible") is False
        and profile.get("approvedBy") == "Hei"
        and bindings.get("roleMappingDigest") == mapping.get("digest")
        and profile.get("digest") == expected_digest
    )
    return [] if valid else ["IDENTITY_AUTHORITY_PROFILE_INVALID"]


def _negative_catalog_issues(root: Path) -> list[str]:
    try:
        catalog = load_json(root / BASE / "fixtures/negative-fixtures-1.0.0.json")
        cases = catalog["cases"]
        payloads = load_json(
            root / BASE / "fixtures/negative/payloads-1.0.0.json"
        )["cases"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["IDENTITY_AUTHORITY_NEGATIVE_CATALOG_INVALID"]
    identities = [case.get("id") for case in cases if isinstance(case, dict)]
    if (
        catalog.get("version") != "IDENTITY-AUTHORITY-NEGATIVE-FIXTURES-1.0.0"
        or set(identities) != REQUIRED_NEGATIVE_CASES
        or len(identities) != len(set(identities))
        or set(payloads) != REQUIRED_NEGATIVE_CASES
    ):
        return ["IDENTITY_AUTHORITY_NEGATIVE_CATALOG_INVALID"]
    for case in cases:
        case_id = case["id"]
        expected_ref = (
            "fixtures/negative/payloads-1.0.0.json#" + case_id
        )
        if (
            case.get("fixtureRef") != expected_ref
            or not isinstance(payloads.get(case_id), dict)
            or not _negative_payload_matches(
                root, case_id, payloads[case_id]
            )
        ):
            return ["IDENTITY_AUTHORITY_NEGATIVE_PAYLOAD_INVALID"]
    return []


def _negative_payload_matches(
    root: Path, case_id: str, value: dict[str, Any]
) -> bool:
    required = {
        "executionStage",
        "afterWatermark",
        "checkpoint",
        "signatureVerified",
        "detachedSignature",
        "payload",
    }
    if not required.issubset(value) or set(value) - (required | {"currentRecord"}):
        return False
    payload = value.get("payload")
    checkpoint = value.get("checkpoint")
    if (
        value.get("executionStage") not in {"adapter", "service"}
        or not isinstance(payload, dict)
        or not isinstance(checkpoint, dict)
        or set(checkpoint)
        != {"sourceVersion", "sourceWatermark", "aggregateVersion"}
        or value.get("afterWatermark") != payload.get("fromWatermark")
        or not isinstance(value.get("signatureVerified"), bool)
        or not isinstance(value.get("detachedSignature"), str)
    ):
        return False
    try:
        batch_schema = load_json(root / BASE / "incremental-batch.schema.json")
    except (OSError, ValueError):
        return False
    if schema_issues(payload, batch_schema):
        return False
    schema_for_kind = {
        "account": BASE / "account.schema.json",
        "organization": BASE / "organization.schema.json",
        "employment-role": BASE / "employment-role.schema.json",
    }
    for record in payload.get("records", []):
        try:
            schema = load_json(root / schema_for_kind[record["recordKind"]])
        except (OSError, ValueError, KeyError, TypeError):
            return False
        if schema_issues(record.get("payload"), schema):
            return False
    records = payload["records"]
    records_of = lambda kind: [
        record for record in records if record.get("recordKind") == kind
    ]
    if case_id == "unknown-role":
        return any(
            record.get("payload", {}).get("sourceRoleCode") == "UNAPPROVED_ROLE"
            for record in records_of("employment-role")
        )
    if case_id == "duplicate-binding":
        accounts = [
            record.get("payload", {}) for record in records_of("account")
        ]
        return (
            len(accounts) == 2
            and accounts[0].get("issuer") == accounts[1].get("issuer")
            and accounts[0].get("subject") == accounts[1].get("subject")
            and accounts[0].get("externalRef") != accounts[1].get("externalRef")
        )
    if case_id == "duplicate-external-id":
        accounts = [
            record.get("payload", {}) for record in records_of("account")
        ]
        return len(accounts) == 2 and accounts[0].get(
            "externalRef"
        ) == accounts[1].get("externalRef")
    if case_id == "orphan-organization":
        organization = records_of("organization")[0]["payload"]
        return organization.get("parentExternalRef") == "ORG-MISSING"
    if case_id == "self-parent-organization":
        organization = records_of("organization")[0]["payload"]
        return organization.get("externalRef") == organization.get(
            "parentExternalRef"
        )
    if case_id == "cyclic-organization":
        organizations = [
            record["payload"] for record in records_of("organization")
        ]
        return (
            len(organizations) == 2
            and organizations[0].get("externalRef")
            == organizations[1].get("parentExternalRef")
            and organizations[1].get("externalRef")
            == organizations[0].get("parentExternalRef")
        )
    if case_id == "invalid-effective-window":
        return any(
            record.get("effectiveTo") is not None
            and record["effectiveTo"] <= record["effectiveFrom"]
            for record in records
        )
    if case_id == "stale-source-version":
        return payload.get("sourceVersion", 0) <= checkpoint.get(
            "sourceVersion", -1
        )
    if case_id == "cursor-gap":
        return payload.get("fromWatermark", 0) > checkpoint.get(
            "sourceWatermark", 0
        )
    if case_id == "same-key-different-payload":
        current = value.get("currentRecord", {})
        return (
            current.get("sourceVersion") == payload.get("sourceVersion")
            and any(
                record.get("payloadDigest", "").removeprefix("sha256:")
                != current.get("payloadDigest")
                for record in records
            )
        )
    if case_id == "mapping-digest-mismatch":
        return payload.get("mappingDigest") != (
            "sha256:f09768f88cd6a595791ec6591e65758b85fd8402585c8ffaa053446214895e29"
        )
    if case_id == "source-signature-invalid":
        return value.get("signatureVerified") is False
    return False


def _lock_issues(root: Path) -> list[str]:
    try:
        lock = load_json(root / LOCK)
        entries = lock["files"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["IDENTITY_AUTHORITY_CONTRACT_LOCK_INVALID"]
    expected_paths = sorted(
        str(path.relative_to(root))
        for path in (root / BASE).rglob("*")
        if path.is_file() and path != root / LOCK
    )
    locked_paths = [entry.get("path") for entry in entries if isinstance(entry, dict)]
    issues: list[str] = []
    if (
        lock.get("version") != "IDENTITY-AUTHORITY-CONTRACT-LOCK-1.0.0"
        or locked_paths != expected_paths
    ):
        issues.append("IDENTITY_AUTHORITY_CONTRACT_LOCK_INVALID")
    for entry in entries:
        if not isinstance(entry, dict):
            issues.append("IDENTITY_AUTHORITY_CONTRACT_LOCK_INVALID")
            continue
        relative = entry.get("path")
        digest = entry.get("sha256")
        try:
            actual = hashlib.sha256((root / relative).read_bytes()).hexdigest()
        except (OSError, TypeError):
            issues.append(f"IDENTITY_AUTHORITY_CONTRACT_LOCK_PATH_MISSING: {relative}")
            continue
        if actual != digest:
            issues.append(f"IDENTITY_AUTHORITY_CONTRACT_LOCK_DIGEST_MISMATCH: {relative}")
    return issues


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) == 2 else Path(".")
    issues = validate(root)
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print(f"identity-authority-contracts: PASS ({len(SCHEMAS)} examples)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
