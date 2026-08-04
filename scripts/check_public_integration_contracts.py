#!/usr/bin/env python3
"""Validate the locked PIC-1.0.0 contract package and deterministic vectors."""

from __future__ import annotations

import base64
import copy
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


sys.path.insert(0, str(Path(__file__).resolve().parent))

from release_json import (  # noqa: E402
    MAX_SAFE_INTEGER,
    canonical_bytes,
    load_json,
    schema_definition_issues,
    schema_issues,
)


CONTRACT_ROOT = Path("contracts/public-integration")
EVENT_ROOT = Path("contracts/events/public-integration")
EVENT_SCHEMA = EVENT_ROOT / "public-integration-event.schema.json"
COMMAND_SCHEMA = EVENT_ROOT / "public-integration-command.schema.json"
OPENAPI = Path("contracts/openapi/public-integration.openapi.json")
REGISTRY = CONTRACT_ROOT / "adapter-registry-1.0.0.json"
RUNTIME_PROFILE = (
    CONTRACT_ROOT / "public-integration-runtime-profile-1.0.0.json"
)
SECURITY_PROFILE = (
    CONTRACT_ROOT / "public-integration-security-profile-1.0.0.json"
)
POLICY = CONTRACT_ROOT / "public-integration-policy-1.0.0.json"
MEASUREMENT = (
    CONTRACT_ROOT / "public-integration-measurement-profile-1.0.0.json"
)
TARGET_DESCRIPTOR_SCHEMA = (
    CONTRACT_ROOT / "public-integration-target-descriptor.schema.json"
)
TARGET_HANDOFF_SCHEMA = (
    CONTRACT_ROOT / "public-integration-target-handoff.schema.json"
)
TARGET_SIGNATURE_SCHEMA = (
    CONTRACT_ROOT
    / "public-integration-target-handoff-signature.schema.json"
)
EVIDENCE_SCHEMA = (
    CONTRACT_ROOT / "public-integration-evidence.schema.json"
)
TARGET_SCENARIOS = (
    CONTRACT_ROOT / "public-integration-target-scenarios-1.0.0.json"
)
AUDIT_PROFILE = (
    CONTRACT_ROOT / "public-integration-audit-profile-1.0.0.json"
)
AUDIT_FIXTURE = (
    CONTRACT_ROOT / "fixtures/audit/conformance-records-1.0.0.json"
)
LOCAL_AUDIT_SCHEMA = Path("contracts/audit/local-audit-fact.schema.json")
PIC = CONTRACT_ROOT / "pic-1.0.0.json"
LOCK = CONTRACT_ROOT / "public-integration-contract-lock-1.0.0.json"
VALID_EVENT = (
    CONTRACT_ROOT / "fixtures/valid/public-task-created-event.json"
)
NEGATIVE_FIXTURES = (
    CONTRACT_ROOT / "fixtures/invalid/negative-fixtures-1.0.0.json"
)
OPTIONAL_COMPATIBILITY = (
    CONTRACT_ROOT / "fixtures/compatibility/optional-addition-1.1.0.json"
)
BREAKING_COMPATIBILITY = (
    CONTRACT_ROOT / "fixtures/compatibility/breaking-without-major.json"
)
DELEGATED_SOURCE = Path(
    "_bmad-output/planning-artifacts/"
    "delegated-decision-baseline-2026-07-17.md"
)

EXPECTED_CAPABILITIES = {
    "public-task",
    "public-message",
    "status-result-writeback",
    "external-transfer-work-order",
    "metric-publication",
}
EXPECTED_NEGATIVE_CASES = {
    "tagged-provenance-both",
    "tagged-provenance-none",
    "event-id-mismatch",
    "source-fact-digest-conflict",
    "route-sequence-zero",
    "route-sequence-overflow",
    "aggregate-version-zero",
    "payload-65537-bytes",
    "compressed-body",
    "unknown-envelope-key",
    "breaking-without-major",
    "deep-link-absolute",
    "deep-link-double-encoded",
    "tsp-unapproved-pause",
    "mpp-normal-n9",
    "mpp-normal-n10",
    "mpp-sensitive-n19",
    "mpp-sensitive-n20",
    "mpp-anti-differencing",
}
EXPECTED_SCENARIOS = {
    "contract-auth",
    "create",
    "update",
    "close",
    "revoke",
    "transient-retry",
    "duplicate-idempotency",
    "out-of-order",
    "dual-id-route-watermark-reconcile",
    "slo-five-minute-budget",
}
UUID_V7 = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
EVENT_TYPE = re.compile(
    r"^scholarsense\.[a-z0-9-]+\.[a-z0-9-]+\.[a-z0-9-]+\.v1$"
)
CANONICAL_UTC = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$"
)
HEX64 = re.compile(r"^[0-9a-f]{64}$")
PRIVATE_KEYS = {
    "studentid",
    "studentnumber",
    "studentname",
    "phone",
    "email",
    "freetext",
    "evidencebody",
    "secret",
    "token",
    "signature",
    "nonce",
    "externalerrorbody",
}


def canonical_digest(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_bytes(value)).hexdigest()


def _derived(prefix: str, value: Any) -> str:
    raw = hashlib.sha256(canonical_bytes(value)).digest()
    encoded = base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")
    return f"{prefix}.{encoded}"


def derive_delivery_intent_id(
    tokenized_work_item_key: str,
    notification_type: str,
    escalation_version: int,
) -> str:
    return _derived(
        "di1",
        {
            "escalationVersion": escalation_version,
            "kind": "message-intent",
            "notificationType": notification_type,
            "workItemKeyToken": tokenized_work_item_key,
        },
    )


