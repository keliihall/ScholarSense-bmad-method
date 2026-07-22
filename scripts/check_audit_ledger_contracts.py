#!/usr/bin/env python3
"""Validate the independently versioned, tamper-evident audit-ledger contracts."""

from __future__ import annotations

import hashlib
import json
import copy
import re
import sys
from pathlib import Path
from typing import Any


sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_audit_contracts import validate as validate_local_audit  # noqa: E402
from release_json import (  # noqa: E402
    canonical_bytes,
    load_json,
    parse_json_bytes,
    schema_definition_issues,
    schema_issues,
)


LEDGER = Path("contracts/audit-ledger")
LOCK = LEDGER / "audit-ledger-contract-lock-1.0.0.json"
SCHEMAS = {
    LEDGER / "fixtures/valid/ledger-record.json": LEDGER / "ledger-record.schema.json",
    LEDGER / "hash-profile-1.0.0.json": LEDGER / "hash-profile.schema.json",
    LEDGER / "audit-ingestion-policy-1.0.0.json": LEDGER / "audit-ingestion-policy.schema.json",
    LEDGER / "fixtures/valid/availability.json": LEDGER / "availability.schema.json",
    LEDGER / "fixtures/valid/integrity-finding.json": LEDGER / "integrity-finding.schema.json",
    LEDGER / "fixtures/valid/alert.json": LEDGER / "alert.schema.json",
    LEDGER / "fixtures/valid/verification-run.json": LEDGER / "verification-run.schema.json",
    LEDGER / "fixtures/valid/ingress-result.json": LEDGER / "ingress-result.schema.json",
}
HASH_FIELDS = [
    "domainTag",
    "hashProfileVersion",
    "ledgerSequence",
    "previousHash",
    "auditId",
    "sourceEventId",
    "producerModule",
    "eventType",
    "eventSchemaVersion",
    "factSchemaVersion",
    "sourceCreatedAt",
    "collectedAt",
    "traceId",
    "aggregateVersion",
    "payloadFingerprint",
    "retentionScheduleVersion",
]
FINDING_CODES = {
    "AUDIT_LEDGER_SEQUENCE_GAP",
    "AUDIT_LEDGER_PREVIOUS_HASH_MISMATCH",
    "AUDIT_LEDGER_ENTRY_HASH_MISMATCH",
    "AUDIT_LEDGER_HEAD_MISMATCH",
    "AUDIT_INGESTION_DUPLICATE_CONFLICT",
    "AUDIT_INGESTION_CONTRACT_REJECTED",
    "AUDIT_INGESTION_BACKLOG",
}
SENSITIVE_KEYS = {
    "actor",
    "object",
    "ip",
    "token",
    "cookie",
    "payload",
    "studentid",
    "evidencebody",
    "rawstudentid",
    "rawip",
}


def validate(project_root: Path) -> list[str]:
    root = project_root.resolve()
    issues: list[str] = []
    issues.extend(f"AUDIT_LEDGER_LOCAL_INPUT_INVALID: {issue}" for issue in validate_local_audit(root))

    schemas: dict[Path, Any] = {}
    for schema_path in sorted(set(SCHEMAS.values())):
        schema = _load(root / schema_path, issues, "AUDIT_LEDGER_SCHEMA_INVALID")
        if schema is None:
            continue
        schemas[schema_path] = schema
        issues.extend(
            f"AUDIT_LEDGER_SCHEMA_INVALID: {schema_path}: {issue}"
            for issue in schema_definition_issues(schema)
        )

    documents: dict[Path, Any] = {}
    for document_path, schema_path in SCHEMAS.items():
        legacy_numbers = document_path.name == "audit-ingestion-policy-1.0.0.json"
        document = _load(
            root / document_path,
            issues,
            "AUDIT_LEDGER_CONTRACT_DOCUMENT_INVALID",
            legacy_numbers=legacy_numbers,
        )
        if document is None:
            continue
        documents[document_path] = document
        schema = schemas.get(schema_path)
        if schema is not None:
            found = schema_issues(document, schema)
            if found:
                issues.append(f"AUDIT_LEDGER_FIXTURE_SCHEMA_REJECTED: {document_path}: {found[0]}")

    ledger = documents.get(LEDGER / "fixtures/valid/ledger-record.json")
    profile = documents.get(LEDGER / "hash-profile-1.0.0.json")
    policy = documents.get(LEDGER / "audit-ingestion-policy-1.0.0.json")
    issues.extend(_hash_profile_issues(profile))
    issues.extend(_policy_issues(policy))
    issues.extend(_ledger_issues(root, ledger, profile))
    issues.extend(_privacy_issues(documents))
    issues.extend(_finding_issues(documents))
    issues.extend(_golden_vector_issues(root))
    issues.extend(_negative_fixture_issues(root))
    issues.extend(_lock_issues(root))
    return sorted(set(issues))


