#!/usr/bin/env python3
"""Validate the approved Subject Registry policy, wire contracts, fixtures and locks."""

from __future__ import annotations

import copy
import hashlib
import json
import sys
from pathlib import Path
from typing import Any


sys.path.insert(0, str(Path(__file__).resolve().parent))

from release_json import canonical_bytes, load_json, schema_definition_issues, schema_issues  # noqa: E402


CONTRACT_ROOT = Path("contracts/subject-registry")
EVENT_ROOT = Path("contracts/events/subject-registry")
OPENAPI = Path("contracts/openapi/subject-mapping.openapi.json")
LOCK = CONTRACT_ROOT / "subject-registry-contract-lock-1.0.0.json"
CONSUMER_LOCK = EVENT_ROOT / "subject-consumer-contract-lock-1.0.0.json"

EXPECTED_ADAPTERS = frozenset({
    "SRC-P0-STUDENT-001",
    "SRC-P0-CARD-001",
    "SRC-P1-NETWORK-001",
    "SRC-P0-ACCOMMODATION-001",
    "SRC-P0-CAMPUS-ACCESS-001",
    "SRC-P0-DORM-ACCESS-001",
})

EXPECTED_CONTROLLED_FILES = frozenset({
    "contracts/subject-registry/subject-mapping-policy.schema.json",
    "contracts/subject-registry/subject-mapping-policy-1.0.0.json",
    "contracts/subject-registry/source-identifier-adapter.schema.json",
    "contracts/subject-registry/source-identifier-adapter-1.0.0.json",
    "contracts/subject-registry/subject-recomputation-policy.schema.json",
    "contracts/subject-registry/subject-recomputation-policy-1.0.0.json",
    "contracts/subject-registry/subject-repair-projection-policy.schema.json",
    "contracts/subject-registry/subject-repair-projection-policy-1.0.0.json",
    "contracts/subject-registry/subject-mapping-exception.schema.json",
    "contracts/subject-registry/subject-resolution.schema.json",
    "contracts/subject-registry/historical-window.schema.json",
    "contracts/subject-registry/mapping-recompute-request.schema.json",
    "contracts/subject-registry/mapping-recompute-job.schema.json",
    "contracts/subject-registry/mapping-recompute-result.schema.json",
    "contracts/subject-registry/deferred-consumer-responsibilities.schema.json",
    "contracts/subject-registry/deferred-consumer-responsibilities-1.0.0.json",
    "contracts/subject-registry/fixtures/valid/mapping-fixtures-1.0.0.json",
    "contracts/subject-registry/fixtures/authorization/subject-repair-authorization-fixtures-1.0.0.json",
    "contracts/subject-registry/fixtures/invalid/negative-fixtures-1.0.0.json",
    "contracts/subject-registry/fixtures/ordering/ordering-fixtures-1.0.0.json",
    "contracts/subject-registry/fixtures/compatibility/compatibility-fixtures-1.0.0.json",
    "contracts/events/subject-registry/subject-mapping-changed-event.schema.json",
    "contracts/events/subject-registry/consumer-registry.schema.json",
    "contracts/events/subject-registry/consumer-registry-1.0.0.json",
    "contracts/events/subject-registry/subject-consumer-contract-lock.schema.json",
    "contracts/events/subject-registry/subject-consumer-contract-lock-1.0.0.json",
    "contracts/openapi/subject-mapping.openapi.json",
    "contracts/subject-registry/subject-registry-contract-lock.schema.json",
})

CONSUMER_LOCK_FILES = frozenset({
    "contracts/events/subject-registry/subject-mapping-changed-event.schema.json",
    "contracts/events/subject-registry/consumer-registry.schema.json",
    "contracts/events/subject-registry/consumer-registry-1.0.0.json",
})

FORBIDDEN_WIRE_KEYS = frozenset({
    "rawIdentifier", "sourceNativeIdentifier", "protectedIdentifierToken",
    "ciphertext", "keyValue", "evidenceBody", "candidateStudentRefs",
})


def canonical_digest(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_bytes(value)).hexdigest()