def derive_generation_key(
    *,
    provenance_mode: str,
    source: str | None = None,
    source_fact_id: str | None = None,
    delivery_intent_id: str | None = None,
) -> str:
    if provenance_mode == "aggregate-stream":
        if not source or not source_fact_id or delivery_intent_id is not None:
            raise ValueError("PIC_PROVENANCE_MODE_INVALID")
        value = {
            "kind": "event",
            "source": source,
            "sourceFactId": source_fact_id,
        }
    elif provenance_mode == "intent-command":
        if not delivery_intent_id or source is not None or source_fact_id is not None:
            raise ValueError("PIC_PROVENANCE_MODE_INVALID")
        value = {"deliveryIntentId": delivery_intent_id, "kind": "intent"}
    else:
        raise ValueError("PIC_PROVENANCE_MODE_INVALID")
    return _derived("g1", value)


def delivery_decision(
    current_route_watermark: int,
    incoming_route_sequence: int,
    last_source_aggregate_version: int | None,
    incoming_source_aggregate_version: int,
    *,
    same_identity: bool,
) -> str:
    if not (
        0 <= current_route_watermark <= MAX_SAFE_INTEGER
        and 1 <= incoming_route_sequence <= MAX_SAFE_INTEGER
        and 1 <= incoming_source_aggregate_version <= MAX_SAFE_INTEGER
    ):
        return "INVALID"
    if incoming_route_sequence == current_route_watermark:
        return "DUPLICATE" if same_identity else "CONFLICT"
    if incoming_route_sequence < current_route_watermark:
        return "STALE" if same_identity else "CONFLICT"
    if incoming_route_sequence > current_route_watermark + 1:
        return "GAP"
    if (
        last_source_aggregate_version is not None
        and incoming_source_aggregate_version <= last_source_aggregate_version
    ):
        return "SOURCE_VERSION_CONFLICT"
    return "NEXT"


def event_issues(
    event: Any,
    *,
    project_root: Path | None = None,
    body_bytes: bytes | None = None,
) -> list[str]:
    root = (project_root or Path(__file__).resolve().parents[1]).resolve()
    issues: list[str] = []
    try:
        schema = load_json(root / EVENT_SCHEMA)
    except (OSError, ValueError):
        return ["EVENT_SCHEMA_UNAVAILABLE"]
    if not isinstance(event, dict):
        return ["EVENT_SCHEMA_REJECTED"]
    try:
        wire = body_bytes if body_bytes is not None else canonical_bytes(event)
    except (TypeError, ValueError):
        return ["EVENT_CANONICAL_JSON_INVALID"]
    if len(wire) > 65_536:
        issues.append("EVENT_PAYLOAD_TOO_LARGE")
    if event.get("contentEncoding", "identity") != "identity":
        issues.append("EVENT_CONTENT_ENCODING_UNSUPPORTED")
    if schema_issues(event, schema):
        issues.append("EVENT_SCHEMA_REJECTED")
    if _contains_private_key(event):
        issues.append("EVENT_PRIVACY_BOUNDARY_VIOLATION")
    data = event.get("data")
    if not isinstance(data, dict):
        return sorted(set(issues + ["EVENT_DATA_INVALID"]))
    if event.get("id") != data.get("eventId"):
        issues.append("EVENT_ID_MISMATCH")
    if event.get("time") != data.get("occurredAt"):
        issues.append("EVENT_TIME_MISMATCH")
    if not CANONICAL_UTC.fullmatch(str(event.get("time", ""))):
        issues.append("EVENT_TIME_NOT_CANONICAL_UTC")
    producer = data.get("producer")
    if event.get("source") != f"urn:scholarsense:{producer}":
        issues.append("EVENT_SOURCE_OWNER_MISMATCH")
    expected_subject = f"{data.get('aggregateType')}/{data.get('aggregateId')}"
    if event.get("subject") != expected_subject:
        issues.append("EVENT_SUBJECT_MISMATCH")
    traceparent = str(event.get("traceparent", ""))
    if len(traceparent.split("-")) < 2 or traceparent.split("-")[1] != data.get("traceId"):
        issues.append("EVENT_TRACE_MISMATCH")
    if not EVENT_TYPE.fullmatch(str(event.get("type", ""))):
        issues.append("EVENT_TYPE_INVALID")
    payload = data.get("payload")
    if isinstance(payload, dict):
        if data.get("eventPayloadDigest") != canonical_digest(payload):
            issues.append("EVENT_PAYLOAD_DIGEST_MISMATCH")
        route_state = payload.get("routeState")
        if isinstance(route_state, str) and not _safe_route_state(route_state):
            issues.append("PIC_DEEP_LINK_INVALID")
    return sorted(set(issues))


def _resolve_json_pointer(document: Any, pointer: str) -> Any:
    if pointer == "#":
        return document
    if not isinstance(pointer, str) or not pointer.startswith("#/"):
        raise ValueError("PIC_COMPATIBILITY_POINTER_INVALID")
    current = document
    for raw in pointer[2:].split("/"):
        key = raw.replace("~1", "/").replace("~0", "~")
        if not isinstance(current, dict) or key not in current:
            raise ValueError("PIC_COMPATIBILITY_POINTER_UNRESOLVED")
        current = current[key]
    return current


def _semantic_version(value: Any) -> tuple[int, int, int]:
    matched = re.fullmatch(r"([0-9]+)\.([0-9]+)\.([0-9]+)", str(value))
    if matched is None:
        raise ValueError("PIC_COMPATIBILITY_VERSION_INVALID")
    return tuple(int(part) for part in matched.groups())


