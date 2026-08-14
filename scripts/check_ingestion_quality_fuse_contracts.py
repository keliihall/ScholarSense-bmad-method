#!/usr/bin/env python3
"""Fail-closed checker for Story 2.5a fuse, task and PIC successor contracts."""

from __future__ import annotations

import hashlib
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any
from uuid import UUID


BASE = Path("contracts/ingestion-quality/quality-fuse")
POLICY_SCHEMA = BASE / "quality-fuse-policy.schema.json"
POLICY = BASE / "quality-fuse-policy-1.0.0.json"
TASK_SCHEMA = BASE / "recovery-task.schema.json"
TASK_POLICY = BASE / "recovery-task-policy-1.0.0.json"
DERIVATION = BASE / "quality-fuse-derivation-matrix-1.0.0.json"
RETENTION = BASE / "quality-fuse-retention-1.0.0.json"
PROJECTION = BASE / "quality-recovery-task-projection-1.0.0.json"
VALID = BASE / "fixtures/valid/quality-fuse-state-vectors-1.0.0.json"
NEGATIVE = BASE / "fixtures/invalid/quality-fuse-negative-fixtures-1.0.0.json"
EVENT_SCHEMA = Path("contracts/events/ingestion-quality/quality-fuse-task-event.schema.json")
EVENT_FIXTURE = Path("contracts/events/ingestion-quality/fixtures/valid/quality-recovery-task-created-v1.json")
RECOVERY_TASK_OPENAPI = Path("contracts/openapi/quality-recovery-tasks.openapi.json")
PIC = Path("contracts/public-integration/pic-1.1.0.json")
ADAPTER_REGISTRY = Path("contracts/public-integration/adapter-registry-1.1.0.json")
LOCK = BASE / "quality-fuse-contract-lock-1.0.0.json"

PIC_PREDECESSOR = Path("contracts/public-integration/pic-1.0.0.json")
ADAPTER_PREDECESSOR = Path("contracts/public-integration/adapter-registry-1.0.0.json")
PIC_LOCK_PREDECESSOR = Path("contracts/public-integration/public-integration-contract-lock-1.0.0.json")
ELIGIBILITY_LOCK = Path("contracts/ingestion-quality/rule-dependency/quality-eligibility-contract-lock-1.0.0.json")
QMDP_LOCK = Path("contracts/ingestion-quality/batch-quality/executable-quality-contract-lock-1.0.0.json")
QSHM_LOCK = Path("contracts/ingestion-quality/batch-quality/quality-snapshot-hash-contract-lock-1.0.0.json")
RFP = Path("contracts/authorization/role-field-policy-rfp-1.0.0.json")
FIELD_PROJECTION = Path("contracts/field-projection/field-projection-policy-binding-1.1.0.json")
V15 = Path("backend/src/main/resources/db/migration/ingestion-quality/V000015__ingestion-quality__quality_eligibility_v1.sql")
RELEASES = tuple(Path(f"contracts/release/release-manifest-{number}.schema.json") for number in range(2, 7))
RELEASES = (Path("contracts/release/release-manifest.schema.json"), *RELEASES)

FILES = (
    POLICY_SCHEMA, POLICY, TASK_SCHEMA, TASK_POLICY, DERIVATION, RETENTION,
    PROJECTION, VALID, NEGATIVE, EVENT_SCHEMA, EVENT_FIXTURE, RECOVERY_TASK_OPENAPI,
    PIC, ADAPTER_REGISTRY,
)
PREDECESSORS = (
    V15, RFP, FIELD_PROJECTION, QMDP_LOCK, QSHM_LOCK, ELIGIBILITY_LOCK,
    ADAPTER_PREDECESSOR, PIC_PREDECESSOR, PIC_LOCK_PREDECESSOR,
)
COPY_PATHS = (
    BASE, EVENT_SCHEMA, EVENT_FIXTURE, RECOVERY_TASK_OPENAPI,
    PIC, ADAPTER_REGISTRY, *PREDECESSORS, *RELEASES,
)


def load(root: Path, relative: Path) -> Any:
    return json.loads((root / relative).read_text(encoding="utf-8"))


def raw_digest(path: Path) -> str:
    return f"sha256:{hashlib.sha256(path.read_bytes()).hexdigest()}"