def evaluate_mapping_fixture(case: dict[str, Any]) -> str:
    candidates = case.get("candidateCount")
    if candidates == 0:
        return "ISOLATE_NO_MATCH"
    if not isinstance(candidates, int) or candidates > 1:
        return "ISOLATE_AMBIGUOUS"
    if case.get("identifierPreviouslyIssued") and not case.get("continuityProof"):
        return "ISOLATE_REISSUE_UNPROVEN"
    if case.get("sourceId") == "SRC-P0-STUDENT-001" and case.get("authorityProof"):
        return "ISSUE_STUDENT_REF"
    return "RESOLVE_EXISTING_ONLY"


def evaluate_ordering_fixture(case: dict[str, Any]) -> str:
    mode = case.get("mode")
    if mode == "completion":
        watermarks = case.get("activeConsumerWatermarks", [])
        event_version = case.get("eventVersion")
        return "COMPLETED" if (
            bool(watermarks)
            and all(item >= event_version for item in watermarks)
            and case.get("reconciliationPassed") is True
        ) else "INCOMPLETE"
    if case.get("eventSeen"):
        return "DUPLICATE"
    current = case.get("currentVersion")
    event = case.get("eventVersion")
    if not isinstance(current, int) or not isinstance(event, int):
        return "POISON"
    if event <= current:
        return "OLD_VERSION"
    if event > current + 1:
        return "GAP_PAUSED"
    return "BACKFILL_APPLY" if mode == "backfill" else "APPLY"


def execute_negative_fixture(case: dict[str, Any], project_root: Path) -> str:
    mapping = copy.deepcopy(load_json(project_root / CONTRACT_ROOT / "subject-mapping-policy-1.0.0.json"))
    recompute = copy.deepcopy(load_json(project_root / CONTRACT_ROOT / "subject-recomputation-policy-1.0.0.json"))
    event = copy.deepcopy(load_json(project_root / EVENT_ROOT / "subject-mapping-changed-event.schema.json"))
    mutation = case.get("mutation")
    if mutation == "approval-draft":
        mapping["approval"]["status"] = "draft"
        return _approval_issues(mapping)[0]
    if mutation == "policy-version-unknown":
        mapping["policyVersion"] = "SMP-9.9.9"
        return "SUBJECT_REGISTRY_POLICY_VERSION_UNKNOWN" if mapping["policyVersion"] != "SMP-1.0.0" else "ACCEPTED"
    if mutation == "duplicate-source-adapter":
        mapping["sourceAdapters"].append(copy.deepcopy(mapping["sourceAdapters"][0]))
        ids = [item["sourceId"] for item in mapping["sourceAdapters"]]
        return "SUBJECT_REGISTRY_ADAPTER_SET_INVALID" if len(ids) != len(set(ids)) else "ACCEPTED"
    if mutation == "enable-fuzzy":
        mapping["matchingRules"]["fuzzyMatchingAllowed"] = True
        return "SUBJECT_REGISTRY_FUZZY_MATCH_FORBIDDEN" if mapping["matchingRules"]["fuzzyMatchingAllowed"] else "ACCEPTED"
    if mutation == "add-quality-recovery-default":
        recompute["windowDays"] = 90
        encoded = json.dumps(recompute, sort_keys=True).lower()
        return "SUBJECT_REGISTRY_QRP_POLICY_CONTAMINATION" if "windowdays" in encoded else "ACCEPTED"
    if mutation == "add-sensitive-event-field":
        event["properties"]["data"]["properties"]["sourceNativeIdentifier"] = {"type": "string"}
        return "SUBJECT_REGISTRY_WIRE_FIELD_FORBIDDEN" if FORBIDDEN_WIRE_KEYS & _keys(event) else "ACCEPTED"
    return "SUBJECT_REGISTRY_FIXTURE_INVALID"


def execute_compatibility_fixture(case: dict[str, Any]) -> str:
    before = tuple(int(item) for item in str(case.get("fromVersion", "")).split("."))
    after = tuple(int(item) for item in str(case.get("toVersion", "")).split("."))
    if len(before) != 3 or len(after) != 3 or after <= before:
        return "VERSION_REGRESSION"
    change = case.get("change")
    if change == "add-optional-field":
        return "COMPATIBLE"
    if change in {"add-required-field", "remove-field"} and after[0] == before[0]:
        return "BREAKING_CHANGE_REQUIRES_MAJOR"
    return "SUBJECT_REGISTRY_FIXTURE_INVALID"