def _load(
        path: Path,
        issues: list[str],
        code: str,
        *,
        legacy_numbers: bool = False) -> Any | None:
    try:
        return load_json(path, legacy_numbers=legacy_numbers)
    except (OSError, ValueError) as error:
        issues.append(f"{code}: {path}: {error}")
        return None


def _hash_profile_issues(profile: Any) -> list[str]:
    expected = {
        "schemaVersion": "AUDIT-LEDGER-HASH-1.0.0",
        "algorithm": "SHA-256",
        "canonicalizationProfile": "SCHOLARSENSE-CANONICAL-JSON-1.0.0",
        "encoding": "UTF-8",
        "domainTag": "scholarsense.audit-ledger.entry.v1",
        "genesis": {"ledgerSequence": 0, "entryHash": "0" * 64},
        "hashMaterialFields": HASH_FIELDS,
        "nullEncoding": "json-null",
        "duplicateKeyPolicy": "reject",
        "unknownPropertyPolicy": "reject",
        "payloadFingerprint": {
            "algorithm": "SHA-256",
            "material": "complete-local-audit-fact",
            "canonicalizationProfile": "SCHOLARSENSE-CANONICAL-JSON-1.0.0",
        },
    }
    return [] if profile == expected else ["AUDIT_LEDGER_HASH_PROFILE_INVALID"]


def _policy_issues(policy: Any) -> list[str]:
    if not isinstance(policy, dict):
        return ["AUDIT_LEDGER_POLICY_SEMANTICS_INVALID"]
    try:
        valid = (
            policy["schemaVersion"] == "AUDIT-INGESTION-POLICY-1.0.0"
            and policy["observation"] == {
                "intervalSeconds": 15,
                "staleAfterSeconds": 45,
                "unconfirmedStatuses": ["pending", "retrying", "failed"],
            }
            and policy["claim"] == {
                "batchSize": 100,
                "leaseSeconds": 60,
                "selection": "FOR UPDATE SKIP LOCKED",
                "dueOrder": ["createdAt", "eventId"],
                "fencingToken": "attempts",
            }
            and policy["retry"] == {
                "transient": "indefinite",
                "capFormula": "min(60s,2^(attempts-1)s)",
                "maximumCapSeconds": 60,
                "jitterLowerInclusive": 0.5,
                "jitterUpperInclusive": 1.0,
                "permanentStatuses": ["failed", "quarantine"],
                "permanentReasons": ["contract-rejected", "idempotency-collision"],
            }
            and policy["idempotency"]["businessKeys"] == ["auditId", "sourceEventId"]
            and policy["idempotency"]["exactRetryFields"] == [
                "auditId", "sourceEventId", "payloadFingerprint", "eventSchemaVersion",
                "factSchemaVersion", "aggregateVersion",
            ]
            and policy["idempotency"]["collisionCode"] == "AUDIT_INGESTION_DUPLICATE_CONFLICT"
            and policy["idempotency"]["collisionAllocatesSequence"] is False
            and policy["idempotency"]["collisionConfirmsSource"] is False
            and policy["availabilityThresholds"] == {
                "comparison": ">=",
                "degraded": {"oldestUnconfirmedAgeSeconds": 60, "unconfirmedCount": 10000},
                "blocked": {
                    "oldestUnconfirmedAgeSeconds": 300,
                    "unconfirmedCount": 50000,
                    "permanentFindingActive": True,
                    "verifierUnhealthy": True,
                    "measurementMissingOrStale": True,
                },
                "recovery": {
                    "comparison": "<",
                    "oldestUnconfirmedAgeSecondsExclusiveMax": 60,
                    "unconfirmedCountExclusiveMax": 10000,
                    "consecutiveHealthyObservations": 2,
                    "requiresNoPermanentFinding": True,
                    "requiresHealthyChainAndHead": True,
                    "requiresFreshObservation": True,
                },
                "highRiskFailureMode": "fail-closed",
                "stableBlockedCode": "AUDIT_AVAILABILITY_BLOCKED",
                "dependencyUnavailableCode": "AUDIT_AVAILABILITY_UNAVAILABLE",
            }
            and policy["alertDeduplication"] == {
                "windowSeconds": 300,
                "keyFields": ["code", "sourceRange"],
                "resolvedEventOnRecovery": True,
            }
            and {value.lower() for value in policy["privacy"]["forbiddenPlaintext"]}
                == SENSITIVE_KEYS - {"rawstudentid", "rawip"}
            and not {"auditId", "eventId", "traceId"} & set(policy["privacy"]["metricLabels"])
        )
    except (KeyError, TypeError):
        valid = False
    return [] if valid else ["AUDIT_LEDGER_POLICY_SEMANTICS_INVALID"]


