#!/usr/bin/env python3
"""Validate Story 2.3 executable quality policy, vectors, successors, and locks."""

from __future__ import annotations

import copy
import hashlib
import json
import sys
from calendar import monthrange
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


sys.path.insert(0, str(Path(__file__).resolve().parent))

from release_json import (  # noqa: E402
    canonical_bytes,
    load_json,
    parse_json_bytes,
    schema_definition_issues,
    schema_issues,
)


BATCH_QUALITY = Path("contracts/ingestion-quality/batch-quality")
DATA_CATALOG = Path("contracts/data-catalog")
POLICY = BATCH_QUALITY / "executable-quality-policy-1.0.0.json"
POLICY_SCHEMA = BATCH_QUALITY / "executable-quality-policy.schema.json"
METRIC_SCHEMA = BATCH_QUALITY / "metric-definition.schema.json"
VECTOR_SCHEMA = BATCH_QUALITY / "quality-metric-vectors.schema.json"
VECTORS = BATCH_QUALITY / "fixtures/valid/quality-metric-vectors-1.0.0.json"
NEGATIVE_VECTORS = BATCH_QUALITY / "fixtures/invalid/negative-fixtures-1.0.0.json"
LOCK_SCHEMA = BATCH_QUALITY / "executable-quality-contract-lock.schema.json"
LOCK = BATCH_QUALITY / "executable-quality-contract-lock-1.0.0.json"
DCC_PREDECESSOR = DATA_CATALOG / "dcc-1.0.0.json"
DCC_SUCCESSOR = DATA_CATALOG / "dcc-1.1.0.json"
DCC_SUCCESSOR_SCHEMA = DATA_CATALOG / "data-source-catalog-1.1.0.schema.json"
SOURCE_DESCRIPTOR_SCHEMA = DATA_CATALOG / "source-contract.schema.json"
QG = DATA_CATALOG / "qg-1.0.0.json"
LIFECYCLE_SCHEMA = BATCH_QUALITY / "batch-quality-lifecycle.schema.json"
LIFECYCLE = BATCH_QUALITY / "batch-quality-lifecycle-1.0.0.json"
RETENTION_SCHEMA = BATCH_QUALITY / "quality-snapshot-retention.schema.json"
RETENTION = BATCH_QUALITY / "quality-snapshot-retention-1.0.0.json"
RETENTION_VECTOR_SCHEMA = (
    BATCH_QUALITY / "quality-snapshot-retention-vectors.schema.json"
)
RETENTION_VECTORS = (
    BATCH_QUALITY
    / "fixtures/valid/quality-snapshot-retention-vectors-1.0.0.json"
)
TASK_0_4_NEGATIVE = (
    BATCH_QUALITY / "fixtures/invalid/task-0-4-negative-fixtures-1.0.0.json"
)
DELETION_EVENT_BASE = Path("contracts/events/ingestion-quality")
DELETION_EVENT_SCHEMA = (
    DELETION_EVENT_BASE / "quality-snapshot-deletion-result.schema.json"
)
DELETION_VALID_FIXTURES = {
    outcome: DELETION_EVENT_BASE
    / f"fixtures/valid/quality-snapshot-deletion-{outcome}-v1.json"
    for outcome in ("completed", "blocked", "partial", "failed")
}
DELETION_ORDERING = (
    DELETION_EVENT_BASE
    / "fixtures/ordering/quality-snapshot-deletion-ordering-1.0.0.json"
)
PUBLIC_EVENT_ENVELOPE = Path("contracts/events/envelope.schema.json")