def evaluate_authorization_fixture(case: dict[str, Any]) -> str:
    if not case.get("objectExists"):
        return "DENY_NONDISCLOSING"
    if case.get("role") == "R7" and case.get("action") == "platform.read":
        return "ALLOW_TECHNICAL_ONLY"
    if (
        case.get("role") == "R6"
        and case.get("ownedSource") is True
        and case.get("action") in {"data-quality.read", "data-quality.repair", "data-quality.reconcile"}
        and case.get("purpose") == "SUBJECT_MAPPING_EXCEPTION_REPAIR"
    ):
        return "ALLOW_SEVEN_FIELDS"
    return "DENY_NONDISCLOSING"


def _keys(value: Any) -> set[str]:
    if isinstance(value, dict):
        return set(value) | {item for child in value.values() for item in _keys(child)}
    if isinstance(value, list):
        return {item for child in value for item in _keys(child)}
    return set()


def _schema_pair_issues(instance: dict[str, Any], schema: dict[str, Any], code: str) -> list[str]:
    return [code] if schema_issues(instance, schema) else []


def _approval_issues(document: dict[str, Any]) -> list[str]:
    approval = document.get("approval")
    if not isinstance(approval, dict) or approval.get("status") != "approved":
        return ["SUBJECT_REGISTRY_POLICY_NOT_APPROVED"]
    required = {
        "approvedBy": "Hei",
        "decisionAuthority": "user-authorized-product-owner",
    }
    if any(approval.get(key) != value for key, value in required.items()):
        return ["SUBJECT_REGISTRY_POLICY_APPROVAL_INVALID"]
    if not str(approval.get("approvedAt", "")).startswith("2026-08-06T"):
        return ["SUBJECT_REGISTRY_POLICY_APPROVAL_INVALID"]
    return []