def execute_compatibility_fixture(
    document: Any,
    *,
    project_root: Path | None = None,
) -> str:
    if not isinstance(document, dict):
        return "PIC_COMPATIBILITY_FIXTURE_INVALID"
    root = (project_root or Path(__file__).resolve().parents[1]).resolve()
    if document.get("schemaPath") != COMMAND_SCHEMA.as_posix():
        return "PIC_COMPATIBILITY_FIXTURE_INVALID"
    try:
        base_schema = load_json(root / COMMAND_SCHEMA)
        candidate_schema = copy.deepcopy(base_schema)
        target = _resolve_json_pointer(
            candidate_schema, str(document.get("schemaTarget"))
        )
        field = document.get("field")
        if not isinstance(target, dict) or not isinstance(field, str) or not field:
            return "PIC_COMPATIBILITY_FIXTURE_INVALID"

        change = document.get("change")
        if change == "add-optional-field":
            producer_version = _semantic_version(
                document.get("producerSchemaVersion")
            )
            consumer_version = _semantic_version(
                document.get("consumerSchemaVersion")
            )
            properties = target.get("properties")
            field_schema = document.get("fieldSchema")
            if (
                producer_version[0] != consumer_version[0]
                or producer_version <= consumer_version
                or not isinstance(properties, dict)
                or field in properties
                or field in target.get("required", [])
                or not isinstance(field_schema, dict)
            ):
                return "PIC_COMPATIBILITY_FIXTURE_INVALID"
            properties[field] = copy.deepcopy(field_schema)
            if schema_definition_issues(candidate_schema):
                return "PIC_COMPATIBILITY_FIXTURE_INVALID"

            producer = copy.deepcopy(document.get("producerInstance"))
            if not isinstance(producer, dict):
                return "PIC_COMPATIBILITY_FIXTURE_INVALID"
            if schema_issues(producer, candidate_schema):
                return "PIC_COMPATIBILITY_FIXTURE_INVALID"
            if not schema_issues(producer, base_schema):
                return "PIC_COMPATIBILITY_FIXTURE_INVALID"

            projected = copy.deepcopy(producer)
            instance_path = document.get("instanceObjectPath")
            if not isinstance(instance_path, list) or any(
                not isinstance(part, str) for part in instance_path
            ):
                return "PIC_COMPATIBILITY_FIXTURE_INVALID"
            projection_target: Any = projected
            for part in instance_path:
                if not isinstance(projection_target, dict) or part not in projection_target:
                    return "PIC_COMPATIBILITY_FIXTURE_INVALID"
                projection_target = projection_target[part]
            if not isinstance(projection_target, dict) or field not in projection_target:
                return "PIC_COMPATIBILITY_FIXTURE_INVALID"
            projection_target.pop(field)
            if projected != document.get("expectedDownProjectedInstance"):
                return "PIC_COMPATIBILITY_FIXTURE_INVALID"
            if schema_issues(projected, base_schema):
                return "PIC_COMPATIBILITY_FIXTURE_INVALID"
            return "compatible"

        if change in {"remove-required-field", "rename-required-field"}:
            declared_major = document.get("declaredMajor")
            consumer_version = _semantic_version(
                document.get("consumerSchemaVersion")
            )
            candidate_version = _semantic_version(
                document.get("candidateSchemaVersion")
            )
            required = target.get("required")
            base_instance = copy.deepcopy(document.get("baseInstance"))
            if (
                declared_major != consumer_version[0]
                or candidate_version[0] != consumer_version[0]
                or not isinstance(required, list)
                or field not in required
                or not isinstance(base_instance, dict)
                or schema_issues(base_instance, base_schema)
            ):
                return "PIC_COMPATIBILITY_FIXTURE_INVALID"
            target["required"] = [item for item in required if item != field]
            probe = copy.deepcopy(base_instance)
            if field not in probe:
                return "PIC_COMPATIBILITY_FIXTURE_INVALID"
            probe.pop(field)
            if not schema_issues(probe, base_schema):
                return "PIC_COMPATIBILITY_FIXTURE_INVALID"
            if schema_issues(probe, candidate_schema):
                return "PIC_COMPATIBILITY_FIXTURE_INVALID"
            return "PIC_BREAKING_CHANGE_REQUIRES_MAJOR"
    except (OSError, ValueError):
        return "PIC_COMPATIBILITY_FIXTURE_INVALID"
    return "PIC_COMPATIBILITY_FIXTURE_INVALID"


