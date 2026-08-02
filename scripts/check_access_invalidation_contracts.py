#!/usr/bin/env python3
"""Validate Story 1.6c invalidation events, ordering, consumers, and v2 input."""

from __future__ import annotations

import copy
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from release_json import (  # noqa: E402
    canonical_bytes,
    load_json,
    schema_definition_issues,
    schema_issues,
)


EVENT_BASE = Path("contracts/events/identity-access")
EVENT_SCHEMA = EVENT_BASE / "access-invalidation-event.schema.json"
EVENT_POLICY = EVENT_BASE / "access-invalidation-policy-1.0.0.json"
CONSUMER_REGISTRY = EVENT_BASE / "consumer-registry-1.0.0.json"
ORDERING_FIXTURES = EVENT_BASE / "fixtures/ordering-fixtures-1.0.0.json"
NEGATIVE_FIXTURES = (
    EVENT_BASE / "fixtures/invalid/negative-fixtures-1.0.0.json"
)
EVENT_LOCK = EVENT_BASE / "access-invalidation-contract-lock-1.0.0.json"
PUBLIC_ENVELOPE = Path("contracts/events/envelope.schema.json")

RESPONSIBILITY_V2_BASE = Path("contracts/responsibility-authority-v2")
RESPONSIBILITY_V2_SCHEMA = (
    RESPONSIBILITY_V2_BASE / "responsibility-relation.schema.json"
)
RESPONSIBILITY_V2_RECORD_SCHEMA = (
    RESPONSIBILITY_V2_BASE / "responsibility-record.schema.json"
)
RESPONSIBILITY_V2_SNAPSHOT_SCHEMA = (
    RESPONSIBILITY_V2_BASE / "full-snapshot.schema.json"
)
RESPONSIBILITY_V2_RUNTIME_SCHEMA = (
    RESPONSIBILITY_V2_BASE / "runtime-profile.schema.json"
)
RESPONSIBILITY_V2_POLICY = (
    RESPONSIBILITY_V2_BASE / "responsibility-policy-2.0.0.json"
)
RESPONSIBILITY_V2_FIXTURE = (
    RESPONSIBILITY_V2_BASE
    / "fixtures/valid/responsibility-relation-corrected.json"
)
RESPONSIBILITY_V2_SNAPSHOT_FIXTURE = (
    RESPONSIBILITY_V2_BASE / "fixtures/valid/full-snapshot.json"
)
RESPONSIBILITY_V2_RUNTIME_PROFILE = (
    RESPONSIBILITY_V2_BASE / "sandbox-runtime-profile-2.0.0.json"
)
RESPONSIBILITY_V2_LINEAGE_DIGEST_PROFILE = (
    RESPONSIBILITY_V2_BASE / "lineage-digest-profile-1.0.0.json"
)
RESPONSIBILITY_V2_NEGATIVE = (
    RESPONSIBILITY_V2_BASE
    / "fixtures/invalid/negative-fixtures-2.0.0.json"
)
RESPONSIBILITY_V2_LOCK = (
    RESPONSIBILITY_V2_BASE
    / "responsibility-authority-contract-lock-2.0.0.json"
)
RESPONSIBILITY_V1_LOCK = Path(
    "contracts/responsibility-authority/"
    "responsibility-authority-contract-lock-1.0.0.json"
)
RESPONSIBILITY_V1_LOCK_SHA256 = (
    "aaaeee17923104bb7a1ab33c8b15aee7498cea84e69d9072d2afab16077e73df"
)

VALID_EVENT_DIR = EVENT_BASE / "fixtures/valid"
MAX_EVENT_BYTES = 64 * 1024
EXPECTED_EVENT_TYPE = (
    "scholarsense.identity-access.responsibility.changed.v1"
)
EXPECTED_NEGATIVE_CASES = {
    "missing-lineage",
    "event-id-mismatch",
    "cross-lineage-supersedes",
    "lineage-fork",
    "future-supersedes",
    "aggregate-version-gap",
    "same-key-different-payload",
    "unknown-reason-code",
    "raw-identifier-field",
    "payload-over-64-kib",
    "planned-consumer-ack",
    "delegation-grant-bypass",
    "invalid-recovery-reason",
}
EXPECTED_V2_NEGATIVE_CASES = {
    "missing-lineage",
    "free-text-reason",
    "v1-advances-invalidation-watermark",
    "platform-infers-lineage",
    "cross-lineage-supersedes",
}
PRIVATE_KEYS = {
    "studentid",
    "studentnumber",
    "accountid",
    "externalid",
    "tokenvalue",
    "secret",
    "plaintext",
    "sourcepayload",
}


