#!/usr/bin/env python3
"""Fail-closed checker for Story 2.5c observation/finalization contracts."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from typing import Any


BASE = Path("contracts/ingestion-quality/quality-finalization")
OBSERVATION_POLICY = BASE / "recovery-observation-policy-1.0.0.json"
OBSERVATION_FACT = BASE / "recovery-observation-fact.schema.json"
OBSERVATION_DECISION = BASE / "recovery-observation-decision.schema.json"
OBSERVATION_QUERY = BASE / "recovery-observation-query.schema.json"
FINAL_COMMAND = BASE / "recovery-finalization-command.schema.json"
FINAL_RESULT = BASE / "recovery-finalization-result.schema.json"
FINAL_EVENT = BASE / "quality-finalization-event.schema.json"
ERROR_PROFILE = BASE / "recovery-finalization-error-profile-1.0.0.json"
RETENTION = BASE / "recovery-finalization-retention-1.0.0.json"
ARCHITECTURE = BASE / "quality-finalization-architecture-1.0.0.json"
TASK_CLOSE = BASE / "quality-recovery-task-close-1.0.0.json"
VALID = BASE / "fixtures/valid/recovery-finalization-state-vectors-1.0.0.json"
NEGATIVE = BASE / "fixtures/invalid/recovery-finalization-negative-fixtures-1.0.0.json"
HRAP_SUCCESSOR = Path(
    "contracts/authorization/high-risk/high-risk-quality-finalization-1.0.0.json")
PIC_SUCCESSOR = Path("contracts/public-integration/pic-1.2.0.json")
OPENAPI_SUCCESSOR = Path("contracts/openapi/quality-recovery-tasks-1.2.openapi.json")
LOCK = BASE / "quality-finalization-contract-lock-1.0.0.json"

HANDOFF = Path(
    "contracts/ingestion-quality/quality-recovery/quality-recovery-handoff-2.5c-1.0.0.json")
QRP = Path(
    "contracts/ingestion-quality/quality-recovery/quality-recovery-policy-1.0.0.json")
RECOVERY_LOCK = Path(
    "contracts/ingestion-quality/quality-recovery/quality-recovery-contract-lock-1.2.0.json")
HRAP_POLICY = Path("contracts/authorization/high-risk/high-risk-action-policy-1.0.0.json")
HRAP_RUNTIME = Path("contracts/authorization/high-risk/high-risk-runtime-policy-1.0.0.json")
PIC_V1 = Path("contracts/public-integration/pic-1.0.0.json")
PIC_V11 = Path("contracts/public-integration/pic-1.1.0.json")
V15 = Path(
    "backend/src/main/resources/db/migration/ingestion-quality/V000015__ingestion-quality__quality_eligibility_v1.sql")
V16 = Path(
    "backend/src/main/resources/db/migration/ingestion-quality/V000016__ingestion-quality__quality_fuse_task_v1.sql")
V20 = Path(
    "backend/src/main/resources/db/migration/ingestion-quality/V000020__ingestion-quality__quality_recovery_v1.sql")

SUCCESSORS = (
    HRAP_SUCCESSOR, NEGATIVE, VALID, ARCHITECTURE, FINAL_EVENT, TASK_CLOSE,
    FINAL_COMMAND, ERROR_PROFILE, FINAL_RESULT, RETENTION, OBSERVATION_DECISION,
    OBSERVATION_FACT, OBSERVATION_POLICY, OBSERVATION_QUERY, OPENAPI_SUCCESSOR,
    PIC_SUCCESSOR,
)
PREDECESSORS = (
    V15, V16, V20, HRAP_POLICY, HRAP_RUNTIME, RECOVERY_LOCK, HANDOFF, QRP,
    PIC_V1, PIC_V11,
)
COPY_PATHS = (*SUCCESSORS, *PREDECESSORS, LOCK)


def load(root: Path, relative: Path) -> Any:
    return json.loads((root / relative).read_text(encoding="utf-8"))


def raw_digest(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def check(root: Path) -> list[str]:
    issues: list[str] = []
    for relative in COPY_PATHS:
        if not (root / relative).is_file():
            issues.append(f"missing {relative}")
    if issues:
        return issues

    policy = load(root, OBSERVATION_POLICY)
    if (policy.get("policyVersion") != "QOFP-1.0.0"
            or policy.get("owner") != "ingestion-quality"
            or policy.get("clock") != {
                "start": "owner-committed-recoveringStartedAt",
                "now": "trusted-server-utc",
                "precision": "microsecond",
                "durationBoundary": "inclusive",
            }):
        issues.append("owner-commit trusted microsecond observation clock")
    if policy.get("sourceClasses") != {
            "streaming": {
                "minimumConsecutivePassedBatches": 3,
                "observationDuration": "PT60M",
            },
            "dailyBatch": {
                "minimumConsecutivePassedBatches": 2,
                "observationDuration": "P1D",
                "acceptedWireAliases": ["PT24H"],
            },
    }:
        issues.append("exact streaming and daily observation boundaries")
    qualification = policy.get("batchQualification", {})
    if (qualification.get("acceptedPair")
            != "strict-assessed-passed-then-exact-published"
            or qualification.get("sequenceKey")
                != ["sourceId", "sourceVersionOrdinal", "lineageRevision"]
            or set(qualification.get("forbiddenOrderingFields", []))
                != {"eventId", "occurredAt", "watermark", "batchId"}):
        issues.append("strict business sequence and assessed-published pairing")
    gaps = policy.get("absenceAndGap", {})
    if (gaps.get("noData") != "not-pass"
            or gaps.get("providerUnavailable") != "retry-without-relapse"
            or gaps.get("verifiedQualityFailure") != "business-relapse"
            or any(gaps.get(key) != "not-pass-and-fail-closed"
                   for key in ("sequenceGap", "poison", "staleSlo", "unknownProvider"))):
        issues.append("no-data gap poison stale and provider failure semantics")
    if (policy.get("candidateGate") != [
            "same-recovery-and-generation",
            "all-affected-eligibilities-current-and-recovering",
            "current-policy-member-watermark-and-owner-bindings",
            "complete-duration-and-consecutive-batches",
            "zero-verified-quality-failure",
    ] or policy.get("relapse", {}).get("afterTerminalClose")
            != "generation-plus-one-new-episode-and-task-never-reopen"):
        issues.append("all-recovering gate and generation-plus-one relapse")

    schemas = [
        load(root, path) for path in (
            OBSERVATION_FACT, OBSERVATION_DECISION, OBSERVATION_QUERY,
            FINAL_COMMAND, FINAL_RESULT, FINAL_EVENT)
    ]
    if any(schema.get("$schema")
               != "https://json-schema.org/draft/2020-12/schema"
           or schema.get("type") != "object"
           or schema.get("additionalProperties") is not False
           or not set(schema.get("required", [])).issubset(
               set(schema.get("properties", {})))
           for schema in schemas):
        issues.append("closed observation command query result and event schemas")
    command = schemas[3]
    if set(command.get("properties", {})) != {
            "recoveryId", "expectedRecoveryVersion", "expectedTaskVersion",
            "finalObservationWatermark", "idempotencyKey",
    } or set(command.get("x-serverResolved", [])) != {
            "actor", "naturalPerson", "roles", "ownerBinding", "trustedNow",
            "policy", "members", "observationDecision", "approvalReceipt",
            "executionLease", "executionJti",
    }:
        issues.append("opaque-only final command and server-resolved trust")

    hrap = load(root, HRAP_SUCCESSOR)
    if (hrap.get("exactBinding") != {
            "actionType": "quality-fuse.recover",
            "objectType": "RECOVERY_TASK",
            "currentState": "recovering",
            "targetState": "eligible",
    } or hrap.get("approval", {}).get("level") != "D4"
            or hrap.get("approval", {}).get("pendingMinutes") != 240
            or hrap.get("executionConfirmation", {}).get("leaseMinutes") != 15
            or hrap.get("executionConfirmation", {}).get("singleUseJti") is not True
            or hrap.get("predecessorApprovalOrTokenReusable") is not False
            or hrap.get("clientSuppliedTrustedEvidence") != "forbidden"):
        issues.append("fresh exact recovering-to-eligible D4 binding")

    architecture = load(root, ARCHITECTURE)
    expected_lock_order = [
        "source-advisory-lock", "final-idempotency",
        "rule-version-and-member-facts", "episode-and-task",
        "eligibilities-by-id", "observation-decision",
        "approval-and-execution-jti",
    ]
    if (architecture.get("owner") != "ingestion-quality"
            or architecture.get("locking", {}).get("order") != expected_lock_order
            or architecture.get("locking", {}).get("winner")
                != "verified-failure-or-new-policy"
            or architecture.get("ownerTransaction", {}).get("networkIo") is not False
            or architecture.get("ownerTransaction", {}).get("allOrNothing") is not True
            or architecture.get("ownerTransaction", {}).get("partialTerminalState")
                != "forbidden"
            or architecture.get("unresolvedSemantics") != []):
        issues.append("single-owner lock order failure-wins atomic transaction")
    idempotency = architecture.get("idempotency", {})
    if (idempotency.get("key") != ["recoveryId", "finalObservationWatermark"]
            or idempotency.get("sameKeySameDigest")
                != "return-original-committed-result"
            or idempotency.get("sameKeyDifferentDigest") != "conflict"):
        issues.append("final watermark idempotency and canonical digest")

    pic = load(root, PIC_SUCCESSOR)
    close = load(root, TASK_CLOSE)
    if (pic.get("version") != "PIC-1.2.0"
            or pic.get("predecessor", {}).get("sha256")
                != raw_digest(root / PIC_V11).removeprefix("sha256:")
            or pic.get("qualityTask", {}).get("operations")
                != ["create", "update", "close"]
            or pic.get("qualityTask", {}).get("runtimeEvidenceClaim") != "none"):
        issues.append("additive PIC same-task close with deferred target")
    if (close.get("identity") != "same-task-id-work-item-key-and-generation"
            or close.get("closedState") != "terminal-never-reopen"
            or close.get("relapseWithinP1D")
                != "generation-plus-one-new-episode-and-task"
            or close.get("deliverySemantics")
                != "independent-transport-sidecar-never-rolls-back-eligible-or-local-close"):
        issues.append("same task close and transport-orthogonal relapse")

    openapi = load(root, OPENAPI_SUCCESSOR)
    expected_paths = {
        "/quality-recovery-tasks/{taskId}/observation",
        "/quality-recovery-requests/{requestId}/final-approval-requests",
        "/quality-recovery-requests/{requestId}/final-approval-decisions",
        "/quality-recovery-requests/{requestId}/finalize",
    }
    final_command = openapi.get("components", {}).get("schemas", {}).get(
        "FinalCommand", {})
    openapi_observation = openapi.get("components", {}).get("schemas", {}).get(
        "Observation", {})
    query_observation = schemas[2]
    if (openapi.get("openapi") != "3.1.2"
            or openapi.get("info", {}).get("version") != "1.2.0"
            or openapi.get("x-predecessor") != {
                "path": "contracts/openapi/quality-recovery-tasks-1.1.openapi.json",
                "sha256": "659628eef04202936c00c0c131997547766447a5239ceb34d80766936299a1dc",
                "compatibility": "additive-minor",
            }
            or set(openapi.get("paths", {})) != expected_paths
            or set(final_command.get("properties", {})) != {
                "expectedRecoveryVersion", "expectedTaskVersion",
                "finalObservationWatermark",
            }
            or final_command.get("additionalProperties") is not False
            or not {"trustedNow", "approvalReceipt", "executionLease", "executionJti"}
                .issubset(set(final_command.get("x-server-resolved", [])))
            or set(query_observation.get("required", []))
                != set(openapi_observation.get("required", []))
            or set(query_observation.get("properties", {}))
                != set(openapi_observation.get("properties", {}))
            or query_observation.get("properties", {}).get("policyVersion", {}).get(
                "const") != "QRP-1.0.0"
            or not {"failedMembers", "traceId"}.issubset(
                set(query_observation.get("properties", {})))):
        issues.append("strict additive observation and finalization OpenAPI successor")

    valid_ids = {case.get("caseId") for case in load(root, VALID).get("cases", [])}
    negative_ids = {case.get("caseId") for case in load(root, NEGATIVE).get("cases", [])}
    if len(valid_ids) != 12 or not {
            "streaming-three-batches-at-sixty-minutes-ready",
            "daily-two-batches-at-one-day-ready",
            "duration-minus-one-microsecond-not-ready",
            "window-equality-handoff", "window-plus-one-microsecond-history-only",
            "same-key-same-digest-replay",
            "closed-task-relapse-creates-next-generation",
    }.issubset(valid_ids):
        issues.append("finalization boundary and replay vector matrix")
    if len(negative_ids) != 15 or not {
            "no-data-is-not-pass", "business-sequence-gap", "poisoned-pair",
            "stale-slo", "unknown-provider",
            "provider-outage-is-not-business-relapse", "predecessor-token-reuse",
            "same-key-different-digest", "task-reopen", "partial-terminal-write",
    }.issubset(negative_ids):
        issues.append("finalization negative fixture matrix")

    retention = load(root, RETENTION)
    if (retention.get("owner") != "ingestion-quality-retention-workload"
            or retention.get("evidence", {}).get("retention") != "P2Y"
            or retention.get("idempotency", {}).get("retention") != "P90D"
            or retention.get("legalHoldAware") is not True
            or retention.get("scheduler")
                != "iq_cleanup_quality_finalization_expired"):
        issues.append("owner-local finalization retention and executable scheduler")
    error = load(root, ERROR_PROFILE)
    if (error.get("authorizationDenial") != "concealed-404"
            or error.get("externalBodyHandling") != "discard-never-reflect"
            or error.get("freeText") != "forbidden"
            or error.get("internalVersionsAndLocks") != "never-reflect"):
        issues.append("closed private finalization error profile")

    lock = load(root, LOCK)
    expected_successors = {
        str(path): raw_digest(root / path) for path in sorted(SUCCESSORS, key=str)}
    expected_predecessors = {
        str(path): raw_digest(root / path) for path in sorted(PREDECESSORS, key=str)}
    if (lock.get("lockVersion") != "QUALITY-FINALIZATION-CONTRACT-LOCK-1.0.0"
            or lock.get("successorFiles") != expected_successors):
        issues.append("quality finalization successor digest lock")
    if lock.get("predecessors") != expected_predecessors:
        issues.append("quality finalization predecessor digest lock")
    if lock.get("runtimeEvidence") != {
            "observationAndFinalization": "contract-only",
            "story32Consumer": "none",
            "story55PublicTaskApply": "none",
            "story27cNfr8Final": "none",
            "productionDuration": "none",
    }:
        issues.append("quality finalization evidence boundary")
    return issues


def main() -> int:
    issues = check(Path("."))
    if issues:
        for issue in issues:
            print(f"QUALITY_FINALIZATION_CONTRACT: FAIL: {issue}")
        return 1
    print("QUALITY_FINALIZATION_CONTRACT: observation, fresh D4, owner transaction and same-task close valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
