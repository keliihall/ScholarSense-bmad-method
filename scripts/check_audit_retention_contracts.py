#!/usr/bin/env python3
"""Validate Story 1.5 audit search, archive, retention and evidence contracts."""

from __future__ import annotations

import hashlib
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


sys.path.insert(0, str(Path(__file__).resolve().parent))

from release_json import canonical_sha256, load_json, schema_definition_issues, schema_issues  # noqa: E402


CONTRACTS = Path("contracts/audit-retention")
LOCK = CONTRACTS / "audit-retention-contract-lock-1.0.0.json"
SCHEMAS = {
    CONTRACTS / "fixtures/valid/search-request.json": CONTRACTS / "search-request.schema.json",
    CONTRACTS / "fixtures/valid/search-response.json": CONTRACTS / "search-response.schema.json",
    CONTRACTS / "field-projection-profile-1.0.0.json": CONTRACTS / "field-projection-profile.schema.json",
    CONTRACTS / "fixtures/valid/archive-manifest.json": CONTRACTS / "archive-manifest.schema.json",
    CONTRACTS / "fixtures/valid/legal-hold.json": CONTRACTS / "legal-hold.schema.json",
    CONTRACTS / "fixtures/valid/retention-schedule.json": CONTRACTS / "retention-schedule.schema.json",
    CONTRACTS / "fixtures/valid/retention-execution.json": CONTRACTS / "retention-execution.schema.json",
    CONTRACTS / "fixtures/valid/deletion-receipt.json": CONTRACTS / "deletion-receipt-conformance.schema.json",
}
ERROR_CODES = {
    "AUDIT_SEARCH_INVALID_REQUEST",
    "AUDIT_SEARCH_FORBIDDEN",
    "AUDIT_SEARCH_PROJECTION_NOT_CAUGHT_UP",
    "AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE",
    "AUDIT_SEARCH_AUDIT_COMMIT_FAILED",
    "AUDIT_RETENTION_POLICY_INVALID",
    "AUDIT_RETENTION_LEDGER_UNHEALTHY",
    "AUDIT_RETENTION_ARCHIVE_UNAVAILABLE",
    "AUDIT_RETENTION_ARCHIVE_MISMATCH",
    "AUDIT_RETENTION_BLOCKED_BY_LEGAL_HOLD",
    "AUDIT_RETENTION_IDEMPOTENCY_CONFLICT",
    "AUDIT_RETENTION_FENCING_CONFLICT",
    "AUDIT_RETENTION_RECEIPT_BOUNDARY",
}
FORBIDDEN_KEYS = {
    "payload", "actorsearchtoken", "objectsearchtoken", "sourcesearchtoken",
    "grantsearchtoken", "aggregatesearchtoken", "archiveobjecturl", "rawip",
    "rawactorref", "rawobjectref", "deletionreceiptid",
}
HEX64 = re.compile(r"^[0-9a-f]{64}$")


def validate(project_root: Path) -> list[str]:
    root = project_root.resolve()
    issues: list[str] = []
    schemas: dict[Path, Any] = {}
    documents: dict[Path, Any] = {}

    for schema_path in sorted(set(SCHEMAS.values())):
        schema = _load(root / schema_path, issues, "AUDIT_RETENTION_SCHEMA_INVALID")
        if schema is not None:
            schemas[schema_path] = schema
            issues.extend(
                f"AUDIT_RETENTION_SCHEMA_INVALID: {schema_path}: {issue}"
                for issue in schema_definition_issues(schema)
            )

    for document_path, schema_path in SCHEMAS.items():
        document = _load(root / document_path, issues, "AUDIT_RETENTION_DOCUMENT_INVALID")
        if document is None:
            continue
        documents[document_path] = document
        schema = schemas.get(schema_path)
        if schema is not None:
            found = schema_issues(document, schema)
            if found:
                issues.append(f"AUDIT_RETENTION_FIXTURE_SCHEMA_REJECTED: {document_path}: {found[0]}")

    issues.extend(_projection_issues(documents))
    issues.extend(_search_issues(documents))
    issues.extend(_archive_issues(documents))
    issues.extend(_retention_issues(documents))
    issues.extend(_receipt_issues(documents))
    issues.extend(_forbidden_field_issues(documents))
    issues.extend(_negative_fixture_issues(root))
    issues.extend(_lock_issues(root))
    return sorted(set(issues))


def _load(path: Path, issues: list[str], code: str) -> Any | None:
    try:
        return load_json(path)
    except (OSError, ValueError) as error:
        issues.append(f"{code}: {path}: {error}")
        return None