def execute_negative_fixture(
    case: Any,
    *,
    valid_event: dict[str, Any],
    project_root: Path,
) -> str:
    if not isinstance(case, dict) or not isinstance(case.get("input"), dict):
        return "PIC_NEGATIVE_FIXTURE_INPUT_INVALID"
    value = case["input"]
    kind = value.get("kind")
    if kind == "generation-key":
        try:
            derive_generation_key(
                provenance_mode=str(value.get("provenanceMode")),
                source=value.get("source"),
                source_fact_id=value.get("sourceFactId"),
                delivery_intent_id=value.get("deliveryIntentId"),
            )
        except ValueError as error:
            return str(error)
        return "PIC_NEGATIVE_FIXTURE_UNEXPECTED_PASS"
    if kind == "source-fact-conflict":
        return (
            "SOURCE_FACT_DIGEST_CONFLICT"
            if value.get("storedDigest") != value.get("incomingDigest")
            else "PIC_NEGATIVE_FIXTURE_UNEXPECTED_PASS"
        )
    if kind == "event-mutation":
        event = copy.deepcopy(valid_event)
        mutation = value.get("mutation")
        expected_by_mutation = {
            "event-id-mismatch": "EVENT_ID_MISMATCH",
            "route-sequence-zero": "EVENT_SCHEMA_REJECTED",
            "route-sequence-overflow": "EVENT_SCHEMA_REJECTED",
            "aggregate-version-zero": "EVENT_SCHEMA_REJECTED",
            "payload-65537-bytes": "EVENT_PAYLOAD_TOO_LARGE",
            "compressed-body": "EVENT_CONTENT_ENCODING_UNSUPPORTED",
            "unknown-envelope-key": "EVENT_SCHEMA_REJECTED",
            "deep-link-absolute": "PIC_DEEP_LINK_INVALID",
            "deep-link-double-encoded": "PIC_DEEP_LINK_INVALID",
        }
        if mutation == "event-id-mismatch":
            event["data"]["eventId"] = "018f0f9a-7b0d-7abc-8def-0123456789ad"
        elif mutation == "route-sequence-zero":
            event["data"]["routeSequence"] = 0
        elif mutation == "route-sequence-overflow":
            event["data"]["routeSequence"] = MAX_SAFE_INTEGER + 1
        elif mutation == "aggregate-version-zero":
            event["data"]["aggregateVersion"] = 0
        elif mutation == "compressed-body":
            event["contentEncoding"] = "gzip"
        elif mutation == "unknown-envelope-key":
            event["unexpected"] = True
        elif mutation == "deep-link-absolute":
            event["data"]["payload"]["routeState"] = "https://evil.invalid/item"
        elif mutation == "deep-link-double-encoded":
            event["data"]["payload"]["routeState"] = "%252Fadmin"
        elif mutation != "payload-65537-bytes":
            return "PIC_NEGATIVE_FIXTURE_INPUT_INVALID"
        if mutation == "payload-65537-bytes":
            body = b"x" * 65_537
        elif mutation == "route-sequence-overflow":
            # Preserve the actual wire representation so the schema boundary,
            # rather than canonical-number normalization, owns this rejection.
            body = json.dumps(event, separators=(",", ":")).encode("utf-8")
        else:
            body = None
        detected = event_issues(event, project_root=project_root, body_bytes=body)
        expected = expected_by_mutation.get(str(mutation))
        return expected if expected in detected else "PIC_NEGATIVE_FIXTURE_UNEXPECTED_PASS"
    if kind == "compatibility":
        name = value.get("fixture")
        if name != "breaking-without-major.json":
            return "PIC_NEGATIVE_FIXTURE_INPUT_INVALID"
        return execute_compatibility_fixture(
            load_json(project_root / BREAKING_COMPATIBILITY),
            project_root=project_root,
        )
    if kind == "tsp-pause":
        return (
            "TSP_PAUSE_NOT_APPROVED"
            if value.get("paused") is True and value.get("approved") is not True
            else "PIC_NEGATIVE_FIXTURE_UNEXPECTED_PASS"
        )
    if kind == "mpp-threshold":
        count = value.get("count")
        threshold = 20 if value.get("sensitiveOrCrossDomain") is True else 10
        if not isinstance(count, int) or isinstance(count, bool):
            return "PIC_NEGATIVE_FIXTURE_INPUT_INVALID"
        return "MPP_PUBLISHABLE" if count >= threshold else "MPP_SUPPRESSED"
    if kind == "mpp-anti-differencing":
        return (
            "MPP_DIFFERENCING_BLOCKED"
            if value.get("overlappingQuery") is True
            else "PIC_NEGATIVE_FIXTURE_UNEXPECTED_PASS"
        )
    return "PIC_NEGATIVE_FIXTURE_INPUT_INVALID"


def materialize_audit_record(
    fixture: dict[str, Any], case: dict[str, Any]
) -> dict[str, Any]:
    local_fact = dict(fixture["baseLocalAuditFact"])
    local_fact.update(case.get("localAuditFactOverrides", {}))
    integration = dict(fixture["baseIntegrationContext"])
    integration.update(case.get("integrationContextOverrides", {}))
    for field in case.get("removeIntegrationFields", []):
        integration.pop(field, None)
    return {
        "path": case.get("path"),
        "localAuditFact": local_fact,
        "integrationContext": integration,
    }