def validate(project_root: Path) -> list[str]:
    root = project_root.resolve()
    issues: list[str] = []
    event_schema = _load_schema(root / EVENT_SCHEMA, "EVENT", issues)
    public_schema = _load_schema(
        root / PUBLIC_ENVELOPE,
        "PUBLIC_EVENT_ENVELOPE",
        issues,
        validate_definition=False,
    )
    valid_events = _load_valid_events(root, issues)
    for relative, event in valid_events.items():
        for issue in event_issues(
            event,
            schema=event_schema,
            public_schema=public_schema,
        ):
            issues.append(f"{issue}: {relative}")
    issues.extend(_lineage_issues(valid_events))
    issues.extend(_ordering_issues(root))
    issues.extend(_policy_issues(root))
    issues.extend(_consumer_registry_issues(root))
    issues.extend(_negative_fixture_issues(root, event_schema, public_schema))
    issues.extend(_responsibility_v2_issues(root))
    issues.extend(
        _lock_issues(
            root,
            EVENT_BASE,
            EVENT_LOCK,
            [
                PUBLIC_ENVELOPE,
                RESPONSIBILITY_V2_LOCK,
            ],
            "ACCESS_INVALIDATION",
        )
    )
    issues.extend(
        _lock_issues(
            root,
            RESPONSIBILITY_V2_BASE,
            RESPONSIBILITY_V2_LOCK,
            [RESPONSIBILITY_V1_LOCK],
            "RESPONSIBILITY_AUTHORITY_V2",
        )
    )
    v1_lock_path = root / RESPONSIBILITY_V1_LOCK
    if (
        not v1_lock_path.is_file()
        or hashlib.sha256(v1_lock_path.read_bytes()).hexdigest()
        != RESPONSIBILITY_V1_LOCK_SHA256
    ):
        issues.append("RESPONSIBILITY_AUTHORITY_V1_LOCK_CHANGED")
    return sorted(set(issues))


def delivery_decision(
    current_watermark: int,
    incoming_version: int,
    *,
    same_payload: bool,
) -> str:
    """Classify one route without allowing gaps to affect another route."""
    if incoming_version == current_watermark:
        return "DUPLICATE" if same_payload else "CONFLICT"
    if incoming_version < current_watermark:
        return "OLD_IGNORED"
    if incoming_version == current_watermark + 1:
        return "APPLIED"
    return "GAP_BACKFILL_REQUIRED"


def event_issues(
    event: Any,
    *,
    schema: Any | None = None,
    public_schema: Any | None = None,
) -> list[str]:
    """Return stable contract reason codes for one wire event."""
    issues: list[str] = []
    if schema is None:
        root = Path(__file__).resolve().parents[1]
        try:
            schema = load_json(root / EVENT_SCHEMA)
            public_schema = load_json(root / PUBLIC_ENVELOPE)
        except (OSError, ValueError):
            return ["EVENT_SCHEMA_UNAVAILABLE"]
    if not isinstance(event, dict):
        return ["EVENT_SCHEMA_REJECTED"]
    try:
        if len(canonical_bytes(event)) > MAX_EVENT_BYTES:
            issues.append("EVENT_PAYLOAD_TOO_LARGE")
    except (TypeError, ValueError):
        issues.append("EVENT_CANONICAL_JSON_INVALID")
        return issues
    if _contains_private_field(event):
        issues.append("EVENT_PRIVACY_BOUNDARY_VIOLATION")
    if schema_issues(event, schema):
        issues.append("EVENT_SCHEMA_REJECTED")
    if public_schema is not None and _public_envelope_issues(
        event, public_schema
    ):
        issues.append("EVENT_PUBLIC_ENVELOPE_REJECTED")
    data = event.get("data")
    if isinstance(data, dict):
        if event.get("id") != data.get("eventId"):
            issues.append("EVENT_ID_MISMATCH")
        if data.get("aggregateVersion") != data.get("invalidationVersion"):
            issues.append("EVENT_VERSION_MISMATCH")
        if (
            data.get("changeKind") == "revalidated"
            and data.get("reasonCode") != "RECONCILIATION_RECOVERED"
        ):
            issues.append("RECOVERY_TAXONOMY_INVALID")
        if (
            data.get("aggregateType") == "identity-cause"
            and data.get("causeEventId") is not None
        ):
            issues.append("CAUSE_EVENT_NESTING_INVALID")
        if (
            data.get("aggregateType") == "responsibility-scope"
            and not str(event.get("subject", "")).endswith(
                str(data.get("lineageId", ""))
            )
        ):
            issues.append("EVENT_SUBJECT_LINEAGE_MISMATCH")
    return sorted(set(issues))


def _load_schema(
    path: Path,
    label: str,
    issues: list[str],
    *,
    validate_definition: bool = True,
) -> Any:
    try:
        schema = load_json(path)
    except (OSError, ValueError) as error:
        issues.append(f"{label}_SCHEMA_INVALID: {error.__class__.__name__}")
        return {}
    if validate_definition:
        issues.extend(
            f"{label}_SCHEMA_INVALID: {issue}"
            for issue in schema_definition_issues(schema)
        )
    return schema


def _public_envelope_issues(event: dict[str, Any], schema: Any) -> list[str]:
    if not isinstance(schema, dict):
        return ["PUBLIC_EVENT_ENVELOPE_INVALID"]
    properties = schema.get("properties", {})
    required = schema.get("required", [])
    if any(key not in event for key in required):
        return ["PUBLIC_EVENT_ENVELOPE_REQUIRED_MISSING"]
    event_type = event.get("type")
    pattern = properties.get("type", {}).get("pattern")
    try:
        import re

        type_valid = isinstance(event_type, str) and re.fullmatch(
            pattern, event_type
        )
    except (re.error, TypeError):
        type_valid = False
    valid = (
        event.get("specversion")
        == properties.get("specversion", {}).get("const")
        and isinstance(event.get("id"), str)
        and bool(event.get("id"))
        and isinstance(event.get("source"), str)
        and bool(event.get("source"))
        and type_valid
        and isinstance(event.get("subject"), str)
        and bool(event.get("subject"))
        and isinstance(event.get("time"), str)
        and event.get("datacontenttype")
        == properties.get("datacontenttype", {}).get("const")
        and "data" in event
    )
    return [] if valid else ["PUBLIC_EVENT_ENVELOPE_INVALID"]


