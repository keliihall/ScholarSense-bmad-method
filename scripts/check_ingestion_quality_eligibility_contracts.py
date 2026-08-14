#!/usr/bin/env python3
"""Fail-closed checker for Story 2.4 registry, eligibility and ordering locks."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from typing import Any


BASE = Path("contracts/ingestion-quality/rule-dependency")
REGISTRY_SCHEMA = BASE / "rule-dependency-registry.schema.json"
REGISTRY = BASE / "rule-dependency-registry-1.0.0.json"
DERIVATION = BASE / "derivation-matrix-1.0.0.json"
POLICY_SCHEMA = BASE / "quality-eligibility-policy.schema.json"
POLICY = BASE / "quality-eligibility-policy-1.0.0.json"
FACT_SCHEMA = BASE / "quality-eligibility.schema.json"
ORDERING = BASE / "ordering-backfill-1.0.0.json"
RETENTION = BASE / "quality-eligibility-retention-1.0.0.json"
PROJECTION = BASE / "dependency-eligibility-projection-1.0.0.json"
SYNTHETIC = BASE / "fixtures/valid/synthetic-composition-vectors-1.0.0.json"
NEGATIVE = BASE / "fixtures/invalid/negative-fixtures-1.0.0.json"
EVENT_SCHEMA = Path("contracts/events/ingestion-quality/quality-eligibility-event.schema.json")
EVENT_FIXTURE = Path("contracts/events/ingestion-quality/fixtures/valid/quality-eligibility-changed-v1.json")
EVENT_NEGATIVE = Path("contracts/events/ingestion-quality/fixtures/invalid/quality-eligibility-negative-fixtures-1.0.0.json")
EVENT_ORDERING = Path("contracts/events/ingestion-quality/fixtures/ordering/quality-eligibility-ordering-1.0.0.json")
CONSUMER_HANDOFF = Path("contracts/events/ingestion-quality/quality-eligibility-consumer-handoff-1.0.0.json")
RECOVERY_COMMAND = BASE / "quality-eligibility-recovery-command-1.0.0.json"
OPENAPI = Path("contracts/openapi/quality-eligibilities.openapi.json")
LOCK = BASE / "quality-eligibility-contract-lock-1.0.0.json"

DCC = Path("contracts/data-catalog/dcc-1.1.0.json")
SEED = Path("contracts/data-catalog/dependency-registry-1.0.0.json")
RULE_CATALOG = Path("_bmad-output/planning-artifacts/rule-catalog.md")
QMDP_LOCK = Path("contracts/ingestion-quality/batch-quality/executable-quality-contract-lock-1.0.0.json")
QSHM_LOCK = Path("contracts/ingestion-quality/batch-quality/quality-snapshot-hash-contract-lock-1.0.0.json")
RFP = Path("contracts/authorization/role-field-policy-rfp-1.0.0.json")
FIELD_11 = Path("contracts/field-projection/field-projection-policy-binding-1.1.0.json")
UPSTREAM_EVENT_LOCK = Path("contracts/events/ingestion-quality/data-batch-quality-event-contract-lock-1.0.0.json")

FILES = (
    REGISTRY_SCHEMA, REGISTRY, DERIVATION, POLICY_SCHEMA, POLICY, FACT_SCHEMA,
    ORDERING, RETENTION, PROJECTION, SYNTHETIC, NEGATIVE, EVENT_SCHEMA,
    EVENT_FIXTURE, EVENT_NEGATIVE, EVENT_ORDERING, CONSUMER_HANDOFF,
    RECOVERY_COMMAND, OPENAPI,
)
UPSTREAM = (DCC, SEED, RULE_CATALOG, QMDP_LOCK, QSHM_LOCK, RFP, FIELD_11, UPSTREAM_EVENT_LOCK)
COPY_PATHS = (
    BASE, EVENT_SCHEMA, EVENT_FIXTURE, EVENT_NEGATIVE, EVENT_ORDERING,
    CONSUMER_HANDOFF, OPENAPI, *UPSTREAM,
)

SOURCE_TO_DEPENDENCY = {
    "SRC-P0-CAMPUS-ACCESS-001": "DEP-P0-CAMPUS-ACCESS-001",
    "SRC-P0-DORM-ACCESS-001": "DEP-P0-DORM-ACCESS-001",
    "SRC-P0-ACCOMMODATION-001": "DEP-P0-ACCOMMODATION-001",
    "SRC-P0-LEAVE-001": "DEP-P0-LEAVE-001",
    "SRC-P0-CALENDAR-001": "DEP-P0-CALENDAR-001",
    "SRC-P0-CARD-001": "DEP-P0-CONSUMPTION-001",
    "SRC-P0-TIMETABLE-001": "DEP-P0-TIMETABLE-001",
    "SRC-P0-DEVICE-001": "DEP-P0-DEVICE-001",
    "SRC-P1-OFFCAMPUS-001": "DEP-P1-OFFCAMPUS-001",
    "SRC-P1-NETWORK-001": "DEP-P1-NETWORK-001",
    "SRC-P1-ACADEMIC-001": "DEP-P1-ACADEMIC-001",
}
RULE_ORACLE = {
    "ACC-SAFE-001": {
        "DEP-P0-CAMPUS-ACCESS-001", "DEP-P0-DORM-ACCESS-001",
        "DEP-P0-ACCOMMODATION-001", "DEP-P0-LEAVE-001",
        "DEP-P0-CALENDAR-001", "DEP-P0-TIMETABLE-001", "DEP-P0-DEVICE-001",
    },
    "ACC-SAFE-002": {
        "DEP-P0-DORM-ACCESS-001", "DEP-P0-ACCOMMODATION-001",
        "DEP-P0-LEAVE-001", "DEP-P0-CALENDAR-001",
        "DEP-P0-TIMETABLE-001", "DEP-P0-DEVICE-001",
    },
    "ECON-012": {
        "DEP-P0-CONSUMPTION-001", "DEP-P0-LEAVE-001",
        "DEP-P0-CALENDAR-001", "DEP-P1-OFFCAMPUS-001",
    },
    "NIGHT-001": {
        "DEP-P1-NETWORK-001", "DEP-P0-LEAVE-001",
        "DEP-P0-CALENDAR-001", "DEP-P1-OFFCAMPUS-001",
    },
    "ACADEMIC-001": {
        "DEP-P1-ACADEMIC-001", "DEP-P0-TIMETABLE-001", "DEP-P0-CALENDAR-001",
    },
}
EXCLUDED_SOURCES = {
    "SRC-P0-STUDENT-001", "SRC-P0-RESPONSIBILITY-001", "SRC-P1-CARE-LIST-001",
    "SRC-P1-PSYCH-DEID-001", "SRC-P1-AID-001", "SRC-P1-WORK-VISIT-001",
}


def load(root: Path, relative: Path) -> Any:
    return json.loads((root / relative).read_text(encoding="utf-8"))


def raw_digest(path: Path) -> str:
    return f"sha256:{hashlib.sha256(path.read_bytes()).hexdigest()}"


def canonical_digest(value: Any) -> str:
    body = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return f"sha256:{hashlib.sha256(body).hexdigest()}"


def registry_issues(registry: Any) -> list[str]:
    issues: list[str] = []
    if not isinstance(registry, dict):
        return ["registry document"]
    rules = registry.get("rules", [])
    if not isinstance(rules, list):
        return ["registry rules"]
    rule_ids = [rule.get("ruleId") for rule in rules if isinstance(rule, dict)]
    if set(rule_ids) != set(RULE_ORACLE) or len(rule_ids) != len(RULE_ORACLE):
        issues.append("source-backed rule set must be the five RC-1.0.0 rules")
    for rule in rules:
        if not isinstance(rule, dict):
            issues.append("registry rule shape")
            continue
        rule_id = rule.get("ruleId")
        composition = rule.get("composition", {})
        members = rule.get("members", [])
        if composition != {"operator": "all-of", "threshold": None}:
            issues.append(f"{rule_id}: production composition must be required all-of")
        seen: set[tuple[Any, Any]] = set()
        actual: set[Any] = set()
        for member in members if isinstance(members, list) else []:
            if not isinstance(member, dict):
                issues.append(f"{rule_id}: member shape")
                continue
            source_id, dependency_id = member.get("sourceId"), member.get("dependencyId")
            identity = (source_id, dependency_id)
            if identity in seen:
                issues.append(f"{rule_id}: duplicate member")
            seen.add(identity)
            actual.add(dependency_id)
            expected_dependency = SOURCE_TO_DEPENDENCY.get(source_id)
            if expected_dependency is None:
                issues.append(f"{rule_id}: unknown source")
            if dependency_id not in SOURCE_TO_DEPENDENCY.values():
                issues.append(f"{rule_id}: unknown dependency")
            if expected_dependency is not None and dependency_id != expected_dependency:
                issues.append(f"{rule_id}: source/dependency identity drift")
            if member.get("requirement") != "required":
                issues.append(f"{rule_id}: production member must be required")
        if rule_id in RULE_ORACLE and actual != RULE_ORACLE[rule_id]:
            issues.append(f"{rule_id}: incomplete production registration")
    if set(registry.get("excludedSources", [])) != EXCLUDED_SOURCES:
        issues.append("six non-rule consumer sources must remain excluded")
    return issues


def synthetic_issues(fixture: Any) -> list[str]:
    issues: list[str] = []
    cases = fixture.get("cases", []) if isinstance(fixture, dict) else []
    required_cases = {
        "all-of-optional-does-not-mask-required", "any-of-boundary", "threshold-exact-boundary",
        "required-fused-fails-closed", "required-recovering-fails-closed",
        "required-version-gap-fails-closed",
    }
    if {case.get("caseId") for case in cases if isinstance(case, dict)} != required_cases:
        issues.append("synthetic composition cases incomplete")
    for case in cases:
        if not isinstance(case, dict):
            continue
        members = case.get("members", [])
        ids = [member.get("dependencyId") for member in members if isinstance(member, dict)]
        if len(ids) != len(set(ids)):
            issues.append(f"{case.get('caseId')}: duplicate member")
        operator, threshold = case.get("operator"), case.get("threshold")
        if operator == "threshold":
            if not isinstance(threshold, int) or threshold < 1 or threshold > len(members):
                issues.append(f"{case.get('caseId')}: threshold out of bounds")
        elif threshold is not None:
            issues.append(f"{case.get('caseId')}: threshold only allowed for threshold operator")
    return issues


def ordering_issues(policy: Any) -> list[str]:
    issues: list[str] = []
    source = policy.get("sourceOrdering", {}) if isinstance(policy, dict) else {}
    if source.get("sequenceKey") != ["sourceId", "sourceVersionOrdinal", "lineageRevision"]:
        issues.append("cross-batch sequence must use source version ordinal and lineage revision")
    if source.get("lineageRevisionSemantics") != (
            "owner-local-zero-for-lineage-root-then-derived-from-exact-direct-"
            "supersedesBatchId-chain-plus-one; not assumed on the Story-2.3 wire event"):
        issues.append("lineage revision must be derived owner-locally from exact supersedes chain")
    forbidden = set(source.get("forbiddenOrderingFields", []))
    if forbidden != {"batchAggregateVersion", "eventId", "occurredAt", "watermark"}:
        issues.append("forbidden ordering fields are not locked")
    pairing = policy.get("batchPairing", {}) if isinstance(policy, dict) else {}
    if (pairing.get("assessedFailedV3") != "terminal-no-published-v4-expected"
            or pairing.get("assessedPassedV3") != "pending-until-exact-published-v4"
            or pairing.get("publishedV4") != "same-batch-and-snapshot-only"):
        issues.append("assessed/published pairing matrix")
    scenarios = {case.get("case"): case for case in policy.get("scenarios", []) if isinstance(case, dict)} if isinstance(policy, dict) else {}
    if set(scenarios) != {"duplicate", "old", "gap", "poison", "backfill", "retention-expired", "unrecoverable"}:
        issues.append("ordering scenarios incomplete")
    poison = scenarios.get("poison", {})
    if poison.get("cursorAdvances") is not False or poison.get("businessStateChanges") is not False:
        issues.append("poison fail-closed semantics")
    gap = scenarios.get("gap", {})
    if gap.get("expectedAction") != "pause-and-backfill" or gap.get("cursorAdvances") is not False:
        issues.append("gap fail-closed semantics")
    return issues


def event_issues(event: Any) -> list[str]:
    issues: list[str] = []
    if not isinstance(event, dict):
        return ["eligibility event shape"]
    data = event.get("data", {})
    fact = data.get("qualityEligibility", {}) if isinstance(data, dict) else {}
    if event.get("id") != data.get("eventId"):
        issues.append("eligibility event identity binding")
    if data.get("aggregateId") != fact.get("eligibilityId"):
        issues.append("eligibility aggregate identity binding")
    if event.get("subject") != f"quality-eligibility/{data.get('aggregateId')}":
        issues.append("eligibility event subject binding")
    if event.get("time") != data.get("occurredAt") or fact.get("occurredAt") != data.get("occurredAt"):
        issues.append("eligibility event time binding")
    if data.get("aggregateVersion") != fact.get("aggregateVersion"):
        issues.append("eligibility aggregate version binding")
    if data.get("traceId") != fact.get("traceId") or data.get("runtimeEvidenceClaim") != "none":
        issues.append("eligibility trace/runtime binding")
    traceparent = event.get("traceparent", "")
    if len(traceparent) < 35 or traceparent[3:35] != data.get("traceId"):
        issues.append("eligibility traceparent binding")
    business_key = f"{fact.get('ruleId')}@{fact.get('ruleVersion')}@{fact.get('registryVersion')}"
    if fact.get("businessKey") != business_key:
        issues.append("eligibility business key binding")
    if "deliveryStatus" in json.dumps(event, ensure_ascii=False):
        issues.append("eligibility source aggregate cannot carry deliveryStatus")
    if len(json.dumps(event, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")) > 65_536:
        issues.append("eligibility event exceeds 64 KiB")
    return issues


def check(root: Path) -> list[str]:
    issues: list[str] = []
    for relative in (*FILES, *UPSTREAM, LOCK):
        if not (root / relative).is_file():
            issues.append(f"missing {relative}")
    if issues:
        return issues

    registry = load(root, REGISTRY)
    issues.extend(registry_issues(registry))
    bindings = load(root, SEED).get("bindings", [])
    if {item.get("sourceId"): item.get("dependencyId") for item in bindings} != SOURCE_TO_DEPENDENCY:
        issues.append("stable dependency identity seed drift")
    dcc = load(root, DCC)
    rule_sources = {source.get("sourceId") for source in dcc.get("sources", []) if source.get("consumerMode") == "rule-dependency"}
    if rule_sources != set(SOURCE_TO_DEPENDENCY):
        issues.append("DCC rule-consumer source set drift")

    expected_upstream = {
        "catalog": ("DCC-1.1.0", raw_digest(root / DCC)),
        "identitySeed": ("DCC-DEPENDENCY-1.0.0", raw_digest(root / SEED)),
        "ruleCatalog": ("RC-1.0.0", raw_digest(root / RULE_CATALOG)),
    }
    for field, (version, digest) in expected_upstream.items():
        if registry.get(field) != {"version": version, "digest": digest}:
            issues.append(f"registry {field} version/digest binding")
    qmdp = load(root, QMDP_LOCK)
    qshm = load(root, QSHM_LOCK)
    expected_profiles = {
        "qmdp": {"version": qmdp.get("profileVersion"), "digest": qmdp.get("policyCanonicalDigest")},
        "qshm": {"version": qshm.get("hashProfileVersion"), "digest": qshm.get("profileCanonicalDigest")},
    }
    if registry.get("qualityProfiles") != expected_profiles:
        issues.append("registry QMDP/QSHM version/digest binding")

    policy = load(root, POLICY)
    if (policy.get("statusValues") != ["eligible", "fused", "recovering", "missing"]
            or policy.get("businessKey") != ["ruleId", "ruleVersion", "registryVersion"]
            or policy.get("recoveryBoundary", {}).get("runtimeOwnerStory") != "2.5"
            or policy.get("runtimeEvidenceClaim") != "none"):
        issues.append("quality eligibility policy semantics")
    pairing = policy.get("upstreamPairing", {})
    if (pairing.get("snapshotLookup") != "exact-snapshotId-and-immutableHash-never-latest"
            or pairing.get("impactScopeCodesSemantics") != "QMDP-metric-identifiers-only-never-dependency-impact"):
        issues.append("upstream integrity bindings")

    issues.extend(synthetic_issues(load(root, SYNTHETIC)))
    issues.extend(ordering_issues(load(root, ORDERING)))
    retention = load(root, RETENTION)
    if (retention.get("retentionScheduleVersion") != "RS-1.0.0"
            or retention.get("classification") != "reporting-and-operational-snapshot"
            or retention.get("retentionPeriod") != "P2Y"
            or retention.get("idempotencyRecordRetention") != "P90D"
            or retention.get("runtimeEvidenceClaim") != "none"):
        issues.append("retention mapping must preserve RS-1.0.0 two-year/90-day classes")
    projection = load(root, PROJECTION)
    authorization = projection.get("authorization", {})
    if (projection.get("objectClass") != "Dependency"
            or projection.get("purpose") != "data-quality.read"
            or projection.get("requiredScopeAnchor") != "OWNED_SOURCE"
            or authorization.get("requiresEveryExposedMemberAuthorized") is not True
            or authorization.get("partialMemberResult") != "redacted-or-not-found-without-count-leak"
            or authorization.get("readAuditBeforeSensitiveResponse") is not True):
        issues.append("projection authorization must bind owned source, all members and read audit")
    if projection.get("predecessor", {}).get("sha256") != raw_digest(root / FIELD_11).removeprefix("sha256:"):
        issues.append("projection predecessor digest binding")
    if projection.get("roleFieldPolicy", {}).get("sha256") != raw_digest(root / RFP).removeprefix("sha256:"):
        issues.append("projection RFP digest binding")

    negative_cases = {case.get("caseId") for case in load(root, NEGATIVE).get("cases", [])}
    required_negative = {"unknown-source", "duplicate-dependency-member", "non-rule-consumer-source", "corroborate-direct-binding", "spm-as-rule", "threshold-missing", "threshold-out-of-range", "registry-digest-drift", "snapshot-hash-drift", "impact-scope-as-dependency", "technical-error-as-fused", "batch-v3-v4-cross-batch-order"}
    if negative_cases != required_negative:
        issues.append("negative fixture matrix incomplete")
    event = load(root, EVENT_FIXTURE)
    issues.extend(event_issues(event))
    event_fact = event.get("data", {}).get("qualityEligibility", {})
    event_members = event_fact.get("members", [])
    event_dependencies = {
        member.get("dependencyId") for member in event_members if isinstance(member, dict)
    }
    if (event_fact.get("registryDigest") != canonical_digest(registry)
            or event_dependencies != RULE_ORACLE.get(event_fact.get("ruleId"), set())
            or len(event_dependencies) != len(event_members)
            or any(member.get("requirement") != "required" for member in event_members)
            or set(event_fact.get("failedMembers", []))
                != {member.get("dependencyId") for member in event_members
                    if member.get("state") != "eligible"}):
        issues.append("eligibility event must carry the complete digest-bound production composition")
    for member in event_members:
        try:
            versions = (int(member.get("sourceVersion", "0")),
                        int(member.get("dependencyVersion", "0")))
        except (TypeError, ValueError):
            versions = (0, 0)
        if any(value < 1 or value > 9_007_199_254_740_991 for value in versions):
            issues.append("eligibility event member version must be a safe positive integer")

    event_schema = load(root, EVENT_SCHEMA)
    if (event_schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema"
            or event_schema.get("properties", {}).get("type", {}).get("const") != "scholarsense.ingestion-quality.quality-eligibility.changed.v1"
            or event_schema.get("x-maximumWireBytes") != 65_536
            or len(event_schema.get("x-crossFieldBindings", [])) != 6):
        issues.append("eligibility event schema")

    negative = load(root, EVENT_NEGATIVE)
    negative_cases = {
        case.get("caseId") for case in negative.get("cases", []) if isinstance(case, dict)
    }
    if (negative.get("runtimeEvidenceClaim") != "none"
            or negative_cases != {
                "event-id-mismatch", "subject-mismatch", "aggregate-version-mismatch",
                "occurred-at-mismatch", "trace-mismatch", "registry-digest-drift",
                "unknown-member", "duplicate-member", "delivery-status-on-aggregate",
                "wire-size-overflow",
            }):
        issues.append("eligibility invalid event fixtures incomplete")

    event_ordering = load(root, EVENT_ORDERING)
    ordering_cases = {
        case.get("caseId"): case for case in event_ordering.get("scenarios", [])
        if isinstance(case, dict)
    }
    if (event_ordering.get("runtimeEvidenceClaim") != "none"
            or set(ordering_cases) != {"duplicate", "old", "gap", "poison", "backfill"}
            or ordering_cases.get("duplicate", {}).get("expectedAction") != "duplicate-no-op"
            or ordering_cases.get("gap", {}).get("expectedAction") != "pause-and-backfill"
            or ordering_cases.get("poison", {}).get("expectedAggregateVersion") != 1
            or ordering_cases.get("backfill", {}).get("expectedAggregateVersion") != 3):
        issues.append("eligibility duplicate/old/gap/poison/backfill fixtures incomplete")

    recovery = load(root, RECOVERY_COMMAND)
    if (recovery.get("ownerPort")
            != policy.get("recoveryBoundary", {}).get("ownerPort")
            or recovery.get("runtimeOwnerStory") != "2.5"
            or recovery.get("story24RuntimeActivation") != "not-installed"
            or recovery.get("runtimeEvidenceClaim") != "none"
            or recovery.get("command", {}).get("allowedTransition")
                != "fused-to-recovering"):
        issues.append("Story 2.5 recovery owner handoff")

    handoff = load(root, CONSUMER_HANDOFF)
    if (handoff.get("targetStory") != "3.2"
            or handoff.get("eventType") != event.get("type")
            or handoff.get("runtimeEvidenceClaim") != "none"
            or handoff.get("consumerRequirements", {}).get("maximumWireBytes") != 65_536
            or handoff.get("objectsNotCreatedByStory24")
                != ["RuleEvaluation", "Candidate", "Clue"]):
        issues.append("Story 3.2 consumer conformance handoff")

    openapi = load(root, OPENAPI)
    serialized_openapi = json.dumps(openapi, ensure_ascii=False, separators=(",", ":"))
    if (openapi.get("openapi") != "3.1.2"
            or openapi.get("servers") != [{"url": "/api/v1"}]
            or set(openapi.get("paths", {})) != {
                "/quality-eligibilities", "/quality-eligibilities/{eligibilityId}"
            }
            or any(set(path) != {"get"} for path in openapi.get("paths", {}).values())
            or any(value in serialized_openapi for value in (
                "deliveryStatus", "studentId", "rawRecord", "metricResults"))):
        issues.append("quality eligibility read-only OpenAPI projection")

    lock = load(root, LOCK)
    expected_files = {str(path): raw_digest(root / path) for path in FILES}
    expected_upstream_files = {str(path): raw_digest(root / path) for path in UPSTREAM}
    canonical = {
        str(REGISTRY): canonical_digest(registry),
        str(POLICY): canonical_digest(policy),
        str(ORDERING): canonical_digest(load(root, ORDERING)),
    }
    if (lock.get("lockVersion") != "QUALITY-ELIGIBILITY-CONTRACT-LOCK-1.0.0"
            or lock.get("runtimeEvidenceClaim") != "none"
            or lock.get("files") != expected_files
            or lock.get("upstreamFiles") != expected_upstream_files
            or lock.get("canonicalDigests") != canonical):
        issues.append("contract lock mismatch")
    return issues


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    issues = check(root)
    if issues:
        for issue in issues:
            print(f"QUALITY_ELIGIBILITY_CONTRACT: {issue}")
        return 1
    print("QUALITY_ELIGIBILITY_CONTRACT: registry, eligibility, ordering, projection, retention and lock valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