def audit_record_issues(
    record: Any, profile: dict[str, Any]
) -> list[str]:
    if not isinstance(record, dict) or set(record) != {
        "path", "localAuditFact", "integrationContext"
    }:
        return ["PIC_AUDIT_RECORD_SHAPE_INVALID"]
    fact = record["localAuditFact"]
    context = record["integrationContext"]
    if not isinstance(fact, dict) or not isinstance(context, dict):
        return ["PIC_AUDIT_RECORD_SHAPE_INVALID"]
    issues: list[str] = []
    try:
        schema = load_json(
            Path(__file__).resolve().parents[1] / LOCAL_AUDIT_SCHEMA
        )
        if schema_issues(fact, schema):
            issues.append("PIC_AUDIT_LOCAL_FACT_SHAPE_INVALID")
    except (OSError, ValueError):
        issues.append("PIC_AUDIT_LOCAL_FACT_SCHEMA_UNAVAILABLE")
    actions = {
        item.get("path"): item
        for item in profile.get("actionVocabulary", [])
        if isinstance(item, dict)
    }
    action = actions.get(record["path"])
    if action is None:
        issues.append("PIC_AUDIT_ACTION_PATH_UNKNOWN")
    elif (
        fact.get("action") != action.get("action")
        or fact.get("outcome") != action.get("outcome")
        or fact.get("reasonCode") != action.get("reasonCode")
        or context.get("result") != action.get("result")
        or context.get("errorCode") not in action.get("allowedErrorCodes", [])
    ):
        issues.append("PIC_AUDIT_ACTION_RESULT_MISMATCH")

    shape = profile.get("recordShape", {})
    common = set(shape.get("commonIntegrationFields", []))
    stream = set(shape.get("streamFields", []))
    intent = set(shape.get("intentFields", []))
    present = set(context)
    has_stream = stream <= present
    has_intent = intent <= present
    any_stream = bool(stream & present)
    any_intent = bool(intent & present)
    if any_stream and any_intent:
        issues.append("PIC_AUDIT_PROVENANCE_TAG_BOTH")
    elif not any_stream and not any_intent:
        issues.append("PIC_AUDIT_PROVENANCE_TAG_NONE")
    elif (any_stream and not has_stream) or (any_intent and not has_intent):
        issues.append("PIC_AUDIT_PROVENANCE_TAG_INCOMPLETE")
    else:
        expected_mode = "aggregate-stream" if has_stream else "intent-command"
        if context.get("provenanceMode") != expected_mode:
            issues.append("PIC_AUDIT_PROVENANCE_TAG_MISMATCH")
        expected_fields = common | (stream if has_stream else intent)
        if present != expected_fields:
            issues.append("PIC_AUDIT_CONTEXT_UNKNOWN_FIELDS")
    if not common <= present:
        issues.append("PIC_AUDIT_COMMON_FIELDS_MISSING")
    if context.get("contractVersion") != "PIC-1.0.0":
        issues.append("PIC_AUDIT_CONTRACT_VERSION_INVALID")
    if not re.fullmatch(
        r"gkt_v1_k[0-9]+_[0-9a-f]{64}",
        str(context.get("generationKeyToken", "")),
    ):
        issues.append("PIC_AUDIT_GENERATION_TOKEN_INVALID")
    for field in ("sourceAggregateVersion", "attemptNo"):
        value = context.get(field)
        if not isinstance(value, int) or isinstance(value, bool) or not (
            1 <= value <= MAX_SAFE_INTEGER
        ):
            issues.append(f"PIC_AUDIT_SAFE_INTEGER_INVALID:{field}")
    if fact.get("aggregateVersion") != context.get("sourceAggregateVersion"):
        issues.append("PIC_AUDIT_AGGREGATE_VERSION_MISMATCH")
    if has_stream:
        if not UUID_V7.fullmatch(str(context.get("eventId", ""))):
            issues.append("PIC_AUDIT_EVENT_ID_INVALID")
        if not re.fullmatch(
            r"sft_v1_k[0-9]+_[0-9a-f]{64}",
            str(context.get("sourceFactIdToken", "")),
        ):
            issues.append("PIC_AUDIT_SOURCE_FACT_TOKEN_INVALID")
        if not HEX64.fullmatch(str(context.get("sourceFactDigest", ""))):
            issues.append("PIC_AUDIT_SOURCE_FACT_DIGEST_INVALID")
        if not re.fullmatch(
            r"epd_v1_k[0-9]+_[0-9a-f]{64}",
            str(context.get("eventPayloadDigestToken", "")),
        ):
            issues.append("PIC_AUDIT_EVENT_PAYLOAD_DIGEST_TOKEN_INVALID")
        route_sequence = context.get("routeSequence")
        if not isinstance(route_sequence, int) or isinstance(
            route_sequence, bool
        ) or not (1 <= route_sequence <= MAX_SAFE_INTEGER):
            issues.append("PIC_AUDIT_ROUTE_SEQUENCE_INVALID")
    if has_intent:
        if not re.fullmatch(
            r"dit_v1_k[0-9]+_[0-9a-f]{64}",
            str(context.get("deliveryIntentIdToken", "")),
        ):
            issues.append("PIC_AUDIT_INTENT_TOKEN_INVALID")
        if not re.fullmatch(
            r"rqd_v1_k[0-9]+_[0-9a-f]{64}",
            str(context.get("requestDigestToken", "")),
        ):
            issues.append("PIC_AUDIT_REQUEST_DIGEST_TOKEN_INVALID")
        escalation = context.get("escalationVersion")
        if not isinstance(escalation, int) or isinstance(escalation, bool) or not (
            1 <= escalation <= MAX_SAFE_INTEGER
        ):
            issues.append("PIC_AUDIT_ESCALATION_VERSION_INVALID")
    forbidden = {
        str(value).casefold()
        for value in profile.get("observability", {}).get(
            "forbiddenReachableFields", []
        )
    }
    if {
        str(key).casefold()
        for key in _all_mapping_keys(record)
    } & forbidden:
        issues.append("PIC_AUDIT_FORBIDDEN_FIELD_REACHABLE")
    return sorted(set(issues))


def _all_mapping_keys(value: Any) -> set[str]:
    if isinstance(value, dict):
        result = {str(key) for key in value}
        for item in value.values():
            result.update(_all_mapping_keys(item))
        return result
    if isinstance(value, list):
        result: set[str] = set()
        for item in value:
            result.update(_all_mapping_keys(item))
        return result
    return set()


def privacy_surface_issues(profile: Any) -> list[str]:
    if not isinstance(profile, dict):
        return ["PIC_AUDIT_PROFILE_INVALID"]
    issues: list[str] = []
    if (
        profile.get("version") != "PIC-AUDIT-1.0.0"
        or profile.get("contractVersion") != "PIC-1.0.0"
        or profile.get("scope") != "test-contract-conformance-only"
        or profile.get("localAuditFactSchemaVersion")
        != "LOCAL-AUDIT-FACT-1.0.0"
        or profile.get("productionEmitter") != "none"
        or profile.get("auditSuccessorVersion") != "none"
    ):
        issues.append("PIC_AUDIT_PRODUCTION_BOUNDARY_INVALID")
    observability = profile.get("observability", {})
    expected_metric_labels = {
        "action", "channelId", "contractMajor", "result", "errorCode"
    }
    actual_metric_labels = set(
        observability.get("metrics", {}).get("allowedLabels", [])
    )
    if actual_metric_labels != expected_metric_labels:
        issues.append("PIC_AUDIT_METRIC_LABEL_HIGH_CARDINALITY")
    forbidden = set(observability.get("forbiddenReachableFields", []))
    required_forbidden = {
        "externalBody", "externalErrorBody", "secret", "studentId",
        "studentName", "freeText", "evidenceBody", "nonce", "signature",
        "token", "phone", "email",
    }
    if forbidden != required_forbidden:
        issues.append("PIC_AUDIT_PRIVACY_DENYLIST_INVALID")
    allowed_surfaces: set[str] = set()
    for surface, field_key in (
        ("logs", "allowedFields"),
        ("metrics", "allowedLabels"),
        ("traces", "allowedAttributes"),
        ("evidence", "allowedFields"),
    ):
        fields = observability.get(surface, {}).get(field_key, [])
        if not isinstance(fields, list) or len(fields) != len(set(fields)):
            issues.append(f"PIC_AUDIT_SURFACE_FIELDS_INVALID:{surface}")
        allowed_surfaces.update(str(field) for field in fields)
    if {value.casefold() for value in allowed_surfaces} & {
        value.casefold() for value in forbidden
    }:
        issues.append("PIC_AUDIT_FORBIDDEN_FIELD_REACHABLE")
    boundary = profile.get("productionBoundary", {})
    if boundary != {
        "syntheticEvidenceIncludedInRuntimeDenominator": False,
        "realBusinessObjectsCreated": 0,
        "productionConsumerInstalled": False,
        "productionRuntimeClaim": "none",
    }:
        issues.append("PIC_AUDIT_RUNTIME_DENOMINATOR_INVALID")
    actions = profile.get("actionVocabulary", [])
    if {item.get("path") for item in actions if isinstance(item, dict)} != {
        "accepted", "rejected", "retry", "confirmed", "failed",
        "reconcile", "replay", "gap", "poison",
    }:
        issues.append("PIC_AUDIT_ACTION_COVERAGE_INVALID")
    return sorted(set(issues))