def _projection_issues(documents: dict[Path, Any]) -> list[str]:
    profile = documents.get(CONTRACTS / "field-projection-profile-1.0.0.json")
    if not isinstance(profile, dict):
        return ["AUDIT_RETENTION_PROJECTION_INVALID"]
    expected = {
        "schemaVersion": "AUDIT-FIELD-PROJECTION-1.0.0",
        "rfpVersion": "RFP-1.0.0",
        "denyOverrides": True,
        "views": {
            "business": {
                "action": "audit.search-business-metadata",
                "classes": {"B": "clear", "I": "masked", "C": "hidden", "S": "hidden", "E": "hidden", "N": "hidden", "G": "clear", "T": "masked"},
            },
            "technical": {
                "action": "audit.search-technical-metadata",
                "classes": {"B": "clear", "I": "hidden", "C": "hidden", "S": "hidden", "E": "hidden", "N": "hidden", "G": "masked", "T": "clear"},
            },
        },
        "multiRoleResolution": "most-restrictive-applicable-projection",
        "hiddenKeyPolicy": "omit-before-dto-construction",
        "maskedValuePolicy": "request-local-fixed-alias",
    }
    return [] if profile == expected else ["AUDIT_RETENTION_PROJECTION_INVALID"]


def _search_issues(documents: dict[Path, Any]) -> list[str]:
    request = documents.get(CONTRACTS / "fixtures/valid/search-request.json")
    response = documents.get(CONTRACTS / "fixtures/valid/search-response.json")
    issues: list[str] = []
    if isinstance(request, dict):
        if request.get("size", 0) < 1 or request.get("size", 0) > 100 or request.get("page", -1) < 0:
            issues.append("AUDIT_RETENTION_SEARCH_PAGE_INVALID")
        if request.get("occurredFrom") and request.get("occurredTo"):
            if _instant(request["occurredFrom"]) >= _instant(request["occurredTo"]):
                issues.append("AUDIT_RETENTION_SEARCH_WINDOW_INVALID")
    if isinstance(response, dict):
        watermark = response.get("projectionWatermark", -1)
        head = response.get("sourceLedgerHead", -1)
        as_of = response.get("asOfSequence", -1)
        if as_of > min(head, watermark):
            issues.append("AUDIT_RETENTION_SEARCH_SNAPSHOT_INVALID")
        items = response.get("items", [])
        sort_keys = [(item.get("occurredAt"), item.get("ledgerSequence")) for item in items if isinstance(item, dict)]
        expected = sorted(sort_keys, key=lambda item: (_instant(item[0]), item[1]), reverse=True)
        if sort_keys != expected:
            issues.append("AUDIT_RETENTION_SEARCH_SORT_INVALID")
    return issues


def _archive_issues(documents: dict[Path, Any]) -> list[str]:
    manifest = documents.get(CONTRACTS / "fixtures/valid/archive-manifest.json")
    if not isinstance(manifest, dict):
        return ["AUDIT_RETENTION_ARCHIVE_MANIFEST_INVALID"]
    expected_count = manifest.get("sequenceEnd", 0) - manifest.get("sequenceStart", 0) + 1
    valid = (
        manifest.get("schemaVersion") == "AUDIT-ARCHIVE-MANIFEST-1.0.0"
        and manifest.get("scheduleVersion") == "RS-1.0.0"
        and manifest.get("recordCount") == expected_count
        and manifest.get("storageBoundary") == "independent-object-lock"
        and manifest.get("readBackVerified") is True
        and all(HEX64.fullmatch(str(manifest.get(key, ""))) for key in ("scopeHash", "firstPreviousHash", "lastEntryHash", "contentDigest"))
    )
    return [] if valid else ["AUDIT_RETENTION_ARCHIVE_MANIFEST_INVALID"]


def _retention_issues(documents: dict[Path, Any]) -> list[str]:
    schedule = documents.get(CONTRACTS / "fixtures/valid/retention-schedule.json")
    hold = documents.get(CONTRACTS / "fixtures/valid/legal-hold.json")
    execution = documents.get(CONTRACTS / "fixtures/valid/retention-execution.json")
    issues: list[str] = []
    if isinstance(schedule, dict):
        version = str(schedule.get("scheduleVersion", ""))
        if version.startswith("RS-") and not version.startswith("RS-1."):
            issues.append("AUDIT_RETENTION_UNKNOWN_MAJOR")
        if schedule.get("scheduleVersion") != "RS-1.0.0" or schedule.get("retentionYears") != 3 \
                or schedule.get("windowSemantics") != "[startAt,endAt)" or schedule.get("approved") is not True:
            issues.append("AUDIT_RETENTION_SCHEDULE_INVALID")
    if isinstance(hold, dict):
        if _instant(hold.get("startAt")) >= _instant(hold.get("endAt")) \
                or not (_instant(hold.get("startAt")) <= _instant(hold.get("reviewAt")) < _instant(hold.get("endAt"))):
            issues.append("AUDIT_RETENTION_HOLD_WINDOW_INVALID")
    if isinstance(execution, dict):
        version = str(execution.get("scheduleVersion", ""))
        if version != "RS-1.0.0":
            issues.append("AUDIT_RETENTION_UNKNOWN_MAJOR" if version.startswith("RS-2.") else "AUDIT_RETENTION_POLICY_DRIFT")
        if execution.get("scopeType") != "audit-domain" or execution.get("nonProductionEvidence") is not True \
                or execution.get("fixtureId") in (None, ""):
            issues.append("AUDIT_RETENTION_PRODUCTION_RECEIPT_FORBIDDEN")
        material = {
            "executionId": execution.get("executionId"),
            "scheduleVersion": execution.get("scheduleVersion"),
            "scopeHash": execution.get("scopeHash"),
            "asOfSequence": execution.get("asOfSequence"),
        }
        expected_digest = canonical_sha256(material)
        if execution.get("requestDigest") != expected_digest:
            issues.append("AUDIT_RETENTION_IDEMPOTENCY_INVALID")
        if execution.get("state") == "succeeded" and execution.get("unmetGuards") != []:
            issues.append("AUDIT_RETENTION_SUCCESS_WITH_UNMET_GUARDS")
    return issues