MAX_EVENT_BYTES = 64 * 1024
MAX_SAFE_INTEGER = 9_007_199_254_740_991
UUID_V7_PATTERN = (
    "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    "[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
OWNER_LOCAL_RESULT_KEYS = frozenset({
    "onlineSnapshot",
    "onlineMetrics",
    "readModels",
    "indexes",
    "caches",
    "objects",
})

TASK_0_4_UPSTREAM_RAW_DIGESTS = {
    "contracts/events/envelope.schema.json":
        "10d75ab24a6df63a2520f263ba29c9f6f45bc5e78165deb20109b37fd4eadd6d",
    "contracts/audit-retention/audit-retention-contract-lock-1.0.0.json":
        "321755d18d6de7ee8eef436bd070a5d5f6ad94773bbbe686ba89b0cf21926c8b",
    "contracts/audit-retention/deletion-receipt-conformance.schema.json":
        "ac8be47069b23a9885d361ee513ec9d1880b080c86278cdba6ab2791fcc356bc",
    "_bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md":
        "94e0943902140ee0d3f79fceab711e1498a6186a0402f7a26bf7f20bf7c9d97a",
    "_bmad-output/planning-artifacts/sprint-change-proposal-2026-08-08.md":
        "3e63dd42179adb48bed6b405e266cefda45cee3de4335aa29caf5c96e767f1f5",
    "contracts/field-projection/field-projection-contract-lock-1.1.0.json":
        "91270f357115d3e7f9273723b677143e6d3adbc5a9a0e3f249279c64ebbd5a11",
    "contracts/release/canonical-json-profile-1.0.0.json":
        "28cfa27dfc947d1a352f2e68e9f5df7274d582dcc666a3a46f13f65898a15245",
    "contracts/release/canonical-json-test-vectors-1.0.0.json":
        "8d130b1c22e4fce8ff4fa8c2eff6bde6d5dc20cede7721fd8e71ed6c6dbbc0a7",
}

HISTORICAL_RAW_DIGESTS = {
    "contracts/data-catalog/dcc-1.0.0.json":
        "755c28e7bbecdcb5f30e50b8bb8e8c9d549663fe65d0a20e88aebf90ba4a1586",
    "contracts/data-catalog/qg-1.0.0.json":
        "1789c6099ef3a48b87714394c903449c8f75b9ce92cba300f752da33f7e2bced",
    "contracts/data-catalog/quality-gate.schema.json":
        "1cc21fdc51ca6b60151fc89f2adc8cf7f252ae775e64cbf1bd43929447ae7ff5",
    "contracts/data-catalog/data-catalog-contract-lock-1.0.1.json":
        "0192c0f777e40e647c73f5dd455054c718f0d2d8efac9c67a36b025566857463",
}

EXECUTABLE_LOCKED_FILES = frozenset({
    "contracts/data-catalog/data-source-catalog-1.1.0.schema.json",
    "contracts/data-catalog/dcc-1.1.0.json",
    "contracts/data-catalog/qg-1.0.0.json",
    "contracts/data-catalog/sources/src-p0-accommodation-001.schema.json",
    "contracts/data-catalog/sources/src-p0-calendar-001.schema.json",
    "contracts/data-catalog/sources/src-p0-campus-access-001.schema.json",
    "contracts/data-catalog/sources/src-p0-card-001.schema.json",
    "contracts/data-catalog/sources/src-p0-device-001.schema.json",
    "contracts/data-catalog/sources/src-p0-dorm-access-001.schema.json",
    "contracts/data-catalog/sources/src-p0-leave-001.schema.json",
    "contracts/data-catalog/sources/src-p0-responsibility-001-2.1.0.schema.json",
    "contracts/data-catalog/sources/src-p0-student-001.schema.json",
    "contracts/data-catalog/sources/src-p0-timetable-001.schema.json",
    "contracts/data-catalog/sources/src-p1-academic-001.schema.json",
    "contracts/data-catalog/sources/src-p1-aid-001.schema.json",
    "contracts/data-catalog/sources/src-p1-care-list-001-1.1.0.schema.json",
    "contracts/data-catalog/sources/src-p1-network-001.schema.json",
    "contracts/data-catalog/sources/src-p1-offcampus-001.schema.json",
    "contracts/data-catalog/sources/src-p1-psych-deid-001.schema.json",
    "contracts/data-catalog/sources/src-p1-work-visit-001.schema.json",
    "contracts/ingestion-quality/batch-quality/executable-quality-contract-lock.schema.json",
    "contracts/ingestion-quality/batch-quality/executable-quality-policy-1.0.0.json",
    "contracts/ingestion-quality/batch-quality/executable-quality-policy.schema.json",
    "contracts/ingestion-quality/batch-quality/fixtures/invalid/negative-fixtures-1.0.0.json",
    "contracts/ingestion-quality/batch-quality/fixtures/valid/quality-metric-vectors-1.0.0.json",
    "contracts/ingestion-quality/batch-quality/metric-definition.schema.json",
    "contracts/ingestion-quality/batch-quality/quality-metric-vectors.schema.json",
    "contracts/ingestion-quality/batch-quality/batch-quality-lifecycle.schema.json",
    "contracts/ingestion-quality/batch-quality/batch-quality-lifecycle-1.0.0.json",
    "contracts/ingestion-quality/batch-quality/quality-snapshot-retention.schema.json",
    "contracts/ingestion-quality/batch-quality/quality-snapshot-retention-1.0.0.json",
    "contracts/ingestion-quality/batch-quality/quality-snapshot-retention-vectors.schema.json",
    "contracts/ingestion-quality/batch-quality/fixtures/valid/quality-snapshot-retention-vectors-1.0.0.json",
    "contracts/ingestion-quality/batch-quality/fixtures/invalid/task-0-4-negative-fixtures-1.0.0.json",
    "contracts/events/envelope.schema.json",
    "contracts/events/ingestion-quality/quality-snapshot-deletion-result.schema.json",
    "contracts/events/ingestion-quality/fixtures/valid/quality-snapshot-deletion-completed-v1.json",
    "contracts/events/ingestion-quality/fixtures/valid/quality-snapshot-deletion-blocked-v1.json",
    "contracts/events/ingestion-quality/fixtures/valid/quality-snapshot-deletion-partial-v1.json",
    "contracts/events/ingestion-quality/fixtures/valid/quality-snapshot-deletion-failed-v1.json",
    "contracts/events/ingestion-quality/fixtures/ordering/quality-snapshot-deletion-ordering-1.0.0.json",
    "contracts/audit-retention/audit-retention-contract-lock-1.0.0.json",
    "contracts/audit-retention/deletion-receipt-conformance.schema.json",
    "_bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md",
    "_bmad-output/planning-artifacts/sprint-change-proposal-2026-08-08.md",
    "contracts/field-projection/field-projection-contract-lock-1.1.0.json",
    "contracts/release/canonical-json-profile-1.0.0.json",
    "contracts/release/canonical-json-test-vectors-1.0.0.json",
})

COMMON_METRIC_ORDER = (
    "PRIMARY_KEY_COMPLETENESS_BP",
    "P0_SUBJECT_MAPPING_BP",
    "REQUIRED_FIELD_VALIDITY_BP",
    "VALID_RECORD_RATE_BP",
    "CORE_FIELD_COVERAGE_BP",
    "FRESHNESS_WITHIN_SLO_BP",
    "UNRESOLVED_INTERVAL_CONFLICT_COUNT",
    "DUPLICATE_BUSINESS_KEY_COUNT",
    "VERSION_REGRESSION_COUNT",
    "SOURCE_CONTINUITY_GATE",
    "SCHEMA_ALLOWLIST_COMPATIBILITY_BP",
    "FORBIDDEN_FIELD_COUNT",
)

MANIFEST_FRESHNESS_BINDING = {
    "sourceOccurredAt": "manifest.sourceOccurredAt",
    "scheduledDueAt": "manifest.scheduledDueAt",
    "receivedAt": "manifest.receivedAt",
    "laneId": "manifest.laneId",
}

P0_MAPPING_SOURCES = frozenset({
    "SRC-P0-STUDENT-001",
    "SRC-P0-ACCOMMODATION-001",
    "SRC-P0-CARD-001",
    "SRC-P0-CAMPUS-ACCESS-001",
    "SRC-P0-DORM-ACCESS-001",
    "SRC-P0-LEAVE-001",
    "SRC-P0-TIMETABLE-001",
})

COMMON_SPECS = {
    "PRIMARY_KEY_COMPLETENESS_BP": ("primary-key", "ratio", "manifest-records-with-complete-valid-business-key", "manifest-declared-record-count", "basis-point", ">=", 9950, 10000, "always"),
    "P0_SUBJECT_MAPPING_BP": ("primary-key", "ratio", "p0-records-with-unique-student-ref", "p0-records-requiring-subject-mapping", "basis-point", ">=", 9950, 10000, "source-in-approved-set"),
    "REQUIRED_FIELD_VALIDITY_BP": ("coverage", "ratio", "records-with-valid-required-field-set", "manifest-declared-record-count", "basis-point", "=", 1, 1, "always"),
    "VALID_RECORD_RATE_BP": ("coverage", "ratio", "records-passing-schema-interval-purpose", "manifest-declared-record-count", "basis-point", ">=", 9950, 10000, "always"),
    "CORE_FIELD_COVERAGE_BP": ("coverage", "ratio", "valid-core-field-cells", "expected-core-field-cells", "basis-point", ">=", 9800, 10000, "source-field-group-present"),
    "FRESHNESS_WITHIN_SLO_BP": ("freshness", "ratio", "on-time-delivery-units", "expected-delivery-units", "basis-point", ">=", 9900, 10000, "always"),
    "UNRESOLVED_INTERVAL_CONFLICT_COUNT": ("continuity", "count", "unresolved-interval-conflicts", 1, "count", "=", 0, 1, "always"),
    "DUPLICATE_BUSINESS_KEY_COUNT": ("continuity", "count", "duplicate-business-keys", 1, "count", "=", 0, 1, "always"),
    "VERSION_REGRESSION_COUNT": ("continuity", "count", "version-regressions", 1, "count", "=", 0, 1, "always"),
    "SOURCE_CONTINUITY_GATE": ("continuity", "composite-and", "passing-member-count", "applicable-member-count", "member-count", "=", 1, 1, "always"),
    "SCHEMA_ALLOWLIST_COMPATIBILITY_BP": ("compatibility", "ratio", "schema-allowlist-compatible-records", "manifest-declared-record-count", "basis-point", "=", 1, 1, "always"),
    "FORBIDDEN_FIELD_COUNT": ("privacy", "count", "forbidden-field-hits", 1, "count", "=", 0, 1, "always"),
}

EXPECTED_SOURCE_GATES = {
    "SRC-P0-STUDENT-001": ("student-core-field-group", "student-effective-interval-conflict"),
    "SRC-P0-RESPONSIBILITY-001": ("active-authority-unmapped", "revocation-duration", "manifest-reconcile"),
    "SRC-P0-ACCOMMODATION-001": ("concurrent-interval-conflict", "accommodation-core-field-group"),
    "SRC-P0-CARD-001": ("card-core-field-group", "correction-chain-complete"),
    "SRC-P0-CAMPUS-ACCESS-001": ("event-id-duplicate", "device-direction-time-field-group"),
    "SRC-P0-DORM-ACCESS-001": ("event-id-duplicate", "building-catalog-map"),
    "SRC-P0-DEVICE-001": ("device-catalog-map", "heartbeat-gap-explained"),
    "SRC-P0-LEAVE-001": ("leave-core-field-group", "filing-interval-conflict"),
    "SRC-P0-CALENDAR-001": ("calendar-exactly-one-current-day-type",),
    "SRC-P0-TIMETABLE-001": ("timetable-core-field-group", "business-key-version-regression"),
    "SRC-P1-OFFCAMPUS-001": ("p0-accommodation-overlap-disambiguation",),
    "SRC-P1-NETWORK-001": ("forbidden-content-field-count", "manifest-partition-reconcile"),
    "SRC-P1-ACADEMIC-001": ("manifest-reconcile", "seal-correction-chain-complete"),
    "SRC-P1-CARE-LIST-001": ("care-list-core-field-group",),
    "SRC-P1-PSYCH-DEID-001": ("psych-core-field-allowlist", "forbidden-content-field-count"),
    "SRC-P1-AID-001": ("aid-core-field-group",),
    "SRC-P1-WORK-VISIT-001": ("work-visit-required-field-group",),
}

EXPECTED_GATE_SPECS = {
    ("SRC-P0-STUDENT-001", "student-core-field-group"): ("CORE_FIELD_COVERAGE_BP", "ratio", "records-passing-source-field-group", "records-applicable-to-source-field-group", "basis-point", ">=", 9950, 10000, "source-field-group-present", ("studentRef", "effectiveFrom", "effectiveTo")),
    ("SRC-P0-STUDENT-001", "student-effective-interval-conflict"): ("UNRESOLVED_INTERVAL_CONFLICT_COUNT", "count", "unresolved-interval-conflicts", 1, "count", "=", 0, 1, "always", None),
    ("SRC-P0-RESPONSIBILITY-001", "active-authority-unmapped"): ("SOURCE_CONTINUITY_GATE", "count", "unmapped-active-authority-evidence", 1, "count", "=", 0, 1, "always", None),
    ("SRC-P0-RESPONSIBILITY-001", "revocation-duration"): ("SOURCE_CONTINUITY_GATE", "duration", "revocation-duration-milliseconds", 1, "millisecond", "<=", 900000, 1, "always", None),
    ("SRC-P0-RESPONSIBILITY-001", "manifest-reconcile"): ("VALID_RECORD_RATE_BP", "ratio", "manifest-reconciled-records", "manifest-declared-record-count", "basis-point", ">=", 9990, 10000, "always", None),
    ("SRC-P0-ACCOMMODATION-001", "concurrent-interval-conflict"): ("UNRESOLVED_INTERVAL_CONFLICT_COUNT", "count", "concurrent-interval-conflicts", 1, "count", "=", 0, 1, "always", None),
    ("SRC-P0-ACCOMMODATION-001", "accommodation-core-field-group"): ("CORE_FIELD_COVERAGE_BP", "ratio", "records-passing-source-field-group", "records-applicable-to-source-field-group", "basis-point", ">=", 9950, 10000, "source-field-group-present", ("studentRef", "accommodationType", "campusCode", "effectiveFrom", "effectiveTo")),
    ("SRC-P0-CARD-001", "card-core-field-group"): ("CORE_FIELD_COVERAGE_BP", "ratio", "records-passing-source-field-group", "records-applicable-to-source-field-group", "basis-point", ">=", 9950, 10000, "source-field-group-present", ("eventId", "subjectRef", "categoryCode", "amountMinor", "currency")),
    ("SRC-P0-CARD-001", "correction-chain-complete"): ("SOURCE_CONTINUITY_GATE", "count", "correction-chain-violations", 1, "count", "=", 0, 1, "always", None),
    ("SRC-P0-CAMPUS-ACCESS-001", "event-id-duplicate"): ("DUPLICATE_BUSINESS_KEY_COUNT", "count", "duplicate-event-ids", 1, "count", "=", 0, 1, "always", None),
    ("SRC-P0-CAMPUS-ACCESS-001", "device-direction-time-field-group"): ("CORE_FIELD_COVERAGE_BP", "ratio", "records-passing-source-field-group", "records-applicable-to-source-field-group", "basis-point", ">=", 9990, 10000, "source-field-group-present", ("deviceId", "direction", "occurredAt")),
    ("SRC-P0-DORM-ACCESS-001", "event-id-duplicate"): ("DUPLICATE_BUSINESS_KEY_COUNT", "count", "duplicate-event-ids", 1, "count", "=", 0, 1, "always", None),
    ("SRC-P0-DORM-ACCESS-001", "building-catalog-map"): ("CORE_FIELD_COVERAGE_BP", "ratio", "building-catalog-mapped-records", "records-applicable-to-source-field-group", "basis-point", ">=", 9990, 10000, "source-field-group-present", ("buildingCode",)),
    ("SRC-P0-DEVICE-001", "device-catalog-map"): ("CORE_FIELD_COVERAGE_BP", "ratio", "device-map-valid-records", "records-applicable-to-source-field-group", "basis-point", "=", 1, 1, "source-field-group-present", ("deviceId", "locationCode", "appliesToSourceId")),
    ("SRC-P0-DEVICE-001", "heartbeat-gap-explained"): ("SOURCE_CONTINUITY_GATE", "count", "unexplained-heartbeat-gaps", 1, "count", "=", 0, 1, "always", None),
    ("SRC-P0-LEAVE-001", "leave-core-field-group"): ("CORE_FIELD_COVERAGE_BP", "ratio", "records-passing-source-field-group", "records-applicable-to-source-field-group", "basis-point", ">=", 9990, 10000, "source-field-group-present", ("filingType", "effectiveFrom", "effectiveTo", "approvalState", "sourceVersion")),
    ("SRC-P0-LEAVE-001", "filing-interval-conflict"): ("UNRESOLVED_INTERVAL_CONFLICT_COUNT", "count", "unresolved-interval-conflicts", 1, "count", "=", 0, 1, "always", None),
    ("SRC-P0-CALENDAR-001", "calendar-exactly-one-current-day-type"): ("SOURCE_CONTINUITY_GATE", "ratio", "calendar-current-days", "calendar-expected-days", "basis-point", "=", 1, 1, "always", None),
    ("SRC-P0-TIMETABLE-001", "timetable-core-field-group"): ("CORE_FIELD_COVERAGE_BP", "ratio", "records-passing-source-field-group", "records-applicable-to-source-field-group", "basis-point", ">=", 9950, 10000, "source-field-group-present", ("activityState", "campusCode", "locationCode", "enrollmentState", "effectiveAt")),
    ("SRC-P0-TIMETABLE-001", "business-key-version-regression"): ("VERSION_REGRESSION_COUNT", "count", "version-regressions", 1, "count", "=", 0, 1, "always", None),
    ("SRC-P1-OFFCAMPUS-001", "p0-accommodation-overlap-disambiguation"): ("CORE_FIELD_COVERAGE_BP", "ratio", "overlap-disambiguated-records", "overlap-records", "basis-point", "=", 1, 1, "overlap-records-present", ("p0AccommodationDisambiguation",)),
    ("SRC-P1-NETWORK-001", "forbidden-content-field-count"): ("FORBIDDEN_FIELD_COUNT", "count", "forbidden-content-hits", 1, "count", "=", 0, 1, "always", None),
    ("SRC-P1-NETWORK-001", "manifest-partition-reconcile"): ("VALID_RECORD_RATE_BP", "ratio", "manifest-reconciled-partitions", "expected-delivery-units", "basis-point", "=", 1, 1, "always", None),
    ("SRC-P1-ACADEMIC-001", "manifest-reconcile"): ("VALID_RECORD_RATE_BP", "ratio", "manifest-reconciled-records", "manifest-declared-record-count", "basis-point", "=", 1, 1, "always", None),
    ("SRC-P1-ACADEMIC-001", "seal-correction-chain-complete"): ("SOURCE_CONTINUITY_GATE", "count", "chain-violations", 1, "count", "=", 0, 1, "always", None),
    ("SRC-P1-CARE-LIST-001", "care-list-core-field-group"): ("CORE_FIELD_COVERAGE_BP", "ratio", "records-passing-source-field-group", "records-applicable-to-source-field-group", "basis-point", "=", 1, 1, "source-field-group-present", ("purpose", "evidenceRef", "effectiveFrom", "effectiveTo", "approvalVersion")),
    ("SRC-P1-PSYCH-DEID-001", "psych-core-field-allowlist"): ("CORE_FIELD_COVERAGE_BP", "ratio", "allowlist-valid-records", "records-applicable-to-source-field-group", "basis-point", "=", 1, 1, "source-field-group-present", ("category", "purpose", "effectiveFrom", "effectiveTo")),
    ("SRC-P1-PSYCH-DEID-001", "forbidden-content-field-count"): ("FORBIDDEN_FIELD_COUNT", "count", "forbidden-content-hits", 1, "count", "=", 0, 1, "always", None),
    ("SRC-P1-AID-001", "aid-core-field-group"): ("CORE_FIELD_COVERAGE_BP", "ratio", "records-passing-source-field-group", "records-applicable-to-source-field-group", "basis-point", "=", 1, 1, "source-field-group-present", ("purpose", "effectiveFrom", "effectiveTo", "approvalVersion")),
    ("SRC-P1-WORK-VISIT-001", "work-visit-required-field-group"): ("REQUIRED_FIELD_VALIDITY_BP", "ratio", "records-passing-source-field-group", "records-applicable-to-source-field-group", "basis-point", ">=", 9950, 10000, "source-field-group-present", ("visitId", "visitorRef", "localDate", "areaCode", "visitMode", "controlledSummaryCode", "workVisitPolicyVersion", "sourceVersion")),
}

EXPECTED_LANES = {
    "SRC-P0-STUDENT-001": ("incremental-record", "daily-full-partition"),
    "SRC-P0-RESPONSIBILITY-001": ("incremental-authority-fact", "daily-full-partition"),
    "SRC-P0-ACCOMMODATION-001": ("change-record", "daily-full-partition"),
    "SRC-P0-CARD-001": ("transaction-record", "settlement-partition"),
    "SRC-P0-CAMPUS-ACCESS-001": ("event-record", "daily-reconcile-partition"),
    "SRC-P0-DORM-ACCESS-001": ("event-record", "daily-reconcile-partition"),
    "SRC-P0-DEVICE-001": ("heartbeat-record", "fault-fact"),
    "SRC-P0-LEAVE-001": ("approval-revocation-record", "daily-reconcile-partition"),
    "SRC-P0-CALENDAR-001": ("normal-projection", "emergency-correction"),
    "SRC-P0-TIMETABLE-001": ("cancellation-reschedule-record", "daily-full-partition"),
    "SRC-P1-OFFCAMPUS-001": ("change-record", "daily-reconcile-partition"),
    "SRC-P1-NETWORK-001": ("complete-partition",),
    "SRC-P1-ACADEMIC-001": ("sealed-batch", "correction-record"),
    "SRC-P1-CARE-LIST-001": ("change-record", "revoke-expiry-record"),
    "SRC-P1-PSYCH-DEID-001": ("batch-partition",),
    "SRC-P1-AID-001": ("batch-partition", "revoke-record"),
    "SRC-P1-WORK-VISIT-001": ("submission-partition",),
}

EXPECTED_LANE_SPECS = {
    ("SRC-P0-STUDENT-001", "incremental-record"): ("record", {"kind":"duration","laterField":"manifest.receivedAt","earlierField":"record.sourceUpdatedAt","maxDurationMilliseconds":14400000}),
    ("SRC-P0-STUDENT-001", "daily-full-partition"): ("scheduled-partition", {"kind":"local-cutoff","laterField":"manifest.receivedAt","earlierField":"manifest.scheduledDueAt","dueLocalTime":"06:00:00","dayOffset":0}),
    ("SRC-P0-RESPONSIBILITY-001", "incremental-authority-fact"): ("authority-fact", {"kind":"duration","laterField":"manifest.receivedAt","earlierField":"manifest.sourceOccurredAt","maxDurationMilliseconds":900000}),
    ("SRC-P0-RESPONSIBILITY-001", "daily-full-partition"): ("scheduled-partition", {"kind":"local-cutoff","laterField":"manifest.receivedAt","earlierField":"manifest.scheduledDueAt","dueLocalTime":"06:00:00","dayOffset":0}),
    ("SRC-P0-ACCOMMODATION-001", "change-record"): ("record", {"kind":"duration","laterField":"manifest.receivedAt","earlierField":"record.sourceUpdatedAt","maxDurationMilliseconds":3600000}),
    ("SRC-P0-ACCOMMODATION-001", "daily-full-partition"): ("scheduled-partition", {"kind":"local-cutoff","laterField":"manifest.receivedAt","earlierField":"manifest.scheduledDueAt","dueLocalTime":"06:00:00","dayOffset":0}),
    ("SRC-P0-CARD-001", "transaction-record"): ("record", {"kind":"duration","laterField":"record.receivedAt","earlierField":"record.occurredAt","maxDurationMilliseconds":900000}),
    ("SRC-P0-CARD-001", "settlement-partition"): ("scheduled-partition", {"kind":"local-cutoff","laterField":"manifest.receivedAt","earlierField":"manifest.scheduledDueAt","dueLocalTime":"06:00:00","dayOffset":1}),
    ("SRC-P0-CAMPUS-ACCESS-001", "event-record"): ("record", {"kind":"duration","laterField":"record.receivedAt","earlierField":"record.occurredAt","maxDurationMilliseconds":300000}),
    ("SRC-P0-CAMPUS-ACCESS-001", "daily-reconcile-partition"): ("scheduled-partition", {"kind":"local-cutoff","laterField":"manifest.receivedAt","earlierField":"manifest.scheduledDueAt","dueLocalTime":"06:00:00","dayOffset":0}),
    ("SRC-P0-DORM-ACCESS-001", "event-record"): ("record", {"kind":"duration","laterField":"record.receivedAt","earlierField":"record.occurredAt","maxDurationMilliseconds":300000}),
    ("SRC-P0-DORM-ACCESS-001", "daily-reconcile-partition"): ("scheduled-partition", {"kind":"local-cutoff","laterField":"manifest.receivedAt","earlierField":"manifest.scheduledDueAt","dueLocalTime":"06:00:00","dayOffset":0}),
    ("SRC-P0-DEVICE-001", "heartbeat-record"): ("record", {"kind":"duration","laterField":"manifest.receivedAt","earlierField":"record.heartbeatAt","maxDurationMilliseconds":300000}),
    ("SRC-P0-DEVICE-001", "fault-fact"): ("fault-fact", {"kind":"duration","laterField":"manifest.receivedAt","earlierField":"record.effectiveFrom","maxDurationMilliseconds":600000}),
    ("SRC-P0-LEAVE-001", "approval-revocation-record"): ("record", {"kind":"duration","laterField":"manifest.receivedAt","earlierField":"record.sourceUpdatedAt","maxDurationMilliseconds":900000}),
    ("SRC-P0-LEAVE-001", "daily-reconcile-partition"): ("scheduled-partition", {"kind":"local-cutoff","laterField":"manifest.receivedAt","earlierField":"manifest.scheduledDueAt","dueLocalTime":"06:00:00","dayOffset":0}),
    ("SRC-P0-CALENDAR-001", "normal-projection"): ("calendar-projection", {"kind":"advance-horizon","laterField":"manifest.scheduledDueAt","earlierField":"manifest.receivedAt","minimumLeadMilliseconds":604800000}),
    ("SRC-P0-CALENDAR-001", "emergency-correction"): ("record", {"kind":"duration","laterField":"manifest.receivedAt","earlierField":"record.effectiveAt","maxDurationMilliseconds":3600000}),
    ("SRC-P0-TIMETABLE-001", "cancellation-reschedule-record"): ("record", {"kind":"duration","laterField":"manifest.receivedAt","earlierField":"record.sourceUpdatedAt","maxDurationMilliseconds":1800000}),
    ("SRC-P0-TIMETABLE-001", "daily-full-partition"): ("scheduled-partition", {"kind":"local-cutoff","laterField":"manifest.receivedAt","earlierField":"manifest.scheduledDueAt","dueLocalTime":"06:00:00","dayOffset":0}),
    ("SRC-P1-OFFCAMPUS-001", "change-record"): ("record", {"kind":"duration","laterField":"manifest.receivedAt","earlierField":"record.effectiveFrom","maxDurationMilliseconds":3600000}),
    ("SRC-P1-OFFCAMPUS-001", "daily-reconcile-partition"): ("scheduled-partition", {"kind":"local-cutoff","laterField":"manifest.receivedAt","earlierField":"manifest.scheduledDueAt","dueLocalTime":"06:00:00","dayOffset":0}),
    ("SRC-P1-NETWORK-001", "complete-partition"): ("scheduled-partition", {"kind":"local-cutoff","laterField":"manifest.receivedAt","earlierField":"manifest.scheduledDueAt","dueLocalTime":"08:00:00","dayOffset":1}),
    ("SRC-P1-ACADEMIC-001", "sealed-batch"): ("sealed-batch", {"kind":"local-cutoff","laterField":"manifest.receivedAt","earlierField":"manifest.scheduledDueAt","dueLocalTime":"08:00:00","dayOffset":1}),
    ("SRC-P1-ACADEMIC-001", "correction-record"): ("record", {"kind":"duration","laterField":"manifest.receivedAt","earlierField":"manifest.sourceOccurredAt","maxDurationMilliseconds":14400000}),
    ("SRC-P1-CARE-LIST-001", "change-record"): ("record", {"kind":"duration","laterField":"manifest.receivedAt","earlierField":"manifest.sourceOccurredAt","maxDurationMilliseconds":3600000}),
    ("SRC-P1-CARE-LIST-001", "revoke-expiry-record"): ("record", {"kind":"duration","laterField":"manifest.receivedAt","earlierField":"manifest.sourceOccurredAt","maxDurationMilliseconds":900000}),
    ("SRC-P1-PSYCH-DEID-001", "batch-partition"): ("scheduled-partition", {"kind":"local-cutoff","laterField":"manifest.receivedAt","earlierField":"manifest.scheduledDueAt","dueLocalTime":"08:00:00","dayOffset":1}),
    ("SRC-P1-AID-001", "batch-partition"): ("scheduled-partition", {"kind":"local-cutoff","laterField":"manifest.receivedAt","earlierField":"manifest.scheduledDueAt","dueLocalTime":"08:00:00","dayOffset":1}),
    ("SRC-P1-AID-001", "revoke-record"): ("record", {"kind":"duration","laterField":"manifest.receivedAt","earlierField":"manifest.sourceOccurredAt","maxDurationMilliseconds":14400000}),
    ("SRC-P1-WORK-VISIT-001", "submission-partition"): ("scheduled-partition", {"kind":"local-cutoff","laterField":"manifest.receivedAt","earlierField":"manifest.scheduledDueAt","dueLocalTime":"08:00:00","dayOffset":1}),
}

MANDATORY_VECTOR_CASES = frozenset({
    "ratio-boundary-pass",
    "ratio-one-unit-below-fail",
    "ratio-one-unit-above-pass",
    "zero-denominator-evaluation-error",
    "count-zero-pass",
    "count-one-fail",
    "duration-boundary-pass",
    "duration-one-millisecond-over-fail",
    "not-applicable-closed-predicate",
    "shanghai-cutoff-inclusive-pass",
    "source-override-boundary-pass",
    "source-override-one-unit-below-fail",
})

_DEFAULT_ROOT = Path(__file__).resolve().parents[1]


def _raw_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _canonical_digest(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_bytes(value)).hexdigest()


def _has_float(value: Any) -> bool:
    if isinstance(value, float):
        return True
    if isinstance(value, dict):
        return any(_has_float(child) for child in value.values())
    if isinstance(value, list):
        return any(_has_float(child) for child in value)
    return False


def _operand(value: str | int) -> dict[str, Any]:
    if isinstance(value, int):
        return {"kind": "constant", "value": value}
    return {"kind": "measured", "operandId": value}


def _half_up_basis_points(numerator: int, denominator: int) -> int:
    scaled = numerator * 10000
    quotient, remainder = divmod(scaled, denominator)
    return quotient + (1 if remainder * 2 >= denominator else 0)


def _metric_index(policy: dict[str, Any]) -> dict[tuple[str | None, str | None, str], dict[str, Any]]:
    index: dict[tuple[str | None, str | None, str], dict[str, Any]] = {}
    for metric in policy.get("commonMetrics", []):
        if isinstance(metric, dict):
            index[(None, None, metric.get("metricId"))] = metric
    for source in policy.get("sources", []):
        if not isinstance(source, dict):
            continue
        for gate in source.get("sourceGates", []):
            if isinstance(gate, dict):
                index[(source.get("sourceId"), gate.get("gateId"), gate.get("metricId"))] = gate
    return index


def evaluate_vector(policy: dict[str, Any], case: dict[str, Any]) -> dict[str, Any]:
    source_id = case.get("sourceId")
    gate_id = case.get("gateId")
    key = (source_id, gate_id, case.get("metricId"))
    if source_id is None and gate_id is None:
        key = (None, None, case.get("metricId"))
    metric = _metric_index(policy).get(key)
    if metric is None:
        raise KeyError("QUALITY_VECTOR_METRIC_UNKNOWN")
    observed = case["input"]
    if observed.get("applicable") is not True:
        return {"result": "not-applicable", "valueBasisPoints": None, "reasonCode": None}
    numerator = observed.get("numerator")
    denominator = observed.get("denominator")
    if not isinstance(numerator, int) or isinstance(numerator, bool) or not isinstance(denominator, int) or isinstance(denominator, bool):
        raise ValueError("QUALITY_VECTOR_INTEGER_REQUIRED")
    if denominator == 0:
        return {
            "result": "evaluation-error",
            "valueBasisPoints": None,
            "reasonCode": "QUALITY_POLICY_ZERO_DENOMINATOR",
        }
    threshold_numerator = metric["thresholdNumerator"]
    threshold_denominator = metric["thresholdDenominator"]
    left = numerator * threshold_denominator
    right = threshold_numerator * denominator
    operator = metric["operator"]
    passed = (
        (operator == ">=" and left >= right)
        or (operator == "<=" and left <= right)
        or (operator == "=" and left == right)
    )
    value = _half_up_basis_points(numerator, denominator) if metric["unit"] == "basis-point" else None
    return {"result": "passed" if passed else "failed", "valueBasisPoints": value, "reasonCode": None}


def metric_vector_issues(
    policy: dict[str, Any],
    document: Any,
    *,
    vector_schema: dict[str, Any] | None = None,
) -> list[str]:
    issues: list[str] = []
    if _has_float(document):
        issues.append("QUALITY_VECTOR_BINARY_FLOAT_FORBIDDEN")
    if not isinstance(document, dict):
        return sorted(set(issues + ["QUALITY_VECTOR_SCHEMA_REJECTED"]))
    schema = vector_schema
    if schema is None:
        try:
            schema = load_json(_DEFAULT_ROOT / VECTOR_SCHEMA)
        except (OSError, ValueError):
            schema = None
    if schema is None or schema_issues(document, schema):
        issues.append("QUALITY_VECTOR_SCHEMA_REJECTED")
    cases = document.get("cases")
    if not isinstance(cases, list):
        return sorted(set(issues))
    case_ids = [item.get("caseId") for item in cases if isinstance(item, dict)]
    if len(case_ids) != len(set(case_ids)):
        issues.append("QUALITY_VECTOR_CASE_ID_DUPLICATE")
    missing = MANDATORY_VECTOR_CASES - set(case_ids)
    if missing:
        issues.append("QUALITY_VECTOR_REQUIRED_CASE_MISSING")
    scoped_sources = {
        item.get("sourceId")
        for item in cases
        if isinstance(item, dict)
        and isinstance(item.get("sourceId"), str)
        and isinstance(item.get("gateId"), str)
    }
    if scoped_sources != set(EXPECTED_SOURCE_GATES):
        issues.append("QUALITY_VECTOR_SOURCE_OVERRIDE_COVERAGE_INVALID")
    index = _metric_index(policy)
    for case in cases:
        if not isinstance(case, dict):
            continue
        source_id = case.get("sourceId")
        gate_id = case.get("gateId")
        key = (source_id, gate_id, case.get("metricId"))
        if source_id is None and gate_id is None:
            key = (None, None, case.get("metricId"))
        if key not in index:
            issues.append("QUALITY_VECTOR_METRIC_UNKNOWN")
            continue
        if case.get("expectedJava") != case.get("expectedSql"):
            issues.append("QUALITY_VECTOR_CROSS_IMPLEMENTATION_MISMATCH")
            continue
        try:
            actual = evaluate_vector(policy, case)
        except (KeyError, TypeError, ValueError):
            issues.append("QUALITY_VECTOR_EVALUATION_INVALID")
            continue
        if actual != case.get("expectedJava"):
            issues.append("QUALITY_VECTOR_EXPECTATION_MISMATCH")
    cutoff_case = next((item for item in cases if isinstance(item, dict) and item.get("caseId") == "shanghai-cutoff-inclusive-pass"), None)
    if isinstance(cutoff_case, dict):
        observed = cutoff_case.get("input", {})
        if (
            observed.get("timezone") != "Asia/Shanghai"
            or observed.get("observedAt") != observed.get("cutoffAt")
            or _parse_instant(observed.get("observedAt")) is None
        ):
            issues.append("QUALITY_VECTOR_CUTOFF_SEMANTICS_INVALID")
    return sorted(set(issues))


def _parse_instant(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo is not None else None


def _common_metric_issues(metrics: Any, metric_schema: dict[str, Any]) -> list[str]:
    if not isinstance(metrics, list):
        return ["BATCH_QUALITY_COMMON_METRICS_INVALID"]
    issues: list[str] = []
    ids = [item.get("metricId") for item in metrics if isinstance(item, dict)]
    if tuple(ids) != COMMON_METRIC_ORDER:
        issues.append("BATCH_QUALITY_COMMON_METRIC_SET_INVALID")
    for metric in metrics:
        if not isinstance(metric, dict) or schema_issues(metric, metric_schema):
            issues.append("BATCH_QUALITY_METRIC_SCHEMA_REJECTED")
            continue
        metric_id = metric["metricId"]
        spec = COMMON_SPECS.get(metric_id)
        if spec is None:
            issues.append("BATCH_QUALITY_COMMON_METRIC_SET_INVALID")
            continue
        category, kind, numerator, denominator, unit, operator, threshold_numerator, threshold_denominator, predicate = spec
        expected_calculation = {
            "kind": kind,
            "numerator": _operand(numerator),
            "denominator": _operand(denominator),
        }
        if any((
            metric.get("definitionKind") != "common",
            metric.get("formulaId") != f"QMDP-1.0.0/{metric_id}",
            metric.get("formulaVersion") != "1.0.0",
            metric.get("category") != category,
            metric.get("calculation") != expected_calculation,
            metric.get("unit") != unit,
            metric.get("operator") != operator,
            metric.get("thresholdNumerator") != threshold_numerator,
            metric.get("thresholdDenominator") != threshold_denominator,
            metric.get("applicability", {}).get("predicateId") != predicate,
            metric.get("owner") != "Hei",
            metric.get("hardGate") is not True,
        )):
            issues.append(f"BATCH_QUALITY_COMMON_METRIC_SEMANTICS_INVALID: {metric_id}")
    p0 = next((item for item in metrics if isinstance(item, dict) and item.get("metricId") == "P0_SUBJECT_MAPPING_BP"), {})
    if set(p0.get("applicability", {}).get("sourceIds", [])) != P0_MAPPING_SOURCES:
        issues.append("BATCH_QUALITY_P0_MAPPING_SCOPE_INVALID")
    return issues


def _binding_matches(root: Path, binding: Any, expected_path: str, expected_version: str) -> bool:
    path = root / expected_path
    if not isinstance(binding, dict) or not path.is_file():
        return False
    try:
        document = load_json(path)
    except (OSError, ValueError):
        return False
    return binding == {
        "path": expected_path,
        "version": expected_version,
        "rawSha256": _raw_sha256(path),
        "canonicalDigest": _canonical_digest(document),
    }


def _expected_applicable_common_metrics(source_id: str) -> tuple[str, ...]:
    has_core_field_group = any(
        gate_source_id == source_id
        and spec[0] == "CORE_FIELD_COVERAGE_BP"
        and spec[-1] is not None
        for (gate_source_id, _), spec in EXPECTED_GATE_SPECS.items()
    )
    return tuple(
        metric_id
        for metric_id in COMMON_METRIC_ORDER
        if metric_id != "P0_SUBJECT_MAPPING_BP" or source_id in P0_MAPPING_SOURCES
        if metric_id != "CORE_FIELD_COVERAGE_BP" or has_core_field_group
    )


def _source_issues(
    root: Path,
    policy: dict[str, Any],
    catalog: dict[str, Any],
    metric_schema: dict[str, Any],
) -> list[str]:
    issues: list[str] = []
    descriptors = catalog.get("sources", [])
    profiles = policy.get("sources", [])
    if not isinstance(descriptors, list) or not isinstance(profiles, list):
        return ["BATCH_QUALITY_SOURCE_SET_INVALID"]
    descriptor_by_id = {item.get("sourceId"): item for item in descriptors if isinstance(item, dict)}
    profile_by_id = {item.get("sourceId"): item for item in profiles if isinstance(item, dict)}
    if list(profile_by_id) != list(descriptor_by_id) or set(profile_by_id) != set(EXPECTED_SOURCE_GATES):
        issues.append("BATCH_QUALITY_SOURCE_SET_INVALID")
    formula_ids: set[str] = set()
    for source_id, expected_gate_ids in EXPECTED_SOURCE_GATES.items():
        descriptor = descriptor_by_id.get(source_id, {})
        profile = profile_by_id.get(source_id, {})
        if profile.get("owner") != descriptor.get("responsibleRole"):
            issues.append(f"BATCH_QUALITY_SOURCE_OWNER_INVALID: {source_id}")
        applicable_common_metric_ids = profile.get("applicableCommonMetricIds")
        if (
            not isinstance(applicable_common_metric_ids, list)
            or tuple(applicable_common_metric_ids) != _expected_applicable_common_metrics(source_id)
        ):
            issues.append(f"BATCH_QUALITY_APPLICABLE_COMMON_METRIC_SET_INVALID: {source_id}")
        schema_path = f"contracts/data-catalog/{descriptor.get('schemaRef', '')}"
        if not _binding_matches(root, profile.get("schemaBinding"), schema_path, str(descriptor.get("schemaVersion", ""))):
            issues.append(f"BATCH_QUALITY_SOURCE_SCHEMA_BINDING_INVALID: {source_id}")
        source_schema_path = root / schema_path
        try:
            source_schema = load_json(source_schema_path)
        except (OSError, ValueError):
            issues.append(f"BATCH_QUALITY_SOURCE_SCHEMA_INVALID: {source_id}")
            continue
        required = set(source_schema.get("required", []))
        properties = set(source_schema.get("properties", {}))
        if not set(descriptor.get("businessKeys", [])).issubset(required & properties):
            issues.append(f"BATCH_QUALITY_BUSINESS_KEY_BINDING_INVALID: {source_id}")
        gates = profile.get("sourceGates", [])
        gate_ids = tuple(item.get("gateId") for item in gates if isinstance(item, dict))
        if gate_ids != expected_gate_ids:
            issues.append(f"BATCH_QUALITY_SOURCE_GATE_SET_INVALID: {source_id}")
        for gate in gates:
            if not isinstance(gate, dict) or schema_issues(gate, metric_schema):
                issues.append(f"BATCH_QUALITY_SOURCE_GATE_SCHEMA_REJECTED: {source_id}")
                continue
            formula_id = f"QMDP-1.0.0/{source_id}/{gate.get('gateId')}"
            if (
                gate.get("definitionKind") != "source-gate"
                or gate.get("sourceId") != source_id
                or gate.get("formulaId") != formula_id
                or gate.get("formulaVersion") != "1.0.0"
                or gate.get("owner") != descriptor.get("responsibleRole")
            ):
                issues.append(f"BATCH_QUALITY_SOURCE_GATE_IDENTITY_INVALID: {source_id}")
            if formula_id in formula_ids:
                issues.append("BATCH_QUALITY_SOURCE_GATE_FORMULA_DUPLICATE")
            formula_ids.add(formula_id)
            spec = EXPECTED_GATE_SPECS.get((source_id, gate.get("gateId")))
            if spec is None:
                issues.append(f"BATCH_QUALITY_SOURCE_GATE_SEMANTICS_INVALID: {source_id}")
            else:
                (
                    expected_metric_id,
                    expected_kind,
                    expected_numerator,
                    expected_denominator,
                    expected_unit,
                    expected_operator,
                    expected_threshold_numerator,
                    expected_threshold_denominator,
                    expected_predicate,
                    expected_field_set,
                ) = spec
                expected_calculation = {
                    "kind": expected_kind,
                    "numerator": _operand(expected_numerator),
                    "denominator": _operand(expected_denominator),
                }
                actual_field_set = tuple(gate.get("fieldSet", [])) if "fieldSet" in gate else None
                expected_category = COMMON_SPECS[expected_metric_id][0]
                if any((
                    gate.get("metricId") != expected_metric_id,
                    gate.get("category") != expected_category,
                    gate.get("calculation") != expected_calculation,
                    gate.get("unit") != expected_unit,
                    gate.get("operator") != expected_operator,
                    gate.get("boundary") != "inclusive",
                    gate.get("thresholdNumerator") != expected_threshold_numerator,
                    gate.get("thresholdDenominator") != expected_threshold_denominator,
                    gate.get("denominatorZeroBehavior") != "evaluation-error",
                    gate.get("valueScale") != 0,
                    gate.get("roundingMode") != "HALF_UP",
                    gate.get("comparisonStage") != "pre-rounding-cross-multiply",
                    gate.get("applicability", {}).get("predicateId") != expected_predicate,
                    actual_field_set != expected_field_set,
                    gate.get("approvalRef") != "AUTH-2026-08-08-001",
                    gate.get("effectiveAt") != "2026-08-09T10:02:22+08:00",
                    gate.get("evidenceRef") != "story://2.3/DEC-019/AUTH-2026-08-08-001",
                    gate.get("hardGate") is not True,
                )):
                    issues.append(f"BATCH_QUALITY_SOURCE_GATE_SEMANTICS_INVALID: {source_id}/{gate.get('gateId')}")
            if gate.get("calculation", {}).get("kind") == "count" and gate.get("calculation", {}).get("denominator") != {"kind": "constant", "value": 1}:
                issues.append(f"BATCH_QUALITY_COUNT_DENOMINATOR_INVALID: {source_id}")
            if gate.get("calculation", {}).get("kind") == "duration" and gate.get("unit") != "millisecond":
                issues.append(f"BATCH_QUALITY_DURATION_UNIT_INVALID: {source_id}")
            if "fieldSet" in gate:
                field_set = gate.get("fieldSet")
                if (
                    not isinstance(field_set, list)
                    or not set(field_set).issubset(properties)
                    or (
                        source_id == "SRC-P1-WORK-VISIT-001"
                        and tuple(field_set) != tuple(source_schema.get("required", []))
                    )
                ):
                    issues.append(f"BATCH_QUALITY_SOURCE_FIELD_BINDING_INVALID: {source_id}")
                expected_metric = "REQUIRED_FIELD_VALIDITY_BP" if source_id == "SRC-P1-WORK-VISIT-001" else "CORE_FIELD_COVERAGE_BP"
                if gate.get("metricId") != expected_metric:
                    issues.append(f"BATCH_QUALITY_FIELD_GROUP_MAPPING_INVALID: {source_id}")
        lanes = profile.get("freshnessLanes", [])
        lane_ids = tuple(item.get("laneId") for item in lanes if isinstance(item, dict))
        if lane_ids != EXPECTED_LANES[source_id]:
            issues.append(f"BATCH_QUALITY_FRESHNESS_LANE_SET_INVALID: {source_id}")
        for lane in lanes:
            if (
                not isinstance(lane, dict)
                or lane.get("allowNoActivity") is not False
                or lane.get("timezone") != "Asia/Shanghai"
                or lane.get("inclusive") is not True
            ):
                issues.append(f"BATCH_QUALITY_NO_ACTIVITY_OR_TIMEZONE_INVALID: {source_id}")
                continue
            expected_lane = EXPECTED_LANE_SPECS.get((source_id, lane.get("laneId")))
            if expected_lane is None or (lane.get("unit"), lane.get("rule")) != expected_lane:
                issues.append(f"BATCH_QUALITY_FRESHNESS_LANE_SEMANTICS_INVALID: {source_id}/{lane.get('laneId')}")
            rule = lane.get("rule", {})
            for key in ("laterField", "earlierField"):
                field = rule.get(key) if isinstance(rule, dict) else None
                if isinstance(field, str) and field.startswith("record."):
                    if field.removeprefix("record.") not in properties:
                        issues.append(
                            f"BATCH_QUALITY_FRESHNESS_FIELD_BINDING_INVALID: {source_id}/{lane.get('laneId')}"
                        )
                elif isinstance(field, str) and field.startswith("manifest."):
                    if field not in MANIFEST_FRESHNESS_BINDING.values():
                        issues.append(
                            f"BATCH_QUALITY_FRESHNESS_FIELD_BINDING_INVALID: {source_id}/{lane.get('laneId')}"
                        )
    return issues


def _catalog_issues(root: Path, catalog: Any) -> list[str]:
    if not isinstance(catalog, dict):
        return ["BATCH_QUALITY_DCC_SUCCESSOR_INVALID"]
    issues: list[str] = []
    try:
        catalog_schema = load_json(root / DCC_SUCCESSOR_SCHEMA)
        descriptor_schema = catalog_schema.get("$defs", {}).get("sourceDescriptor")
        predecessor = load_json(root / DCC_PREDECESSOR)
    except (OSError, ValueError):
        return ["BATCH_QUALITY_DCC_SUCCESSOR_SCHEMA_INVALID"]
    if not isinstance(descriptor_schema, dict) or not isinstance(predecessor, dict):
        return ["BATCH_QUALITY_DCC_SUCCESSOR_SCHEMA_INVALID"]
    if schema_definition_issues(catalog_schema) or schema_issues(catalog, catalog_schema):
        issues.append("BATCH_QUALITY_DCC_SUCCESSOR_SCHEMA_REJECTED")
    sources = catalog.get("sources", [])
    if not isinstance(sources, list) or len(sources) != 17:
        return issues + ["BATCH_QUALITY_DCC_SOURCE_SET_INVALID"]
    by_id = {item.get("sourceId"): item for item in sources if isinstance(item, dict)}
    if len(by_id) != 17 or set(by_id) != set(EXPECTED_SOURCE_GATES):
        issues.append("BATCH_QUALITY_DCC_SOURCE_SET_INVALID")
    expected_catalog = copy.deepcopy(predecessor)
    expected_catalog["contractVersion"] = "DCC-1.1.0"
    expected_catalog["effectiveAt"] = "2026-08-09T12:00:59+08:00"
    expected_catalog["schemaEvolution"] = "successor-only-no-in-place-mutation"
    expected_by_id = {
        item.get("sourceId"): item
        for item in expected_catalog.get("sources", [])
        if isinstance(item, dict)
    }
    expected_by_id["SRC-P0-RESPONSIBILITY-001"]["schemaRef"] = (
        "sources/src-p0-responsibility-001-2.1.0.schema.json"
    )
    expected_by_id["SRC-P0-RESPONSIBILITY-001"]["schemaVersion"] = (
        "RESPONSIBILITY-AUTHORITY-V2-2.1.0"
    )
    expected_by_id["SRC-P1-NETWORK-001"]["businessKeys"] = [
        "subjectRef",
        "windowStartsAt",
    ]
    expected_by_id["SRC-P1-CARE-LIST-001"]["schemaRef"] = (
        "sources/src-p1-care-list-001-1.1.0.schema.json"
    )
    expected_by_id["SRC-P1-CARE-LIST-001"]["schemaVersion"] = (
        "CARE-LIST-SLICE-1.1.0"
    )
    expected_top = {key: value for key, value in expected_catalog.items() if key != "sources"}
    actual_top = {key: value for key, value in catalog.items() if key != "sources"}
    if actual_top != expected_top:
        issues.append("BATCH_QUALITY_DCC_PREDECESSOR_PARITY_INVALID: catalog")
    expected_order = [item.get("sourceId") for item in expected_catalog.get("sources", [])]
    actual_order = [item.get("sourceId") for item in sources if isinstance(item, dict)]
    if actual_order != expected_order:
        issues.append("BATCH_QUALITY_DCC_PREDECESSOR_PARITY_INVALID: source-order")
    for source_id, expected_descriptor in expected_by_id.items():
        if by_id.get(source_id) != expected_descriptor:
            issues.append(f"BATCH_QUALITY_DCC_PREDECESSOR_PARITY_INVALID: {source_id}")
    expected = {
        "SRC-P0-RESPONSIBILITY-001": (["relationId", "sourceVersion"], "RESPONSIBILITY-AUTHORITY-V2-2.1.0", "sources/src-p0-responsibility-001-2.1.0.schema.json"),
        "SRC-P1-NETWORK-001": (["subjectRef", "windowStartsAt"], "NETWORK-AGGREGATE-1.0.0", "sources/src-p1-network-001.schema.json"),
        "SRC-P1-CARE-LIST-001": (["listFactId"], "CARE-LIST-SLICE-1.1.0", "sources/src-p1-care-list-001-1.1.0.schema.json"),
    }
    for source_id, descriptor in by_id.items():
        if schema_issues(descriptor, descriptor_schema):
            issues.append(f"BATCH_QUALITY_DCC_DESCRIPTOR_INVALID: {source_id}")
        if source_id in expected:
            keys, version, reference = expected[source_id]
            if (
                descriptor.get("businessKeys") != keys
                or descriptor.get("schemaVersion") != version
                or descriptor.get("schemaRef") != reference
            ):
                issues.append(f"BATCH_QUALITY_DCC_SUCCESSOR_MAPPING_INVALID: {source_id}")
        schema_path = root / DATA_CATALOG / str(descriptor.get("schemaRef", ""))
        try:
            source_schema = load_json(schema_path)
        except (OSError, ValueError):
            issues.append(f"BATCH_QUALITY_DCC_SOURCE_SCHEMA_INVALID: {source_id}")
            continue
        if schema_definition_issues(source_schema):
            issues.append(f"BATCH_QUALITY_DCC_SOURCE_SCHEMA_INVALID: {source_id}")
        keys = set(descriptor.get("businessKeys", []))
        if not keys.issubset(set(source_schema.get("required", [])) & set(source_schema.get("properties", {}))):
            issues.append(f"BATCH_QUALITY_BUSINESS_KEY_BINDING_INVALID: {source_id}")
    return issues


def _policy_issues(
    root: Path,
    policy: Any,
    catalog: dict[str, Any],
    policy_schema: dict[str, Any],
    metric_schema: dict[str, Any],
) -> list[str]:
    if not isinstance(policy, dict):
        return ["BATCH_QUALITY_POLICY_INVALID"]
    issues: list[str] = []
    if schema_issues(policy, policy_schema):
        issues.append("BATCH_QUALITY_POLICY_SCHEMA_REJECTED")
    expected_top = {
        "profileVersion": "QMDP-1.0.0",
        "decisionId": "DEC-019",
        "authorityRef": "AUTH-2026-08-08-001",
        "approvalRef": "AUTH-2026-08-08-001",
        "approvedBy": "Hei",
        "approvedAt": "2026-08-09T10:02:22+08:00",
        "effectiveAt": "2026-08-09T10:02:22+08:00",
        "owner": "Hei",
        "evidenceRef": "story://2.3/DEC-019/AUTH-2026-08-08-001",
        "status": "approved",
    }
    if any(policy.get(key) != value for key, value in expected_top.items()):
        issues.append("BATCH_QUALITY_POLICY_AUTHORITY_INVALID")
    if policy.get("freshnessManifestBinding") != MANIFEST_FRESHNESS_BINDING:
        issues.append("BATCH_QUALITY_FRESHNESS_MANIFEST_BINDING_INVALID")
    if not _binding_matches(
        root,
        policy.get("controlledInputs", {}).get("dataCatalog"),
        "contracts/data-catalog/dcc-1.1.0.json",
        "DCC-1.1.0",
    ):
        issues.append("BATCH_QUALITY_DCC_BINDING_INVALID")
    if not _binding_matches(
        root,
        policy.get("controlledInputs", {}).get("qualityGate"),
        "contracts/data-catalog/qg-1.0.0.json",
        "QG-1.0.0",
    ):
        issues.append("BATCH_QUALITY_QG_BINDING_INVALID")
    issues.extend(_common_metric_issues(policy.get("commonMetrics"), metric_schema))
    issues.extend(_source_issues(root, policy, catalog, metric_schema))
    constraints = policy.get("nonMetricConstraints", [])
    expected_constraints = {
        ("not-econ-hit-evidence", "SRC-P1-AID-001", "ECON-012-hit-evidence"),
        ("not-student-evaluation-feature", "SRC-P1-WORK-VISIT-001", "student-evaluation-feature"),
    }
    actual_constraints = {
        (item.get("constraintId"), item.get("sourceId"), item.get("deniedConsumerPurpose"))
        for item in constraints
        if isinstance(item, dict) and item.get("kind") == "consumer-purpose-deny"
    }
    if actual_constraints != expected_constraints:
        issues.append("BATCH_QUALITY_NON_METRIC_CONSTRAINT_INVALID")
    numeric_ids = {
        item.get("metricId") for item in policy.get("commonMetrics", []) if isinstance(item, dict)
    } | {
        item.get("gateId")
        for source in policy.get("sources", []) if isinstance(source, dict)
        for item in source.get("sourceGates", []) if isinstance(item, dict)
    }
    if {item[0] for item in expected_constraints} & numeric_ids:
        issues.append("BATCH_QUALITY_PURPOSE_CONSTRAINT_AS_METRIC")
    return issues


def policy_issues(project_root: Path, policy: Any) -> list[str]:
    """Validate an in-memory candidate policy without trusting its digest lock."""
    root = project_root.resolve()
    try:
        catalog = load_json(root / DCC_SUCCESSOR)
        policy_schema = load_json(root / POLICY_SCHEMA)
        metric_schema = load_json(root / METRIC_SCHEMA)
    except (OSError, ValueError):
        return ["BATCH_QUALITY_POLICY_VALIDATION_INPUT_INVALID"]
    return sorted(set(_policy_issues(root, policy, catalog, policy_schema, metric_schema)))


def _instant(value: Any) -> datetime:
    if not isinstance(value, str):
        raise ValueError("timestamp must be a string")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("timestamp must carry an offset")
    return parsed.astimezone(timezone.utc)


def _plus_utc_years(value: str, years: int) -> datetime:
    current = _instant(value)
    target_year = current.year + years
    target_day = min(current.day, monthrange(target_year, current.month)[1])
    return current.replace(year=target_year, day=target_day)


def _schema_candidate_issues(
        root: Path,
        candidate: Any,
        schema_path: Path,
        code: str) -> list[str]:
    try:
        schema = load_json(root / schema_path)
    except (OSError, ValueError):
        return [code]
    if schema_definition_issues(schema) or schema_issues(candidate, schema):
        return [code]
    return []


def lifecycle_issues(project_root: Path, lifecycle: Any) -> list[str]:
    """Validate the approved append-only DataBatch lifecycle materialization."""
    root = project_root.resolve()
    issues = _schema_candidate_issues(
        root, lifecycle, LIFECYCLE_SCHEMA, "BATCH_LIFECYCLE_SCHEMA_REJECTED")
    if not isinstance(lifecycle, dict):
        return sorted(set(issues + ["BATCH_LIFECYCLE_SCHEMA_REJECTED"]))
    if lifecycle.get("contractVersion") != "BATCH-QUALITY-LIFECYCLE-1.0.0":
        issues.append("BATCH_LIFECYCLE_VERSION_INVALID")
    if lifecycle.get("owner") != "ingestion-quality":
        issues.append("BATCH_LIFECYCLE_OWNER_INVALID")
    if lifecycle.get("initialState") != "receiving":
        issues.append("BATCH_LIFECYCLE_INITIAL_STATE_INVALID")
    if set(lifecycle.get("terminalStates", [])) != {"published", "quality-failed"}:
        issues.append("BATCH_LIFECYCLE_TERMINAL_STATE_INVALID")
    expected_transitions = {
        ("receiving", "sealed"),
        ("sealed", "quality-passed"),
        ("sealed", "quality-failed"),
        ("quality-passed", "published"),
    }
    actual_transitions = {
        (item.get("from"), item.get("to"))
        for item in lifecycle.get("allowedTransitions", [])
        if isinstance(item, dict)
    }
    if actual_transitions != expected_transitions:
        issues.append("BATCH_LIFECYCLE_TRANSITION_INVALID")
    if lifecycle.get("reopenAllowed") is not False:
        issues.append("BATCH_LIFECYCLE_REOPEN_INVALID")
    if lifecycle.get("manualResultOverrideAllowed") is not False:
        issues.append("BATCH_LIFECYCLE_MANUAL_OVERRIDE_INVALID")
    if (
        set(lifecycle.get("sealedManifestFields", []))
        != {
            "recordCount",
            "validRecordCount",
            "rejectedRecordCount",
            "observationWindow.startAt",
            "observationWindow.endAt",
            "cutoffAt",
            "timezone",
            "watermark",
            "sourceSchemaVersion",
            "sourceSchemaDigest",
            "dataCatalogVersion",
            "dataCatalogDigest",
            "qualityGateVersion",
            "qualityGateDigest",
            "qualityMetricDecisionProfileVersion",
            "qualityMetricDecisionProfileDigest",
            "manifestDigest",
            "sourceOccurredAt",
            "scheduledDueAt",
            "receivedAt",
            "laneId",
        }
        or lifecycle.get("sealImmutability") != "all-fields-frozen"
    ):
        issues.append("BATCH_LIFECYCLE_SEALED_MANIFEST_INVALID")
    visibility = lifecycle.get("factVisibility")
    if not isinstance(visibility, dict) or (
        set(visibility.get("invisibleStates", []))
        != {"receiving", "sealed", "quality-failed"}
        or visibility.get("visibleState") != "published"
        or visibility.get("publicationUnit") != "whole-batch-atomic"
    ):
        issues.append("BATCH_LIFECYCLE_FACT_VISIBILITY_INVALID")

    identity = lifecycle.get("identity")
    if not isinstance(identity, dict) or (
        identity.get("sameIdentitySameDigest") != "return-existing"
        or identity.get("sameIdentityDifferentDigest") != "identity-conflict"
        or identity.get("sourceVersionRegression") != "version-regression"
        or set(identity.get("businessIdentityFields", []))
        != {"sourceId", "businessKey", "sourceVersion"}
        or identity.get("manifestDigestField") != "manifestDigest"
    ):
        issues.append("BATCH_LIFECYCLE_IDENTITY_REPLAY_INVALID")

    correction = lifecycle.get("correction")
    if not isinstance(correction, dict) or (
        correction.get("createsNewBatch") is not True
        or correction.get("sameLineageRequired") is not True
        or correction.get("directPredecessorOnly") is not True
        or correction.get("mutatePredecessor") is not False
        or correction.get("forksAllowed") is not False
        or correction.get("derivationKind") != "fail-closed-materialization"
        or set(correction.get("requiredEvidenceFields", []))
        != {"supersedesBatchId", "lineageId", "reasonCode", "effectiveAt"}
        or set(correction.get("reasonCodeEnum", []))
        != {"SOURCE_CORRECTION", "LATE_ARRIVAL"}
    ):
        issues.append("BATCH_LIFECYCLE_CORRECTION_INVALID")

    time_semantics = lifecycle.get("timeSemantics")
    if not isinstance(time_semantics, dict) or (
        time_semantics.get("observationWindow") != "[startAt,endAt)"
        or time_semantics.get("storageTimezone") != "UTC"
        or time_semantics.get("businessTimezone") != "Asia/Shanghai"
        or time_semantics.get("cutoffSource") != "sealed-manifest"
        or time_semantics.get("cutoffFrozenAtSeal") is not True
        or time_semantics.get("evaluatedAtMeaning")
        != "valid-assessment-commit-time"
        or time_semantics.get("publishedAtMeaning")
        != "passed-facts-visibility-commit-time"
        or time_semantics.get("assessmentChronology")
        != "sealedAt<=evaluatedAt<=publishedAt-when-present"
    ):
        issues.append("BATCH_LIFECYCLE_TIME_SEMANTICS_INVALID")

    evaluation = lifecycle.get("evaluation")
    if not isinstance(evaluation, dict) or (
        evaluation.get("validPassingResultState") != "quality-passed"
        or evaluation.get("validFailingResultState") != "quality-failed"
        or evaluation.get("technicalErrorState") != "sealed"
        or evaluation.get("technicalErrorCreatesSnapshot") is not False
        or evaluation.get("technicalErrorPublishesFacts") is not False
        or evaluation.get("validEvaluationCreatesUniqueSnapshot") is not True
    ):
        issues.append("BATCH_LIFECYCLE_TECHNICAL_ERROR_INVALID")
    return sorted(set(issues))


def evaluate_batch_identity(lifecycle: Any, case: Any) -> str:
    """Classify one receive/replay attempt without rewriting an existing batch."""
    if lifecycle_issues(_DEFAULT_ROOT, lifecycle):
        return "CONTRACT_INVALID"
    if not isinstance(case, dict) or not isinstance(case.get("incoming"), dict):
        return "INPUT_INVALID"
    existing = case.get("existing")
    incoming = case["incoming"]
    if existing is None:
        return "CREATE"
    if not isinstance(existing, dict):
        return "INPUT_INVALID"
    same_business_identity = all(
        existing.get(key) == incoming.get(key)
        for key in ("sourceId", "businessKey")
    )
    if not same_business_identity:
        return "CREATE"
    existing_version = existing.get("sourceVersion")
    incoming_version = incoming.get("sourceVersion")
    if not isinstance(existing_version, int) or not isinstance(incoming_version, int):
        return "INPUT_INVALID"
    if incoming_version < existing_version:
        return "VERSION_REGRESSION"
    if incoming_version > existing_version:
        return "CREATE"
    if existing.get("manifestDigest") == incoming.get("manifestDigest"):
        return "REPLAY_EXISTING"
    return "IDENTITY_CONFLICT"


def batch_correction_lineage_issues(
        lifecycle: Any, batches: Any) -> list[str]:
    issues: list[str] = []
    if lifecycle_issues(_DEFAULT_ROOT, lifecycle):
        issues.append("BATCH_LIFECYCLE_CONTRACT_INVALID")
    if not isinstance(batches, list) or not all(isinstance(item, dict) for item in batches):
        return sorted(set(issues + ["BATCH_LIFECYCLE_CORRECTION_EVIDENCE_INVALID"]))
    by_id = {item.get("batchId"): item for item in batches}
    successors: dict[Any, list[dict[str, Any]]] = {}
    for item in batches:
        predecessor_id = item.get("supersedesBatchId")
        if predecessor_id is None:
            continue
        successors.setdefault(predecessor_id, []).append(item)
        predecessor = by_id.get(predecessor_id)
        if not isinstance(predecessor, dict):
            issues.append("BATCH_LIFECYCLE_CORRECTION_PREDECESSOR_MISSING")
            continue
        if item.get("lineageId") != predecessor.get("lineageId"):
            issues.append("BATCH_LIFECYCLE_CORRECTION_CROSS_LINEAGE")
        if any(
            item.get(key) != predecessor.get(key)
            for key in ("sourceId", "businessKey")
        ):
            issues.append("BATCH_LIFECYCLE_CORRECTION_IDENTITY_INVALID")
        if not isinstance(item.get("sourceVersion"), int) or (
            item.get("sourceVersion") <= predecessor.get("sourceVersion", -1)
        ):
            issues.append("BATCH_LIFECYCLE_CORRECTION_VERSION_INVALID")
        if item.get("reasonCode") not in {"SOURCE_CORRECTION", "LATE_ARRIVAL"}:
            issues.append("BATCH_LIFECYCLE_CORRECTION_EVIDENCE_INVALID")
        try:
            if _instant(item.get("effectiveAt")) < _instant(predecessor.get("effectiveAt")):
                issues.append("BATCH_LIFECYCLE_CORRECTION_EVIDENCE_INVALID")
        except (TypeError, ValueError):
            issues.append("BATCH_LIFECYCLE_CORRECTION_EVIDENCE_INVALID")
    if any(len(children) > 1 for children in successors.values()):
        issues.append("BATCH_LIFECYCLE_CORRECTION_FORK")
    return sorted(set(issues))


def batch_window_contains(
        lifecycle: Any, value: Any, observation_window: Any) -> bool:
    if lifecycle_issues(_DEFAULT_ROOT, lifecycle):
        return False
    if not isinstance(observation_window, dict):
        return False
    try:
        moment = _instant(value)
        start = _instant(observation_window.get("startAt"))
        end = _instant(observation_window.get("endAt"))
    except (TypeError, ValueError):
        return False
    return start <= moment < end


def batch_lifecycle_case_issues(lifecycle: Any, case: Any) -> list[str]:
    issues: list[str] = []
    if lifecycle_issues(_DEFAULT_ROOT, lifecycle):
        issues.append("BATCH_LIFECYCLE_CONTRACT_INVALID")
    if not isinstance(case, dict):
        return sorted(set(issues + ["BATCH_LIFECYCLE_CASE_INVALID"]))
    window = case.get("observationWindow")
    try:
        start = _instant(window.get("startAt"))
        end = _instant(window.get("endAt"))
        _instant(case.get("cutoffAt"))
        sealed = _instant(case.get("sealedAt"))
        if start >= end:
            issues.append("BATCH_LIFECYCLE_TIME_ORDER_INVALID")
    except (AttributeError, TypeError, ValueError):
        issues.append("BATCH_LIFECYCLE_TIME_ORDER_INVALID")
        sealed = None

    outcome = case.get("evaluationOutcome")
    state = case.get("state")
    evaluated_value = case.get("evaluatedAt")
    published_value = case.get("publishedAt")
    snapshot_created = case.get("snapshotCreated")
    if outcome == "evaluation-error":
        if (
            state != "sealed"
            or evaluated_value is not None
            or published_value is not None
            or snapshot_created is not False
        ):
            issues.append("BATCH_LIFECYCLE_TECHNICAL_ERROR_INVALID")
        return sorted(set(issues))
    if outcome not in {"passed", "quality-failed"}:
        issues.append("BATCH_LIFECYCLE_OUTCOME_INVALID")
        return sorted(set(issues))
    try:
        evaluated = _instant(evaluated_value)
        if sealed is None or evaluated < sealed:
            issues.append("BATCH_LIFECYCLE_TIME_ORDER_INVALID")
    except (TypeError, ValueError):
        evaluated = None
        issues.append("BATCH_LIFECYCLE_TIME_ORDER_INVALID")
    if snapshot_created is not True:
        issues.append("BATCH_LIFECYCLE_SNAPSHOT_INVALID")
    if outcome == "quality-failed":
        if state != "quality-failed" or published_value is not None:
            issues.append("BATCH_LIFECYCLE_FAILED_PUBLISH_INVALID")
    else:
        if state not in {"quality-passed", "published"}:
            issues.append("BATCH_LIFECYCLE_PASS_STATE_INVALID")
        if state == "published":
            try:
                if evaluated is None or _instant(published_value) < evaluated:
                    issues.append("BATCH_LIFECYCLE_TIME_ORDER_INVALID")
            except (TypeError, ValueError):
                issues.append("BATCH_LIFECYCLE_TIME_ORDER_INVALID")
        elif published_value is not None:
            issues.append("BATCH_LIFECYCLE_TIME_ORDER_INVALID")
    return sorted(set(issues))


def retention_policy_issues(project_root: Path, policy: Any) -> list[str]:
    """Validate the approved QualitySnapshot-to-RS retention mapping."""
    root = project_root.resolve()
    issues = _schema_candidate_issues(
        root, policy, RETENTION_SCHEMA, "QUALITY_SNAPSHOT_RETENTION_SCHEMA_REJECTED")
    if not isinstance(policy, dict):
        return sorted(set(issues + ["QUALITY_SNAPSHOT_RETENTION_SCHEMA_REJECTED"]))
    if (
        policy.get("policyVersion") != "QUALITY-SNAPSHOT-RETENTION-1.0.0"
        or policy.get("decisionId") != "DEC-019"
        or policy.get("authorityRef") != "AUTH-2026-08-08-001"
        or policy.get("effectiveAt") != "2026-08-09T10:02:22+08:00"
    ):
        issues.append("QUALITY_SNAPSHOT_RETENTION_AUTHORITY_INVALID")
    if (
        policy.get("owner") != "ingestion-quality"
        or policy.get("executor") != "ingestion-quality-retention-executor"
    ):
        issues.append("QUALITY_SNAPSHOT_RETENTION_OWNER_INVALID")
    if (
        policy.get("objectType") != "QualitySnapshot"
        or policy.get("retentionScheduleVersion") != "RS-1.0.0"
        or policy.get("retentionClass")
        != "reporting-and-operational-snapshot"
    ):
        issues.append("QUALITY_SNAPSHOT_RETENTION_MAPPING_INVALID")
    if (
        policy.get("retentionStartField") != "evaluatedAt"
        or policy.get("publishedAtAffectsRetention") is not False
        or set(policy.get("appliesToOverallResults", []))
        != {"quality-passed", "quality-failed"}
    ):
        issues.append("QUALITY_SNAPSHOT_RETENTION_START_INVALID")
    if (
        policy.get("retentionPeriod") != "P2Y"
        or policy.get("calendarArithmetic")
        != "utc-calendar-plus-years-end-of-month-clamp"
    ):
        issues.append("QUALITY_SNAPSHOT_RETENTION_PERIOD_INVALID")
    if (
        policy.get("expirationAction") != "delete"
        or policy.get("anonymizationFallbackAllowed") is not False
    ):
        issues.append("QUALITY_SNAPSHOT_RETENTION_ACTION_INVALID")
    if set(policy.get("ownerLocalTargets", [])) != {
        "quality-snapshot-row",
        "quality-snapshot-metric-rows",
        "owner-read-models",
        "owner-indexes",
        "owner-caches",
        "owner-objects",
    }:
        issues.append("QUALITY_SNAPSHOT_RETENTION_TARGET_SET_INVALID")
    derived = policy.get("derivedFrom")
    if not isinstance(derived, dict) or (
        derived.get("consumedBaseline")
        != "AUDIT-RETENTION-CONTRACT-LOCK-1.0.0"
        or set(derived.get("reusedPrimitives", []))
        != {
            "utc-calendar-plus-years-end-of-month-clamp",
            "equal-due-eligible",
            "targeted-half-open-legal-hold",
        }
        or set(derived.get("excludedSemantics", []))
        != {
            "audit-data-class",
            "occurredAt-retention-anchor",
            "RetentionExecution",
            "deletion-receipt",
        }
    ):
        issues.append("QUALITY_SNAPSHOT_RETENTION_BASELINE_BINDING_INVALID")

    scope_binding = policy.get("scopeDigestBinding")
    if not isinstance(scope_binding, dict) or (
        scope_binding.get("algorithm") != "sha256-canonical-json"
        or scope_binding.get("canonicalizationProfile")
        != "SCHOLARSENSE-CANONICAL-JSON-1.0.0"
        or scope_binding.get("materialFields")
        != [
            "objectType",
            "snapshotId",
            "sourceId",
            "snapshotAggregateVersion",
            "evaluatedAt",
            "retentionDueAt",
            "snapshotImmutableHash",
            "retentionPolicyVersion",
            "retentionScheduleVersion",
        ]
        or scope_binding.get("digestField") != "scope.scopeDigest"
    ):
        issues.append("QUALITY_SNAPSHOT_RETENTION_SCOPE_BINDING_INVALID")

    registry_binding = policy.get("consumerRegistryBinding")
    lifecycle_rules = (
        registry_binding.get("membershipLifecycleRules")
        if isinstance(registry_binding, dict) else None
    )
    expected_conformance_anchors = [
        {
            "conformanceAnchorId": "active-clue-care-consumer-set",
            "registryVersion": "QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0",
            "members": [
                {
                    "consumerId": "clue-care",
                    "registryMembership": "required-for-snapshot",
                    "lifecycleStatus": "active",
                }
            ],
            "membersDigest": (
                "sha256:c3cb72b301ee258c0b563c99be2504be"
                "6d9e3e58e0ce7beed5f2430d65fb9af5"
            ),
            "registryDigest": (
                "sha256:bb48cbb73b60c2490b8abc17bcd0955d"
                "2d2c28da3339ab697c3500690d7c2247"
            ),
        },
        {
            "conformanceAnchorId": "inactive-legacy-holder-consumer-set",
            "registryVersion": "QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0",
            "members": [
                {
                    "consumerId": "legacy-clue-projection",
                    "registryMembership": "unreleased-reference-holder",
                    "lifecycleStatus": "inactive",
                }
            ],
            "membersDigest": (
                "sha256:9ebf1ec0f3317b9959fb9d183cb814bd"
                "5c624b9f62d34c4cd3b0d162f83e28e0"
            ),
            "registryDigest": (
                "sha256:7728b0a7cc6bb5e2c73d6c623ac7200"
                "e345ddba84f5e83ed517fdec97309afae"
            ),
        },
        {
            "conformanceAnchorId": "empty-consumer-set",
            "registryVersion": "QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0",
            "members": [],
            "membersDigest": (
                "sha256:4f53cda18c2baa0c0354bb5f9a3ecbe"
                "5ed12ab4d8e11ba873c2f11161202b945"
            ),
            "registryDigest": (
                "sha256:8857f8a20f859397609157dd8f84eaae"
                "9bd674f28b9ddad3025d92cb8ffe3c3b"
            ),
        },
    ]
    if not isinstance(registry_binding, dict) or (
        registry_binding.get("exactMemberSetSource")
        != "consumerRegistry.members=sorted-consumers-projection"
        or registry_binding.get("memberSort") != "consumerId-ascending"
        or registry_binding.get("memberFields")
        != ["consumerId", "registryMembership", "lifecycleStatus"]
        or registry_binding.get("registryDigestAlgorithm")
        != "sha256-canonical-json"
        or registry_binding.get("registryDigestMaterialFields")
        != ["registryVersion", "members"]
        or registry_binding.get("emptyMemberSetAllowed") is not True
        or registry_binding.get("consumerIdUnique") is not True
        or not isinstance(lifecycle_rules, dict)
        or set(lifecycle_rules.get("required-for-snapshot", []))
        != {"active", "inactive"}
        or set(lifecycle_rules.get("unreleased-reference-holder", []))
        != {"active", "inactive", "retired"}
        or lifecycle_rules.get("planned-never-held-reference") != ["planned"]
        or set(registry_binding.get("attestationBindingFields", []))
        != {
            "consumerId",
            "registryVersion",
            "registryDigest",
            "scopeDigest",
            "snapshotId",
            "snapshotImmutableHash",
            "requiredAggregateVersion",
        }
        or registry_binding.get("conformanceEvidence") != {
            "provider": (
                "quality-snapshot-consumer-registry-conformance-anchor"
            ),
            "evidenceRefPrefix": (
                "contract://quality-snapshot-retention/consumer-registry/"
            ),
            "verificationStatus": "contract-conformance",
            "runtimeEvidenceClaim": "none",
        }
        or registry_binding.get("productionAuthority") != {
            "conformanceAnchorAccepted": False,
            "trustedPortOwnerTask": "Task 3",
            "trustedPort": "consumer-registry-authority",
        }
        or registry_binding.get("conformanceAnchors")
        != expected_conformance_anchors
    ):
        issues.append("QUALITY_SNAPSHOT_RETENTION_REGISTRY_BINDING_INVALID")

    legal_scope = policy.get("legalHoldScopeBinding")
    if not isinstance(legal_scope, dict) or (
        legal_scope.get("checkedScopeDigestField")
        != "guards.legalHold.checkedScopeDigest"
        or legal_scope.get("mustEqualField") != "scope.scopeDigest"
        or legal_scope.get("clearStillBindsCurrentScope") is not True
        or legal_scope.get("matchedRequiresCurrentScopeAndExactCount") is not True
    ):
        issues.append("QUALITY_SNAPSHOT_RETENTION_LEGAL_SCOPE_INVALID")

    chronology = policy.get("eventChronology")
    if not isinstance(chronology, dict) or (
        chronology.get("committedOrdering")
        != "retentionDueAt<=guardChecks<=deletionCommittedAt<=occurredAt"
        or chronology.get("noCommitOrdering")
        != "retentionDueAt<=guardChecks<=occurredAt"
        or set(chronology.get("guardCheckFields", []))
        != {
            "guards.trustedTime.observedAt",
            "guards.legalHold.checkedAt",
            "guards.consumerRegistry.checkedAt",
            "guards.consumerWatermarksCheckedAt",
        }
        or chronology.get("attestationUpperBound")
        != "deletionCommittedAt-when-present-otherwise-occurredAt"
        or chronology.get("availableCheckTimeRequired") is not True
        or "unavailableCheckTimeMustBeNull" in chronology
    ):
        issues.append("QUALITY_SNAPSHOT_RETENTION_CHRONOLOGY_INVALID")

    eligibility = policy.get("eligibility")
    if not isinstance(eligibility, dict) or (
        eligibility.get("trustedTimeRequired") is not True
        or eligibility.get("dueComparison") != "trustedNow>=retentionDueAt"
        or eligibility.get("legalHoldWindow") != "[startAt,endAt)"
        or eligibility.get("consumerDenominator")
        != "required-for-snapshot-plus-unreleased-reference-holders"
        or eligibility.get("watermarkRule")
        != "confirmedAggregateVersion>=requiredAggregateVersion"
        or eligibility.get("watermarkEqualitySatisfies") is not True
        or eligibility.get("inactiveExitRule")
        != "decommission-no-reference-attestation-required"
        or eligibility.get("plannedExclusionRule")
        != "planned-and-never-held-reference"
        or eligibility.get("watermarkDoesNotReplaceAttestation") is not True
        or eligibility.get("evidenceCopyAckRequired") is not True
        or set(eligibility.get("evidenceCopyAckSatisfyingValues", []))
        != {"copied", "not-required"}
        or set(eligibility.get("notRequiredAttestationBinding", []))
        != {"snapshotId", "snapshotImmutableHash", "requiredAggregateVersion"}
        or eligibility.get("transportAckCountsAsEvidenceCopyAck") is not False
        or eligibility.get("inboxAckCountsAsEvidenceCopyAck") is not False
        or eligibility.get("dependencyUnavailableResult") != "blocked"
    ):
        issues.append("QUALITY_SNAPSHOT_RETENTION_ELIGIBILITY_INVALID")

    downstream = policy.get("downstreamEvidence")
    if not isinstance(downstream, dict) or (
        downstream.get("ownerModule") != "clue-care"
        or set(downstream.get("ownerStories", [])) != {"3.4", "3.5"}
        or downstream.get("objectType") != "EvidenceSnapshot"
        or set(downstream.get("copyTriggers", []))
        != {"candidate-create", "clue-create"}
        or downstream.get("copyMode") != "self-contained-minimum"
        or downstream.get("ownerSnapshotReferenceMayBeSoleEvidence") is not False
        or downstream.get("retentionScheduleVersion") != "RS-1.0.0"
        or downstream.get("retentionAnchor") != "owner-domain-case-closure"
        or downstream.get("retentionPeriod") != "P3Y"
        or downstream.get("runtimeEvidenceClaim") != "none"
    ):
        issues.append("QUALITY_SNAPSHOT_RETENTION_HANDOFF_INVALID")

    backup = policy.get("backup")
    if not isinstance(backup, dict) or (
        backup.get("policyVersion") != "DRP-1.0.0"
        or backup.get("maximumRetentionPeriod") != "P35D"
        or backup.get("maximumRetentionDays") != 35
        or backup.get("dueWindow")
        != "deletionCommittedAt<=backupExpiryDueAt<=deletionCommittedAt+P35D"
        or backup.get("partialBackupExpiryDueAtRequired") is not True
        or backup.get("physicalDeletionClaim") != "none"
    ):
        issues.append("QUALITY_SNAPSHOT_RETENTION_BACKUP_INVALID")
    owner_result = policy.get("ownerResult")
    if not isinstance(owner_result, dict) or (
        owner_result.get("eventType")
        != "scholarsense.ingestion-quality.quality-snapshot.deletion-result.v1"
        or owner_result.get("payloadContractVersion") != "PIC-1.0.0"
        or set(owner_result.get("outcomes", []))
        != {"completed", "blocked", "partial", "failed"}
        or owner_result.get("wireSizeLimitBytes") != MAX_EVENT_BYTES
        or owner_result.get("wireEncoding") != "UTF-8"
        or owner_result.get("wireMeasurement")
        != "received-raw-utf8-bytes-before-json-parse"
        or owner_result.get("byteOrderMarkAllowed") is not False
        or owner_result.get("wireJsonParser")
        != "strict-duplicate-key-rejecting"
        or owner_result.get("wirePrettyJsonAllowed") is not True
        or owner_result.get("wireCanonicalByteEqualityRequired") is not False
        or owner_result.get("blockedCompletionRule")
        != "direct-successor-same-aggregate"
        or owner_result.get("idempotencyIdentity") != "eventId"
        or owner_result.get("sameIdentitySamePayload") != "duplicate"
        or owner_result.get("sameIdentityDifferentPayload") != "conflict"
        or owner_result.get("lineageRootRule")
        != "aggregateVersion=1-and-supersedesResultId=null"
        or owner_result.get("lineageSuccessorRule")
        != (
            "aggregateVersion=predecessor.aggregateVersion+1-and-"
            "supersedesResultId=predecessor.eventId"
        )
        or owner_result.get("lineageScopeRule")
        != "same-aggregate-and-same-scope"
        or owner_result.get("lineageChronologyRule")
        != "successor-evidence-at-or-after-predecessor-occurredAt"
        or owner_result.get("lineageForkAllowed") is not False
        or owner_result.get("completedSupersessionAllowed") is not False
        or owner_result.get("ownerResultIsFinalReceipt") is not False
        or owner_result.get("databaseTransactionDigestBinding") != {
            "semantics": "contract-binding-only",
            "algorithm": "sha256-canonical-json",
            "canonicalizationProfile": (
                "SCHOLARSENSE-CANONICAL-JSON-1.0.0"
            ),
            "materialFields": [
                "executionId",
                "scopeDigest",
                "result",
                "deletionCommittedAt",
                "transactionId",
                "onlineSnapshot",
                "onlineMetrics",
            ],
            "targetMaterialFields": [
                "status",
                "selectedCount",
                "deletedCount",
                "remainingCount",
                "evidenceDigest",
                "errorCode",
            ],
            "digestFields": [
                (
                    "ownerLocalResults.onlineSnapshot."
                    "transactionEvidenceDigest"
                ),
                (
                    "ownerLocalResults.onlineMetrics."
                    "transactionEvidenceDigest"
                ),
            ],
            "databaseCommitProof": False,
            "runtimeEvidenceClaim": "none",
            "productionAtomicityEvidenceTasks": ["Task 3", "Task 7"],
        }
    ):
        issues.append("QUALITY_SNAPSHOT_RETENTION_OWNER_RESULT_INVALID")
    audit_handoff = policy.get("auditHandoff")
    if not isinstance(audit_handoff, dict) or (
        audit_handoff.get("targetOwner") != "audit-operations"
        or audit_handoff.get("inputKind") != "owner-local-deletion-result"
        or audit_handoff.get("finalReceiptOwner") != "audit-operations"
        or audit_handoff.get("conformanceReceiptSatisfiesProduction") is not False
    ):
        issues.append("QUALITY_SNAPSHOT_RETENTION_HANDOFF_INVALID")
    return sorted(set(issues))


def _retention_scope_material(
        policy: dict[str, Any],
        case_input: dict[str, Any],
        due_at: datetime) -> dict[str, Any] | None:
    scope = case_input.get("scope")
    if not isinstance(scope, dict):
        return None
    required_fields = (
        "objectType",
        "snapshotId",
        "sourceId",
        "snapshotAggregateVersion",
        "evaluatedAt",
        "retentionDueAt",
        "snapshotImmutableHash",
    )
    if any(key not in scope for key in required_fields):
        return None
    expected_due = due_at.isoformat().replace("+00:00", "Z")
    if (
        scope.get("objectType") != "QualitySnapshot"
        or scope.get("snapshotAggregateVersion")
        != case_input.get("requiredAggregateVersion")
        or scope.get("evaluatedAt") != case_input.get("evaluatedAt")
    ):
        return None
    try:
        if _instant(scope.get("retentionDueAt")) != due_at:
            return None
    except (TypeError, ValueError):
        return None
    material = {key: scope[key] for key in required_fields}
    material["retentionDueAt"] = expected_due
    material["retentionPolicyVersion"] = policy.get("policyVersion")
    material["retentionScheduleVersion"] = policy.get(
        "retentionScheduleVersion")
    return material


def _registry_members(consumers: Any) -> list[dict[str, Any]] | None:
    if not isinstance(consumers, list) or not all(
        isinstance(consumer, dict) for consumer in consumers
    ):
        return None
    members = [
        {
            "consumerId": consumer.get("consumerId"),
            "registryMembership": consumer.get("registryMembership"),
            "lifecycleStatus": consumer.get("lifecycleStatus"),
        }
        for consumer in consumers
    ]
    if any(not isinstance(member["consumerId"], str) for member in members):
        return None
    return sorted(members, key=lambda member: member["consumerId"])


def _consumer_authority_binding_valid(
        policy: Any,
        registry: Any,
        scope_digest: Any) -> bool:
    if not isinstance(registry, dict):
        return False
    status = registry.get("status")
    if status == "unavailable":
        return True
    if status != "available" or not isinstance(policy, dict):
        return False
    registry_binding = policy.get("consumerRegistryBinding")
    if not isinstance(registry_binding, dict):
        return False
    anchors = registry_binding.get("conformanceAnchors")
    anchor_id = registry.get("conformanceAnchorId")
    if not isinstance(anchors, list) or not isinstance(anchor_id, str):
        return False
    matching_anchors = [
        anchor
        for anchor in anchors
        if isinstance(anchor, dict)
        and anchor.get("conformanceAnchorId") == anchor_id
    ]
    if len(matching_anchors) != 1:
        return False
    anchor = matching_anchors[0]
    members = anchor.get("members")
    if not isinstance(members, list):
        return False
    expected_members_digest = _canonical_digest(members)
    expected_registry_digest = _canonical_digest({
        "registryVersion": anchor.get("registryVersion"),
        "members": members,
    })
    checked_at = registry.get("checkedAt")
    try:
        _instant(checked_at)
    except (TypeError, ValueError):
        return False
    if (
        anchor.get("membersDigest") != expected_members_digest
        or anchor.get("registryDigest") != expected_registry_digest
        or registry.get("registryVersion") != anchor.get("registryVersion")
        or registry.get("registryDigest") != anchor.get("registryDigest")
        or registry.get("members") != members
        or not isinstance(scope_digest, str)
    ):
        return False
    evidence_profile = registry_binding.get("conformanceEvidence")
    if not isinstance(evidence_profile, dict):
        return False
    expected_evidence = {
        "provider": evidence_profile.get("provider"),
        "evidenceRef": (
            f"{evidence_profile.get('evidenceRefPrefix')}{anchor_id}"
        ),
        "scopeDigest": scope_digest,
        "registryVersion": anchor.get("registryVersion"),
        "registryDigest": anchor.get("registryDigest"),
        "membersDigest": anchor.get("membersDigest"),
        "checkedAt": checked_at,
        "verificationStatus": evidence_profile.get("verificationStatus"),
        "runtimeEvidenceClaim": evidence_profile.get("runtimeEvidenceClaim"),
    }
    return registry.get("authorityEvidence") == expected_evidence


def _retention_input_issues(
        policy: dict[str, Any],
        case_input: dict[str, Any],
        due_at: datetime) -> list[str]:
    issues: list[str] = []
    scope = case_input.get("scope")
    scope_material = _retention_scope_material(policy, case_input, due_at)
    if (
        scope_material is None
        or not isinstance(scope, dict)
        or scope.get("scopeDigest") != _canonical_digest(scope_material)
    ):
        issues.append("RETENTION_SCOPE_DIGEST_MISMATCH")

    registry = case_input.get("consumerRegistry")
    consumers = case_input.get("consumers")
    expected_members = _registry_members(consumers)
    if not isinstance(registry, dict) or expected_members is None:
        return sorted(set(issues + ["CONSUMER_REGISTRY_MEMBER_SET_MISMATCH"]))
    members = registry.get("members")
    if registry.get("status") == "available":
        scope_digest = scope.get("scopeDigest") if isinstance(scope, dict) else None
        if not _consumer_authority_binding_valid(
                policy, registry, scope_digest):
            issues.append("RETENTION_CONSUMER_AUTHORITY_BINDING_INVALID")
        if members != expected_members:
            issues.append("CONSUMER_REGISTRY_MEMBER_SET_MISMATCH")
        consumer_ids = [member["consumerId"] for member in expected_members]
        if len(consumer_ids) != len(set(consumer_ids)):
            issues.append("CONSUMER_REGISTRY_MEMBER_SET_MISMATCH")
        registry_material = {
            "registryVersion": registry.get("registryVersion"),
            "members": members,
        }
        if registry.get("registryDigest") != _canonical_digest(registry_material):
            issues.append("CONSUMER_REGISTRY_DIGEST_MISMATCH")

    seen_attestations: set[bytes] = set()
    for consumer in consumers:
        membership = consumer.get("registryMembership")
        lifecycle = consumer.get("lifecycleStatus")
        valid_membership = (
            membership == "required-for-snapshot"
            and lifecycle in {"active", "inactive"}
        ) or (
            membership == "unreleased-reference-holder"
            and lifecycle in {"active", "inactive", "retired"}
        ) or (
            membership == "planned-never-held-reference"
            and lifecycle == "planned"
        )
        if not valid_membership:
            issues.append("CONSUMER_REGISTRY_MEMBER_SET_MISMATCH")
        attestation = consumer.get("attestation")
        if not isinstance(attestation, dict):
            continue
        try:
            encoded_attestation = canonical_bytes(attestation)
        except (TypeError, ValueError):
            encoded_attestation = b""
        if encoded_attestation in seen_attestations:
            issues.append("ATTESTATION_REPLAY_DETECTED")
        seen_attestations.add(encoded_attestation)
        if attestation.get("consumerId") != consumer.get("consumerId"):
            issues.append("ATTESTATION_CONSUMER_BINDING_MISMATCH")
        if (
            attestation.get("registryVersion") != registry.get("registryVersion")
            or attestation.get("registryDigest") != registry.get("registryDigest")
        ):
            issues.append("ATTESTATION_REGISTRY_BINDING_MISMATCH")
        if (
            not isinstance(scope, dict)
            or attestation.get("scopeDigest") != scope.get("scopeDigest")
        ):
            issues.append("RETENTION_ATTESTATION_SCOPE_BINDING_MISMATCH")
        try:
            evaluated_at = _instant(case_input.get("evaluatedAt"))
            attested_at = _instant(attestation.get("attestedAt"))
            upper_bounds = []
            for upper_value in (
                case_input.get("consumerWatermarksCheckedAt"),
                case_input.get("trustedNow"),
            ):
                if upper_value is not None:
                    upper_bounds.append(_instant(upper_value))
            if attested_at < evaluated_at or any(
                attested_at > upper_bound for upper_bound in upper_bounds
            ):
                issues.append("RETENTION_ATTESTATION_TIME_INVALID")
        except (TypeError, ValueError):
            issues.append("RETENTION_ATTESTATION_TIME_INVALID")
        if (
            attestation.get("kind") == "decommission-no-reference"
            and lifecycle not in {"inactive", "retired"}
        ):
            issues.append("ACTIVE_CONSUMER_DECOMMISSION_INVALID")
    return sorted(set(issues))


def _attestation_matches(
        consumer: dict[str, Any],
        case_input: dict[str, Any],
        allowed_kinds: set[str]) -> bool:
    attestation = consumer.get("attestation")
    scope = case_input.get("scope")
    registry = case_input.get("consumerRegistry")
    if (
        not isinstance(attestation, dict)
        or not isinstance(scope, dict)
        or not isinstance(registry, dict)
    ):
        return False
    return (
        attestation.get("kind") in allowed_kinds
        and attestation.get("snapshotId") == scope.get("snapshotId")
        and attestation.get("snapshotImmutableHash")
        == scope.get("snapshotImmutableHash")
        and attestation.get("requiredAggregateVersion")
        == case_input.get("requiredAggregateVersion")
        and attestation.get("consumerId") == consumer.get("consumerId")
        and attestation.get("registryVersion") == registry.get("registryVersion")
        and attestation.get("registryDigest") == registry.get("registryDigest")
        and attestation.get("scopeDigest") == scope.get("scopeDigest")
    )


def _retention_blockers(case_input: dict[str, Any], due_at: datetime) -> list[str]:
    blockers: list[str] = []
    dependencies = case_input.get("dependencies")
    if not isinstance(dependencies, dict):
        dependencies = {}
    if dependencies.get("trustedTime") != "available":
        blockers.append("TRUSTED_TIME_UNAVAILABLE")
        trusted_now = None
    else:
        try:
            trusted_now = _instant(case_input.get("trustedNow"))
        except (TypeError, ValueError):
            trusted_now = None
            blockers.append("TRUSTED_TIME_UNAVAILABLE")
    if trusted_now is not None and trusted_now < due_at:
        blockers.append("RETENTION_NOT_DUE")

    if dependencies.get("legalHold") != "available":
        blockers.append("LEGAL_HOLD_DEPENDENCY_UNAVAILABLE")
    elif trusted_now is not None:
        scope = case_input.get("scope")
        scope_digest = scope.get("scopeDigest") if isinstance(scope, dict) else None
        for hold in case_input.get("legalHolds", []):
            if not isinstance(hold, dict) or hold.get("scopeDigest") != scope_digest:
                continue
            try:
                if _instant(hold.get("startAt")) <= trusted_now < _instant(hold.get("endAt")):
                    blockers.append("LEGAL_HOLD_ACTIVE")
                    break
            except (TypeError, ValueError):
                blockers.append("LEGAL_HOLD_DEPENDENCY_UNAVAILABLE")
                break

    registry = case_input.get("consumerRegistry")
    if not isinstance(registry, dict) or registry.get("status") != "available":
        blockers.append("CONSUMER_REGISTRY_UNAVAILABLE")
    watermark_available = dependencies.get("consumerWatermarks") == "available"
    if not watermark_available:
        blockers.append("CONSUMER_WATERMARK_DEPENDENCY_UNAVAILABLE")
    for consumer in case_input.get("consumers", []):
        if not isinstance(consumer, dict):
            blockers.append("CONSUMER_WATERMARK_DEPENDENCY_UNAVAILABLE")
            continue
        membership = consumer.get("registryMembership")
        decommissioned = _attestation_matches(
            consumer, case_input, {"decommission-no-reference"})
        included = membership == "required-for-snapshot" or (
            membership == "unreleased-reference-holder" and not decommissioned
        )
        if not included:
            continue
        if watermark_available:
            status = consumer.get("watermarkStatus")
            confirmed = consumer.get("confirmedAggregateVersion")
            required_version = case_input.get("requiredAggregateVersion")
            if status == "missing":
                blockers.append("CONSUMER_WATERMARK_MISSING")
            elif status == "unknown":
                blockers.append("CONSUMER_WATERMARK_UNKNOWN")
            elif (
                status != "confirmed"
                or not isinstance(confirmed, int)
                or not isinstance(required_version, int)
            ):
                blockers.append("CONSUMER_WATERMARK_UNKNOWN")
            elif confirmed < required_version:
                blockers.append("CONSUMER_WATERMARK_BEHIND")
        ack = consumer.get("evidenceCopyAck")
        if ack == "copied":
            valid_attestation = _attestation_matches(
                consumer, case_input, {"evidence-copy"})
        elif ack == "not-required":
            valid_attestation = _attestation_matches(
                consumer,
                case_input,
                {"zero-dependency-no-reference", "decommission-no-reference"},
            )
        else:
            valid_attestation = False
        if not valid_attestation:
            blockers.append("EVIDENCE_COPY_ACK_MISSING")
    return sorted(set(blockers))


def evaluate_retention_vector(policy: Any, case: Any) -> dict[str, Any]:
    """Evaluate a deterministic Task 0 retention vector."""
    if retention_policy_issues(_DEFAULT_ROOT, policy):
        return {"decision": "evaluation-error", "reasonCodes": ["POLICY_INVALID"]}
    if not isinstance(case, dict):
        return {"decision": "evaluation-error", "reasonCodes": ["INPUT_INVALID"]}
    candidate = case.get("input", case)
    if not isinstance(candidate, dict):
        return {"decision": "evaluation-error", "reasonCodes": ["INPUT_INVALID"]}
    if candidate.get("overallResult") not in {"quality-passed", "quality-failed"}:
        return {"decision": "evaluation-error", "reasonCodes": ["RESULT_INVALID"]}
    try:
        due_at = _plus_utc_years(candidate.get("evaluatedAt"), 2)
    except (TypeError, ValueError):
        return {"decision": "evaluation-error", "reasonCodes": ["EVALUATED_AT_INVALID"]}
    input_issues = _retention_input_issues(policy, candidate, due_at)
    if input_issues:
        return {
            "decision": "evaluation-error",
            "retentionDueAt": due_at.isoformat().replace("+00:00", "Z"),
            "reasonCodes": input_issues,
        }
    blockers = _retention_blockers(candidate, due_at)
    return {
        "decision": "eligible" if not blockers else "blocked",
        "retentionDueAt": due_at.isoformat().replace("+00:00", "Z"),
        "reasonCodes": blockers,
    }


def _normalized_keys(value: Any) -> set[str]:
    keys: set[str] = set()
    if isinstance(value, dict):
        for key, child in value.items():
            keys.add(str(key).lower().replace("_", "").replace("-", ""))
            keys.update(_normalized_keys(child))
    elif isinstance(value, list):
        for child in value:
            keys.update(_normalized_keys(child))
    return keys


def _deletion_scope_digest(data: dict[str, Any]) -> str | None:
    scope = data.get("scope")
    if not isinstance(scope, dict):
        return None
    keys = (
        "objectType",
        "snapshotId",
        "sourceId",
        "snapshotAggregateVersion",
        "evaluatedAt",
        "retentionDueAt",
        "snapshotImmutableHash",
    )
    if any(key not in scope for key in keys):
        return None
    material = {key: scope[key] for key in keys}
    material["retentionPolicyVersion"] = data.get("retentionPolicyVersion")
    material["retentionScheduleVersion"] = data.get("retentionScheduleVersion")
    return _canonical_digest(material)


def _event_attestation_matches(
        consumer: dict[str, Any], scope: dict[str, Any]) -> bool:
    attestation = consumer.get("attestation")
    if not isinstance(attestation, dict):
        return False
    return (
        attestation.get("snapshotId") == scope.get("snapshotId")
        and attestation.get("snapshotImmutableHash")
        == scope.get("snapshotImmutableHash")
        and attestation.get("requiredAggregateVersion")
        == consumer.get("requiredAggregateVersion")
        and consumer.get("requiredAggregateVersion")
        == scope.get("snapshotAggregateVersion")
    )


def _consumer_guard_blockers(data: Any) -> list[str]:
    blockers: list[str] = []
    if not isinstance(data, dict):
        return ["GUARDS_INVALID"]
    guards = data.get("guards")
    if not isinstance(guards, dict):
        return ["GUARDS_INVALID"]
    scope = data.get("scope")
    if not isinstance(scope, dict):
        return ["GUARDS_INVALID"]
    trusted = guards.get("trustedTime")
    if not isinstance(trusted, dict) or trusted.get("status") != "available":
        blockers.append("TRUSTED_TIME_UNAVAILABLE")
    else:
        try:
            if _instant(trusted.get("observedAt")) < _instant(
                scope.get("retentionDueAt")
            ):
                blockers.append("RETENTION_NOT_DUE")
        except (TypeError, ValueError):
            blockers.append("TRUSTED_TIME_UNAVAILABLE")
    legal_hold = guards.get("legalHold")
    if not isinstance(legal_hold, dict):
        blockers.append("LEGAL_HOLD_DEPENDENCY_UNAVAILABLE")
    elif legal_hold.get("status") == "matched":
        matched_digests = legal_hold.get("matchedScopeDigests")
        if (
            isinstance(matched_digests, list)
            and legal_hold.get("matchedCount") == len(matched_digests)
            and scope.get("scopeDigest") in matched_digests
        ):
            blockers.append("LEGAL_HOLD_MATCHED")
        else:
            blockers.append("LEGAL_HOLD_DEPENDENCY_UNAVAILABLE")
    elif legal_hold.get("status") == "clear":
        if (
            legal_hold.get("matchedCount") != 0
            or legal_hold.get("matchedScopeDigests") != []
        ):
            blockers.append("LEGAL_HOLD_DEPENDENCY_UNAVAILABLE")
    else:
        blockers.append("LEGAL_HOLD_DEPENDENCY_UNAVAILABLE")
    registry = guards.get("consumerRegistry")
    if not isinstance(registry, dict) or (
        registry.get("status") != "available"
        or registry.get("registryVersion")
        != "QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0"
        or not isinstance(registry.get("registryDigest"), str)
        or len(registry.get("registryDigest")) != 71
        or not registry.get("registryDigest").startswith("sha256:")
    ):
        blockers.append("CONSUMER_REGISTRY_UNAVAILABLE")
    consumers = guards.get("consumerWatermarks")
    if not isinstance(consumers, list):
        return sorted(set(blockers + ["WATERMARK_DEPENDENCY_UNAVAILABLE"]))
    for consumer in consumers:
        if not isinstance(consumer, dict):
            blockers.append("WATERMARK_DEPENDENCY_UNAVAILABLE")
            continue
        membership = consumer.get("registryMembership")
        attestation = consumer.get("attestation")
        attestation_matches = _event_attestation_matches(consumer, scope)
        released_reference_holder = (
            membership == "unreleased-reference-holder"
            and attestation_matches
            and isinstance(attestation, dict)
            and attestation.get("kind") == "decommission-no-reference"
        )
        included = membership == "required-for-snapshot" or (
            membership == "unreleased-reference-holder"
            and not released_reference_holder
        )
        if membership == "planned-never-held-reference":
            if consumer.get("lifecycleStatus") != "planned":
                blockers.append("CONSUMER_REGISTRY_UNAVAILABLE")
            continue
        if not included:
            continue
        watermark_status = consumer.get("watermarkStatus")
        confirmed = consumer.get("confirmedAggregateVersion")
        required_version = consumer.get("requiredAggregateVersion")
        if watermark_status == "missing":
            blockers.append("CONSUMER_WATERMARK_MISSING")
        elif watermark_status == "unknown":
            blockers.append("CONSUMER_WATERMARK_UNKNOWN")
        elif (
            watermark_status != "confirmed"
            or not isinstance(confirmed, int)
            or isinstance(confirmed, bool)
            or not isinstance(required_version, int)
            or isinstance(required_version, bool)
            or required_version != scope.get("snapshotAggregateVersion")
        ):
            blockers.append("WATERMARK_DEPENDENCY_UNAVAILABLE")
        elif confirmed < required_version:
            blockers.append("CONSUMER_WATERMARK_BEHIND")

        evidence_copy_ack = consumer.get("evidenceCopyAck")
        if evidence_copy_ack == "copied":
            evidence_satisfied = (
                attestation_matches
                and isinstance(attestation, dict)
                and attestation.get("kind") == "evidence-copy"
            )
        elif evidence_copy_ack == "not-required":
            evidence_satisfied = (
                attestation_matches
                and isinstance(attestation, dict)
                and attestation.get("kind")
                in {"zero-dependency-no-reference", "decommission-no-reference"}
            )
        else:
            evidence_satisfied = False
        if not evidence_satisfied:
            blockers.append("EVIDENCE_COPY_ACK_MISSING")
    return sorted(set(blockers))


CONTROLLED_DELETION_FAILURE_CODES = frozenset({
    "INDEX_DELETE_FAILED",
    "OBJECT_DELETE_FAILED",
    "OWNER_STORE_UNAVAILABLE",
})


def _is_sha256_digest(value: Any) -> bool:
    if not isinstance(value, str) or len(value) != 71:
        return False
    if not value.startswith("sha256:"):
        return False
    return all(character in "0123456789abcdef" for character in value[7:])


def _is_uuid_v7(value: Any) -> bool:
    if not isinstance(value, str) or len(value) != 36:
        return False
    if any(value[index] != "-" for index in (8, 13, 18, 23)):
        return False
    compact = value.replace("-", "")
    return (
        len(compact) == 32
        and all(character in "0123456789abcdef" for character in compact)
        and value[14] == "7"
        and value[19] in "89ab"
    )


def _owner_result_counts_valid(result: Any) -> bool:
    if not isinstance(result, dict):
        return False
    counts = [
        result.get("selectedCount"),
        result.get("deletedCount"),
        result.get("remainingCount"),
    ]
    if any(
        not isinstance(value, int) or isinstance(value, bool) or value < 0
        for value in counts
    ):
        return False
    selected, deleted, remaining = counts
    status = result.get("status")
    error_code = result.get("errorCode")
    evidence_digest = result.get("evidenceDigest")
    digest_valid = _is_sha256_digest(evidence_digest)
    if status == "deleted":
        return (
            selected > 0
            and deleted == selected
            and remaining == 0
            and error_code is None
            and digest_valid
        )
    if status == "already-absent":
        return selected == deleted == remaining == 0 and error_code is None and digest_valid
    if status in {"not-applicable", "not-attempted"}:
        return (
            selected == deleted == remaining == 0
            and error_code is None
            and evidence_digest is None
        )
    if status == "failed":
        return (
            selected > 0
            and deleted == 0
            and remaining == selected
            and isinstance(error_code, str)
            and bool(error_code)
            and digest_valid
        )
    return False


def _database_transaction_evidence_material(
        data: dict[str, Any], local_results: dict[str, Any]) -> dict[str, Any]:
    target_fields = (
        "status",
        "selectedCount",
        "deletedCount",
        "remainingCount",
        "evidenceDigest",
        "errorCode",
    )
    online_snapshot = local_results.get("onlineSnapshot")
    online_metrics = local_results.get("onlineMetrics")
    snapshot = online_snapshot if isinstance(online_snapshot, dict) else {}
    metrics = online_metrics if isinstance(online_metrics, dict) else {}
    scope = data.get("scope")
    return {
        "executionId": data.get("executionId"),
        "scopeDigest": (
            scope.get("scopeDigest") if isinstance(scope, dict) else None
        ),
        "result": data.get("result"),
        "deletionCommittedAt": data.get("deletionCommittedAt"),
        "transactionId": snapshot.get("transactionId"),
        "onlineSnapshot": {
            field: snapshot.get(field) for field in target_fields
        },
        "onlineMetrics": {
            field: metrics.get(field) for field in target_fields
        },
    }


def _owner_database_issues(
        data: dict[str, Any], local_results: dict[str, Any]) -> list[str]:
    issues: list[str] = []
    result = data.get("result")
    database_keys = ("onlineSnapshot", "onlineMetrics")
    database_results = [local_results.get(key) for key in database_keys]
    if not all(isinstance(item, dict) for item in database_results):
        return [
            "DELETION_RESULT_MANDATORY_DB_TARGET_INVALID",
            "DELETION_RESULT_OWNER_DB_ATOMICITY_EVIDENCE_INVALID",
        ]

    if any(item.get("status") == "not-applicable" for item in database_results):
        issues.append("DELETION_RESULT_MANDATORY_DB_TARGET_INVALID")
    if result in {"completed", "partial"} and any(
            item.get("status") == "not-attempted" for item in database_results):
        issues.append("DELETION_RESULT_MANDATORY_DB_TARGET_INVALID")

    snapshot = database_results[0]
    snapshot_status = snapshot.get("status")
    snapshot_selected = snapshot.get("selectedCount")
    if (
        snapshot_status in {"deleted", "failed"}
        and snapshot_selected != 1
    ):
        issues.append("DELETION_RESULT_SNAPSHOT_COUNT_INVALID")

    success_statuses = {"deleted", "already-absent"}
    if (
        (database_results[0].get("status") in success_statuses)
        != (database_results[1].get("status") in success_statuses)
    ):
        issues.append("DELETION_RESULT_OWNER_DB_ATOMICITY_INVALID")

    transaction_ids = [item.get("transactionId") for item in database_results]
    transaction_digests = [
        item.get("transactionEvidenceDigest") for item in database_results
    ]
    if result == "blocked":
        transaction_evidence_valid = (
            transaction_ids == [None, None]
            and transaction_digests == [None, None]
        )
    elif result in {"completed", "partial", "failed"}:
        expected_digest = _canonical_digest(
            _database_transaction_evidence_material(data, local_results)
        )
        transaction_evidence_valid = (
            transaction_ids[0] == transaction_ids[1]
            and _is_uuid_v7(transaction_ids[0])
            and transaction_digests[0] == transaction_digests[1]
            and transaction_digests[0] == expected_digest
        )
    else:
        transaction_evidence_valid = False
    if not transaction_evidence_valid:
        issues.append("DELETION_RESULT_OWNER_DB_ATOMICITY_EVIDENCE_INVALID")

    for key in OWNER_LOCAL_RESULT_KEYS - set(database_keys):
        item = local_results.get(key)
        if not isinstance(item, dict):
            continue
        if (
            item.get("transactionId") is not None
            or item.get("transactionEvidenceDigest") is not None
        ):
            issues.append("DELETION_RESULT_OWNER_DB_ATOMICITY_EVIDENCE_INVALID")
    return issues


def _backup_issues(data: dict[str, Any]) -> list[str]:
    issues: list[str] = []
    backup = data.get("backup")
    if not isinstance(backup, dict) or (
        backup.get("policyVersion") != "DRP-1.0.0"
        or backup.get("maximumRetentionDays") != 35
        or backup.get("physicalDeletionClaim") != "none"
    ):
        return ["DELETION_RESULT_BACKUP_INVARIANT"]
    committed_value = data.get("deletionCommittedAt")
    due_value = backup.get("backupExpiryDueAt")
    if data.get("result") in {"completed", "partial"}:
        if due_value is None:
            issues.append("DELETION_RESULT_BACKUP_DUE_REQUIRED")
            return issues
        try:
            committed_at = _instant(committed_value)
            due_at = _instant(due_value)
            if not (committed_at <= due_at <= committed_at + timedelta(days=35)):
                issues.append("DELETION_RESULT_BACKUP_INVARIANT")
        except (TypeError, ValueError):
            issues.append("DELETION_RESULT_BACKUP_INVARIANT")
    elif data.get("result") in {"blocked", "failed"} and due_value is not None:
        issues.append("DELETION_RESULT_BACKUP_INVARIANT")
    return issues


def _pic_binding_issues(data: dict[str, Any]) -> list[str]:
    if (
        data.get("contractVersion") != "PIC-1.0.0"
        or not _is_uuid_v7(data.get("correlationId"))
        or not _is_uuid_v7(data.get("causationId"))
    ):
        return ["DELETION_RESULT_PIC_BINDING_INVALID"]
    return []


def _trace_issues(event: dict[str, Any], data: dict[str, Any]) -> list[str]:
    issues: list[str] = []
    traceparent = event.get("traceparent")
    parts = traceparent.split("-") if isinstance(traceparent, str) else []
    trace_id = parts[1] if len(parts) == 4 else None
    span_id = parts[2] if len(parts) == 4 else None
    if trace_id != data.get("traceId"):
        issues.append("DELETION_RESULT_TRACE_MISMATCH")
    if trace_id == "0" * 32 or span_id == "0" * 16:
        issues.append("DELETION_RESULT_TRACE_ZERO_INVALID")
    return issues


def _consumer_registry_material(consumers: Any) -> list[dict[str, Any]] | None:
    if not isinstance(consumers, list):
        return None
    members: list[dict[str, Any]] = []
    for consumer in consumers:
        if not isinstance(consumer, dict):
            return None
        member = {
            "consumerId": consumer.get("consumerId"),
            "registryMembership": consumer.get("registryMembership"),
            "lifecycleStatus": consumer.get("lifecycleStatus"),
        }
        if not all(isinstance(value, str) and value for value in member.values()):
            return None
        members.append(member)
    return sorted(members, key=lambda member: member["consumerId"])


def _guard_binding_issues(
        data: dict[str, Any], retention_policy: Any) -> list[str]:
    issues: list[str] = []
    scope = data.get("scope")
    guards = data.get("guards")
    if not isinstance(scope, dict) or not isinstance(guards, dict):
        return ["DELETION_RESULT_GUARD_BINDING_INVALID"]
    legal_hold = guards.get("legalHold")
    if (
        not isinstance(legal_hold, dict)
        or legal_hold.get("checkedScopeDigest") != scope.get("scopeDigest")
    ):
        issues.append("DELETION_RESULT_LEGAL_HOLD_SCOPE_MISMATCH")

    registry = guards.get("consumerRegistry")
    consumers = guards.get("consumerWatermarks")
    members = _consumer_registry_material(consumers)
    if not isinstance(registry, dict) or members is None:
        return sorted(set(issues + ["DELETION_RESULT_CONSUMER_REGISTRY_BINDING_INVALID"]))
    registry_version = registry.get("registryVersion")
    registry_digest = registry.get("registryDigest")
    registry_status = registry.get("status")
    if not _consumer_authority_binding_valid(
            retention_policy, registry, scope.get("scopeDigest")):
        issues.append("DELETION_RESULT_CONSUMER_AUTHORITY_BINDING_INVALID")
    if registry_status == "available":
        expected_digest = _canonical_digest({
            "registryVersion": registry_version,
            "members": members,
        })
        registry_binding_valid = (
            registry.get("members") == members
            and registry_digest == expected_digest
        )
    elif registry_status == "unavailable":
        registry_binding_valid = True
    else:
        registry_binding_valid = False
    if not registry_binding_valid:
        issues.append("DELETION_RESULT_CONSUMER_REGISTRY_BINDING_INVALID")

    try:
        evaluated_at = _instant(scope.get("evaluatedAt"))
        occurred_at = _instant(data.get("occurredAt"))
        committed_value = data.get("deletionCommittedAt")
        decision_at = (
            _instant(committed_value)
            if committed_value is not None
            else occurred_at
        )
    except (TypeError, ValueError):
        evaluated_at = None
        decision_at = None

    seen_attestations: dict[bytes, str] = {}
    for consumer in consumers:
        attestation = consumer.get("attestation")
        if not isinstance(attestation, dict):
            continue
        if (
            consumer.get("lifecycleStatus") == "active"
            and attestation.get("kind") == "decommission-no-reference"
        ):
            issues.append(
                "DELETION_RESULT_ACTIVE_CONSUMER_DECOMMISSION_INVALID")
        if attestation.get("consumerId") != consumer.get("consumerId"):
            issues.append("DELETION_RESULT_ATTESTATION_CONSUMER_BINDING_MISMATCH")
        if (
            attestation.get("registryVersion") != registry_version
            or attestation.get("registryDigest") != registry_digest
        ):
            issues.append("DELETION_RESULT_ATTESTATION_REGISTRY_BINDING_MISMATCH")
        if (
            attestation.get("scopeDigest") != scope.get("scopeDigest")
            or attestation.get("snapshotId") != scope.get("snapshotId")
            or attestation.get("snapshotImmutableHash")
            != scope.get("snapshotImmutableHash")
            or attestation.get("requiredAggregateVersion")
            != scope.get("snapshotAggregateVersion")
        ):
            issues.append("DELETION_RESULT_ATTESTATION_SCOPE_MISMATCH")
        try:
            attested_at = _instant(attestation.get("attestedAt"))
            if (
                evaluated_at is None
                or decision_at is None
                or not (evaluated_at <= attested_at <= decision_at)
            ):
                issues.append("DELETION_RESULT_ATTESTATION_TIME_INVALID")
        except (TypeError, ValueError):
            issues.append("DELETION_RESULT_ATTESTATION_TIME_INVALID")
        try:
            attestation_bytes = canonical_bytes(attestation)
        except (TypeError, ValueError):
            continue
        previous_consumer = seen_attestations.get(attestation_bytes)
        if (
            previous_consumer is not None
            and previous_consumer != consumer.get("consumerId")
        ):
            issues.append("DELETION_RESULT_ATTESTATION_REPLAY_DETECTED")
        seen_attestations[attestation_bytes] = consumer.get("consumerId")
    return sorted(set(issues))


def _guard_chronology_issues(data: dict[str, Any]) -> list[str]:
    scope = data.get("scope")
    guards = data.get("guards")
    if not isinstance(scope, dict) or not isinstance(guards, dict):
        return ["DELETION_RESULT_CHRONOLOGY_INVALID"]
    trusted = guards.get("trustedTime")
    legal_hold = guards.get("legalHold")
    registry = guards.get("consumerRegistry")
    try:
        due_at = _instant(scope.get("retentionDueAt"))
        occurred_at = _instant(data.get("occurredAt"))
        guard_time_specs = [
            (
                trusted,
                "observedAt",
                isinstance(trusted, dict)
                and trusted.get("status") == "available",
            ),
            (
                legal_hold,
                "checkedAt",
                isinstance(legal_hold, dict)
                and legal_hold.get("status") in {"clear", "matched"},
            ),
            (
                registry,
                "checkedAt",
                isinstance(registry, dict)
                and registry.get("status") == "available",
            ),
            (
                guards,
                "consumerWatermarksCheckedAt",
                isinstance(guards.get("consumerWatermarks"), list),
            ),
        ]
        guard_times: list[datetime] = []
        for guard, field, required in guard_time_specs:
            if not isinstance(guard, dict):
                raise ValueError("guard is not an object")
            value = guard.get(field)
            if value is None:
                if required:
                    raise ValueError("required guard time is absent")
                continue
            guard_times.append(_instant(value))
        if not all(due_at <= checked_at <= occurred_at for checked_at in guard_times):
            return ["DELETION_RESULT_CHRONOLOGY_INVALID"]
        committed_value = data.get("deletionCommittedAt")
        if committed_value is not None:
            committed_at = _instant(committed_value)
            if not (
                due_at <= committed_at <= occurred_at
                and all(checked_at <= committed_at for checked_at in guard_times)
            ):
                return ["DELETION_RESULT_CHRONOLOGY_INVALID"]
    except (AttributeError, TypeError, ValueError):
        return ["DELETION_RESULT_CHRONOLOGY_INVALID"]
    return []


def deletion_result_wire_issues(
        project_root: Path, raw_bytes: Any) -> list[str]:
    """Validate the actual UTF-8 event wire bytes, including their byte length."""
    issues: list[str] = []
    if not isinstance(raw_bytes, (bytes, bytearray)):
        return ["DELETION_RESULT_WIRE_INVALID"]
    wire_bytes = bytes(raw_bytes)
    if len(wire_bytes) > MAX_EVENT_BYTES:
        issues.append("DELETION_RESULT_PAYLOAD_TOO_LARGE")
    try:
        event = parse_json_bytes(wire_bytes)
    except (TypeError, ValueError):
        return sorted(set(issues + ["DELETION_RESULT_CANONICAL_JSON_INVALID"]))
    issues.extend(deletion_result_issues(project_root, event))
    return sorted(set(issues))


def deletion_result_issues(project_root: Path, event: Any) -> list[str]:
    """Validate a strict owner-local QualitySnapshot deletion result event."""
    root = project_root.resolve()
    issues: list[str] = []
    if not isinstance(event, dict):
        return ["DELETION_RESULT_SCHEMA_REJECTED"]
    try:
        if len(canonical_bytes(event)) > MAX_EVENT_BYTES:
            issues.append("DELETION_RESULT_PAYLOAD_TOO_LARGE")
    except (TypeError, ValueError):
        return ["DELETION_RESULT_CANONICAL_JSON_INVALID"]
    private_keys = {
        "studentid",
        "studentnumber",
        "rawstudentidentifier",
        "sourcepayload",
        "evidencebody",
        "plaintext",
        "secret",
    }
    if _normalized_keys(event) & private_keys:
        issues.append("DELETION_RESULT_PRIVACY_BOUNDARY")
    issues.extend(_schema_candidate_issues(
        root, event, DELETION_EVENT_SCHEMA, "DELETION_RESULT_SCHEMA_REJECTED"))
    data = event.get("data")
    if not isinstance(data, dict):
        return sorted(set(issues + ["DELETION_RESULT_SCHEMA_REJECTED"]))
    issues.extend(_pic_binding_issues(data))
    if event.get("id") != data.get("eventId"):
        issues.append("DELETION_RESULT_ID_MISMATCH")
    scope = data.get("scope")
    if not isinstance(scope, dict) or event.get("subject") != (
        f"quality-snapshot/{scope.get('snapshotId')}"
    ):
        issues.append("DELETION_RESULT_SUBJECT_MISMATCH")
    if event.get("time") != data.get("occurredAt"):
        issues.append("DELETION_RESULT_TIME_MISMATCH")
    issues.extend(_trace_issues(event, data))
    if data.get("aggregateId") != data.get("executionId"):
        issues.append("DELETION_RESULT_EXECUTION_IDENTITY_MISMATCH")
    aggregate_version = data.get("aggregateVersion")
    if (
        not isinstance(aggregate_version, int)
        or isinstance(aggregate_version, bool)
        or aggregate_version < 1
        or aggregate_version > MAX_SAFE_INTEGER
    ):
        issues.append("DELETION_RESULT_VERSION_INVALID")
    if not isinstance(scope, dict) or scope.get("scopeDigest") != (
        _deletion_scope_digest(data)
    ):
        issues.append("DELETION_RESULT_SCOPE_DIGEST_MISMATCH")
    if isinstance(scope, dict):
        try:
            expected_due = _plus_utc_years(scope.get("evaluatedAt"), 2)
            if _instant(scope.get("retentionDueAt")) != expected_due:
                issues.append("DELETION_RESULT_RETENTION_SCOPE_INVALID")
        except (TypeError, ValueError):
            issues.append("DELETION_RESULT_RETENTION_SCOPE_INVALID")

    handoff = data.get("auditHandoff")
    if (
        "deletionreceiptid" in _normalized_keys(event)
        or not isinstance(handoff, dict)
        or handoff.get("targetOwner") != "audit-operations"
        or handoff.get("inputKind") != "owner-local-deletion-result"
        or handoff.get("finalReceiptOwner") != "audit-operations"
        or handoff.get("ownerResultIsFinalReceipt") is not False
        or handoff.get("conformanceReceiptSatisfiesProduction") is not False
    ):
        issues.append("DELETION_RESULT_FINAL_RECEIPT_BOUNDARY")

    result = data.get("result")
    local_results = data.get("ownerLocalResults")
    if not isinstance(local_results, dict) or set(local_results) != OWNER_LOCAL_RESULT_KEYS:
        issues.append("DELETION_RESULT_TARGET_SET_INVALID")
        local_results = {}
    if any(not _owner_result_counts_valid(item) for item in local_results.values()):
        issues.append("DELETION_RESULT_TARGET_COUNT_INVALID")
    issues.extend(_owner_database_issues(data, local_results))
    guards = data.get("guards")
    consumers = guards.get("consumerWatermarks") if isinstance(guards, dict) else None
    if isinstance(consumers, list):
        consumer_ids = [
            consumer.get("consumerId")
            for consumer in consumers
            if isinstance(consumer, dict)
        ]
        if (
            len(consumer_ids) != len(consumers)
            or not all(
                isinstance(consumer_id, str) and consumer_id
                for consumer_id in consumer_ids
            )
            or len(set(consumer_ids)) != len(consumer_ids)
        ):
            issues.append("DELETION_RESULT_CONSUMER_SET_INVALID")
    else:
        issues.append("DELETION_RESULT_CONSUMER_SET_INVALID")
    try:
        retention_policy = load_json(root / RETENTION)
    except (OSError, TypeError, ValueError):
        retention_policy = None
    issues.extend(_guard_binding_issues(data, retention_policy))
    issues.extend(_guard_chronology_issues(data))
    blockers = _consumer_guard_blockers(data)
    blocker_codes = data.get("blockerCodes")
    failure_codes = data.get("failureCodes")
    reason_lists_valid = (
        isinstance(blocker_codes, list)
        and isinstance(failure_codes, list)
        and all(isinstance(code, str) for code in blocker_codes)
        and all(isinstance(code, str) for code in failure_codes)
        and len(set(blocker_codes)) == len(blocker_codes)
        and len(set(failure_codes)) == len(failure_codes)
    )
    if not reason_lists_valid:
        issues.append("DELETION_RESULT_REASON_SET_INVALID")
        blocker_codes = []
        failure_codes = []
    if set(blocker_codes) != set(blockers):
        issues.append("DELETION_RESULT_BLOCKER_SET_MISMATCH")
    target_failure_codes = {
        item.get("errorCode")
        for item in local_results.values()
        if isinstance(item, dict)
        and item.get("status") == "failed"
        and isinstance(item.get("errorCode"), str)
    }
    if set(failure_codes) != target_failure_codes:
        issues.append("DELETION_RESULT_FAILURE_SET_MISMATCH")
    if (
        any(code not in CONTROLLED_DELETION_FAILURE_CODES for code in failure_codes)
        or any(
            code not in CONTROLLED_DELETION_FAILURE_CODES
            for code in target_failure_codes
        )
    ):
        issues.append("DELETION_RESULT_FAILURE_CODE_UNCONTROLLED")

    if result == "completed":
        completed_targets = all(
            isinstance(item, dict)
            and
            item.get("status") in {"deleted", "already-absent", "not-applicable"}
            and item.get("remainingCount") == 0
            and item.get("errorCode") is None
            for item in local_results.values()
        )
        if (
            blockers
            or blocker_codes
            or failure_codes
            or not completed_targets
            or data.get("deletionCommittedAt") is None
        ):
            issues.append("DELETION_RESULT_COMPLETED_INVARIANT")
    elif result == "blocked":
        blocked_targets = all(
            isinstance(item, dict)
            and
            item.get("status") == "not-attempted"
            and item.get("deletedCount") == 0
            for item in local_results.values()
        )
        if (
            not blockers
            or not blocker_codes
            or failure_codes
            or not blocked_targets
            or data.get("deletionCommittedAt") is not None
        ):
            issues.append("DELETION_RESULT_BLOCKED_INVARIANT")
    elif result == "partial":
        has_delete = any(
            isinstance(item, dict)
            and
            item.get("status") == "deleted" and item.get("deletedCount", 0) > 0
            for item in local_results.values()
        )
        has_failure = any(
            isinstance(item, dict)
            and (
                item.get("status") == "failed"
                or item.get("remainingCount", 0) > 0
            )
            for item in local_results.values()
        )
        if (
            blockers
            or blocker_codes
            or not failure_codes
            or not has_delete
            or not has_failure
            or data.get("deletionCommittedAt") is None
        ):
            issues.append("DELETION_RESULT_PARTIAL_INVARIANT")
    elif result == "failed":
        failed_targets = any(
            isinstance(item, dict) and item.get("status") == "failed"
            for item in local_results.values()
        ) and all(
            isinstance(item, dict)
            and
            item.get("deletedCount") == 0
            and item.get("status") in {"not-attempted", "failed"}
            for item in local_results.values()
        )
        if (
            blockers
            or blocker_codes
            or not failure_codes
            or not failed_targets
            or data.get("deletionCommittedAt") is not None
        ):
            issues.append("DELETION_RESULT_FAILED_INVARIANT")
    else:
        issues.append("DELETION_RESULT_OUTCOME_INVALID")
    issues.extend(_backup_issues(data))
    return sorted(set(issues))


def deletion_delivery_decision(
        current_watermark: int,
        incoming_version: int,
        *,
        same_payload: bool) -> str:
    if (
        not isinstance(current_watermark, int)
        or isinstance(current_watermark, bool)
        or current_watermark < 0
        or current_watermark > MAX_SAFE_INTEGER
        or not isinstance(incoming_version, int)
        or isinstance(incoming_version, bool)
        or incoming_version < 1
        or incoming_version > MAX_SAFE_INTEGER
        or not isinstance(same_payload, bool)
    ):
        return "INVALID_INPUT"
    if incoming_version == current_watermark:
        return "DUPLICATE" if same_payload else "CONFLICT"
    if incoming_version < current_watermark:
        return "OLD_IGNORED"
    if incoming_version == current_watermark + 1:
        return "APPLIED"
    return "GAP_BACKFILL_REQUIRED"


def _lineage_scope_bytes(data: dict[str, Any]) -> bytes | None:
    scope = data.get("scope")
    if not isinstance(scope, dict):
        return None
    try:
        return canonical_bytes(scope)
    except (TypeError, ValueError):
        return None


def deletion_result_lineage_issues(events: Any) -> list[str]:
    issues: list[str] = []
    if (
        not isinstance(events, list)
        or not events
        or not all(isinstance(item, dict) for item in events)
    ):
        return ["DELETION_RESULT_LINEAGE_INVALID"]
    by_event_id: dict[str, dict[str, Any]] = {}
    event_bytes_by_id: dict[str, bytes] = {}
    unique_events: list[dict[str, Any]] = []
    for event in events:
        data = event.get("data")
        if not isinstance(data, dict):
            issues.append("DELETION_RESULT_LINEAGE_INVALID")
            continue
        event_id = event.get("id")
        if not isinstance(event_id, str) or not event_id:
            issues.append("DELETION_RESULT_LINEAGE_INVALID")
            continue
        try:
            event_bytes = canonical_bytes(event)
        except (TypeError, ValueError):
            issues.append("DELETION_RESULT_LINEAGE_INVALID")
            continue
        previous_bytes = event_bytes_by_id.get(event_id)
        if previous_bytes is not None:
            if previous_bytes != event_bytes:
                issues.append("DELETION_RESULT_IDEMPOTENCY_CONFLICT")
            continue
        event_bytes_by_id[event_id] = event_bytes
        by_event_id[event_id] = event
        unique_events.append(event)

    if not unique_events:
        return sorted(set(issues + ["DELETION_RESULT_LINEAGE_INVALID"]))

    roots = [
        event
        for event in unique_events
        if event["data"].get("supersedesResultId") is None
    ]
    if len(roots) > 1:
        issues.append("DELETION_RESULT_LINEAGE_MULTIPLE_ROOTS")
    elif not roots:
        issues.append("DELETION_RESULT_LINEAGE_ROOT_MISSING")
    for root in roots:
        if root["data"].get("aggregateVersion") != 1:
            issues.append("DELETION_RESULT_LINEAGE_ROOT_VERSION_INVALID")

    versions: dict[tuple[str, int], str] = {}
    for event in unique_events:
        data = event["data"]
        aggregate_id = data.get("aggregateId")
        aggregate_version = data.get("aggregateVersion")
        if (
            not isinstance(aggregate_id, str)
            or not isinstance(aggregate_version, int)
            or isinstance(aggregate_version, bool)
        ):
            issues.append("DELETION_RESULT_LINEAGE_INVALID")
            continue
        version_key = (aggregate_id, aggregate_version)
        previous_event_id = versions.get(version_key)
        if previous_event_id is not None and previous_event_id != event.get("id"):
            issues.append("DELETION_RESULT_LINEAGE_VERSION_CONFLICT")
        versions[version_key] = event["id"]

    if roots:
        root_data = roots[0]["data"]
        root_scope = _lineage_scope_bytes(root_data)
        for event in unique_events:
            data = event["data"]
            if (
                data.get("retentionPolicyVersion")
                != root_data.get("retentionPolicyVersion")
                or data.get("retentionScheduleVersion")
                != root_data.get("retentionScheduleVersion")
            ):
                issues.append("DELETION_RESULT_LINEAGE_POLICY_DRIFT")
            if _lineage_scope_bytes(data) != root_scope:
                issues.append("DELETION_RESULT_LINEAGE_SCOPE_DRIFT")

    successors: dict[str, list[dict[str, Any]]] = {}
    for event in unique_events:
        data = event["data"]
        predecessor_id = data.get("supersedesResultId")
        if predecessor_id is None:
            continue
        if not isinstance(predecessor_id, str):
            issues.append("DELETION_RESULT_SUPERSEDES_MISSING")
            continue
        successors.setdefault(predecessor_id, []).append(event)
        predecessor = by_event_id.get(predecessor_id)
        if not isinstance(predecessor, dict) or not isinstance(predecessor.get("data"), dict):
            issues.append("DELETION_RESULT_SUPERSEDES_MISSING")
            continue
        predecessor_data = predecessor["data"]
        if data.get("aggregateId") != predecessor_data.get("aggregateId"):
            issues.append("DELETION_RESULT_SUPERSEDES_CROSS_AGGREGATE")
        predecessor_version = predecessor_data.get("aggregateVersion")
        if (
            not isinstance(predecessor_version, int)
            or isinstance(predecessor_version, bool)
            or data.get("aggregateVersion") != predecessor_version + 1
        ):
            issues.append("DELETION_RESULT_VERSION_GAP")
        try:
            predecessor_occurred_at = _instant(
                predecessor_data.get("occurredAt")
            )
            successor_times = [_instant(data.get("occurredAt"))]
            guards = data.get("guards")
            if not isinstance(guards, dict):
                raise ValueError("successor guards are absent")
            guard_time_paths = (
                (guards.get("trustedTime"), "observedAt"),
                (guards.get("legalHold"), "checkedAt"),
                (guards.get("consumerRegistry"), "checkedAt"),
                (guards, "consumerWatermarksCheckedAt"),
            )
            for guard, field in guard_time_paths:
                if not isinstance(guard, dict):
                    raise ValueError("successor guard is not an object")
                value = guard.get(field)
                if value is not None:
                    successor_times.append(_instant(value))
            committed_value = data.get("deletionCommittedAt")
            if committed_value is not None:
                successor_times.append(_instant(committed_value))
            if any(
                successor_time < predecessor_occurred_at
                for successor_time in successor_times
            ):
                issues.append("DELETION_RESULT_LINEAGE_CHRONOLOGY_INVALID")
        except (AttributeError, TypeError, ValueError):
            issues.append("DELETION_RESULT_LINEAGE_CHRONOLOGY_INVALID")
        if predecessor_data.get("result") == "completed":
            issues.append("DELETION_RESULT_TERMINAL_SUPERSESSION")
    if any(len(items) > 1 for items in successors.values()):
        issues.append("DELETION_RESULT_LINEAGE_FORK")
    return sorted(set(issues))


def _retention_vector_issues(
        policy: Any,
        vectors: Any,
        vector_schema: Any | None) -> list[str]:
    issues: list[str] = []
    if not isinstance(vectors, dict):
        return ["QUALITY_SNAPSHOT_RETENTION_VECTOR_INVALID"]
    if vector_schema is not None and schema_issues(vectors, vector_schema):
        issues.append("QUALITY_SNAPSHOT_RETENTION_VECTOR_SCHEMA_REJECTED")
    cases = vectors.get("cases")
    if not isinstance(cases, list) or not cases:
        return sorted(set(issues + ["QUALITY_SNAPSHOT_RETENTION_VECTOR_INVALID"]))
    case_ids: set[Any] = set()
    for case in cases:
        if not isinstance(case, dict) or case.get("caseId") in case_ids:
            issues.append("QUALITY_SNAPSHOT_RETENTION_VECTOR_INVALID")
            continue
        case_ids.add(case.get("caseId"))
        if evaluate_retention_vector(policy, case) != case.get("expected"):
            issues.append(
                f"QUALITY_SNAPSHOT_RETENTION_VECTOR_RESULT_MISMATCH: {case.get('caseId')}"
            )
    mandatory = {
        "quality-passed-exact-due",
        "quality-failed-exact-due",
        "published-at-ignored",
        "one-millisecond-before-due",
        "leap-day-end-of-month-clamp",
        "legal-hold-start-inclusive",
        "legal-hold-end-exclusive",
        "watermark-equal",
        "watermark-one-behind",
        "evidence-copy-ack-missing",
        "trusted-time-unavailable",
        "legal-hold-dependency-unavailable",
        "watermark-dependency-unavailable",
        "inactive-reference-holder-blocked",
        "inactive-no-reference-attested-excluded",
        "empty-required-consumer-set",
    }
    if not mandatory.issubset(case_ids):
        issues.append("QUALITY_SNAPSHOT_RETENTION_VECTOR_COVERAGE_INVALID")
    return sorted(set(issues))


def _deletion_ordering_issues(ordering: Any) -> list[str]:
    if not isinstance(ordering, dict) or ordering.get("route") != (
        "consumerId|producer|aggregateType|aggregateId"
    ):
        return ["DELETION_RESULT_ORDERING_INVALID"]
    issues: list[str] = []
    cases = ordering.get("cases")
    if not isinstance(cases, list) or not cases:
        return ["DELETION_RESULT_ORDERING_INVALID"]
    for case in cases:
        if not isinstance(case, dict):
            issues.append("DELETION_RESULT_ORDERING_INVALID")
            continue
        try:
            actual = deletion_delivery_decision(
                case["currentWatermark"],
                case["incomingVersion"],
                same_payload=case["samePayload"],
            )
        except (KeyError, TypeError):
            issues.append("DELETION_RESULT_ORDERING_INVALID")
            continue
        if actual != case.get("expectedDecision"):
            issues.append(
                f"DELETION_RESULT_ORDERING_MISMATCH: {case.get('caseId')}"
            )
    return sorted(set(issues))


def _first_code(issues: list[str], preferred: str) -> str:
    for issue in issues:
        if issue.startswith(preferred):
            return preferred
    return issues[0].split(":", 1)[0] if issues else "NEGATIVE_FIXTURE_NOT_REJECTED"


def execute_task_0_4_negative_fixture(
        case: Any, project_root: Path) -> str:
    """Execute one closed mutation from the Task 0.4 negative catalog."""
    if not isinstance(case, dict) or not isinstance(case.get("caseId"), str):
        return "TASK_0_4_NEGATIVE_FIXTURE_INVALID"
    root = project_root.resolve()
    case_id = case["caseId"]
    expected = str(case.get("expectedCode", ""))
    try:
        lifecycle = load_json(root / LIFECYCLE)
        retention = load_json(root / RETENTION)
        completed = load_json(root / DELETION_VALID_FIXTURES["completed"])
        blocked = load_json(root / DELETION_VALID_FIXTURES["blocked"])
        partial = load_json(root / DELETION_VALID_FIXTURES["partial"])
        failed = load_json(root / DELETION_VALID_FIXTURES["failed"])
    except (OSError, ValueError):
        return "TASK_0_4_NEGATIVE_FIXTURE_INPUT_INVALID"

    issues: list[str]
    if case_id == "illegal-failed-publish":
        candidate = copy.deepcopy(lifecycle)
        candidate["allowedTransitions"].append(
            {"from": "quality-failed", "to": "published"})
        issues = lifecycle_issues(root, candidate)
    elif case_id == "reopen-batch":
        candidate = copy.deepcopy(lifecycle)
        candidate["reopenAllowed"] = True
        issues = lifecycle_issues(root, candidate)
    elif case_id == "same-identity-different-digest":
        existing = {
            "sourceId": "SRC-P0-STUDENT-001",
            "businessKey": "fixture",
            "sourceVersion": 1,
            "manifestDigest": "sha256:" + "a" * 64,
        }
        decision = evaluate_batch_identity(lifecycle, {
            "existing": existing,
            "incoming": {**existing, "manifestDigest": "sha256:" + "b" * 64},
        })
        issues = ["BATCH_LIFECYCLE_IDENTITY_CONFLICT"] if (
            decision == "IDENTITY_CONFLICT") else []
    elif case_id == "correction-cross-lineage":
        parent = {
            "batchId": "019d2c7d-4000-7000-8000-000000000101",
            "sourceId": "SRC-P0-STUDENT-001",
            "businessKey": "fixture",
            "sourceVersion": 1,
            "lineageId": "019d2c7d-4000-7000-8000-000000000100",
            "supersedesBatchId": None,
            "reasonCode": None,
            "effectiveAt": "2026-08-09T00:00:00Z",
        }
        child = {
            **parent,
            "batchId": "019d2c7d-4000-7000-8000-000000000102",
            "sourceVersion": 2,
            "lineageId": "019d2c7d-4000-7000-8000-000000000199",
            "supersedesBatchId": parent["batchId"],
            "reasonCode": "SOURCE_CORRECTION",
            "effectiveAt": "2026-08-09T01:00:00Z",
        }
        issues = batch_correction_lineage_issues(lifecycle, [parent, child])
    elif case_id == "closed-observation-window":
        candidate = copy.deepcopy(lifecycle)
        candidate["timeSemantics"]["observationWindow"] = "[startAt,endAt]"
        issues = lifecycle_issues(root, candidate)
    elif case_id == "technical-error-becomes-quality-failed":
        candidate = copy.deepcopy(lifecycle)
        candidate["evaluation"]["technicalErrorState"] = "quality-failed"
        issues = lifecycle_issues(root, candidate)
    elif case_id in {
        "retention-starts-at-published",
        "retention-fixed-730-days",
        "retention-anonymizes",
    }:
        candidate = copy.deepcopy(retention)
        if case_id == "retention-starts-at-published":
            candidate["retentionStartField"] = "publishedAt"
        elif case_id == "retention-fixed-730-days":
            candidate["retentionPeriod"] = "P730D"
        else:
            candidate["expirationAction"] = "anonymize"
        issues = retention_policy_issues(root, candidate)
    else:
        event = copy.deepcopy(completed)
        if case_id == "completed-with-legal-hold":
            event["data"]["guards"]["legalHold"].update(
                {"status": "matched", "matchedCount": 1})
        elif case_id == "completed-with-watermark-gap":
            consumer = event["data"]["guards"]["consumerWatermarks"][0]
            consumer["confirmedAggregateVersion"] = (
                consumer["requiredAggregateVersion"] - 1)
        elif case_id == "completed-without-evidence-copy":
            event["data"]["guards"]["consumerWatermarks"][0][
                "evidenceCopyAck"] = "missing"
        elif case_id == "blocked-after-delete":
            event = copy.deepcopy(blocked)
            event["data"]["ownerLocalResults"]["onlineSnapshot"].update({
                "status": "deleted",
                "selectedCount": 1,
                "deletedCount": 1,
                "remainingCount": 0,
                "evidenceDigest": "sha256:" + "a" * 64,
            })
        elif case_id == "partial-without-split":
            event = copy.deepcopy(partial)
            for result in event["data"]["ownerLocalResults"].values():
                selected = max(1, result.get("selectedCount", 0))
                result.update({
                    "status": "deleted",
                    "selectedCount": selected,
                    "deletedCount": selected,
                    "remainingCount": 0,
                    "evidenceDigest": "sha256:" + "a" * 64,
                    "errorCode": None,
                })
        elif case_id == "failed-after-commit":
            event = copy.deepcopy(failed)
            event["data"]["deletionCommittedAt"] = "2026-08-09T00:00:00Z"
        elif case_id == "owner-result-forges-final-receipt":
            event["data"]["deletionReceiptId"] = (
                "019d2c7d-4000-7000-8000-000000000999")
            event["data"]["auditHandoff"]["ownerResultIsFinalReceipt"] = True
        elif case_id == "backup-due-drift":
            committed = _instant(event["data"]["deletionCommittedAt"])
            event["data"]["backup"]["backupExpiryDueAt"] = (
                committed + timedelta(days=36)).isoformat().replace("+00:00", "Z")
        elif case_id == "event-id-mismatch":
            event["data"]["eventId"] = (
                "019d2c7d-4000-7000-8000-000000000999")
        elif case_id == "scope-digest-mismatch":
            event["data"]["scope"]["scopeDigest"] = "sha256:" + "0" * 64
        elif case_id == "payload-over-64-kib":
            event["data"]["rawPayload"] = "x" * MAX_EVENT_BYTES
        else:
            return "TASK_0_4_NEGATIVE_FIXTURE_UNKNOWN"
        issues = deletion_result_issues(root, event)
    return _first_code(issues, expected)


def _task_0_4_negative_fixture_issues(root: Path) -> list[str]:
    try:
        catalog = load_json(root / TASK_0_4_NEGATIVE)
    except (OSError, ValueError):
        return ["TASK_0_4_NEGATIVE_FIXTURE_INVALID"]
    cases = catalog.get("cases") if isinstance(catalog, dict) else None
    if not isinstance(cases, list) or not cases:
        return ["TASK_0_4_NEGATIVE_FIXTURE_INVALID"]
    issues: list[str] = []
    for case in cases:
        if not isinstance(case, dict) or execute_task_0_4_negative_fixture(
            case, root) != case.get("expectedCode"):
            case_id = case.get("caseId") if isinstance(case, dict) else None
            issues.append(f"TASK_0_4_NEGATIVE_FIXTURE_NOT_REJECTED: {case_id}")
    return issues


def _historical_issues(root: Path) -> list[str]:
    issues: list[str] = []
    for relative, expected in HISTORICAL_RAW_DIGESTS.items():
        path = root / relative
        if not path.is_file() or _raw_sha256(path) != expected:
            issues.append(f"BATCH_QUALITY_HISTORICAL_DIGEST_MISMATCH: {relative}")
    return issues


def _task_0_4_upstream_issues(root: Path) -> list[str]:
    issues: list[str] = []
    for relative, expected in TASK_0_4_UPSTREAM_RAW_DIGESTS.items():
        path = root / relative
        if not path.is_file() or _raw_sha256(path) != expected:
            issues.append(f"TASK_0_4_UPSTREAM_DIGEST_MISMATCH: {relative}")
    return issues


def lock_issues(project_root: Path) -> list[str]:
    root = project_root.resolve()
    try:
        lock = load_json(root / LOCK)
        schema = load_json(root / LOCK_SCHEMA)
    except (OSError, ValueError):
        return ["BATCH_QUALITY_LOCK_INVALID"]
    issues: list[str] = []
    if schema_definition_issues(schema) or schema_issues(lock, schema):
        issues.append("BATCH_QUALITY_LOCK_SCHEMA_REJECTED")
    digests = lock.get("digests")
    if not isinstance(digests, dict) or set(digests) != EXECUTABLE_LOCKED_FILES:
        issues.append("BATCH_QUALITY_LOCK_FILE_SET_INVALID")
        return sorted(set(issues))
    for relative in sorted(EXECUTABLE_LOCKED_FILES):
        path = root / relative
        if not path.is_file():
            issues.append(f"BATCH_QUALITY_LOCK_FILE_MISSING: {relative}")
            continue
        if digests.get(relative) != "sha256:" + _raw_sha256(path):
            issues.append(f"BATCH_QUALITY_LOCK_DIGEST_MISMATCH: {relative}")
    try:
        policy = load_json(root / POLICY)
        lifecycle = load_json(root / LIFECYCLE)
        retention = load_json(root / RETENTION)
    except (OSError, ValueError):
        policy = None
        lifecycle = None
        retention = None
    if policy is None or lock.get("policyCanonicalDigest") != _canonical_digest(policy):
        issues.append("BATCH_QUALITY_POLICY_CANONICAL_DIGEST_MISMATCH")
    if (
        lifecycle is None
        or lock.get("lifecycleCanonicalDigest") != _canonical_digest(lifecycle)
    ):
        issues.append("BATCH_QUALITY_LIFECYCLE_CANONICAL_DIGEST_MISMATCH")
    if (
        retention is None
        or lock.get("retentionCanonicalDigest") != _canonical_digest(retention)
    ):
        issues.append("BATCH_QUALITY_RETENTION_CANONICAL_DIGEST_MISMATCH")
    return sorted(set(issues))


def _negative_fixture_issues(policy: dict[str, Any], path: Path, vector_schema: dict[str, Any]) -> list[str]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return ["BATCH_QUALITY_NEGATIVE_FIXTURE_INVALID"]
    cases = document.get("cases") if isinstance(document, dict) else None
    if not isinstance(cases, list) or not cases:
        return ["BATCH_QUALITY_NEGATIVE_FIXTURE_INVALID"]
    issues: list[str] = []
    for case in cases:
        if not isinstance(case, dict):
            issues.append("BATCH_QUALITY_NEGATIVE_FIXTURE_INVALID")
            continue
        observed = metric_vector_issues(policy, case.get("document"), vector_schema=vector_schema)
        if case.get("expectedCode") not in observed:
            issues.append(f"BATCH_QUALITY_NEGATIVE_FIXTURE_NOT_REJECTED: {case.get('caseId')}")
    return issues


def validate(project_root: Path) -> list[str]:
    root = project_root.resolve()
    issues: list[str] = []
    schemas: dict[Path, dict[str, Any]] = {}
    for relative in (
        POLICY_SCHEMA,
        METRIC_SCHEMA,
        VECTOR_SCHEMA,
        LOCK_SCHEMA,
        DCC_SUCCESSOR_SCHEMA,
        LIFECYCLE_SCHEMA,
        RETENTION_SCHEMA,
        RETENTION_VECTOR_SCHEMA,
        DELETION_EVENT_SCHEMA,
    ):
        try:
            schema = load_json(root / relative)
        except (OSError, ValueError):
            issues.append(f"BATCH_QUALITY_SCHEMA_INVALID: {relative}")
            continue
        schemas[relative] = schema
        if schema_definition_issues(schema):
            issues.append(f"BATCH_QUALITY_SCHEMA_INVALID: {relative}")
    try:
        catalog = load_json(root / DCC_SUCCESSOR)
    except (OSError, ValueError):
        catalog = {}
        issues.append("BATCH_QUALITY_DCC_SUCCESSOR_INVALID")
    issues.extend(_catalog_issues(root, catalog))
    try:
        policy = load_json(root / POLICY)
    except (OSError, ValueError):
        policy = {}
        issues.append("BATCH_QUALITY_POLICY_INVALID")
    if POLICY_SCHEMA in schemas and METRIC_SCHEMA in schemas:
        issues.extend(_policy_issues(root, policy, catalog, schemas[POLICY_SCHEMA], schemas[METRIC_SCHEMA]))
    try:
        vectors = load_json(root / VECTORS)
    except (OSError, ValueError):
        vectors = {}
        issues.append("QUALITY_VECTOR_DOCUMENT_INVALID")
    issues.extend(metric_vector_issues(policy, vectors, vector_schema=schemas.get(VECTOR_SCHEMA)))
    if VECTOR_SCHEMA in schemas:
        issues.extend(_negative_fixture_issues(policy, root / NEGATIVE_VECTORS, schemas[VECTOR_SCHEMA]))
    try:
        lifecycle = load_json(root / LIFECYCLE)
    except (OSError, ValueError):
        lifecycle = {}
        issues.append("BATCH_LIFECYCLE_DOCUMENT_INVALID")
    issues.extend(lifecycle_issues(root, lifecycle))
    try:
        retention = load_json(root / RETENTION)
    except (OSError, ValueError):
        retention = {}
        issues.append("QUALITY_SNAPSHOT_RETENTION_DOCUMENT_INVALID")
    issues.extend(retention_policy_issues(root, retention))
    try:
        retention_vectors = load_json(root / RETENTION_VECTORS)
    except (OSError, ValueError):
        retention_vectors = {}
        issues.append("QUALITY_SNAPSHOT_RETENTION_VECTOR_INVALID")
    issues.extend(_retention_vector_issues(
        retention, retention_vectors, schemas.get(RETENTION_VECTOR_SCHEMA)))
    deletion_events: dict[str, Any] = {}
    for outcome, relative in DELETION_VALID_FIXTURES.items():
        fixture_path = root / relative
        try:
            raw_event = fixture_path.read_bytes()
            event = load_json(fixture_path)
        except (OSError, ValueError):
            issues.append(f"DELETION_RESULT_FIXTURE_INVALID: {outcome}")
            continue
        deletion_events[outcome] = event
        issues.extend(
            f"{issue}: {outcome}"
            for issue in deletion_result_wire_issues(root, raw_event)
        )
    if {"blocked", "completed"}.issubset(deletion_events):
        issues.extend(deletion_result_lineage_issues([
            deletion_events["blocked"], deletion_events["completed"]]))
    try:
        deletion_ordering = load_json(root / DELETION_ORDERING)
    except (OSError, ValueError):
        deletion_ordering = {}
        issues.append("DELETION_RESULT_ORDERING_INVALID")
    issues.extend(_deletion_ordering_issues(deletion_ordering))
    issues.extend(_task_0_4_negative_fixture_issues(root))
    issues.extend(lock_issues(root))
    issues.extend(_historical_issues(root))
    issues.extend(_task_0_4_upstream_issues(root))
    return sorted(set(issues))


def main() -> int:
    project_root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else _DEFAULT_ROOT
    issues = validate(project_root)
    if issues:
        print("INGESTION_BATCH_CONTRACT_FAIL")
        for issue in issues:
            print(f"- {issue}")
        return 1
    print("INGESTION_BATCH_CONTRACT_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