def validate(project_root: Path) -> list[str]:
    root = project_root.resolve()
    issues: list[str] = []
    documents: dict[Path, Any] = {}
    required_documents = [
        PIC,
        EVENT_SCHEMA,
        COMMAND_SCHEMA,
        OPENAPI,
        REGISTRY,
        RUNTIME_PROFILE,
        SECURITY_PROFILE,
        POLICY,
        MEASUREMENT,
        TARGET_DESCRIPTOR_SCHEMA,
        TARGET_HANDOFF_SCHEMA,
        TARGET_SIGNATURE_SCHEMA,
        EVIDENCE_SCHEMA,
        TARGET_SCENARIOS,
        AUDIT_PROFILE,
        AUDIT_FIXTURE,
        VALID_EVENT,
        NEGATIVE_FIXTURES,
        OPTIONAL_COMPATIBILITY,
        BREAKING_COMPATIBILITY,
        LOCK,
    ]
    for path in required_documents:
        try:
            documents[path] = load_json(root / path)
        except (OSError, ValueError) as error:
            issues.append(
                f"PIC_DOCUMENT_INVALID: {path.as_posix()}: {error.__class__.__name__}"
            )
    for path in (
        EVENT_SCHEMA,
        COMMAND_SCHEMA,
        TARGET_DESCRIPTOR_SCHEMA,
        TARGET_HANDOFF_SCHEMA,
        TARGET_SIGNATURE_SCHEMA,
        EVIDENCE_SCHEMA,
    ):
        if path in documents:
            issues.extend(
                f"PIC_SCHEMA_INVALID: {path.as_posix()}: {issue}"
                for issue in schema_definition_issues(documents[path])
            )
    if OPENAPI in documents:
        issues.extend(_openapi_issues(documents[OPENAPI]))
    if REGISTRY in documents:
        issues.extend(_registry_issues(documents[REGISTRY]))
    if RUNTIME_PROFILE in documents:
        issues.extend(_runtime_profile_issues(documents[RUNTIME_PROFILE]))
    if SECURITY_PROFILE in documents:
        issues.extend(_security_profile_issues(documents[SECURITY_PROFILE]))
    if TARGET_SCENARIOS in documents:
        scenarios = documents[TARGET_SCENARIOS].get("requiredScenarios", [])
        ids = {item.get("id") for item in scenarios if isinstance(item, dict)}
        if ids != EXPECTED_SCENARIOS:
            issues.append("PIC_TARGET_SCENARIO_SET_INVALID")
        protocol = documents[TARGET_SCENARIOS].get("executionProtocol")
        if protocol != {
            "version": "PIC-TARGET-EXECUTION-1.0.0",
            "method": "POST",
            "scenarioPathTemplate": "/_scholarsense/pic/conformance/v1/scenarios/{scenarioId}",
            "cleanupPath": "/_scholarsense/pic/conformance/v1/cleanup",
            "requestMediaType": "application/json",
            "responseMediaType": "application/json",
        }:
            issues.append("PIC_TARGET_EXECUTION_PROTOCOL_INVALID")
    if AUDIT_PROFILE in documents:
        issues.extend(privacy_surface_issues(documents[AUDIT_PROFILE]))
    if AUDIT_PROFILE in documents and AUDIT_FIXTURE in documents:
        fixture = documents[AUDIT_FIXTURE]
        cases = fixture.get("records", []) + fixture.get("negativeCases", [])
        if not isinstance(cases, list):
            issues.append("PIC_AUDIT_FIXTURE_INVALID")
        else:
            for case in cases:
                if not isinstance(case, dict):
                    issues.append("PIC_AUDIT_FIXTURE_INVALID")
                    continue
                record_issues = audit_record_issues(
                    materialize_audit_record(fixture, case),
                    documents[AUDIT_PROFILE],
                )
                expected = case.get("expectedIssue")
                if expected is None and record_issues:
                    issues.extend(
                        f"PIC_AUDIT_VALID_RECORD_REJECTED:{issue}"
                        for issue in record_issues
                    )
                elif expected is not None and expected not in record_issues:
                    issues.append("PIC_AUDIT_NEGATIVE_RECORD_ACCEPTED")
    if (root / "contracts/audit/audit-contract-lock-1.5.0.json").exists():
        issues.append("PIC_AUDIT_SUCCESSOR_1_5_FORBIDDEN")
    if NEGATIVE_FIXTURES in documents:
        cases = documents[NEGATIVE_FIXTURES].get("cases", [])
        ids = {item.get("id") for item in cases if isinstance(item, dict)}
        if ids != EXPECTED_NEGATIVE_CASES:
            issues.append("PIC_NEGATIVE_FIXTURE_COVERAGE_INVALID")
        if VALID_EVENT in documents:
            for case in cases:
                if not isinstance(case, dict):
                    issues.append("PIC_NEGATIVE_FIXTURE_INPUT_INVALID")
                    continue
                actual = execute_negative_fixture(
                    case,
                    valid_event=documents[VALID_EVENT],
                    project_root=root,
                )
                if actual != case.get("expectedCode"):
                    issues.append(
                        f"PIC_NEGATIVE_FIXTURE_RESULT_MISMATCH:{case.get('id')}"
                    )
    for path in (OPTIONAL_COMPATIBILITY, BREAKING_COMPATIBILITY):
        if path in documents and execute_compatibility_fixture(
                documents[path], project_root=root) != documents[path].get("expected"):
            issues.append(f"PIC_COMPATIBILITY_FIXTURE_RESULT_MISMATCH:{path.name}")
    if VALID_EVENT in documents:
        issues.extend(
            event_issues(documents[VALID_EVENT], project_root=root)
        )
    if LOCK in documents:
        issues.extend(_lock_issues(root, documents[LOCK]))
    return sorted(set(issues))