def _ledger_issues(root: Path, ledger: Any, profile: Any) -> list[str]:
    if not isinstance(ledger, dict) or not isinstance(profile, dict):
        return ["AUDIT_LEDGER_RECORD_SEMANTICS_INVALID"]
    issues: list[str] = []
    try:
        local_schema = load_json(root / "contracts/audit/local-audit-fact.schema.json")
        local_issues = schema_issues(ledger["payload"], local_schema)
        if local_issues:
            issues.append(f"AUDIT_LEDGER_LOCAL_FACT_REJECTED: {local_issues[0]}")
        payload_fingerprint = hashlib.sha256(canonical_bytes(ledger["payload"])).hexdigest()
        if ledger["payloadFingerprint"] != payload_fingerprint:
            issues.append("AUDIT_LEDGER_PAYLOAD_FINGERPRINT_MISMATCH")
        if ledger["auditId"] != ledger["payload"]["auditId"] \
                or ledger["producerModule"] != ledger["payload"]["producerModule"] \
                or ledger["factSchemaVersion"] != ledger["payload"]["schemaVersion"] \
                or ledger["traceId"] != ledger["payload"]["traceId"] \
                or ledger["aggregateVersion"] != ledger["payload"]["aggregateVersion"]:
            issues.append("AUDIT_LEDGER_ENVELOPE_FACT_MISMATCH")
        material = _hash_material(ledger, profile)
        expected_hash = hashlib.sha256(canonical_bytes(material)).hexdigest()
        if ledger["entryHash"] != expected_hash:
            issues.append("AUDIT_LEDGER_ENTRY_HASH_MISMATCH")
    except (KeyError, TypeError, ValueError):
        issues.append("AUDIT_LEDGER_RECORD_SEMANTICS_INVALID")
    return issues


def _hash_material(ledger: dict[str, Any], profile: dict[str, Any]) -> dict[str, Any]:
    return {
        "domainTag": profile["domainTag"],
        "hashProfileVersion": ledger["hashProfileVersion"],
        "ledgerSequence": ledger["ledgerSequence"],
        "previousHash": ledger["previousHash"],
        "auditId": ledger["auditId"],
        "sourceEventId": ledger["sourceEventId"],
        "producerModule": ledger["producerModule"],
        "eventType": ledger["eventType"],
        "eventSchemaVersion": ledger["eventSchemaVersion"],
        "factSchemaVersion": ledger["factSchemaVersion"],
        "sourceCreatedAt": ledger["sourceCreatedAt"],
        "collectedAt": ledger["collectedAt"],
        "traceId": ledger["traceId"],
        "aggregateVersion": ledger["aggregateVersion"],
        "payloadFingerprint": ledger["payloadFingerprint"],
        "retentionScheduleVersion": ledger["payload"]["retentionScheduleVersion"],
    }


def _privacy_issues(documents: dict[Path, Any]) -> list[str]:
    issues: list[str] = []
    for path in (
            LEDGER / "fixtures/valid/integrity-finding.json",
            LEDGER / "fixtures/valid/alert.json"):
        document = documents.get(path)
        if _contains_sensitive_key(document):
            issues.append(f"AUDIT_LEDGER_SENSITIVE_FIELD_FORBIDDEN: {path}")
    return issues


def _contains_sensitive_key(value: Any) -> bool:
    if isinstance(value, dict):
        for key, nested in value.items():
            normalized = "".join(character for character in key.lower() if character.isalnum())
            if normalized in SENSITIVE_KEYS or normalized.startswith("raw"):
                return True
            if _contains_sensitive_key(nested):
                return True
    if isinstance(value, list):
        return any(_contains_sensitive_key(item) for item in value)
    return False