def _load_valid_events(
    root: Path, issues: list[str]
) -> dict[Path, dict[str, Any]]:
    result: dict[Path, dict[str, Any]] = {}
    directory = root / VALID_EVENT_DIR
    if not directory.is_dir():
        issues.append("ACCESS_INVALIDATION_VALID_FIXTURES_MISSING")
        return result
    for path in sorted(directory.glob("*.json")):
        try:
            value = load_json(path)
        except (OSError, ValueError):
            issues.append(
                "ACCESS_INVALIDATION_VALID_FIXTURE_INVALID: "
                f"{path.relative_to(root)}"
            )
            continue
        if not isinstance(value, dict):
            issues.append(
                "ACCESS_INVALIDATION_VALID_FIXTURE_INVALID: "
                f"{path.relative_to(root)}"
            )
            continue
        result[path.relative_to(root)] = value
    if len(result) < 5:
        issues.append("ACCESS_INVALIDATION_VALID_FIXTURE_SET_INCOMPLETE")
    return result


def _lineage_issues(
    events: dict[Path, dict[str, Any]]
) -> list[str]:
    issues: list[str] = []
    by_id: dict[str, dict[str, Any]] = {}
    fingerprints: dict[str, bytes] = {}
    children: dict[str, list[str]] = {}
    by_lineage: dict[str, list[dict[str, Any]]] = {}
    for event in events.values():
        event_id = event.get("id")
        data = event.get("data", {})
        if not isinstance(event_id, str) or not isinstance(data, dict):
            continue
        fingerprint = canonical_bytes(event)
        if event_id in fingerprints and fingerprints[event_id] != fingerprint:
            issues.append("EVENT_IDEMPOTENCY_CONFLICT")
        fingerprints[event_id] = fingerprint
        by_id[event_id] = event
        lineage_id = data.get("lineageId")
        if isinstance(lineage_id, str):
            by_lineage.setdefault(lineage_id, []).append(event)
        supersedes_id = data.get("supersedesId")
        if isinstance(supersedes_id, str):
            children.setdefault(supersedes_id, []).append(event_id)
    for parent, child_ids in children.items():
        if len(set(child_ids)) > 1:
            issues.append(f"LINEAGE_FORK: {parent}")
    for lineage_id, lineage_events in by_lineage.items():
        ordered = sorted(
            lineage_events,
            key=lambda value: value.get("data", {}).get(
                "aggregateVersion", -1
            ),
        )
        previous: dict[str, Any] | None = None
        for event in ordered:
            data = event["data"]
            version = data.get("aggregateVersion")
            supersedes_id = data.get("supersedesId")
            if previous is None:
                if version != 1:
                    issues.append(f"LINEAGE_VERSION_GAP: {lineage_id}")
                if supersedes_id is not None:
                    predecessor = by_id.get(supersedes_id)
                    predecessor_lineage = (
                        predecessor.get("data", {}).get("lineageId")
                        if predecessor
                        else None
                    )
                    if predecessor_lineage not in (None, lineage_id):
                        issues.append(
                            f"LINEAGE_SUPERSEDES_CROSS_LINEAGE: {lineage_id}"
                        )
                    elif predecessor is not None:
                        issues.append(
                            f"LINEAGE_FUTURE_SUPERSEDES: {lineage_id}"
                        )
                    else:
                        issues.append(
                            f"LINEAGE_SUPERSEDES_UNKNOWN: {lineage_id}"
                        )
            else:
                previous_data = previous["data"]
                if version != previous_data.get("aggregateVersion", -1) + 1:
                    issues.append(f"LINEAGE_VERSION_GAP: {lineage_id}")
                if supersedes_id != previous.get("id"):
                    predecessor = by_id.get(supersedes_id)
                    if (
                        predecessor is not None
                        and predecessor.get("data", {}).get("lineageId")
                        != lineage_id
                    ):
                        issues.append(
                            f"LINEAGE_SUPERSEDES_CROSS_LINEAGE: {lineage_id}"
                        )
                    else:
                        issues.append(
                            f"LINEAGE_DIRECT_PREDECESSOR_INVALID: {lineage_id}"
                        )
            previous = event
    for event in events.values():
        data = event.get("data", {})
        cause_event_id = data.get("causeEventId")
        if not cause_event_id:
            continue
        cause = by_id.get(cause_event_id)
        if (
            cause is None
            or cause.get("data", {}).get("aggregateType")
            != "identity-cause"
            or cause.get("data", {}).get("lineageId")
            == data.get("lineageId")
        ):
            issues.append(f"CAUSE_EVENT_REFERENCE_INVALID: {event.get('id')}")
    return issues