def _openapi_issues(document: Any) -> list[str]:
    if not isinstance(document, dict):
        return ["PIC_OPENAPI_INVALID"]
    issues: list[str] = []
    if document.get("openapi") != "3.1.2":
        issues.append("PIC_OPENAPI_VERSION_INVALID")
    paths = document.get("paths", {})
    if set(paths) != {
        "/v1/operations/{channelId}",
        "/v1/callbacks/{channelId}",
    }:
        issues.append("PIC_OPENAPI_PATH_SET_INVALID")
    schemas = document.get("components", {}).get("schemas", {})
    receipt = schemas.get("OperationReceipt", {})
    if (
        receipt.get("additionalProperties") is not False
        or set(receipt.get("required", [])) != {
            "operationReceiptId", "resultCode", "acceptedAt",
            "idempotencyScopeToken", "providerEffectKeyToken", "traceId",
        }
    ):
        issues.append("PIC_OPERATION_RECEIPT_INVALID")
    error = schemas.get("ErrorEnvelope", {})
    if error.get("additionalProperties") is not False or set(
        error.get("required", [])
    ) != {"code", "message", "traceId", "fieldErrors"}:
        issues.append("PIC_ERROR_ENVELOPE_INVALID")
    security = document.get("security")
    if security != [{"mutualTls": [], "workloadIdentity": []}]:
        issues.append("PIC_OPENAPI_SECURITY_INVALID")
    return issues


def _registry_issues(document: Any) -> list[str]:
    if not isinstance(document, dict):
        return ["PIC_REGISTRY_INVALID"]
    descriptors = document.get("descriptors")
    if not isinstance(descriptors, list):
        return ["PIC_REGISTRY_INVALID"]
    issues: list[str] = []
    capabilities = {
        item.get("capability") for item in descriptors if isinstance(item, dict)
    }
    if capabilities != EXPECTED_CAPABILITIES or len(descriptors) != 5:
        issues.append("PIC_REGISTRY_CAPABILITY_SET_INVALID")
    lanes: set[tuple[Any, Any]] = set()
    required = {
        "capability",
        "channelId",
        "contractMajor",
        "schemaVersions",
        "consumerSupportedSchemaVersions",
        "owner",
        "ownerStory",
        "operations",
        "events",
        "orderingMode",
        "sideEffectMode",
        "slo",
        "authorization",
        "signatureDigest",
        "failureRetry",
        "reconciliation",
        "sandboxClass",
        "contractTestBinding",
    }
    for descriptor in descriptors:
        if not isinstance(descriptor, dict) or set(descriptor) != required:
            issues.append("PIC_REGISTRY_DESCRIPTOR_INVALID")
            continue
        lane = (descriptor.get("channelId"), descriptor.get("contractMajor"))
        if lane in lanes:
            issues.append("PIC_REGISTRY_LANE_DUPLICATE")
        lanes.add(lane)
        if descriptor.get("orderingMode") not in {
            "aggregate-stream",
            "intent-command",
        }:
            issues.append("PIC_REGISTRY_ORDERING_MODE_INVALID")
        if any(
            key in descriptor
            for key in ("endpoint", "tenant", "credential", "credentialRef")
        ):
            issues.append("PIC_REGISTRY_ENVIRONMENT_BINDING_FORBIDDEN")
    return issues


def _runtime_profile_issues(document: Any) -> list[str]:
    issues: list[str] = []
    if not isinstance(document, dict) or document.get("version") != "PIC-RUNTIME-1.0.0":
        return ["PIC_RUNTIME_PROFILE_INVALID"]
    bindings = document.get("environmentBindings", {})
    if bindings != {
        "targetHandoffFile": "SCHOLARSENSE_PIC_TARGET_HANDOFF_FILE",
        "targetHandoffSignatureFile": "SCHOLARSENSE_PIC_TARGET_HANDOFF_SIGNATURE_FILE",
        "targetHandoffTrustRootFile": "SCHOLARSENSE_PIC_TARGET_HANDOFF_TRUST_ROOT_FILE",
        "targetHandoffExpectedSha256": "SCHOLARSENSE_PIC_TARGET_HANDOFF_EXPECTED_SHA256",
    }:
        issues.append("PIC_TARGET_HANDOFF_INPUTS_INVALID")
    if document.get("deliveryStatuses") != [
        "pending",
        "retrying",
        "confirmed",
        "failed",
    ]:
        issues.append("PIC_DELIVERY_STATUS_INVALID")
    return issues


def _security_profile_issues(document: Any) -> list[str]:
    if not isinstance(document, dict):
        return ["PIC_SECURITY_PROFILE_INVALID"]
    callback = document.get("callback", {})
    workload = document.get("workloadIdentity", {})
    issues: list[str] = []
    if callback.get("signatureLabel") != "sig1" or callback.get(
        "algorithm"
    ) != "ed25519":
        issues.append("PIC_CALLBACK_SIGNATURE_PROFILE_INVALID")
    if callback.get("expiresAfterSeconds") != 300 or callback.get(
        "nonceRetentionSeconds"
    ) != 600:
        issues.append("PIC_CALLBACK_REPLAY_WINDOW_INVALID")
    if workload.get("maximumTtlSeconds") != 300 or not workload.get(
        "mtlsCertificateBound"
    ):
        issues.append("PIC_WORKLOAD_IDENTITY_PROFILE_INVALID")
    return issues