def schema_errors(instance: Any, schema: dict[str, Any], path: str = "$") -> list[str]:
    """Validate the closed JSON Schema 2020-12 keyword subset used by Story 2.5a."""
    errors: list[str] = []
    if "const" in schema and instance != schema["const"]:
        errors.append(f"{path}: const mismatch")
    if "enum" in schema and instance not in schema["enum"]:
        errors.append(f"{path}: value outside enum")

    expected_type = schema.get("type")
    type_matches = {
        "object": isinstance(instance, dict),
        "array": isinstance(instance, list),
        "string": isinstance(instance, str),
        "integer": isinstance(instance, int) and not isinstance(instance, bool),
        "number": isinstance(instance, (int, float)) and not isinstance(instance, bool),
        "boolean": isinstance(instance, bool),
        "null": instance is None,
    }
    if isinstance(expected_type, str) and not type_matches.get(expected_type, False):
        return [*errors, f"{path}: expected {expected_type}"]

    if isinstance(instance, dict):
        required = schema.get("required", [])
        for field in required:
            if field not in instance:
                errors.append(f"{path}: missing {field}")
        properties = schema.get("properties", {})
        if schema.get("additionalProperties") is False:
            for field in instance.keys() - properties.keys():
                errors.append(f"{path}: additional property {field}")
        for field, child_schema in properties.items():
            if field in instance:
                errors.extend(schema_errors(
                    instance[field], child_schema, f"{path}.{field}"))

    if isinstance(instance, list):
        minimum = schema.get("minItems")
        maximum = schema.get("maxItems")
        if isinstance(minimum, int) and len(instance) < minimum:
            errors.append(f"{path}: fewer than {minimum} items")
        if isinstance(maximum, int) and len(instance) > maximum:
            errors.append(f"{path}: more than {maximum} items")
        if schema.get("uniqueItems") is True:
            canonical = [json.dumps(value, sort_keys=True, separators=(",", ":"))
                         for value in instance]
            if len(canonical) != len(set(canonical)):
                errors.append(f"{path}: duplicate items")
        item_schema = schema.get("items")
        if isinstance(item_schema, dict):
            for index, value in enumerate(instance):
                errors.extend(schema_errors(value, item_schema, f"{path}[{index}]"))

    if isinstance(instance, str):
        minimum = schema.get("minLength")
        maximum = schema.get("maxLength")
        if isinstance(minimum, int) and len(instance) < minimum:
            errors.append(f"{path}: shorter than {minimum}")
        if isinstance(maximum, int) and len(instance) > maximum:
            errors.append(f"{path}: longer than {maximum}")
        pattern = schema.get("pattern")
        if isinstance(pattern, str) and re.search(pattern, instance) is None:
            errors.append(f"{path}: pattern mismatch")
        if schema.get("format") == "uuid":
            try:
                if str(UUID(instance)) != instance.lower():
                    raise ValueError
            except (ValueError, AttributeError):
                errors.append(f"{path}: invalid uuid")
        if schema.get("format") == "date-time":
            try:
                datetime.fromisoformat(instance.replace("Z", "+00:00"))
            except ValueError:
                errors.append(f"{path}: invalid date-time")

    if isinstance(instance, (int, float)) and not isinstance(instance, bool):
        if "minimum" in schema and instance < schema["minimum"]:
            errors.append(f"{path}: below minimum")
        if "maximum" in schema and instance > schema["maximum"]:
            errors.append(f"{path}: above maximum")
    return errors


