#!/usr/bin/env python3
"""Fail-closed checker for Story 2.5b QRP, D4 and recovery handoff contracts."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from typing import Any


BASE = Path("contracts/ingestion-quality/quality-recovery")
POLICY_SCHEMA = BASE / "quality-recovery-policy.schema.json"
POLICY = BASE / "quality-recovery-policy-1.0.0.json"
SOURCE_REGISTRY = BASE / "recovery-source-class-registry-1.0.0.json"
CHECKER_BINDING = BASE / "recovery-checker-binding-1.0.0.json"
SAMPLE_PROVIDER = BASE / "recovery-sample-provider-1.0.0.json"
SAMPLE_PROVIDER_SUCCESSOR = BASE / "recovery-sample-provider-1.1.0.json"
RECOVERY_WIRE_COMMON = BASE / "quality-recovery-wire-common.schema.json"
RECOVERY_REQUEST_SCHEMA = BASE / "quality-recovery-request.schema.json"
RECOVERY_EVIDENCE_SCHEMA = BASE / "quality-recovery-evidence-pack.schema.json"
RECOVERY_JOB_SCHEMA = BASE / "quality-recovery-validation-job.schema.json"
RECOVERY_CHECKPOINT_SCHEMA = BASE / "quality-recovery-validation-checkpoint.schema.json"
RECOVERY_RESULT_SCHEMA = BASE / "quality-recovery-validation-result.schema.json"
RECOVERY_RESULT_SUCCESSOR = (
    BASE / "quality-recovery-validation-result-1.1.0.schema.json")
RECOVERY_EVIDENCE_SUCCESSOR = (
    BASE / "quality-recovery-evidence-pack-1.1.0.schema.json")
RECOVERY_PREVIEW_SCHEMA = BASE / "quality-recovery-impact-preview.schema.json"
RECOVERY_EVENT_SCHEMA = BASE / "quality-recovery-event.schema.json"
RECOVERY_PROJECTION_SCHEMA = BASE / "quality-recovery-projection.schema.json"
RECOVERY_ERROR_PROFILE = BASE / "quality-recovery-error-profile-1.0.0.json"
RECOVERY_RETENTION = BASE / "quality-recovery-retention-1.0.0.json"
ARCHITECTURE_CONTRACT = BASE / "quality-recovery-architecture-successor-1.0.0.json"
DERIVATION = BASE / "quality-recovery-derivation-matrix-1.0.0.json"
COMMAND_SUCCESSOR = BASE / "quality-eligibility-recovery-command-1.1.0.json"
HANDOFF = BASE / "quality-recovery-handoff-2.5c-1.0.0.json"
BASELINE = BASE / "quality-recovery-implementation-baseline-1.0.0.json"
BASELINE_OVERLAY = BASE / "quality-recovery-predecessor-overlay-1.0.0.json"
VALID = BASE / "fixtures/valid/quality-recovery-boundary-vectors-1.0.0.json"
NEGATIVE = BASE / "fixtures/invalid/quality-recovery-negative-fixtures-1.0.0.json"
LOCK = BASE / "quality-recovery-contract-lock-1.0.0.json"
LOCK_SUCCESSOR = BASE / "quality-recovery-contract-lock-1.1.0.json"
LOCK_RUNTIME_SUCCESSOR = BASE / "quality-recovery-contract-lock-1.2.0.json"

HRAP_BASE = Path("contracts/authorization/high-risk")
HRAP_POLICY = HRAP_BASE / "high-risk-action-policy-1.0.0.json"
HRAP_RUNTIME_POLICY = HRAP_BASE / "high-risk-runtime-policy-1.0.0.json"
HRAP_REQUEST_SCHEMA = HRAP_BASE / "high-risk-approval-request.schema.json"
HRAP_RECEIPT_SCHEMA = HRAP_BASE / "high-risk-approval-receipt.schema.json"
HRAP_TOKEN_SCHEMA = HRAP_BASE / "high-risk-execution-token.schema.json"
HRAP_LEASE_SCHEMA = HRAP_BASE / "high-risk-execution-authorization-lease.schema.json"
HRAP_LIFECYCLE_SCHEMA = HRAP_BASE / "high-risk-lifecycle-vectors.schema.json"
HRAP_LIFECYCLE_VECTORS = (
    HRAP_BASE / "fixtures/valid/high-risk-lifecycle-vectors-1.0.0.json")
HRAP_NEGATIVE_VECTORS = (
    HRAP_BASE / "fixtures/invalid/high-risk-negative-fixtures-1.0.0.json")
HRAP_REQUEST_FIXTURE = HRAP_BASE / "fixtures/valid/high-risk-approval-request.json"
HRAP_RECEIPT_FIXTURE = HRAP_BASE / "fixtures/valid/high-risk-approval-receipt.json"
HRAP_TOKEN_FIXTURE = HRAP_BASE / "fixtures/valid/high-risk-execution-token.json"
HRAP_LEASE_FIXTURE = (
    HRAP_BASE / "fixtures/valid/high-risk-execution-authorization-lease.json")
HRAP_LOCK_SCHEMA = HRAP_BASE / "high-risk-contract-lock.schema.json"
HRAP_LOCK = HRAP_BASE / "high-risk-contract-lock-1.0.0.json"
AUTHORIZED_SHELL_SUCCESSOR = Path(
    "contracts/authorization/authorized-shell-1.1.0.schema.json")
ACTION_CAPABILITY = BASE / "quality-recovery-action-capability-1.0.0.json"

COMMAND_PREDECESSOR = Path(
    "contracts/ingestion-quality/rule-dependency/quality-eligibility-recovery-command-1.0.0.json")
ELIGIBILITY_LOCK = Path(
    "contracts/ingestion-quality/rule-dependency/quality-eligibility-contract-lock-1.0.0.json")
FUSE_LOCK = Path(
    "contracts/ingestion-quality/quality-fuse/quality-fuse-contract-lock-1.0.0.json")
V15 = Path(
    "backend/src/main/resources/db/migration/ingestion-quality/V000015__ingestion-quality__quality_eligibility_v1.sql")
V16 = Path(
    "backend/src/main/resources/db/migration/ingestion-quality/V000016__ingestion-quality__quality_fuse_task_v1.sql")
RFP = Path("contracts/authorization/role-field-policy-rfp-1.0.0.json")
AUTHORIZED_SHELL_V1 = Path("contracts/authorization/authorized-shell.schema.json")
QUALITY_ELIGIBILITIES_OPENAPI = Path(
    "contracts/openapi/quality-eligibilities.openapi.json")
QUALITY_RECOVERY_TASKS_OPENAPI = Path(
    "contracts/openapi/quality-recovery-tasks.openapi.json")
QUALITY_FUSE_POLICY = Path(
    "contracts/ingestion-quality/quality-fuse/quality-fuse-policy-1.0.0.json")
RECOVERY_TASK_POLICY = Path(
    "contracts/ingestion-quality/quality-fuse/recovery-task-policy-1.0.0.json")
ARCHITECTURE = Path(
    "_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md")
HRAP = Path("_bmad-output/planning-artifacts/high-risk-action-matrix.md")
DELEGATED = Path("_bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md")
RELEASE_V7 = Path("contracts/release/release-manifest-7.schema.json")
EVIDENCE_V7 = Path("contracts/release/evidence-index-7.schema.json")
RUNTIME_V4 = Path("deploy/base/ingestion-quality-runtime-4.0.0.json")
ROLES_V4 = Path("deploy/base/ingestion-quality-roles-4.0.0.json")

FILES = (
    POLICY_SCHEMA, POLICY, SOURCE_REGISTRY, CHECKER_BINDING, SAMPLE_PROVIDER,
    RECOVERY_WIRE_COMMON, RECOVERY_REQUEST_SCHEMA, RECOVERY_EVIDENCE_SCHEMA,
    RECOVERY_JOB_SCHEMA, RECOVERY_CHECKPOINT_SCHEMA, RECOVERY_RESULT_SCHEMA,
    RECOVERY_PREVIEW_SCHEMA, RECOVERY_EVENT_SCHEMA, RECOVERY_PROJECTION_SCHEMA,
    RECOVERY_ERROR_PROFILE, RECOVERY_RETENTION,
    ARCHITECTURE_CONTRACT, DERIVATION, COMMAND_SUCCESSOR, HANDOFF, BASELINE,
    BASELINE_OVERLAY, VALID, NEGATIVE, HRAP_POLICY, HRAP_RUNTIME_POLICY,
    HRAP_REQUEST_SCHEMA, HRAP_RECEIPT_SCHEMA, HRAP_TOKEN_SCHEMA, HRAP_LEASE_SCHEMA,
    HRAP_LIFECYCLE_SCHEMA, HRAP_LIFECYCLE_VECTORS, HRAP_NEGATIVE_VECTORS,
    HRAP_REQUEST_FIXTURE, HRAP_RECEIPT_FIXTURE, HRAP_TOKEN_FIXTURE,
    HRAP_LEASE_FIXTURE, HRAP_LOCK_SCHEMA, HRAP_LOCK,
    AUTHORIZED_SHELL_SUCCESSOR, ACTION_CAPABILITY,
)
ADDITIVE_FILES = (
    SAMPLE_PROVIDER_SUCCESSOR, RECOVERY_RESULT_SUCCESSOR,
    RECOVERY_EVIDENCE_SUCCESSOR)
PREDECESSORS = (
    COMMAND_PREDECESSOR, ELIGIBILITY_LOCK, FUSE_LOCK, V15, V16, RFP,
    AUTHORIZED_SHELL_V1, QUALITY_ELIGIBILITIES_OPENAPI,
    QUALITY_RECOVERY_TASKS_OPENAPI, QUALITY_FUSE_POLICY, RECOVERY_TASK_POLICY,
    ARCHITECTURE, HRAP, DELEGATED, RELEASE_V7, EVIDENCE_V7, RUNTIME_V4, ROLES_V4,
)
COPY_PATHS = (
    *FILES, *ADDITIVE_FILES, *PREDECESSORS, LOCK, LOCK_SUCCESSOR,
    LOCK_RUNTIME_SUCCESSOR)

EXPECTED_SOURCE_CLASSES = {
    "SRC-P0-ACCOMMODATION-001": "streaming",
    "SRC-P0-CARD-001": "streaming",
    "SRC-P0-CAMPUS-ACCESS-001": "streaming",
    "SRC-P0-DORM-ACCESS-001": "streaming",
    "SRC-P0-DEVICE-001": "streaming",
    "SRC-P0-LEAVE-001": "streaming",
    "SRC-P0-CALENDAR-001": "streaming",
    "SRC-P0-TIMETABLE-001": "streaming",
    "SRC-P1-OFFCAMPUS-001": "streaming",
    "SRC-P1-NETWORK-001": "dailyBatch",
    "SRC-P1-ACADEMIC-001": "dailyBatch",
}


def load(root: Path, relative: Path) -> Any:
    return json.loads((root / relative).read_text(encoding="utf-8"))


def raw_digest(path: Path) -> str:
    return f"sha256:{hashlib.sha256(path.read_bytes()).hexdigest()}"


def check(root: Path) -> list[str]:
    issues: list[str] = []
    for relative in (
            *FILES, *ADDITIVE_FILES, *PREDECESSORS, LOCK, LOCK_SUCCESSOR,
            LOCK_RUNTIME_SUCCESSOR):
        if not (root / relative).is_file():
            issues.append(f"missing {relative}")
    if issues:
        return issues

    schema = load(root, POLICY_SCHEMA)
    policy = load(root, POLICY)
    if (schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema"
            or schema.get("additionalProperties") is not False
            or policy.get("$schema") != "quality-recovery-policy.schema.json"
            or policy.get("policyVersion") != "QRP-1.0.0"):
        issues.append("QRP JSON Schema 2020-12 closed policy binding")

    classes = policy.get("sourceClasses", {})
    if classes.get("streaming") != {
            "consecutivePassedBatches": 3, "observationMinutes": 60}:
        issues.append("streaming 3 batches / 60 minutes")
    if classes.get("dailyBatch") != {
            "consecutivePassedBatches": 2, "observationMinutes": 1440}:
        issues.append("daily 2 batches / 24 hours")

    qualification = policy.get("batchQualification", {})
    if (qualification.get("acceptedPair") != "assessed-passed-then-exact-published"
            or qualification.get("sequenceKey")
                != ["sourceId", "sourceVersionOrdinal", "lineageRevision"]
            or set(qualification.get("forbiddenOrderingFields", []))
                != {"eventId", "occurredAt", "watermark", "batchId"}
            or qualification.get("inclusive") is not True):
        issues.append("business batch sequence and exact assessed/published pairing")

    backfill = policy.get("backfill", {})
    if (backfill.get("lookbackDays") != 90
            or backfill.get("startExpression")
                != "max(lastKnownGoodWatermark,trustedNow-minus-P90D)"
            or backfill.get("trustedNowRequired") is not True):
        issues.append("90-day trusted backfill maximum")
    reconciliation = policy.get("reconciliation", {})
    if reconciliation != {"coverage": "full", "expectedMismatchCount": 0}:
        issues.append("full reconciliation mismatch zero")
    sampling = policy.get("sampling", {})
    if (sampling.get("unit") != "subject-window"
            or sampling.get("stratified") is not True
            or sampling.get("minimumSubjectWindows") != 100
            or sampling.get("allIfPopulationFewer") is not True
            or sampling.get("expectedMismatchCount") != 0
            or sampling.get("selectionSeedRequired") is not True):
        issues.append("sample all-if-fewer / minimum 100 / mismatch zero")
    preview_policy = policy.get("impactPreview", {})
    if (preview_policy.get("authorizesExecution") is not False
            or preview_policy.get("finalActionabilityOwnerStory") != "2.5c"
            or set(preview_policy.get("categories", [])) != {
                "already-expired-history-only", "currently-potentially-actionable",
                "expected-to-expire-before-observation-completes",
            }):
        issues.append("preview classification and 2.5c final actionability")

    registry = load(root, SOURCE_REGISTRY)
    actual_sources: dict[str, str] = {}
    source_approval_complete = True
    for item in registry.get("sources", []):
        source_id = item.get("sourceId") if isinstance(item, dict) else None
        source_class = (item.get("sourceClass", item.get("proposedSourceClass"))
                        if isinstance(item, dict) else None)
        if source_id in actual_sources:
            issues.append("source class registry duplicate")
        actual_sources[source_id] = source_class
        if (not isinstance(item, dict)
                or not isinstance(item.get("approvalRef"), str)
                or not item.get("approvalRef")
                or not isinstance(item.get("approvedBy"), str)
                or not item.get("approvedBy")
                or not isinstance(item.get("effectiveAt"), str)
                or not item.get("effectiveAt")):
            source_approval_complete = False
    source_binding_digest_material = "".join(
        f"{source_id}\x1f{actual_sources[source_id]}\n"
        for source_id in sorted(actual_sources)
    ).encode("utf-8")
    source_binding_digest = (
        "sha256:" + hashlib.sha256(source_binding_digest_material).hexdigest())
    if (actual_sources != EXPECTED_SOURCE_CLASSES
            or registry.get("classificationRule")
                != "explicit-owner-approved-binding-never-derived-from-frequency-slo-ui-or-default"
            or registry.get("unknownSource") != "reject"):
        issues.append("explicit closed source class registry")
    source_approval = registry.get("approval", {})
    if (registry.get("status") != "approved"
            or registry.get("runtimeEvidenceClaim") != "contract-only"
            or not source_approval_complete
            or registry.get("proposedBindingSetDigest") != source_binding_digest
            or source_approval.get("approvedBindingSetDigest") != source_binding_digest
            or source_approval.get("approvalRef") != "AUTH-2026-08-12-QRSCR-001"
            or source_approval.get("approvedBy") != "Hei"
            or not source_approval.get("effectiveAt")):
        issues.append("source class approval/effective evidence")

    binding = load(root, CHECKER_BINDING)
    resolution = binding.get("resolution", {})
    if (binding.get("bindingVersion") != "QRCOB-1.0.0"
            or binding.get("authorityProvider")
                != "rule-governance/api/RuleVersionBusinessOwnerBindingQueryPort"
            or binding.get("identityResolver")
                != "identity-access/api/CurrentNaturalPersonBindingQueryPort"
            or resolution.get("perRuleVersion")
                != "exactly-one-active-accountable-business-owner-binding"
            or resolution.get("multipleActiveNaturalPersonsForKey")
                != "dependency-unavailable-fail-closed"
            or resolution.get("missingBinding")
                != "dependency-unavailable-fail-closed"
            or resolution.get("providerUnavailable")
                != "dependency-unavailable-fail-closed"):
        issues.append("ambiguous checker binding fail closed")
    if (binding.get("quorum") != "all-distinct-business-owner-bindings"
            or binding.get("makerExclusion")
                != "maker-principal-must-not-equal-any-required-checker-principal"
            or binding.get("emptyCheckerSet") != "deny"):
        issues.append("all distinct business-owner bindings quorum")

    sample_contract = load(root, SAMPLE_PROVIDER)
    if raw_digest(root / SAMPLE_PROVIDER) != (
            "sha256:2309f38747568a70a3bfbf0c907175b70a9bd1e72fe73ab43c89a64ad7cdf4d2"):
        issues.append("sample provider predecessor digest")
    if (sample_contract.get("publicPort")
            != "signal-evaluation/api/RecoverySampleRecomputeProviderPort"
            or sample_contract.get("ownerInternalPort")
                != "ingestion-quality/application/RecoverySampleRecomputePort"
            or sample_contract.get("producer") != "signal-evaluation"
            or sample_contract.get("consumer") != "ingestion-quality"):
        issues.append("signal-evaluation public sample provider API")
    if set(sample_contract.get("resultFields", [])) != {
            "providerVersion", "selectionSeed", "populationCount", "selectedCount",
            "strataSummaryDigest", "expectedDigest", "actualDigest", "mismatchCount",
            "completedAt", "traceId"}:
        issues.append("sample provider result PII allowlist")
    bounds = sample_contract.get("bounds", {})
    if (not isinstance(bounds.get("timeoutMillis"), int)
            or bounds.get("timeoutMillis") <= 0
            or not isinstance(bounds.get("maximumSelectedSubjectWindows"), int)
            or bounds.get("maximumSelectedSubjectWindows") < 100
            or not isinstance(bounds.get("maximumWireBytes"), int)
            or bounds.get("maximumWireBytes") <= 0):
        issues.append("bounded sample provider timeout")

    sample_successor = load(root, SAMPLE_PROVIDER_SUCCESSOR)
    predecessor = sample_successor.get("predecessor", {})
    approval = sample_successor.get("approval", {})
    if (sample_successor.get("contractVersion")
            != "RECOVERY-SAMPLE-RECOMPUTE-PROVIDER-1.1.0"
            or predecessor.get("contractVersion")
                != "RECOVERY-SAMPLE-RECOMPUTE-PROVIDER-1.0.0"
            or predecessor.get("path") != str(SAMPLE_PROVIDER)
            or predecessor.get("rawSha256") != raw_digest(root / SAMPLE_PROVIDER)
            or approval.get("approvalRef") != "AUTH-2026-08-13-RSP-001"
            or approval.get("approvedBy") != "Hei"
            or not approval.get("effectiveAt")):
        issues.append("approved additive sample provider successor")
    if (sample_successor.get("publicPort")
            != "signal-evaluation/api/RecoverySampleRecomputeProviderPort"
            or sample_successor.get("ownerInternalPort")
                != "ingestion-quality/application/RecoverySampleRecomputePort"
            or sample_successor.get("providerVersion")
                != "RECOVERY-SAMPLE-PROVIDER-1.0.0"
            or set(sample_successor.get("resultFields", [])) != {
                "providerVersion", "selectionSeed", "populationCount", "selectedCount",
                "strata", "strataSummaryDigest", "expectedDigest", "actualDigest",
                "mismatchCount", "completedAt", "traceId"}
            or set(sample_successor.get("stratumFields", [])) != {
                "stratumCode", "populationCount", "selectedCount", "mismatchCount",
                "summaryDigest"}):
        issues.append("sample provider successor strata allowlist")
    strata_rules = sample_successor.get("strataRules", {})
    successor_bounds = sample_successor.get("bounds", {})
    successor_privacy = sample_successor.get("privacy", {})
    if (strata_rules.get("maximumItems") != 128
            or successor_bounds.get("maximumStrata") != 128
            or strata_rules.get("ordering") != "stratumCode-utf8-ascending"
            or strata_rules.get("duplicateCode") != "reject"
            or strata_rules.get("totals")
                != "sum-population-selected-mismatch-must-equal-result-totals"
            or strata_rules.get("summaryDigest")
                != "covers-canonical-ordered-complete-strata-array"
            or successor_privacy.get("studentPlaintext") != "forbidden"
            or successor_privacy.get("subjectOrWindowIdentifier") != "forbidden"
            or successor_privacy.get("rawFailureRows") != "forbidden"
            or successor_privacy.get("freeText") != "forbidden"):
        issues.append("sample provider successor strata totals")

    hrap_schemas = [
        load(root, HRAP_REQUEST_SCHEMA), load(root, HRAP_RECEIPT_SCHEMA),
        load(root, HRAP_TOKEN_SCHEMA), load(root, HRAP_LEASE_SCHEMA),
    ]
    if any(schema_item.get("$schema")
               != "https://json-schema.org/draft/2020-12/schema"
           or schema_item.get("type") != "object"
           or schema_item.get("additionalProperties") is not False
           or not set(schema_item.get("required", [])).issubset(
               set(schema_item.get("properties", {})))
           for schema_item in hrap_schemas):
        issues.append("closed HRAP request/receipt/token/lease schemas")
    lease_states = set(
        hrap_schemas[-1].get("properties", {}).get("state", {}).get("enum", []))
    if lease_states != {"issued", "reserved", "executed", "expired", "cancelled"}:
        issues.append("lease terminal state matrix")

    hrap_machine = load(root, HRAP_POLICY)
    recovery_actions = [
        item for item in hrap_machine.get("actions", [])
        if item.get("actionType") == "quality-fuse.recover"
    ]
    if (hrap_machine.get("policyVersion") != "HRAP-1.0.0"
            or hrap_machine.get("matrixVersion") != "HRAM-1.0.0"
            or recovery_actions != [{"actionType": "quality-fuse.recover", "level": "D4"}]
            or hrap_machine.get("levels", {}).get("D4") != {
                "mechanism": "maker-checker-all-required-checkers",
                "pendingMinutes": 240,
                "executionTokenMinutes": 15,
            }):
        issues.append("machine HRAP D4 quality-fuse.recover policy")
    hrap_runtime = load(root, HRAP_RUNTIME_POLICY)
    execution = hrap_runtime.get("executionAuthorization", {})
    if execution.get("commitBoundary") \
            != "ownerCommittedAt-strictly-before-authorizedUntil":
        issues.append("half-open authorized execution boundary")
    if execution.get("lateConfirmation") \
            != ("finalize-as-executed-when-ownerCommittedAt-strictly-before-"
                "authorizedUntil-even-if-confirmedAt-is-on-or-after-authorizedUntil"):
        issues.append("late confirmation finalizes pre-expiry commit")
    if (set(execution.get("leaseStates", []))
            != {"issued", "reserved", "executed", "expired", "cancelled"}
            or execution.get("oneExecutionJtiPerApproval") is not True
            or execution.get("issuer") != "identity-access"
            or execution.get("audienceRequired") is not True
            or execution.get("keyVersionRequired") is not True
            or execution.get("signatureRequired") is not True):
        issues.append("execution authorization lease lifecycle and trust binding")

    shell_successor = load(root, AUTHORIZED_SHELL_SUCCESSOR)
    action_schema = (shell_successor.get("$defs", {})
                     .get("actionCapability", {}))
    action_properties = action_schema.get("properties", {})
    if (shell_successor.get("$schema")
            != "https://json-schema.org/draft/2020-12/schema"
            or shell_successor.get("additionalProperties") is not False
            or shell_successor.get("properties", {}).get("schemaVersion")
                != {"const": "AUTHORIZED-SHELL-1.1.0"}
            or action_schema.get("additionalProperties") is not False
            or action_properties.get("actionType")
                != {"const": "quality-fuse.recover"}
            or set(action_schema.get("required", [])) != {"actionType", "state"}):
        issues.append("literal quality-fuse.recover action capability")
    action_contract = load(root, ACTION_CAPABILITY)
    if (action_contract.get("pageEntryCapability") != "quality-snapshots"
            or action_contract.get("actionCapability", {}).get("actionType")
                != "quality-fuse.recover"
            or action_contract.get("menuProjection")
                != "action-capability-never-creates-a-menu-item-or-route"):
        issues.append("action capability separate from quality page entry")
    if (action_contract.get("actionRevocationEffect")
            != ("abort-and-delete-recovery-command-query-preview-token-and-"
                "draft-only")
            or action_contract.get("pageReadEffect")
                != ("quality-snapshot-eligibility-and-recovery-task-read-caches-"
                    "remain-independent")):
        issues.append("action revoke preserves quality read caches")

    wire_schema_paths = [
        RECOVERY_WIRE_COMMON, RECOVERY_REQUEST_SCHEMA, RECOVERY_EVIDENCE_SCHEMA,
        RECOVERY_JOB_SCHEMA, RECOVERY_CHECKPOINT_SCHEMA, RECOVERY_RESULT_SCHEMA,
        RECOVERY_PREVIEW_SCHEMA, RECOVERY_EVENT_SCHEMA, RECOVERY_PROJECTION_SCHEMA,
    ]
    wire_schemas = {path: load(root, path) for path in wire_schema_paths}
    closed_wire = all(
        document.get("$schema") == "https://json-schema.org/draft/2020-12/schema"
        and (path == RECOVERY_WIRE_COMMON
             or document.get("additionalProperties") is False)
        for path, document in wire_schemas.items())
    if not closed_wire:
        issues.append("closed recovery request evidence job preview event schemas")
    event_schema = wire_schemas[RECOVERY_EVENT_SCHEMA]
    if (event_schema.get("properties", {}).get("priorState") != {"const": "fused"}
            or event_schema.get("properties", {}).get("targetState")
                != {"const": "recovering"}
            or event_schema.get("properties", {}).get("eventType")
                != {"const": "quality-eligibility.recovering"}
            or set(event_schema.get("x-forbiddenBusinessObjects", []))
                != {"RuleEvaluation", "Candidate", "Clue"}):
        issues.append("recovery event fused to recovering only")
    projection = wire_schemas[RECOVERY_PROJECTION_SCHEMA]
    forbidden_projection_fields = {
        "studentRef", "subjectRef", "makerPrincipal", "checkerPrincipal",
        "receipt", "token", "lease", "freeText",
    }
    if (forbidden_projection_fields
            & set(projection.get("properties", {}))
            or set(projection.get("x-forbidden", [])) != forbidden_projection_fields
            or projection.get("additionalProperties") is not False):
        issues.append("recovery projection PII allowlist")
    error_profile = load(root, RECOVERY_ERROR_PROFILE)
    if (error_profile.get("externalBodyHandling") != "discard-never-reflect"
            or error_profile.get("freeText") != "forbidden"
            or set(error_profile.get("responseFields", []))
                != {"code", "message", "traceId", "fieldErrors",
                    "currentVersion", "retryable"}
            or error_profile.get("concealment")
                != "authorization-denial-equals-object-not-found"):
        issues.append("recovery error privacy profile")
    recovery_retention = load(root, RECOVERY_RETENTION)
    retention_owners = recovery_retention.get("owners", {})
    if (retention_owners.get("identityAccess", {}).get("cleanupOwner")
            != "identity-access-retention-workload"
            or retention_owners.get("ingestionQuality", {}).get("cleanupOwner")
                != "ingestion-quality-retention-workload"
            or retention_owners.get("identityAccess", {}).get("legalHoldAware") is not True
            or retention_owners.get("ingestionQuality", {}).get("legalHoldAware") is not True
            or recovery_retention.get("crossOwnerCleanup") != "forbidden"):
        issues.append("owner-local recovery wire retention")

    hrap_vectors = load(root, HRAP_LIFECYCLE_VECTORS)
    hrap_case_ids = {
        item.get("caseId") for item in hrap_vectors.get("cases", [])
        if isinstance(item, dict)
    }
    required_hrap_cases = {
        "distinct-maker-checker-approved-before-four-hours",
        "approval-exact-four-hour-expiry",
        "token-before-fifteen-minute-expiry-issues-lease",
        "token-exact-fifteen-minute-expiry-denied",
        "execution-same-key-same-digest-replays-original-lease",
        "execution-same-key-different-digest-conflict",
        "owner-commit-one-microsecond-before-expiry",
        "owner-commit-exact-expiry-rejected",
        "late-confirmation-finalizes-pre-expiry-owner-commit",
        "checker-binding-drift-after-owner-commit-compensates",
    }
    if not required_hrap_cases.issubset(hrap_case_ids):
        issues.append("HRAP lifecycle boundary and race vectors")
    hrap_lock = load(root, HRAP_LOCK)
    hrap_lock_files = {
        path for path in FILES
        if str(path).startswith("contracts/authorization/high-risk/")
        and path not in {HRAP_LOCK}
    }
    expected_hrap_lock = {
        str(path): raw_digest(root / path)
        for path in sorted(hrap_lock_files, key=str)
    }
    if hrap_lock.get("files") != expected_hrap_lock:
        issues.append("high-risk authorization raw-byte lock")

    architecture = load(root, ARCHITECTURE_CONTRACT)
    ownership = architecture.get("ownership", {})
    if (ownership.get("approvalReceiptTokenOwner") != "identity-access"
            or ownership.get("recoveryRequestEvidenceJobPreviewOwner") != "ingestion-quality"
            or ownership.get("qualityEligibilityFuseEpisodeRecoveryTaskOwner")
                != "ingestion-quality"):
        issues.append("identity-access HRAP owner and ingestion-quality recovery owner")
    ports = architecture.get("publicPorts", {})
    if (ports.get("recoverySampleRecomputeProviderPort")
            != "signal-evaluation/api/RecoverySampleRecomputeProviderPort"
            or ports.get("recoverySampleOwnerPort")
                != "ingestion-quality/application/RecoverySampleRecomputePort"
            or ports.get("qualityEligibilityRecoveryCommandPort")
                != "ingestion-quality/application/QualityEligibilityRecoveryCommandPort"):
        issues.append("sample public API and single recovery command owner port")
    d4 = architecture.get("d4", {})
    if (d4.get("actionType") != "quality-fuse.recover"
            or d4.get("level") != "D4"
            or d4.get("states") != ["pending", "approved", "rejected", "expired", "cancelled"]
            or d4.get("pendingMinutes") != 240
            or d4.get("executionTokenMinutes") != 15
            or d4.get("differentNaturalPersons") is not True
            or d4.get("clientSuppliedApprovalEvidence") != "forbidden"):
        issues.append("D4 lifecycle 4h/15m and maker-checker separation")
    atomic = architecture.get("atomicExecution", {})
    required_protocol = {
        "identity-access-atomically-redeems-single-use-token-into-durable-bound-lease-and-execution-jti",
        "same-idempotency-key-replays-the-same-lease-until-expiry-never-issues-a-second-lease",
        "ingestion-quality-verifies-trusted-lease-and-current-owner-local-authorization-generation",
        "ingestion-quality-atomically-consumes-unique-execution-jti-and-commits-fused-to-recovering-facts-audit-event-outbox-idempotency",
        "committed-outbox-idempotently-finalizes-lease-as-executed-in-identity-access",
    }
    if (atomic.get("mode") != "durable-cross-owner-execution-authorization-lease"
            or atomic.get("tokenConsumptionOwner") != "identity-access"
            or set(atomic.get("protocol", [])) != required_protocol
            or atomic.get("networkIoInsideTransaction") is not False
            or atomic.get("compensation")
                != "never-reactivate-token-or-lease;reconcile-from-ingestion-quality-outbox"):
        issues.append("durable cross-owner execution authorization lease")
    phases = architecture.get("twoPhaseRecovery", {})
    if (phases.get("story25bTargetState") != "recovering"
            or phases.get("story25cTargetState") != "eligible"
            or phases.get("story25bTokenReusableAfterObservation") is not False
            or phases.get("story25cRequiresFreshExecutionConfirmation") is not True):
        issues.append("2.5b recovering / 2.5c eligible two-phase boundary")
    sample = architecture.get("sampleRecompute", {})
    if (sample.get("providerNotInstalled") != "durable-failure-remain-fused"
            or sample.get("providerUnavailable")
                != "durable-retryable-failure-remain-fused"):
        issues.append("provider-not-installed fail closed")
    allowed_sample_fields = {
        "providerVersion", "selectionSeed", "populationCount", "selectedCount",
        "strataSummaryDigest", "expectedDigest", "actualDigest", "mismatchCount",
        "completedAt", "traceId",
    }
    if (set(sample.get("resultFields", [])) != allowed_sample_fields
            or sample.get("studentPlaintext") != "forbidden"
            or sample.get("createsRuleEvaluationCandidateOrClue") is not False):
        issues.append("sample summary PII allowlist")
    impact = architecture.get("impactPreview", {})
    if (impact.get("authorizesExecution") is not False
            or impact.get("drift") != "invalidate-and-require-explicit-repreview"):
        issues.append("preview never authorizes execution")
    retention = architecture.get("retention", {})
    ia_retention = retention.get("identityAccess", {})
    iq_retention = retention.get("ingestionQuality", {})
    if (ia_retention.get("cleanupOwner") != "identity-access-retention-workload"
            or iq_retention.get("cleanupOwner")
                != "ingestion-quality-retention-workload"
            or "execution-authorization-lease" not in ia_retention.get("facts", [])
            or "recovery-request" not in iq_retention.get("facts", [])
            or ia_retention.get("legalHoldAware") is not True
            or iq_retention.get("legalHoldAware") is not True):
        issues.append("owner-local HRAP and recovery retention")
    concurrency = architecture.get("databaseConcurrency", {})
    if (concurrency.get("commonScope") != "sourceId"
            or concurrency.get("firstLock")
                != ("pg_advisory_xact_lock(hashtextextended("
                    "'quality-eligibility:'||sourceId,0))")
            or concurrency.get("predecessorBytes") != "V15-and-V16-unchanged"
            or set(concurrency.get("doesNotReplace", []))
                != {"aggregate-version-CAS", "lock-inside-reread",
                    "deterministic-row-order"}):
        issues.append("common source advisory lock before every recovery row lock")

    derivation = load(root, DERIVATION)
    conflict_resolutions = derivation.get("conflictResolutions", [])
    selected_lease_resolution = (
        "identity-access atomically redeems the token into one durable bound "
        "execution-authorization lease and executionJti; ingestion-quality "
        "atomically consumes that JTI in its owner transaction, confirms through "
        "outbox, never reactivates an expired or cancelled token/lease, and "
        "reconciles a revocation race back to fused before any downstream business "
        "consumption")
    if (derivation.get("matrixVersion") != "QRDM-1.0.0"
            or len(derivation.get("conflictResolutions", [])) != 4
            or derivation.get("unresolvedSemantics") != []):
        issues.append("authority/conflict matrix fully resolved")
    if (not any(item.get("resolution") == selected_lease_resolution
                for item in conflict_resolutions if isinstance(item, dict))):
        issues.append("derivation matrix durable lease protocol")

    baseline = load(root, BASELINE)
    overlay = load(root, BASELINE_OVERLAY)
    overlay_files = overlay.get("files", [])
    overlay_paths = [item.get("path") for item in overlay_files
                     if isinstance(item, dict)]
    aggregate = hashlib.sha256()
    valid_overlay_files = True
    for item in overlay_files:
        if (not isinstance(item, dict)
                or set(item) != {"path", "blobSha1", "rawSha256"}
                or not isinstance(item.get("path"), str)
                or not isinstance(item.get("blobSha1"), str)
                or len(item["blobSha1"]) != 40
                or not isinstance(item.get("rawSha256"), str)
                or len(item["rawSha256"]) != 64):
            valid_overlay_files = False
            continue
        aggregate.update(item["path"].encode("utf-8"))
        aggregate.update(b"\0")
        aggregate.update(item["rawSha256"].encode("ascii"))
        aggregate.update(b"\n")
    final_candidate = baseline.get("finalPredecessorOverlayCandidate", {})
    if (overlay.get("manifestVersion") != "QRPREDECESSOR-OVERLAY-1.0.0"
            or overlay.get("parentCommit")
                != "017997ae83d12f048f8d192e371d44fa32233550"
            or overlay.get("candidateCommit")
                != "08b2f121e0922de8ae057e1c4987443804ae2b38"
            or overlay.get("candidateTree")
                != "81e666ecff0547dde47e63d05268037775516196"
            or overlay.get("pathCount") != 192
            or len(overlay_files) != 192
            or len(set(overlay_paths)) != 192
            or overlay_paths != sorted(overlay_paths)
            or not valid_overlay_files
            or overlay.get("pathRawSha256Aggregate") != aggregate.hexdigest()
            or final_candidate.get("commit") != overlay.get("candidateCommit")
            or final_candidate.get("tree") != overlay.get("candidateTree")
            or final_candidate.get("changedPathCount") != overlay.get("pathCount")
            or final_candidate.get("pathRawSha256Aggregate")
                != overlay.get("pathRawSha256Aggregate")):
        issues.append("complete Story 2.4/2.5a overlay manifest")
    git_directory = root / ".git"
    if git_directory.exists():
        import subprocess
        try:
            candidate = overlay["candidateCommit"]
            actual_tree = subprocess.check_output(
                ["git", "rev-parse", f"{candidate}^{{tree}}"], cwd=root,
                text=True, stderr=subprocess.DEVNULL).strip()
            actual_paths_raw = subprocess.check_output(
                ["git", "diff-tree", "--no-commit-id", "--name-only", "-r", "-z",
                 candidate], cwd=root, stderr=subprocess.DEVNULL)
            actual_paths = [value.decode("utf-8")
                            for value in actual_paths_raw.split(b"\0") if value]
            if actual_tree != overlay.get("candidateTree") \
                    or actual_paths != overlay_paths:
                issues.append("complete Story 2.4/2.5a overlay manifest")
            else:
                for item in overlay_files:
                    raw = subprocess.check_output(
                        ["git", "show", f"{candidate}:{item['path']}"], cwd=root,
                        stderr=subprocess.DEVNULL)
                    actual_blob = subprocess.check_output(
                        ["git", "rev-parse", f"{candidate}:{item['path']}"], cwd=root,
                        text=True, stderr=subprocess.DEVNULL).strip()
                    if (hashlib.sha256(raw).hexdigest() != item["rawSha256"]
                            or actual_blob != item["blobSha1"]):
                        issues.append("complete Story 2.4/2.5a overlay manifest")
                        break
        except (KeyError, OSError, subprocess.CalledProcessError):
            issues.append("materialized Story 2.4/2.5a overlay candidate available")

    successor = load(root, COMMAND_SUCCESSOR)
    predecessor_digest = raw_digest(root / COMMAND_PREDECESSOR)
    if (successor.get("predecessor", {}).get("sha256")
            != predecessor_digest.removeprefix("sha256:")
            or successor.get("ownerPort")
                != "ingestion-quality/quality-eligibility-recovery-command-v1"
            or successor.get("allowedTransition") != "fused-to-recovering"):
        issues.append("recovery command predecessor digest and single owner successor")
    forbidden = set(successor.get("forbiddenSideEffects", []))
    if not {"eligible", "production", "publish-rule-evaluation", "create-candidate", "create-clue"}.issubset(forbidden):
        issues.append("recovery command forbidden side effects")

    handoff = load(root, HANDOFF)
    if (handoff.get("stateAtHandoff") != "recovering"
            or handoff.get("consumerRequiresFreshExecutionConfirmation") is not True
            or handoff.get("producerTokenReusable") is not False
            or handoff.get("finalWindowRule")
                != "recoveryCompletedAt-less-than-or-equal-latestActionableAt"):
        issues.append("2.5c self-contained handoff and fresh confirmation")

    valid_cases = {
        item.get("caseId") for item in load(root, VALID).get("cases", [])
        if isinstance(item, dict)
    }
    required_valid = {
        "streaming-2-fails", "streaming-3-passes", "streaming-4-passes",
        "daily-1-fails", "daily-2-passes", "daily-3-passes",
        "population-99-all-passes", "population-101-selected-99-fails",
        "population-101-selected-100-passes", "sample-mismatch-1-fails",
        "backfill-lkg-later", "backfill-lookback-later",
        "quality-threshold-minus-one-fails", "quality-threshold-equal-passes",
        "quality-threshold-plus-one-passes", "preview-already-expired",
        "preview-current-and-survives-observation", "preview-expires-before-observation",
        "story25c-completed-equal-actionable",
        "story25c-completed-plus-1us-history-only",
    }
    if valid_cases != required_valid:
        issues.append("boundary vector matrix")
    negative_cases = {
        item.get("caseId") for item in load(root, NEGATIVE).get("cases", [])
        if isinstance(item, dict)
    }
    required_negative = {
        "unknown-policy-version", "unknown-policy-digest", "unknown-source-class",
        "unknown-sample-provider", "unknown-sample-summary-version", "unknown-action-type",
        "maker-equals-checker", "token-reuse", "preview-drift", "transport-order",
        "sample-pii", "bootstrap-rule",
    }
    if negative_cases != required_negative:
        issues.append("negative fixture matrix")

    lock = load(root, LOCK)
    expected_files = {str(path): raw_digest(root / path) for path in sorted(FILES, key=str)}
    expected_predecessors = {
        str(path): raw_digest(root / path) for path in sorted(PREDECESSORS, key=str)}
    if lock.get("files") != expected_files:
        issues.append("quality recovery contract lock mismatch")
    if lock.get("predecessors") != expected_predecessors:
        issues.append("quality recovery predecessor lock mismatch")
    if lock.get("runtimeEvidence") != {
        "qualityRecoveryContracts": "contract-only",
        "sampleProvider": "none",
        "hrapRuntimeOwner": "none",
        "fusedToRecoveringPositiveClosure": "none",
        "story25cEligible": "none",
        "story32Consumer": "none",
        "story55FinalApply": "none",
    }:
        issues.append("Task 0 runtime evidence boundary")
    successor_lock = load(root, LOCK_SUCCESSOR)
    if (successor_lock.get("lockVersion")
            != "QUALITY-RECOVERY-CONTRACT-LOCK-1.1.0"
            or successor_lock.get("predecessorLock") != {
                "path": str(LOCK),
                "rawSha256": raw_digest(root / LOCK),
            }
            or successor_lock.get("successorFiles") != {
                str(SAMPLE_PROVIDER_SUCCESSOR): raw_digest(
                    root / SAMPLE_PROVIDER_SUCCESSOR),
            }):
        issues.append("quality recovery additive successor lock mismatch")
    result_successor = load(root, RECOVERY_RESULT_SUCCESSOR)
    evidence_successor = load(root, RECOVERY_EVIDENCE_SUCCESSOR)
    readiness = result_successor.get("$defs", {}).get("readinessEvidence", {})
    result_required = set(result_successor.get("required", []))
    evidence_required = set(evidence_successor.get("required", []))
    if (result_successor.get("additionalProperties") is not False
            or result_successor.get("properties", {}).get("schemaVersion", {}).get("const")
                != "QUALITY-RECOVERY-VALIDATION-RESULT-1.1.0"
            or result_successor.get("x-predecessor")
                != "quality-recovery-validation-result.schema.json"
            or not {"strata", "expectedDigest", "actualDigest", "readinessEvidence"}
                <= result_required
            or readiness.get("additionalProperties") is not False
            or not {"eligibilityBindings", "currentBindingDigest", "qualityEvidence",
                    "batchEvidence", "backfillEvidence", "requiredMembersEligible",
                    "missingEvidenceCodes"} <= set(readiness.get("required", []))
            or result_successor.get("properties", {}).get(
                "runtimeEvidenceClaim", {}).get("const") != "installed-and-verified"):
        issues.append("validation result executable readiness successor")
    if (evidence_successor.get("additionalProperties") is not False
            or evidence_successor.get("properties", {}).get("schemaVersion", {}).get("const")
                != "QUALITY-RECOVERY-EVIDENCE-PACK-1.1.0"
            or evidence_successor.get("x-predecessor")
                != "quality-recovery-evidence-pack.schema.json"
            or not {"object", "qualityEvidence", "batchEvidence", "backfillEvidence",
                    "reconciliationEvidence", "sampleEvidence", "validationResultDigest"}
                <= evidence_required
            or evidence_successor.get("$defs", {}).get(
                "recoveryTaskBinding", {}).get("additionalProperties") is not False
            or "eligibilityBindings" not in evidence_successor.get(
                "$defs", {}).get("recoveryTaskBinding", {}).get("required", [])
            or evidence_successor.get("properties", {}).get(
                "runtimeEvidenceClaim", {}).get("const") != "installed-and-verified"):
        issues.append("multi-RuleVersion self-contained evidence successor")
    runtime_lock = load(root, LOCK_RUNTIME_SUCCESSOR)
    if (runtime_lock.get("lockVersion")
            != "QUALITY-RECOVERY-CONTRACT-LOCK-1.2.0"
            or runtime_lock.get("predecessorLock") != {
                "path": str(LOCK_SUCCESSOR),
                "rawSha256": raw_digest(root / LOCK_SUCCESSOR),
            }
            or runtime_lock.get("successorFiles") != {
                str(RECOVERY_RESULT_SUCCESSOR): raw_digest(
                    root / RECOVERY_RESULT_SUCCESSOR),
                str(RECOVERY_EVIDENCE_SUCCESSOR): raw_digest(
                    root / RECOVERY_EVIDENCE_SUCCESSOR),
            }):
        issues.append("quality recovery runtime successor lock mismatch")
    return issues


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    issues = check(root)
    if issues:
        for issue in issues:
            print(f"QUALITY_RECOVERY_CONTRACT: {issue}")
        return 1
    print("QUALITY_RECOVERY_CONTRACT: QRP, D4 owner, sample boundary, two-phase handoff and predecessor locks valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