def _lock_issues(root: Path, lock: Any) -> list[str]:
    if not isinstance(lock, dict):
        return ["PIC_LOCK_INVALID"]
    issues: list[str] = []
    if lock.get("version") != "PIC-CONTRACT-LOCK-1.0.0":
        issues.append("PIC_LOCK_VERSION_INVALID")
    paths: set[str] = set()
    for item in lock.get("files", []):
        if not isinstance(item, dict):
            issues.append("PIC_LOCK_ENTRY_INVALID")
            continue
        path = item.get("path")
        digest = item.get("sha256")
        if not isinstance(path, str) or path in paths or not HEX64.fullmatch(
            str(digest or "")
        ):
            issues.append("PIC_LOCK_ENTRY_INVALID")
            continue
        paths.add(path)
        candidate = root / path
        if not candidate.is_file():
            issues.append(f"PIC_LOCK_FILE_MISSING: {path}")
        elif hashlib.sha256(candidate.read_bytes()).hexdigest() != digest:
            issues.append(f"PIC_LOCK_DIGEST_MISMATCH: {path}")
    expected_files = {
        str(path.as_posix())
        for path in (
            PIC,
            EVENT_SCHEMA,
            COMMAND_SCHEMA,
            OPENAPI,
            REGISTRY,
            RUNTIME_PROFILE,
            SECURITY_PROFILE,
            POLICY,
            MEASUREMENT,
            TARGET_DESCRIPTOR_SCHEMA,
            TARGET_HANDOFF_SCHEMA,
            TARGET_SIGNATURE_SCHEMA,
            EVIDENCE_SCHEMA,
            TARGET_SCENARIOS,
            AUDIT_PROFILE,
            AUDIT_FIXTURE,
            VALID_EVENT,
            NEGATIVE_FIXTURES,
            CONTRACT_ROOT
            / "fixtures/compatibility/optional-addition-1.1.0.json",
            CONTRACT_ROOT
            / "fixtures/compatibility/breaking-without-major.json",
        )
    }
    if paths != expected_files:
        issues.append("PIC_LOCK_FILE_SET_INVALID")
    source_path = root / DELEGATED_SOURCE
    source_digest = (
        hashlib.sha256(source_path.read_bytes()).hexdigest()
        if source_path.is_file()
        else None
    )
    bindings = {
        item.get("id"): item
        for item in lock.get("sourceBindings", [])
        if isinstance(item, dict)
    }
    expected_bindings = {
        "PublicIntegration": ("PIC-1.0.0", "lines:43,105-112"),
        "TransferSla": ("TSP-1.0.0", "lines:111"),
        "MetricPublication": ("MPP-1.0.0", "lines:112"),
    }
    for identity, (version, selector) in expected_bindings.items():
        binding = bindings.get(identity, {})
        if (
            binding.get("version") != version
            or binding.get("sourcePath") != DELEGATED_SOURCE.as_posix()
            or binding.get("sourceSha256") != source_digest
            or binding.get("selector") != selector
        ):
            issues.append(f"PIC_SOURCE_BINDING_INVALID: {identity}")
    invariants = lock.get("unchangedBaselines", {})
    expected_invariants = {
        "contracts/events/envelope.schema.json": "10d75ab24a6df63a2520f263ba29c9f6f45bc5e78165deb20109b37fd4eadd6d",
        "contracts/openapi/envelope.openapi.json": "32db5cba22ab4e7b1f7751c1dd744d77f41f8c2f09a59ab8472b2ee440176923",
        "contracts/release/release-manifest.schema.json": "9ee461f8772441366396cb181dd358d3df848aa834837ba19ff76d484a5b1f6e",
        "contracts/release/evidence-index.schema.json": "c9ad274343c42f3b73ed6f6ee62a8adfdb79c490f60fd6b4c95456b094b6629a",
        "contracts/audit/audit-contract-lock-1.0.0.json": "24a4861ddbfa9ac11253a26f6e3c906f1735d73c4d774d713faf9cee9f8cb62f",
        "contracts/audit/audit-contract-lock-1.1.0.json": "a07833c0c75ed28f6d0a534d697af0b09d3d5d8e0476f3c251c2bb8acd0439b9",
        "contracts/audit/audit-contract-lock-1.2.0.json": "cabb6259c4c3c9405823dba93a29de16c81ef730fbb04592bb20a9ad61f48c19",
        "contracts/audit/audit-contract-lock-1.3.0.json": "cd47617b3447c3476fa8b1036a2e488c2d527404eaf0de0f5a64444a74a29b7c",
        "contracts/audit/audit-contract-lock-1.4.0.json": "5244325f843b534ff0535c83446d44399b1cf2af8c521ec80920b2863046ae5a",
    }
    if invariants != expected_invariants:
        issues.append("PIC_UNCHANGED_BASELINE_SET_INVALID")
    for path, expected in expected_invariants.items():
        candidate = root / path
        if not candidate.is_file() or hashlib.sha256(
            candidate.read_bytes()
        ).hexdigest() != expected:
            issues.append(f"PIC_UNCHANGED_BASELINE_DIGEST_MISMATCH: {path}")
    return issues


def _safe_route_state(value: str) -> bool:
    lowered = value.lower()
    return not (
        "://" in value
        or "@" in value
        or "#" in value
        or "\r" in value
        or "\n" in value
        or "%25" in lowered
        or "token" in lowered
        or "student" in lowered
        or lowered.startswith(("javascript:", "data:"))
    )


def _contains_private_key(value: Any) -> bool:
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = re.sub(r"[^a-z0-9]", "", key.lower())
            if normalized in PRIVATE_KEYS or _contains_private_key(child):
                return True
    elif isinstance(value, list):
        return any(_contains_private_key(item) for item in value)
    return False


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    issues = validate(root)
    if issues:
        print("Public integration contract verification failed:")
        for issue in issues:
            print(f"- {issue}")
        return 1
    print(
        "Public integration contracts verified: PIC-1.0.0; "
        "capabilities=5; scenarios=10; maxBodyBytes=65536; "
        "productionRuntimeClaim=none"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