def _finding_issues(documents: dict[Path, Any]) -> list[str]:
    finding = documents.get(LEDGER / "fixtures/valid/integrity-finding.json")
    alert = documents.get(LEDGER / "fixtures/valid/alert.json")
    if not isinstance(finding, dict) or not isinstance(alert, dict):
        return ["AUDIT_LEDGER_FINDING_ALERT_INVALID"]
    valid = (
        finding.get("code") in FINDING_CODES
        and alert.get("code") == finding.get("code")
        and alert.get("findingId") == finding.get("findingId")
        and alert.get("safeDigest") == finding.get("safeDigest")
    )
    return [] if valid else ["AUDIT_LEDGER_FINDING_ALERT_INVALID"]


def _golden_vector_issues(root: Path) -> list[str]:
    try:
        document = load_json(root / LEDGER / "canonical-hash-golden-vectors-1.0.0.json")
        vectors = document["vectors"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["AUDIT_LEDGER_GOLDEN_VECTORS_INVALID"]
    required = {"empty-null", "chinese", "non-bmp", "key-order", "utc-time"}
    found: set[str] = set()
    issues: list[str] = []
    for vector in vectors:
        try:
            found.add(vector["id"])
            encoded = canonical_bytes(vector["value"])
            if vector["canonicalUtf8Hex"] != encoded.hex() \
                    or vector["sha256"] != hashlib.sha256(encoded).hexdigest():
                issues.append(f"AUDIT_LEDGER_GOLDEN_VECTOR_MISMATCH: {vector.get('id')}")
        except (KeyError, TypeError, ValueError):
            issues.append("AUDIT_LEDGER_GOLDEN_VECTORS_INVALID")
    if not required <= found or len(found) != len(vectors):
        issues.append("AUDIT_LEDGER_GOLDEN_VECTORS_INVALID")
    return issues


def _negative_fixture_issues(root: Path) -> list[str]:
    try:
        document = load_json(root / LEDGER / "fixtures/negative-fixtures-1.0.0.json")
        cases = document["cases"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["AUDIT_LEDGER_NEGATIVE_FIXTURES_INVALID"]
    required = {
        "unknown-ledger-major", "idempotency-collision", "finding-sensitive-key",
        "degraded-boundary-below", "blocked-boundary-below", "duplicate-json-key",
        "invalid-sequence", "invalid-entry-hash", "alert-raw-payload",
    }
    ids = {case.get("id") for case in cases if isinstance(case, dict)}
    if ids != required or len(ids) != len(cases):
        return ["AUDIT_LEDGER_NEGATIVE_FIXTURES_INVALID"]
    try:
        ledger = load_json(root / LEDGER / "fixtures/valid/ledger-record.json")
        finding = load_json(root / LEDGER / "fixtures/valid/integrity-finding.json")
        alert = load_json(root / LEDGER / "fixtures/valid/alert.json")
        policy = load_json(
            root / LEDGER / "audit-ingestion-policy-1.0.0.json", legacy_numbers=True)
        ledger_schema = load_json(root / LEDGER / "ledger-record.schema.json")
    except (OSError, ValueError):
        return ["AUDIT_LEDGER_NEGATIVE_FIXTURES_INVALID"]
    for case in cases:
        actual = _execute_negative_fixture(
            case, ledger, finding, alert, policy, ledger_schema)
        expected = case.get("expectedCode", case.get("expectedState"))
        if actual != expected:
            return ["AUDIT_LEDGER_NEGATIVE_FIXTURES_INVALID"]
    return []


def _execute_negative_fixture(
        case: dict[str, Any],
        ledger: dict[str, Any],
        finding: dict[str, Any],
        alert: dict[str, Any],
        policy: dict[str, Any],
        ledger_schema: dict[str, Any]) -> str | None:
    case_id = case.get("id")
    mutation = case.get("mutation")
    if case_id in {"unknown-ledger-major", "invalid-sequence", "invalid-entry-hash"}:
        field, value = _assignment(mutation)
        if field not in {"schemaVersion", "ledgerSequence", "entryHash"}:
            return None
        mutated = copy.deepcopy(ledger)
        mutated[field] = int(value) if field == "ledgerSequence" and value.isdigit() else value
        return "AUDIT_LEDGER_FIXTURE_SCHEMA_REJECTED" \
            if schema_issues(mutated, ledger_schema) else None
    if case_id == "finding-sensitive-key":
        field, value = _assignment(mutation)
        mutated = copy.deepcopy(finding)
        mutated[field] = value
        return "AUDIT_LEDGER_SENSITIVE_FIELD_FORBIDDEN" \
            if _contains_sensitive_key(mutated) else None
    if case_id == "alert-raw-payload":
        field, value = _assignment(mutation)
        mutated = copy.deepcopy(alert)
        mutated[field] = value
        return "AUDIT_LEDGER_SENSITIVE_FIELD_FORBIDDEN" \
            if _contains_sensitive_key(mutated) else None
    if case_id == "idempotency-collision":
        incoming = copy.deepcopy(ledger)
        if mutation == "same-auditId-different-sourceEventId":
            incoming["sourceEventId"] = "019bf18e-6c00-7000-8000-000000999999"
        elif mutation == "different-auditId-different-sourceEventId":
            incoming["auditId"] = "019bf18e-6c00-7000-8000-000000999998"
            incoming["sourceEventId"] = "019bf18e-6c00-7000-8000-000000999999"
        else:
            return None
        return _idempotency_outcome(ledger, incoming, policy)
    if case_id in {"degraded-boundary-below", "blocked-boundary-below"}:
        match = re.fullmatch(r"oldestAge=([0-9]+),count=([0-9]+)", str(mutation))
        if match is None:
            return None
        return _availability_state(policy, int(match.group(1)), int(match.group(2)))
    if case_id == "duplicate-json-key":
        try:
            parse_json_bytes(str(case.get("rawJson", "")).encode("utf-8"))
        except ValueError as error:
            if str(error).startswith("JSON_DUPLICATE_KEY"):
                return "AUDIT_CANONICAL_JSON_DUPLICATE_KEY"
        return None
    return None


def _assignment(mutation: Any) -> tuple[str, str]:
    if not isinstance(mutation, str) or "=" not in mutation:
        return "", ""
    field, value = mutation.split("=", 1)
    return field, value


def _idempotency_outcome(
        existing: dict[str, Any], incoming: dict[str, Any], policy: dict[str, Any]) -> str:
    keys = policy["idempotency"]["businessKeys"]
    exact_fields = policy["idempotency"]["exactRetryFields"]
    if not any(existing[field] == incoming[field] for field in keys):
        return "APPENDED"
    if all(existing[field] == incoming[field] for field in exact_fields):
        return "EXACT_DUPLICATE"
    return policy["idempotency"]["collisionCode"]


def _availability_state(policy: dict[str, Any], age: int, count: int) -> str:
    thresholds = policy["availabilityThresholds"]
    blocked = thresholds["blocked"]
    degraded = thresholds["degraded"]
    if age >= blocked["oldestUnconfirmedAgeSeconds"] \
            or count >= blocked["unconfirmedCount"]:
        return "blocked"
    if age >= degraded["oldestUnconfirmedAgeSeconds"] \
            or count >= degraded["unconfirmedCount"]:
        return "degraded"
    return "healthy"


def _lock_issues(root: Path) -> list[str]:
    try:
        lock = load_json(root / LOCK)
        entries = lock["files"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["AUDIT_LEDGER_CONTRACT_LOCK_INVALID"]
    expected_paths = sorted(
        str(path.relative_to(root))
        for path in (root / LEDGER).rglob("*")
        if path.is_file() and path != root / LOCK
    )
    actual_paths = [entry.get("path") for entry in entries if isinstance(entry, dict)]
    issues: list[str] = []
    if lock.get("version") != "AUDIT-LEDGER-CONTRACT-LOCK-1.0.0" \
            or actual_paths != expected_paths or len(actual_paths) != len(entries):
        issues.append("AUDIT_LEDGER_CONTRACT_LOCK_SCOPE_MISMATCH")
    for entry in entries:
        try:
            digest = hashlib.sha256((root / entry["path"]).read_bytes()).hexdigest()
        except (OSError, KeyError, TypeError):
            issues.append("AUDIT_LEDGER_CONTRACT_LOCK_INVALID")
            continue
        if digest != entry.get("sha256"):
            issues.append(f"AUDIT_LEDGER_CONTRACT_LOCK_DIGEST_MISMATCH: {entry.get('path')}")
    return issues


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) == 2 else Path(".")
    issues = validate(root)
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print(f"audit-ledger-contracts: PASS ({len(SCHEMAS)} controlled instances)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