def check(root: Path) -> list[str]:
    issues: list[str] = []
    for relative in (*FILES, *PREDECESSORS, *RELEASES, LOCK):
        if not (root / relative).is_file():
            issues.append(f"missing {relative}")
    if issues:
        return issues

    policy = load(root, POLICY)
    policy_schema = load(root, POLICY_SCHEMA)
    policy_validation = schema_errors(policy, policy_schema)
    if policy_schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
        issues.append("quality fuse policy schema must use JSON Schema 2020-12")
    if policy_validation:
        issues.append(f"quality fuse policy JSON Schema validation: {policy_validation[0]}")
    if policy.get("singleWriter") != "ingestion-quality":
        issues.append("single eligibility writer must remain ingestion-quality")
    transitions = {item.get("caseId"): item for item in policy.get("transitions", []) if isinstance(item, dict)}
    expected = {
        "eligible-pass": ("eligible", "none"),
        "eligible-fail": ("fused", "create"),
        "fused-pass": ("fused", "preserve"),
        "fused-fail": ("fused", "update"),
        "recovering-pass": ("recovering", "preserve"),
        "recovering-fail": ("fused", "update"),
        "missing-pass": ("eligible", "none"),
        "missing-fail-realtime": ("fused", "create"),
        "bootstrap-fused": ("fused", "none"),
    }
    if set(transitions) != set(expected):
        issues.append("latch transition matrix incomplete")
    for case_id, (state, task_action) in expected.items():
        case = transitions.get(case_id, {})
        if case.get("appliedState") != state or case.get("taskAction") != task_action:
            label = case_id.replace("-", " + ", 1)
            issues.append(f"{label} latch/task semantics")
    triggers = policy.get("triggerReasons", {})
    if triggers.get("business") != ["REQUIRED_MEMBER_FAILED", "COMPOSITION_THRESHOLD_FAILED"]:
        issues.append("verified business trigger reason set")
    if triggers.get("technical") != []:
        issues.append("technical trigger reason must remain empty")
    forbidden = {"BOOTSTRAP_FUSED", "MEMBER_VERSION_GAP", "EVENT_VERSION_GAP", "POISON_EVENT", "DIGEST_MISMATCH"}
    if not forbidden.issubset(set(triggers.get("nonTriggering", []))):
        issues.append("bootstrap/gap/poison/digest negative triggers")
    if policy.get("upstream", {}).get("lookup") != "exact-self-contained-evidence-never-latest":
        issues.append("upstream evidence must never use latest lookup")
    provenance = policy.get("provenance", {})
    if provenance != {
        "realtime": "REALTIME_ASSESSMENT",
        "bootstrap": "BOOTSTRAP_MATERIALIZATION",
        "bootstrapRequiresExplicitImportOrMigration": True,
        "absenceOfPriorFactDoesNotImplyBootstrap": True,
    }:
        issues.append("bootstrap/import provenance must be explicit")

    derivation = load(root, DERIVATION)
    if set(derivation.get("legacyMappings", {})) != {"Q1", "Q2"} or derivation.get("unknownLegacyMapping") != "reject/QUALITY_FUSE_UNKNOWN_GATE_MAPPING":
        issues.append("Q1/Q2 mapping must be closed and reject unknown values")
    if derivation.get("conflicts") or derivation.get("unresolvedSemantics"):
        issues.append("authority derivation has unresolved semantics")
    owners = derivation.get("owners", {})
    if owners.get("ruleRuntimeApply") != "Story-3.2" or owners.get("publicTaskFinalApply") != "Story-5.5":
        issues.append("downstream runtime/final owner boundary")

    task_policy = load(root, TASK_POLICY)
    cardinality = task_policy.get("cardinality", {})
    if cardinality.get("key") != ["sourceId", "dependencyId", "activeIncidentGeneration"] or cardinality.get("maximumActiveTasksPerKey") != 1:
        issues.append("task cardinality must use source/dependency/generation")
    lifecycle = task_policy.get("lifecycle", {})
    if lifecycle.get("closedThenFailed") != "allocate-new-generation-and-new-task":
        issues.append("closed episode failure must allocate a new generation")
    identity = task_policy.get("identity", {})
    if (identity.get("workItemKey") != "opaque-deterministic-HMAC-of-episodeBusinessKey"
            or identity.get("hmacDomain")
                != "scholarsense\\0quality-fuse\\0work-item-key\\0v1"
            or identity.get("keyProvider") != "deployment-protected-secret-provider"
            or identity.get("keyVersion") != "pinned-at-episode-creation"
            or identity.get("rotation")
                != "new-key-affects-new-episodes-only;retain-old-key-through-episode-and-retention-end"):
        issues.append("workItemKey HMAC key version and rotation contract")
    delivery = task_policy.get("delivery", {})
    if (delivery.get("states") != ["pending", "retrying", "confirmed", "failed"]
            or delivery.get("confirmedMeans") != "target-confirmed-delivery-only"
            or delivery.get("doesNotMutate") != ["RecoveryTask.status", "QualityEligibility.status"]):
        issues.append("delivery state must remain independent")
    task_schema = load(root, TASK_SCHEMA)
    task_validation = schema_errors(
        load(root, EVENT_FIXTURE).get("data", {}).get("task"), task_schema)
    if task_schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
        issues.append("RecoveryTask schema must use JSON Schema 2020-12")
    if task_validation:
        issues.append(f"RecoveryTask JSON Schema validation: {task_validation[0]}")
    serialized_task_schema = json.dumps(task_schema, ensure_ascii=False)
    if "deliveryStatus" in serialized_task_schema or "taskDelivery" in serialized_task_schema:
        issues.append("RecoveryTask schema must exclude delivery sidecar fields")
    if task_schema.get("additionalProperties") is not False or task_schema.get("properties", {}).get("status", {}).get("const") != "open":
        issues.append("Story 2.5a RecoveryTask business state")
    trigger_schema = task_schema.get("properties", {}).get("trigger", {})
    if (trigger_schema.get("additionalProperties") is not False
            or trigger_schema.get("required") != ["batchId", "snapshotId", "reasonCode"]
            or trigger_schema.get("properties", {}).get("reasonCode", {}).get("enum")
                != ["REQUIRED_MEMBER_FAILED", "COMPOSITION_THRESHOLD_FAILED"]):
        issues.append("public trigger schema must freeze the RecoveryTask v1 reason shape")
    affected_schema = task_schema.get("properties", {}).get("affectedRules", {})
    if (affected_schema.get("minItems") != 1
            or affected_schema.get("uniqueItems") is not True
            or affected_schema.get("items", {}).get("type") != "string"
            or affected_schema.get("items", {}).get("pattern")
                != "^[A-Z0-9][A-Z0-9_-]{1,63}@[0-9A-Za-z][0-9A-Za-z._+-]{0,63}$"):
        issues.append("public affectedRules schema must freeze ruleId@version strings")

    valid_cases = {item.get("caseId") for item in load(root, VALID).get("cases", []) if isinstance(item, dict)}
    if valid_cases != {
        "eligible-fail-new", "fused-pass-latched", "fused-fail-update",
        "recovering-pass-latched", "recovering-fail-relapse", "bootstrap-fused-no-task",
        "missing-fail-realtime",
    }:
        issues.append("state vector fixture matrix incomplete")
    negative_cases = {item.get("caseId") for item in load(root, NEGATIVE).get("cases", []) if isinstance(item, dict)}
    required_negative = {
        "unsealed-batch", "assessment-incomplete", "profile-digest-mismatch", "unknown-q1-q2",
        "bootstrap-fused-task", "event-gap-as-business-failure", "poison-as-business-failure",
        "duplicate-active-task", "cross-owner-projection", "delivery-on-eligibility",
        "same-key-different-body", "payload-65537",
    }
    if negative_cases != required_negative:
        issues.append("negative fixture matrix incomplete")

    projection = load(root, PROJECTION)
    authorization = projection.get("authorization", {})
    if (projection.get("objectClass") != "RecoveryTask"
            or projection.get("purpose") != "data-quality.read"
            or projection.get("requiredScopeAnchor") != "OWNED_SOURCE"
            or authorization.get("beforeQueryAndSerialization") is not True
            or authorization.get("sensitiveDetailAuditBeforeResponse") is not True
            or authorization.get("partialOwnerProjection") != "authorized-slice-without-count-page-or-not-found-leak"):
        issues.append("RecoveryTask source-owner projection and read audit")

    event = load(root, EVENT_FIXTURE)
    data = event.get("data", {})
    if event.get("id") != data.get("eventId") or event.get("subject") != f"quality-recovery-task/{data.get('taskId')}":
        issues.append("quality task event identity binding")
    if data.get("runtimeEvidenceClaim") != "none":
        issues.append("quality task runtime evidence must remain none until target activation")
    public_task = data.get("task", {})
    if public_task.get("trigger", {}).get("reasonCode") not in {
            "REQUIRED_MEMBER_FAILED", "COMPOSITION_THRESHOLD_FAILED"}:
        issues.append("public task reason code must not expose internal eligibility reasons")
    affected_rules = public_task.get("affectedRules")
    if (not isinstance(affected_rules, list) or not affected_rules
            or any(not isinstance(rule, str)
                   or re.fullmatch(r"[A-Z0-9][A-Z0-9_-]{1,63}@[0-9A-Za-z][0-9A-Za-z._+-]{0,63}", rule) is None
                   for rule in affected_rules)):
        issues.append("public affectedRules must use ruleId@version strings")
    body = json.dumps(event, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    if len(body) > 65_536:
        issues.append("quality task event exceeds 64 KiB")
    serialized_event = body.decode("utf-8")
    if any(field in serialized_event for field in ("studentRef", "studentName", "rawRecord", "failureRow")):
        issues.append("quality task event contains prohibited PII/body fields")
    event_schema = load(root, EVENT_SCHEMA)
    if (event_schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema"
            or event_schema.get("x-maximumWireBytes") != 65_536
            or event_schema.get("properties", {}).get("type", {}).get("const")
                != "scholarsense.ingestion-quality.quality-recovery-task.changed.v1"):
        issues.append("quality task event schema")

    openapi = load(root, RECOVERY_TASK_OPENAPI)
    task_paths = openapi.get("paths", {})
    list_operation = task_paths.get("/quality-recovery-tasks", {}).get("get", {})
    detail_operation = task_paths.get("/quality-recovery-tasks/{taskId}", {}).get("get", {})
    source_parameter = openapi.get("components", {}).get("parameters", {}).get("SourceId", {})
    cursor_constraint = list_operation.get("x-cross-field-constraints", [])
    schemas = openapi.get("components", {}).get("schemas", {})
    response_codes = {"200", "400", "404", "503"}
    if (openapi.get("openapi") != "3.1.2"
            or set(task_paths) != {
                "/quality-recovery-tasks", "/quality-recovery-tasks/{taskId}"
            }
            or any(set(value) != {"get"} for value in task_paths.values())
            or source_parameter.get("in") != "query"
            or source_parameter.get("required") is not True
            or set(list_operation.get("responses", {})) != response_codes
            or set(detail_operation.get("responses", {})) != response_codes
            or cursor_constraint != [{
                "fields": ["afterOccurredAt", "afterTaskId"],
                "rule": "all-or-none",
                "failureCode": "INGESTION_QUALITY_REQUEST_INVALID",
            }]):
        issues.append("RecoveryTask OpenAPI 3.1.2 paths, source scope, cursor and errors")
    required_schemas = {
        "QualityRecoveryTask", "RecoveryTaskTrigger", "CurrentEvidence",
        "TaskDelivery", "Cursor", "QualityRecoveryTaskPage", "Error", "FieldError",
    }
    if (not required_schemas.issubset(schemas)
            or any(schemas[name].get("additionalProperties") is not False
                   for name in required_schemas)
            or schemas.get("QualityRecoveryTask", {}).get("properties", {}).get(
                "status", {}).get("const") != "open"
            or schemas.get("TaskDelivery", {}).get("properties", {}).get(
                "status", {}).get("enum") != [
                    "pending", "retrying", "confirmed", "failed"
                ]):
        issues.append("RecoveryTask OpenAPI strict projection and delivery schema")

    pic = load(root, PIC)
    if (pic.get("version") != "PIC-1.1.0"
            or pic.get("predecessor", {}).get("sha256") != raw_digest(root / PIC_PREDECESSOR).removeprefix("sha256:")
            or pic.get("qualityTask", {}).get("producer") != "ingestion-quality"
            or pic.get("qualityTask", {}).get("targetActivation") != "deferred"):
        issues.append("PIC additive quality task successor")
    registry = load(root, ADAPTER_REGISTRY)
    descriptors = [item for item in registry.get("descriptors", []) if item.get("owner") == "ingestion-quality"]
    if (len(descriptors) != 1
            or descriptors[0].get("channelId") != "pic.quality-recovery-task.v1"
            or descriptors[0].get("events") != ["scholarsense.ingestion-quality.quality-recovery-task.changed.v1"]
            or descriptors[0].get("sideEffectMode") != "target-activation-deferred"):
        issues.append("PIC quality task route must be unique and ingestion-quality-owned")
    if registry.get("predecessor", {}).get("sha256") != raw_digest(root / ADAPTER_PREDECESSOR).removeprefix("sha256:"):
        issues.append("adapter registry predecessor digest")

    retention = load(root, RETENTION)
    if (retention.get("businessFacts") != "P2Y"
            or retention.get("idempotencyRecords") != "P90D"
            or retention.get("deliveryReceipts") != "P90D"
            or retention.get("readAudit") != "P2Y"
            or retention.get("cleanupEntrypoint")
                != "iq_cleanup_quality_fuse_expired(trustedCutoff)"
            or retention.get("legalHold")
                != "protect-business-delivery-idempotency-and-read-audit"
            or retention.get("appendOnlyHistory") is not True):
        issues.append("quality fuse retention/correction mapping")

    lock = load(root, LOCK)
    expected_files = {str(path): raw_digest(root / path) for path in sorted(FILES, key=str)}
    expected_predecessors = {str(path): raw_digest(root / path) for path in sorted(PREDECESSORS, key=str)}
    expected_releases = {str(path): raw_digest(root / path) for path in RELEASES}
    if lock.get("files") != expected_files:
        issues.append("quality fuse contract lock mismatch")
    if lock.get("predecessors") != expected_predecessors:
        issues.append("predecessor digest mismatch")
    if lock.get("releasePredecessors") != expected_releases:
        issues.append("release v1-v6 predecessor digest mismatch")
    runtime = lock.get("runtimeEvidence", {})
    if runtime != {
        "ownerLocalFuseTaskOutbox": "conformance-only",
        "publicTaskTargetActivation": "none",
        "story32ConsumerActivation": "none",
        "story55FinalApply": "none",
    }:
        issues.append("runtime evidence boundary")
    return issues


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    issues = check(root)
    if issues:
        for issue in issues:
            print(f"QUALITY_FUSE_CONTRACT: {issue}")
        return 1
    print("QUALITY_FUSE_CONTRACT: latch, episode, task, PIC route, retention and predecessor locks valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