def validate(project_root: Path, *, policy: dict[str, Any] | None = None) -> list[str]:
    issues: list[str] = []
    mapping = policy or load_json(project_root / CONTRACT_ROOT / "subject-mapping-policy-1.0.0.json")
    recompute = load_json(project_root / CONTRACT_ROOT / "subject-recomputation-policy-1.0.0.json")
    adapter = load_json(project_root / CONTRACT_ROOT / "source-identifier-adapter-1.0.0.json")
    projection = load_json(project_root / CONTRACT_ROOT / "subject-repair-projection-policy-1.0.0.json")
    deferred = load_json(project_root / CONTRACT_ROOT / "deferred-consumer-responsibilities-1.0.0.json")
    registry = load_json(project_root / EVENT_ROOT / "consumer-registry-1.0.0.json")
    event = load_json(project_root / EVENT_ROOT / "subject-mapping-changed-event.schema.json")
    openapi = load_json(project_root / OPENAPI)

    schema_pairs = [
        (mapping, "subject-mapping-policy.schema.json", "SUBJECT_REGISTRY_MAPPING_POLICY_SCHEMA_INVALID"),
        (adapter, "source-identifier-adapter.schema.json", "SUBJECT_REGISTRY_ADAPTER_SCHEMA_INVALID"),
        (recompute, "subject-recomputation-policy.schema.json", "SUBJECT_REGISTRY_RECOMPUTE_POLICY_SCHEMA_INVALID"),
        (projection, "subject-repair-projection-policy.schema.json", "SUBJECT_REGISTRY_PROJECTION_POLICY_SCHEMA_INVALID"),
        (deferred, "deferred-consumer-responsibilities.schema.json", "SUBJECT_REGISTRY_DEFERRED_SCHEMA_INVALID"),
    ]
    for instance, schema_name, code in schema_pairs:
        schema = load_json(project_root / CONTRACT_ROOT / schema_name)
        if schema_definition_issues(schema):
            issues.append("SUBJECT_REGISTRY_SCHEMA_DEFINITION_INVALID")
        issues.extend(_schema_pair_issues(instance, schema, code))

    registry_schema = load_json(project_root / EVENT_ROOT / "consumer-registry.schema.json")
    if schema_definition_issues(registry_schema) or schema_issues(registry, registry_schema):
        issues.append("SUBJECT_REGISTRY_CONSUMER_REGISTRY_INVALID")
    if schema_definition_issues(event):
        issues.append("SUBJECT_REGISTRY_EVENT_SCHEMA_INVALID")

    if mapping.get("policyVersion") != "SMP-1.0.0":
        issues.append("SUBJECT_REGISTRY_POLICY_VERSION_UNKNOWN")
    issues.extend(_approval_issues(mapping))
    issues.extend(_approval_issues(recompute))
    issues.extend(_approval_issues(projection))
    adapters = mapping.get("sourceAdapters", [])
    adapter_ids = [item.get("sourceId") for item in adapters if isinstance(item, dict)]
    if len(adapter_ids) != len(set(adapter_ids)) or set(adapter_ids) != EXPECTED_ADAPTERS:
        issues.append("SUBJECT_REGISTRY_ADAPTER_SET_INVALID")
    issuers = [item for item in adapters if isinstance(item, dict) and item.get("mayIssueStudentRef")]
    if len(issuers) != 1 or issuers[0].get("sourceId") != "SRC-P0-STUDENT-001":
        issues.append("SUBJECT_REGISTRY_ISSUANCE_AUTHORITY_INVALID")
    rules = mapping.get("matchingRules", {})
    if rules.get("fuzzyMatchingAllowed") is not False:
        issues.append("SUBJECT_REGISTRY_FUZZY_MATCH_FORBIDDEN")
    if rules.get("crossSourceFallbackAllowed") is not False:
        issues.append("SUBJECT_REGISTRY_CROSS_SOURCE_FALLBACK_FORBIDDEN")
    if mapping.get("canonicalSubjectId") != "StudentRef" or mapping.get("subjectRefSemantics") != "wire-alias-of-StudentRef":
        issues.append("SUBJECT_REGISTRY_CANONICAL_ID_INVALID")

    identity = recompute.get("jobIdentity", {}).get("fields")
    if identity != ["correctionLineageId", "studentRef", "ruleId", "ruleVersion", "scenarioId", "windowId", "inputWatermarksDigest"]:
        issues.append("SUBJECT_REGISTRY_RECOMPUTE_IDENTITY_INVALID")
    encoded_recompute = json.dumps(recompute, sort_keys=True).lower()
    if any(token in encoded_recompute for token in ("windowdays", "subjectwindowssample", "makerchecker")):
        issues.append("SUBJECT_REGISTRY_QRP_POLICY_CONTAMINATION")
    if recompute.get("actionableBoundary", {}).get("mode") != "required-from-rule-or-scenario-contract":
        issues.append("SUBJECT_REGISTRY_ACTIONABLE_BOUNDARY_INVALID")

    active = [item for item in registry.get("consumers", []) if item.get("lifecycle") == "active"]
    planned = [item for item in registry.get("consumers", []) if item.get("lifecycle") == "planned"]
    if [item.get("ownerModule") for item in active] != ["ingestion-quality"]:
        issues.append("SUBJECT_REGISTRY_ACTIVE_CONSUMER_SET_INVALID")
    if {item.get("ownerModule") for item in planned} != {"signal-evaluation", "clue-care"}:
        issues.append("SUBJECT_REGISTRY_PLANNED_CONSUMER_SET_INVALID")
    if any(item.get("runtimeEvidenceClaim") != "none" for item in registry.get("consumers", [])):
        issues.append("SUBJECT_REGISTRY_RUNTIME_CLAIM_INVALID")

    if FORBIDDEN_WIRE_KEYS & (_keys(event) | _keys(openapi)):
        issues.append("SUBJECT_REGISTRY_WIRE_FIELD_FORBIDDEN")
    if openapi.get("openapi") != "3.1.2" or openapi.get("servers") != [{"url": "/api/v1"}]:
        issues.append("SUBJECT_REGISTRY_OPENAPI_VERSION_INVALID")
    expected_paths = {
        "/subject-mapping-exceptions",
        "/subject-mapping-exceptions/{exceptionId}",
        "/subject-mapping-exceptions/{exceptionId}/repair",
        "/subject-recompute-jobs/{jobId}",
    }
    if set(openapi.get("paths", {})) != expected_paths:
        issues.append("SUBJECT_REGISTRY_OPENAPI_PATH_SET_INVALID")

    consumer_lock = load_json(project_root / CONSUMER_LOCK)
    consumer_digests = consumer_lock.get("digests", {})
    if set(consumer_digests) != CONSUMER_LOCK_FILES:
        issues.append("SUBJECT_REGISTRY_CONSUMER_LOCK_SET_INVALID")
    else:
        for path, digest in consumer_digests.items():
            if canonical_digest(load_json(project_root / path)) != digest:
                issues.append("SUBJECT_REGISTRY_CONSUMER_LOCK_DIGEST_INVALID")
                break

    lock = load_json(project_root / LOCK)
    digests = lock.get("digests", {})
    if set(digests) != EXPECTED_CONTROLLED_FILES:
        issues.append("SUBJECT_REGISTRY_LOCK_SET_INVALID")
    else:
        for path, digest in digests.items():
            if canonical_digest(load_json(project_root / path)) != digest:
                issues.append("SUBJECT_REGISTRY_LOCK_DIGEST_INVALID")
                break

    mapping_fixtures = load_json(project_root / CONTRACT_ROOT / "fixtures/valid/mapping-fixtures-1.0.0.json")
    if any(evaluate_mapping_fixture(case) != case.get("expected") for case in mapping_fixtures.get("cases", [])):
        issues.append("SUBJECT_REGISTRY_MAPPING_FIXTURE_INVALID")
    ordering_fixtures = load_json(project_root / CONTRACT_ROOT / "fixtures/ordering/ordering-fixtures-1.0.0.json")
    if any(evaluate_ordering_fixture(case) != case.get("expected") for case in ordering_fixtures.get("cases", [])):
        issues.append("SUBJECT_REGISTRY_ORDERING_FIXTURE_INVALID")
    negative_fixtures = load_json(project_root / CONTRACT_ROOT / "fixtures/invalid/negative-fixtures-1.0.0.json")
    if any(execute_negative_fixture(case, project_root) != case.get("expectedCode") for case in negative_fixtures.get("cases", [])):
        issues.append("SUBJECT_REGISTRY_NEGATIVE_FIXTURE_INVALID")
    compatibility_fixtures = load_json(project_root / CONTRACT_ROOT / "fixtures/compatibility/compatibility-fixtures-1.0.0.json")
    if any(execute_compatibility_fixture(case) != case.get("expected") for case in compatibility_fixtures.get("cases", [])):
        issues.append("SUBJECT_REGISTRY_COMPATIBILITY_FIXTURE_INVALID")
    authorization_fixtures = load_json(project_root / CONTRACT_ROOT / "fixtures/authorization/subject-repair-authorization-fixtures-1.0.0.json")
    if any(evaluate_authorization_fixture(case) != case.get("expected") for case in authorization_fixtures.get("cases", [])):
        issues.append("SUBJECT_REGISTRY_AUTHORIZATION_FIXTURE_INVALID")

    deferred_text = (project_root / "_bmad-output/implementation-artifacts/deferred-work.md").read_text(encoding="utf-8")
    traceability_text = (project_root / "_bmad-output/planning-artifacts/requirements-traceability.md").read_text(encoding="utf-8")
    if "SUBJECT-DEFERRED-CONSUMERS-1.0.0" not in deferred_text or "runtimeEvidenceClaim=none" not in deferred_text:
        issues.append("SUBJECT_REGISTRY_DEFERRED_TRACKING_MISSING")
    if "FR-13 runtime staged" not in traceability_text:
        issues.append("SUBJECT_REGISTRY_TRACEABILITY_MISSING")
    return sorted(set(issues))


def main() -> int:
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
    issues = validate(root)
    if issues:
        for issue in issues:
            print(issue, file=sys.stderr)
        return 1
    print("subject-registry-contracts: PASS (SMP-1.0.0/SRP-1.0.0; adapters=6; consumers=3; runtimeEvidenceClaim=none)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