def _ordering_issues(root: Path) -> list[str]:
    try:
        fixture = load_json(root / ORDERING_FIXTURES)
        cases = fixture["cases"]
        out_of_order = fixture["outOfOrderDelivery"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["ACCESS_INVALIDATION_ORDERING_FIXTURES_INVALID"]
    actual_ids: list[str] = []
    valid = (
        fixture.get("version")
        == "ACCESS-INVALIDATION-ORDERING-FIXTURES-1.0.0"
        and fixture.get("route")
        == "consumerId|producer|aggregateType|lineageId"
    )
    for case in cases:
        actual_ids.append(case.get("id"))
        decision = delivery_decision(
            case.get("currentWatermark"),
            case.get("incomingVersion"),
            same_payload=case.get("samePayload"),
        )
        valid = valid and decision == case.get("expectedDecision")
    valid = valid and set(actual_ids) == {
        "next-contiguous",
        "duplicate-same",
        "same-key-different-payload",
        "old-version",
        "gap",
    }
    watermark = out_of_order.get("initialWatermark")
    decisions: list[str] = []
    for incoming in out_of_order.get("incomingVersions", []):
        decision = delivery_decision(
            watermark, incoming, same_payload=True
        )
        decisions.append(decision)
        if decision == "APPLIED":
            watermark = incoming
    valid = (
        valid
        and decisions == out_of_order.get("expectedDecisions")
        and watermark == out_of_order.get("expectedFinalWatermark")
        and out_of_order.get("unrelatedRouteWatermark")
        == out_of_order.get("expectedUnrelatedRouteWatermark")
    )
    return [] if valid else ["ACCESS_INVALIDATION_ORDERING_FIXTURES_INVALID"]


def _policy_issues(root: Path) -> list[str]:
    try:
        policy = load_json(root / EVENT_POLICY)
    except (OSError, ValueError):
        return ["ACCESS_INVALIDATION_POLICY_INVALID"]
    valid = (
        policy.get("version") == "ACCESS-INVALIDATION-POLICY-1.0.0"
        and policy.get("eventType") == EXPECTED_EVENT_TYPE
        and policy.get("approvedBy") == "Hei"
        and policy.get("lineage", {}).get("successorRule")
        == "same-lineage-direct-predecessor-only"
        and policy.get("ordering", {}).get("nextVersion")
        == "currentWatermark+1"
        and policy.get("ordering", {}).get("gapScope")
        == "affected-route-only"
        and policy.get("wire", {}).get("maximumBytes") == MAX_EVENT_BYTES
        and policy.get("wire", {}).get("eventIdEqualsDataEventId") is True
        and policy.get("sourceEvidence", {}).get(
            "aggregateVersionMayReplaceSourceWatermark"
        )
        is False
        and policy.get("recovery", {}).get("deletePriorInvalidation")
        == "forbidden"
        and policy.get("completion", {}).get("plannedConsumerCountsAsComplete")
        is False
        and policy.get("completion", {}).get("transportAckCountsAsApply")
        is False
        and policy.get("authorization", {}).get(
            "delegationGrantMayBypassCurrentScope"
        )
        is False
        and policy.get("authorization", {}).get(
            "relationshipInvalidationEndsWholeSession"
        )
        is False
    )
    return [] if valid else ["ACCESS_INVALIDATION_POLICY_INVALID"]


def _consumer_registry_issues(root: Path) -> list[str]:
    try:
        registry = load_json(root / CONSUMER_REGISTRY)
        consumers = registry["consumers"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["ACCESS_INVALIDATION_CONSUMER_REGISTRY_INVALID"]
    by_id = {
        item.get("consumerId"): item
        for item in consumers
        if isinstance(item, dict)
    }
    expected_ids = {
        "authorization-current-scope",
        "public-task",
        "reporting-export",
        "responsibility-transfer",
        "mobile-surface-verification",
    }
    current = by_id.get("authorization-current-scope", {})
    planned_ids = {
        "public-task",
        "reporting-export",
        "responsibility-transfer",
    }
    valid = (
        registry.get("version")
        == "ACCESS-INVALIDATION-CONSUMER-REGISTRY-1.0.0"
        and registry.get("approvedBy") == "Hei"
        and registry.get("completionDenominator")
        == "applicable-active-required-consumers"
        and set(by_id) == expected_ids
        and len(consumers) == len(by_id)
        and current.get("owner") == "identity-access"
        and current.get("lifecycle") == "active"
        and current.get("required") is True
        and current.get("initialWatermark") == 0
        and current.get("runtimeEvidenceClaim") == "current-runtime"
        and current.get("consumerKind")
        == "invalidation-fence-read-model"
    )
    for consumer_id in planned_ids:
        item = by_id.get(consumer_id, {})
        valid = (
            valid
            and item.get("lifecycle") == "planned/not-installed"
            and item.get("required") is False
            and item.get("activationAt") is None
            and item.get("initialWatermark") is None
            and item.get("runtimeEvidenceClaim") == "none"
        )
    mobile = by_id.get("mobile-surface-verification", {})
    valid = (
        valid
        and mobile.get("consumerKind") == "surface-verification"
        and mobile.get("lifecycle") == "planned/not-installed"
        and mobile.get("required") is False
        and mobile.get("runtimeEvidenceClaim") == "none"
        and mobile.get("applyBoundary")
        == "shared-server-api-no-independent-projection"
    )
    return (
        []
        if valid
        else ["ACCESS_INVALIDATION_CONSUMER_REGISTRY_INVALID"]
    )


def _negative_fixture_issues(
    root: Path, schema: Any, public_schema: Any
) -> list[str]:
    try:
        catalog = load_json(root / NEGATIVE_FIXTURES)
        cases = catalog["cases"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["ACCESS_INVALIDATION_NEGATIVE_FIXTURES_INVALID"]
    ids = [case.get("id") for case in cases if isinstance(case, dict)]
    expected_codes = {
        case.get("id"): case.get("expectedReasonCode")
        for case in cases
        if isinstance(case, dict)
    }
    if (
        catalog.get("version")
        != "ACCESS-INVALIDATION-NEGATIVE-FIXTURES-1.0.0"
        or set(ids) != EXPECTED_NEGATIVE_CASES
        or len(ids) != len(set(ids))
    ):
        return ["ACCESS_INVALIDATION_NEGATIVE_FIXTURES_INVALID"]
    exercised = _exercise_negative_cases(root, schema, public_schema)
    if any(exercised.get(case_id) != code for case_id, code in expected_codes.items()):
        return ["ACCESS_INVALIDATION_NEGATIVE_FIXTURES_NOT_REJECTED"]
    return []


def _exercise_negative_cases(
    root: Path, schema: Any, public_schema: Any
) -> dict[str, str]:
    corrected = load_json(
        root
        / VALID_EVENT_DIR
        / "responsibility-corrected-v1.json"
    )
    revoked = load_json(
        root / VALID_EVENT_DIR / "responsibility-revoked-v2.json"
    )
    revalidated = load_json(
        root
        / VALID_EVENT_DIR
        / "responsibility-revalidated-v3.json"
    )
    derived = load_json(
        root / VALID_EVENT_DIR / "account-disabled-derived-v1.json"
    )
    result: dict[str, str] = {}

    value = copy.deepcopy(revoked)
    del value["data"]["lineageId"]
    result["missing-lineage"] = _expected_issue(
        event_issues(value, schema=schema, public_schema=public_schema),
        "EVENT_SCHEMA_REJECTED",
    )

    value = copy.deepcopy(revoked)
    value["data"]["eventId"] = "019c0000-0000-7000-8000-000000000099"
    result["event-id-mismatch"] = _expected_issue(
        event_issues(value, schema=schema, public_schema=public_schema),
        "EVENT_ID_MISMATCH",
    )

    value = copy.deepcopy(revoked)
    value["data"]["reasonCode"] = "because the row changed"
    result["unknown-reason-code"] = _expected_issue(
        event_issues(value, schema=schema, public_schema=public_schema),
        "EVENT_SCHEMA_REJECTED",
    )

    value = copy.deepcopy(revoked)
    value["data"]["studentId"] = "raw-student-001"
    result["raw-identifier-field"] = _expected_issue(
        event_issues(value, schema=schema, public_schema=public_schema),
        "EVENT_PRIVACY_BOUNDARY_VIOLATION",
    )

    value = copy.deepcopy(revoked)
    value["data"]["rawPadding"] = "x" * MAX_EVENT_BYTES
    result["payload-over-64-kib"] = _expected_issue(
        event_issues(value, schema=schema, public_schema=public_schema),
        "EVENT_PAYLOAD_TOO_LARGE",
    )

    value = copy.deepcopy(revalidated)
    value["data"]["reasonCode"] = "SOURCE_CORRECTION"
    result["invalid-recovery-reason"] = _expected_issue(
        event_issues(value, schema=schema, public_schema=public_schema),
        "RECOVERY_TAXONOMY_INVALID",
    )

    cross = copy.deepcopy(derived)
    cross["data"]["supersedesId"] = corrected["id"]
    result["cross-lineage-supersedes"] = _first_reason(
        _lineage_issues(
            {
                Path("corrected.json"): corrected,
                Path("cross.json"): cross,
            }
        ),
        "LINEAGE_SUPERSEDES_CROSS_LINEAGE",
    )

    fork = copy.deepcopy(revoked)
    fork["id"] = "019c0000-0000-7000-8000-000000000090"
    fork["data"]["eventId"] = fork["id"]
    result["lineage-fork"] = _first_reason(
        _lineage_issues(
            {
                Path("corrected.json"): corrected,
                Path("revoked.json"): revoked,
                Path("fork.json"): fork,
            }
        ),
        "LINEAGE_FORK",
    )

    future = copy.deepcopy(corrected)
    future["data"]["supersedesId"] = revoked["id"]
    result["future-supersedes"] = _first_reason(
        _lineage_issues(
            {
                Path("future.json"): future,
                Path("revoked.json"): revoked,
            }
        ),
        "LINEAGE_FUTURE_SUPERSEDES",
    )

    gap = copy.deepcopy(revoked)
    gap["data"]["aggregateVersion"] = 4
    gap["data"]["invalidationVersion"] = 4
    result["aggregate-version-gap"] = _first_reason(
        _lineage_issues(
            {
                Path("corrected.json"): corrected,
                Path("gap.json"): gap,
            }
        ),
        "LINEAGE_VERSION_GAP",
    )

    conflict = copy.deepcopy(revoked)
    conflict["data"]["reasonCode"] = "SOURCE_CORRECTION"
    result["same-key-different-payload"] = _first_reason(
        _lineage_issues(
            {
                Path("revoked.json"): revoked,
                Path("conflict.json"): conflict,
            }
        ),
        "EVENT_IDEMPOTENCY_CONFLICT",
    )
    registry = load_json(root / CONSUMER_REGISTRY)
    public_task = next(
        item
        for item in registry["consumers"]
        if item["consumerId"] == "public-task"
    )
    result["planned-consumer-ack"] = (
        "CONSUMER_NOT_ACTIVE"
        if public_task.get("lifecycle") == "planned/not-installed"
        and public_task.get("runtimeEvidenceClaim") == "none"
        else "NEGATIVE_CASE_NOT_REJECTED"
    )
    policy = load_json(root / EVENT_POLICY)
    result["delegation-grant-bypass"] = (
        "CURRENT_SCOPE_OVERRIDE_FORBIDDEN"
        if policy.get("authorization", {}).get(
            "delegationGrantMayBypassCurrentScope"
        )
        is False
        else "NEGATIVE_CASE_NOT_REJECTED"
    )
    return result


def _expected_issue(issues: list[str], expected: str) -> str:
    return expected if expected in issues else "NEGATIVE_CASE_NOT_REJECTED"


def _first_reason(issues: list[str], prefix: str) -> str:
    return (
        prefix
        if any(issue == prefix or issue.startswith(prefix + ":") for issue in issues)
        else "NEGATIVE_CASE_NOT_REJECTED"
    )


def _responsibility_v2_issues(root: Path) -> list[str]:
    issues: list[str] = []
    schema = _load_schema(
        root / RESPONSIBILITY_V2_SCHEMA,
        "RESPONSIBILITY_AUTHORITY_V2",
        issues,
    )
    record_schema = _load_schema(
        root / RESPONSIBILITY_V2_RECORD_SCHEMA,
        "RESPONSIBILITY_AUTHORITY_V2_RECORD",
        issues,
    )
    snapshot_schema = _load_schema(
        root / RESPONSIBILITY_V2_SNAPSHOT_SCHEMA,
        "RESPONSIBILITY_AUTHORITY_V2_SNAPSHOT",
        issues,
    )
    runtime_schema = _load_schema(
        root / RESPONSIBILITY_V2_RUNTIME_SCHEMA,
        "RESPONSIBILITY_AUTHORITY_V2_RUNTIME_PROFILE",
        issues,
    )
    try:
        fixture = load_json(root / RESPONSIBILITY_V2_FIXTURE)
        payload = fixture["payload"]
    except (OSError, ValueError, KeyError, TypeError):
        issues.append("RESPONSIBILITY_AUTHORITY_V2_FIXTURE_INVALID")
        payload = None
        fixture = {}
    if (
        payload is not None
        and (
            fixture.get("contractVersion")
            != "RESPONSIBILITY-AUTHORITY-2.0.0"
            or schema_issues(fixture, record_schema)
            or fixture.get("$schema")
            != "../../responsibility-record.schema.json"
        )
    ):
        issues.append("RESPONSIBILITY_AUTHORITY_V2_FIXTURE_REJECTED")
    if isinstance(payload, dict):
        canonical_payload = copy.deepcopy(payload)
        claimed_digest = canonical_payload.pop("payloadDigest", "")
        actual_digest = "sha256:" + hashlib.sha256(
            canonical_bytes(canonical_payload)
        ).hexdigest()
        if claimed_digest != actual_digest:
            issues.append(
                "RESPONSIBILITY_AUTHORITY_V2_PAYLOAD_DIGEST_MISMATCH"
            )
    try:
        snapshot = load_json(root / RESPONSIBILITY_V2_SNAPSHOT_FIXTURE)
    except (OSError, ValueError):
        issues.append("RESPONSIBILITY_AUTHORITY_V2_SNAPSHOT_FIXTURE_INVALID")
        snapshot = {}
    if (
        not isinstance(snapshot, dict)
        or schema_issues(snapshot, snapshot_schema)
        or snapshot.get("$schema") != "../../full-snapshot.schema.json"
        or snapshot.get("contractVersion")
        != "RESPONSIBILITY-AUTHORITY-2.0.0"
        or snapshot.get("lineageDigestProfile")
        != "RESPONSIBILITY-LINEAGE-DIGEST-1.0.0"
    ):
        issues.append("RESPONSIBILITY_AUTHORITY_V2_SNAPSHOT_FIXTURE_REJECTED")
    else:
        records = snapshot.get("records", [])
        lineages = snapshot.get("lineages", [])
        canonical_records = sorted(
            (
                f"{record['relationRefToken']}|"
                f"{record['studentEquivalenceDomain'].removeprefix('sha256:')}|"
                f"{record['recordVersion']}|"
                f"{record['payloadDigest'].removeprefix('sha256:')}"
            )
            for record in records
        )
        projection_digest = hashlib.sha256(
            "\n".join(canonical_records).encode("utf-8")
        ).hexdigest()
        canonical_lineages = sorted(
            (
                f"{lineage['relationRefToken']}|{lineage['lineageId']}|"
                f"{lineage['rootEventId']}|{lineage['headEventId']}|"
                f"{lineage['eventCount']}|"
                f"{lineage['canonicalChainDigest'].removeprefix('sha256:')}"
            )
            for lineage in lineages
        )
        lineage_digest = hashlib.sha256(
            "\n".join(canonical_lineages).encode("utf-8")
        ).hexdigest()
        record_tokens = {
            record.get("relationRefToken") for record in records
        }
        lineage_tokens = {
            lineage.get("relationRefToken") for lineage in lineages
        }
        if (
            snapshot.get("expectedCount") != len(records)
            or snapshot.get("canonicalDigest")
            != "sha256:" + projection_digest
            or snapshot.get("lineageCount") != len(lineages)
            or len({lineage.get("lineageId") for lineage in lineages})
            != len(lineages)
            or record_tokens != lineage_tokens
            or snapshot.get("canonicalLineageDigest")
            != "sha256:" + lineage_digest
        ):
            issues.append(
                "RESPONSIBILITY_AUTHORITY_V2_SNAPSHOT_EVIDENCE_MISMATCH"
            )
    try:
        lineage_profile = load_json(
            root / RESPONSIBILITY_V2_LINEAGE_DIGEST_PROFILE
        )
        vectors = lineage_profile["goldenVectors"]
    except (OSError, ValueError, KeyError, TypeError):
        issues.append("RESPONSIBILITY_AUTHORITY_V2_LINEAGE_DIGEST_INVALID")
        lineage_profile = {}
        vectors = []
    expected_fields = [
        "lineageVersion",
        "eventId",
        "supersedesId",
        "sourceVersion",
        "sourceWatermark",
        "recordVersion",
        "payloadDigestWithoutPrefix",
        "changeKindLowercase",
        "reasonCodeUppercase",
        "effectiveAtUtcMicrosecond",
    ]
    valid_digests: set[str] = set()
    if (
        lineage_profile.get("version")
        != "RESPONSIBILITY-LINEAGE-DIGEST-1.0.0"
        or lineage_profile.get("algorithm") != "SHA-256"
        or lineage_profile.get("encoding") != "UTF-8"
        or lineage_profile.get("lineSeparator") != "LF"
        or lineage_profile.get("fieldSeparator") != "|"
        or lineage_profile.get("header")
        != "RESPONSIBILITY-LINEAGE-DIGEST-1.0.0"
        or lineage_profile.get("nullSupersedes") != "-"
        or lineage_profile.get("fields") != expected_fields
        or not vectors
    ):
        issues.append("RESPONSIBILITY_AUTHORITY_V2_LINEAGE_DIGEST_INVALID")
    for vector in vectors:
        try:
            canonical_events = []
            for event in vector["events"]:
                canonical_events.append("|".join([
                    str(event["lineageVersion"]),
                    event["eventId"],
                    event["supersedesId"] or "-",
                    str(event["sourceVersion"]),
                    str(event["sourceWatermark"]),
                    str(event["recordVersion"]),
                    event["payloadDigest"],
                    event["changeKind"],
                    event["reasonCode"],
                    event["effectiveAt"],
                ]))
            canonical_text = (
                "RESPONSIBILITY-LINEAGE-DIGEST-1.0.0\n"
                + "\n".join(canonical_events)
            )
            digest_value = hashlib.sha256(
                canonical_text.encode("utf-8")
            ).hexdigest()
            if (
                vector.get("canonicalText") != canonical_text
                or vector.get("sha256") != digest_value
            ):
                raise ValueError("golden mismatch")
            valid_digests.add(digest_value)
        except (KeyError, TypeError, ValueError):
            issues.append(
                "RESPONSIBILITY_AUTHORITY_V2_LINEAGE_DIGEST_INVALID"
            )
    snapshot_chain_digests = {
        str(lineage.get("canonicalChainDigest", "")).removeprefix(
            "sha256:"
        )
        for lineage in snapshot.get("lineages", [])
        if isinstance(lineage, dict)
    }
    if snapshot_chain_digests and not snapshot_chain_digests <= valid_digests:
        issues.append("RESPONSIBILITY_AUTHORITY_V2_LINEAGE_DIGEST_INVALID")
    try:
        policy = load_json(root / RESPONSIBILITY_V2_POLICY)
    except (OSError, ValueError):
        issues.append("RESPONSIBILITY_AUTHORITY_V2_POLICY_INVALID")
        policy = {}
    try:
        runtime_profile = load_json(root / RESPONSIBILITY_V2_RUNTIME_PROFILE)
    except (OSError, ValueError):
        issues.append("RESPONSIBILITY_AUTHORITY_V2_RUNTIME_PROFILE_INVALID")
        runtime_profile = {}
    canonical_profile = copy.deepcopy(runtime_profile)
    profile_digest = canonical_profile.pop("digest", None)
    policy_digest = "sha256:" + hashlib.sha256(
        canonical_bytes(policy)
    ).hexdigest()
    runtime_bindings = runtime_profile.get("bindings", {})
    if (
        schema_issues(runtime_profile, runtime_schema)
        or profile_digest
        != "sha256:" + hashlib.sha256(
            canonical_bytes(canonical_profile)
        ).hexdigest()
        or runtime_profile.get("policyDigest") != policy_digest
        or runtime_profile.get("effectiveAt") != policy.get("effectiveAt")
        or runtime_bindings.get("writeContractVersion")
        != policy.get("dualRead", {}).get("writeVersion")
        or runtime_bindings.get("dualReadWindowHours") != 336
        or runtime_bindings.get("cutoverEnabled") is not True
        or runtime_bindings.get("lineageDigestProfile")
        != "RESPONSIBILITY-LINEAGE-DIGEST-1.0.0"
    ):
        issues.append("RESPONSIBILITY_AUTHORITY_V2_RUNTIME_PROFILE_INVALID")
    dual_read = policy.get("dualRead", {})
    provenance = policy.get("lineageProvenance", {})
    valid_policy = (
        policy.get("version") == "RESPONSIBILITY-POLICY-2.0.0"
        and policy.get("contractVersion")
        == "RESPONSIBILITY-AUTHORITY-2.0.0"
        and policy.get("approvedBy") == "Hei"
        and provenance.get("owner")
        == "SRC-P0-RESPONSIBILITY-001 source owner"
        and provenance.get("platformInference") == "forbidden"
        and dual_read.get("acceptedContractVersions")
        == [
            "RESPONSIBILITY-AUTHORITY-1.0.0",
            "RESPONSIBILITY-AUTHORITY-2.0.0",
        ]
        and dual_read.get("writeVersion")
        == "RESPONSIBILITY-AUTHORITY-2.0.0"
        and dual_read.get("version1Behavior")
        == "live-until-atomic-activation"
        and dual_read.get("version1MayAdvanceInvalidationWatermark") is False
        and dual_read.get("cutoverCondition")
        == "version2-full-replay-lineage-and-reconciliation-pass"
        and policy.get("replay", {}).get("startingWatermark") == 0
        and policy.get("replay", {}).get("requiresLineageReconciliation")
        is True
    )
    if not valid_policy:
        issues.append("RESPONSIBILITY_AUTHORITY_V2_POLICY_INVALID")
    try:
        negative = load_json(root / RESPONSIBILITY_V2_NEGATIVE)
        cases = negative["cases"]
    except (OSError, ValueError, KeyError, TypeError):
        issues.append("RESPONSIBILITY_AUTHORITY_V2_NEGATIVE_FIXTURES_INVALID")
        return issues
    case_ids = [
        case.get("id") for case in cases if isinstance(case, dict)
    ]
    if (
        negative.get("version")
        != "RESPONSIBILITY-AUTHORITY-NEGATIVE-FIXTURES-2.0.0"
        or set(case_ids) != EXPECTED_V2_NEGATIVE_CASES
        or len(case_ids) != len(set(case_ids))
        or not all(
            isinstance(case.get("expectedReasonCode"), str)
            and case["expectedReasonCode"].startswith("RESPONSIBILITY_V2_")
            for case in cases
        )
    ):
        issues.append("RESPONSIBILITY_AUTHORITY_V2_NEGATIVE_FIXTURES_INVALID")
    if isinstance(payload, dict):
        missing = copy.deepcopy(payload)
        missing.pop("lineageId", None)
        unknown = copy.deepcopy(payload)
        unknown["reasonCode"] = "free text"
        if not schema_issues(missing, schema) or not schema_issues(unknown, schema):
            issues.append(
                "RESPONSIBILITY_AUTHORITY_V2_NEGATIVE_FIXTURES_NOT_REJECTED"
            )
    return issues


def _lock_issues(
    root: Path,
    base: Path,
    lock_path: Path,
    upstream_paths: list[Path],
    label: str,
) -> list[str]:
    try:
        lock = load_json(root / lock_path)
        entries = lock["files"]
        upstream = lock["upstreamLocks"]
    except (OSError, ValueError, KeyError, TypeError):
        return [f"{label}_CONTRACT_LOCK_INVALID"]
    expected_paths = sorted(
        str(path.relative_to(root))
        for path in (root / base).rglob("*")
        if path.is_file() and path.relative_to(root) != lock_path
    )
    actual_paths = [
        entry.get("path") for entry in entries if isinstance(entry, dict)
    ]
    if actual_paths != expected_paths:
        return [f"{label}_CONTRACT_LOCK_INVALID"]
    for entry in entries:
        path = root / entry["path"]
        if (
            not path.is_file()
            or entry.get("sha256")
            != hashlib.sha256(path.read_bytes()).hexdigest()
        ):
            return [
                f"{label}_CONTRACT_LOCK_DIGEST_MISMATCH: "
                f"{entry.get('path')}"
            ]
    expected_upstream = [
        {
            "path": str(path),
            "sha256": hashlib.sha256((root / path).read_bytes()).hexdigest(),
        }
        for path in upstream_paths
    ]
    if upstream != expected_upstream:
        return [f"{label}_UPSTREAM_LOCK_DIGEST_MISMATCH"]
    return []


def _contains_private_field(value: Any) -> bool:
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = key.lower().replace("_", "").replace("-", "")
            if normalized in PRIVATE_KEYS or normalized.startswith("raw"):
                return True
            if _contains_private_field(child):
                return True
    elif isinstance(value, list):
        return any(_contains_private_field(item) for item in value)
    return False


def main() -> int:
    issues = validate(Path.cwd())
    if issues:
        for issue in issues:
            print(issue)
        return 1
    print("access invalidation contracts: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