def _receipt_issues(documents: dict[Path, Any]) -> list[str]:
    receipt = documents.get(CONTRACTS / "fixtures/valid/deletion-receipt.json")
    if not isinstance(receipt, dict):
        return ["AUDIT_RETENTION_RECEIPT_CONFORMANCE_INVALID"]
    required = {"consumers", "readModels", "objects", "indexes", "caches"}
    watermarks = receipt.get("confirmedWatermarks", {})
    valid = (
        receipt.get("schemaVersion") == "DELETION-RECEIPT-CONFORMANCE-1.0.0"
        and receipt.get("conformanceOnly") is True
        and receipt.get("runtimeIssuable") is False
        and required == set(watermarks)
        and all(isinstance(value, int) and value >= 0 for value in watermarks.values())
        and receipt.get("backupLifecycleDays") == 35
        and receipt.get("backupExpired") is True
    )
    return [] if valid else ["AUDIT_RETENTION_RECEIPT_CONFORMANCE_INVALID"]


def _forbidden_field_issues(documents: dict[Path, Any]) -> list[str]:
    issues: list[str] = []

    def visit(value: Any) -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                if key.lower() in FORBIDDEN_KEYS:
                    issues.append(f"AUDIT_RETENTION_FORBIDDEN_FIELD: {key}")
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    for path, document in documents.items():
        if "deletion-receipt" not in path.name:
            visit(document)
    return issues


def _negative_fixture_issues(root: Path) -> list[str]:
    path = root / CONTRACTS / "fixtures/negative-fixtures-1.0.0.json"
    try:
        catalog = load_json(path)
    except (OSError, ValueError) as error:
        return [f"AUDIT_RETENTION_NEGATIVE_FIXTURES_INVALID: {error}"]
    expected = {
        "unknown-major", "hidden-field", "policy-drift", "token-leak",
        "forged-production-receipt", "missing-consumer-watermark", "backup-not-expired",
    }
    ids = {case.get("id") for case in catalog.get("cases", []) if isinstance(case, dict)}
    return [] if ids == expected else ["AUDIT_RETENTION_NEGATIVE_FIXTURES_INVALID"]


def _lock_issues(root: Path) -> list[str]:
    try:
        lock = load_json(root / LOCK)
    except (OSError, ValueError) as error:
        return [f"AUDIT_RETENTION_LOCK_INVALID: {error}"]
    expected_paths = sorted(
        str(path.relative_to(root))
        for path in (root / CONTRACTS).rglob("*.json")
        if path.relative_to(root) != LOCK
    )
    entries = lock.get("files", []) if isinstance(lock, dict) else []
    locked_paths = [entry.get("path") for entry in entries if isinstance(entry, dict)]
    issues: list[str] = []
    if lock.get("version") != "AUDIT-RETENTION-CONTRACT-LOCK-1.0.0" or locked_paths != expected_paths:
        issues.append("AUDIT_RETENTION_LOCK_INVENTORY_MISMATCH")
    for entry in entries:
        path = root / str(entry.get("path"))
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != entry.get("sha256"):
            issues.append(f"AUDIT_RETENTION_LOCK_DIGEST_MISMATCH: {entry.get('path')}")
    consumed = lock.get("consumedLockDigests", {})
    for name, relative in {
        "audit": Path("contracts/audit/audit-contract-lock-1.0.0.json"),
        "auditLedger": Path("contracts/audit-ledger/audit-ledger-contract-lock-1.0.0.json"),
    }.items():
        path = root / relative
        if not path.is_file() or consumed.get(name) != hashlib.sha256(path.read_bytes()).hexdigest():
            issues.append(f"AUDIT_RETENTION_CONSUMED_LOCK_DRIFT: {name}")
    return issues


def _instant(value: Any) -> datetime:
    if not isinstance(value, str):
        return datetime.min.astimezone()
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def main(argv: list[str]) -> int:
    root = Path(argv[1] if len(argv) > 1 else ".")
    issues = validate(root)
    if issues:
        for issue in issues:
            print(issue, file=sys.stderr)
        return 1
    print(f"audit-retention-contracts: PASS ({len(SCHEMAS)} controlled documents)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
