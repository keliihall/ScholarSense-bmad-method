#!/usr/bin/env python3
"""Run deterministic local PIC conformance and emit privacy-safe evidence."""

from __future__ import annotations

import argparse
import copy
import dataclasses
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Any, Mapping


ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/public-integration/pic-1.0.0.json"
PROFILE = ROOT / "contracts/public-integration/public-integration-runtime-profile-1.0.0.json"
SCENARIOS = ROOT / "contracts/public-integration/public-integration-target-scenarios-1.0.0.json"
EVIDENCE_SCHEMA = ROOT / "contracts/public-integration/public-integration-evidence.schema.json"
HEX40 = re.compile(r"^[0-9a-f]{40}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
REQUIRED_GATE_IDS = frozenset({
    "contract-package",
    "reference-adapters",
    "postgresql-atomicity",
})
SCENARIO_GATE_BINDINGS = {
    "contract-auth": frozenset({"contract-package", "reference-adapters"}),
    "create": frozenset({"reference-adapters", "postgresql-atomicity"}),
    "update": frozenset({"reference-adapters", "postgresql-atomicity"}),
    "close": frozenset({"reference-adapters", "postgresql-atomicity"}),
    "revoke": frozenset({"reference-adapters", "postgresql-atomicity"}),
    "transient-retry": frozenset({"reference-adapters", "postgresql-atomicity"}),
    "duplicate-idempotency": frozenset({"reference-adapters", "postgresql-atomicity"}),
    "out-of-order": frozenset({"contract-package", "postgresql-atomicity"}),
    "dual-id-route-watermark-reconcile": frozenset({
        "contract-package", "postgresql-atomicity"
    }),
    "slo-five-minute-budget": frozenset({"contract-package", "reference-adapters"}),
}


@dataclasses.dataclass(frozen=True)
class ExecutableGateResult:
    gate_id: str
    command: tuple[str, ...]
    exit_code: int
    output: bytes

    @property
    def passed(self) -> bool:
        return self.exit_code == 0

    @property
    def output_digest(self) -> str:
        return sha256_bytes(self.output)


@dataclasses.dataclass(frozen=True)
class LocalScenarioResult:
    scenario_results: list[dict[str, Any]]
    failed_count: int
    skipped_count: int
    synthetic_created: int
    synthetic_closed: int
    synthetic_revoked: int
    reference_route_watermark_to: int
    final_external_state: str
    internal_invariants: dict[str, bool]


def canonical_json_bytes(document: Any) -> bytes:
    return json.dumps(
        document,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def file_sha256(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def _digest_token(label: str) -> str:
    return sha256_bytes(("PIC-SYNTHETIC-" + label).encode())


def run_local_scenarios(
    *,
    gate_results: Mapping[str, ExecutableGateResult],
) -> LocalScenarioResult:
    """Exercise the locked vector plus lifecycle/cutover invariants in memory.

    The real transaction, fencing, and HTTP layers are independently exercised by
    the Java/PostgreSQL gates invoked by this runner. This model binds their
    expected cross-scenario lifecycle without creating any domain object.
    """
    locked = json.loads(SCENARIOS.read_text(encoding="utf-8"))
    if set(gate_results) != REQUIRED_GATE_IDS or any(
        gate_id != result.gate_id
        for gate_id, result in gate_results.items()
    ):
        raise ValueError("PIC_LOCAL_EXECUTABLE_GATE_VECTOR_INVALID")
    engine = _LocalConformanceEngine()
    scenario_results: list[dict[str, Any]] = []
    for scenario in locked["requiredScenarios"]:
        scenario_id = scenario["id"]
        expected = scenario["sideEffectExpected"]
        execution = engine.execute(scenario_id)
        required_gates = SCENARIO_GATE_BINDINGS[scenario_id]
        gates_passed = all(gate_results[gate_id].passed for gate_id in required_gates)
        status = "pass" if (
            gates_passed
            and execution["sideEffectCount"] == (1 if expected else 0)
            and execution["assertionPassed"]
        ) else "fail"
        observation = "|".join(
            gate_results[gate_id].output_digest
            for gate_id in sorted(required_gates)
        )
        item: dict[str, Any] = {
            "id": scenario_id,
            "status": status,
            "receiptOrChallengeDigest": sha256_bytes(
                (scenario_id + "|" + observation).encode("ascii")
            ),
            "sideEffectExpected": expected,
            "sideEffectCount": execution["sideEffectCount"],
        }
        if execution.get("externalReferenceDigest") is not None:
            item["externalReferenceDigest"] = execution["externalReferenceDigest"]
            item["newObjectCount"] = execution["newObjectCount"]
        elif scenario_id == "duplicate-idempotency":
            item["originalExternalReferenceDigest"] = engine.external_task_one
            item["newObjectCount"] = 0
        scenario_results.append(item)

    reference_gate = gate_results["reference-adapters"].passed
    postgres_gate = gate_results["postgresql-atomicity"].passed
    contract_gate = gate_results["contract-package"].passed
    invariants = {
        "sameExternalTaskReference": (
            reference_gate and postgres_gate and engine.same_task_reference_observed
        ),
        "twoIntentMappingsNoThirdOnReplay": (
            reference_gate and engine.intent_replay_observed
        ),
        "routeGapRecoveredInOrder": (
            contract_gate and postgres_gate and engine.route_gap_recovered
        ),
        "sparseSourceVersionsAccepted": (
            contract_gate and engine.sparse_source_versions_observed
        ),
        "terminalLateConfirmFenced": (
            postgres_gate and engine.terminal_late_confirm_fenced
        ),
        "terminalDuringDrainingAbortsCutover": (
            postgres_gate and engine.terminal_during_draining_aborted
        ),
        "sameMajorCutoverNoLoss": (
            postgres_gate and engine.same_major_cutover_no_loss
        ),
        "preSwitchAbortAllowed": (
            postgres_gate and engine.pre_switch_abort_observed
        ),
        "postEffectRollbackFailClosed": (
            postgres_gate and engine.post_effect_rollback_blocked
        ),
        "crossMajorZeroProviderCalls": (
            contract_gate and engine.cross_major_attempt_rejected
        ),
        "mappingMismatchOrphanReconciled": (
            postgres_gate and engine.mapping_mismatch_reconciled
        ),
        "sloBoundaryExact": (
            contract_gate and reference_gate and engine.slo_boundary_observed
        ),
    }
    if all(result.passed for result in gate_results.values()) and not all(
        invariants.values()
    ):
        raise AssertionError("PIC_LOCAL_MODEL_INVARIANT_FAILED")
    return LocalScenarioResult(
        scenario_results=scenario_results,
        failed_count=sum(item["status"] != "pass" for item in scenario_results),
        skipped_count=0,
        synthetic_created=engine.synthetic_created,
        synthetic_closed=engine.synthetic_closed,
        synthetic_revoked=engine.synthetic_revoked,
        reference_route_watermark_to=engine.reference_route_watermark,
        final_external_state=engine.final_external_state,
        internal_invariants=invariants,
    )


class _LocalConformanceEngine:
    def __init__(self) -> None:
        self.external_task_one = _digest_token("external-task-one")
        self.external_task_two = _digest_token("external-task-two")
        self.task_lineage: dict[str, str] = {}
        self.intent_mappings: dict[str, str] = {}
        self.synthetic_created = 0
        self.synthetic_closed = 0
        self.synthetic_revoked = 0
        self.reference_route_watermark = 0
        self.final_external_state = "none"
        self.applied_route_sequence: list[int] = []
        self.applied_source_versions: list[int] = []
        self._pending_routes: dict[int, int] = {}
        self._next_route_sequence = 1
        self.terminal_fence_epoch = 0
        self.cutover_state = "active"
        self.held_generations: list[str] = []
        self.pre_switch_abort_observed = False
        self.post_effect_rollback_blocked = False
        self.cross_major_provider_calls = 0
        self.orphan_mappings: set[str] = {"orphan-generation"}
        self._effects: set[str] = set()
        self.same_task_reference_observed = False
        self.intent_replay_observed = False
        self.route_gap_recovered = False
        self.sparse_source_versions_observed = False
        self.terminal_late_confirm_fenced = False
        self.terminal_during_draining_aborted = False
        self.same_major_cutover_no_loss = False
        self.cross_major_attempt_rejected = False
        self.mapping_mismatch_reconciled = False
        self.slo_boundary_observed = False
        self._inflight_claim_fence: int | None = None

    def _record_effect(self, effect_key: str) -> int:
        before = len(self._effects)
        self._effects.add(effect_key)
        return len(self._effects) - before

    def _bind_task(self, work_item: str, proposed_reference: str) -> str:
        reference = self.task_lineage.setdefault(work_item, proposed_reference)
        self.same_task_reference_observed = (
            reference == proposed_reference and len(self.task_lineage) == 1
        )
        return reference

    def _record_intent(self, intent_id: str) -> str:
        return self.intent_mappings.setdefault(
            intent_id, _digest_token("notification-" + intent_id)
        )

    def _receive_route(self, sequence: int, source_version: int) -> str:
        if sequence < self._next_route_sequence:
            return "duplicate"
        if sequence > self._next_route_sequence:
            self._pending_routes[sequence] = source_version
            return "gap"
        self._apply_route(sequence, source_version)
        while self._next_route_sequence in self._pending_routes:
            pending_version = self._pending_routes.pop(self._next_route_sequence)
            self._apply_route(self._next_route_sequence, pending_version)
        return "next"

    def _apply_route(self, sequence: int, source_version: int) -> None:
        if self.applied_source_versions and source_version <= self.applied_source_versions[-1]:
            raise AssertionError("PIC_LOCAL_SOURCE_VERSION_NOT_MONOTONIC")
        self.applied_route_sequence.append(sequence)
        self.applied_source_versions.append(source_version)
        self._next_route_sequence += 1

    def _begin_same_major_cutover(self, generations: list[str]) -> None:
        if self.cutover_state != "active":
            raise AssertionError("PIC_LOCAL_CUTOVER_STATE_INVALID")
        self.cutover_state = "draining"
        self.held_generations.extend(generations)

    def _apply_terminal(self) -> None:
        prior_fence = self.terminal_fence_epoch
        prior_state = self.cutover_state
        self.terminal_fence_epoch += 1
        if prior_state == "draining":
            self.cutover_state = "terminal-aborted"
            self.held_generations.clear()
        self.terminal_during_draining_aborted = (
            prior_state == "draining" and self.cutover_state == "terminal-aborted"
        )
        self.same_major_cutover_no_loss = not self.held_generations
        self.pre_switch_abort_observed = (
            prior_fence == 0 and self.cutover_state == "terminal-aborted"
        )

    def _confirm_at_fence(self, claimed_fence: int) -> bool:
        return claimed_fence == self.terminal_fence_epoch

    def _attempt_post_effect_rollback(self, effect_key: str) -> bool:
        allowed = effect_key not in self._effects
        self.post_effect_rollback_blocked = not allowed
        return allowed

    def _attempt_cross_major_cutover(self, source_major: int, target_major: int) -> bool:
        if source_major != target_major:
            self.cross_major_attempt_rejected = True
            return False
        self.cross_major_provider_calls += 1
        return True

    def _reconcile_orphan(self, generation_key: str) -> bool:
        removed = generation_key in self.orphan_mappings
        self.orphan_mappings.discard(generation_key)
        self.mapping_mismatch_reconciled = removed and not self.orphan_mappings
        return removed

    @staticmethod
    def _within_slo(elapsed_ms: int) -> bool:
        return elapsed_ms <= 300_000

    def execute(self, scenario_id: str) -> dict[str, Any]:
        result: dict[str, Any] = {
            "assertionPassed": True,
            "sideEffectCount": 0,
            "externalReferenceDigest": None,
            "newObjectCount": 0,
        }
        if scenario_id == "contract-auth":
            result["assertionPassed"] = not self._effects
        elif scenario_id == "create":
            reference = self._bind_task("work-item-one", self.external_task_one)
            effect_count = self._record_effect("create-work-item-one")
            self.synthetic_created += effect_count
            self.final_external_state = "open"
            self._inflight_claim_fence = self.terminal_fence_epoch
            result.update(sideEffectCount=effect_count,
                          externalReferenceDigest=reference,
                          newObjectCount=effect_count)
        elif scenario_id == "update":
            reference = self._bind_task("work-item-one", self.external_task_one)
            effect_count = self._record_effect("update-work-item-one")
            result.update(assertionPassed=self.same_task_reference_observed,
                          sideEffectCount=effect_count,
                          externalReferenceDigest=reference,
                          newObjectCount=0)
        elif scenario_id == "close":
            effect_count = self._record_effect("close-work-item-one")
            self.synthetic_closed += effect_count
            self.final_external_state = "closed"
            result.update(sideEffectCount=effect_count,
                          externalReferenceDigest=self.external_task_one,
                          newObjectCount=0)
        elif scenario_id == "revoke":
            self._begin_same_major_cutover(["held-generation"])
            effect_count = self._record_effect("revoke-work-item-two")
            self.synthetic_created += effect_count
            self.synthetic_revoked += effect_count
            self._apply_terminal()
            if self._inflight_claim_fence is None:
                raise AssertionError("PIC_LOCAL_INFLIGHT_CLAIM_MISSING")
            self.terminal_late_confirm_fenced = not self._confirm_at_fence(
                self._inflight_claim_fence
            )
            self.final_external_state = "revoked"
            result.update(sideEffectCount=effect_count,
                          externalReferenceDigest=self.external_task_two,
                          newObjectCount=effect_count)
        elif scenario_id == "transient-retry":
            effect = "transient-operation"
            first = self._record_effect(effect)
            replay = self._record_effect(effect)
            result.update(assertionPassed=first == 1 and replay == 0,
                          sideEffectCount=first + replay,
                          externalReferenceDigest=self.external_task_one,
                          newObjectCount=0)
        elif scenario_id == "duplicate-idempotency":
            due_intent = _digest_token("due-intent")
            escalation_intent = _digest_token("escalation-intent")
            first_due = self._record_intent(due_intent)
            escalation = self._record_intent(escalation_intent)
            replay_due = self._record_intent(due_intent)
            self.intent_replay_observed = (
                first_due == replay_due
                and first_due != escalation
                and len(self.intent_mappings) == 2
            )
            result["assertionPassed"] = (
                self.task_lineage.get("work-item-one") == self.external_task_one
                and self.intent_replay_observed
            )
        elif scenario_id == "out-of-order":
            decisions = (
                self._receive_route(1, 1),
                self._receive_route(3, 9),
                self._receive_route(2, 4),
            )
            self.route_gap_recovered = (
                decisions == ("next", "gap", "next")
                and self.applied_route_sequence == [1, 2, 3]
                and not self._pending_routes
            )
            self.sparse_source_versions_observed = (
                self.applied_source_versions == [1, 4, 9]
            )
            result["assertionPassed"] = (
                self.route_gap_recovered and self.sparse_source_versions_observed
            )
        elif scenario_id == "dual-id-route-watermark-reconcile":
            self.reference_route_watermark = 9
            reconciled = self._reconcile_orphan("orphan-generation")
            rollback_allowed = self._attempt_post_effect_rollback(
                "create-work-item-one"
            )
            cross_major_allowed = self._attempt_cross_major_cutover(1, 2)
            result["assertionPassed"] = (
                reconciled
                and not rollback_allowed
                and not cross_major_allowed
                and self.cross_major_provider_calls == 0
            )
        elif scenario_id == "slo-five-minute-budget":
            segments = (25_000, 35_000, 75_000, 100_000, 65_000)
            elapsed = sum(segments)
            self.slo_boundary_observed = (
                self._within_slo(elapsed - 1)
                and self._within_slo(elapsed)
                and not self._within_slo(elapsed + 1)
            )
            effect_count = self._record_effect("slo-budget-observation")
            result.update(assertionPassed=(
                              elapsed == 300_000 and self.slo_boundary_observed
                          ),
                          sideEffectCount=effect_count,
                          externalReferenceDigest=self.external_task_one,
                          newObjectCount=0)
        else:
            raise ValueError("PIC_LOCAL_SCENARIO_UNIMPLEMENTED:" + scenario_id)
        return result


def calculate_evidence_digest(evidence: dict[str, Any]) -> str:
    normalized = copy.deepcopy(evidence)
    normalized["evidenceDigest"] = "0" * 64
    return sha256_bytes(canonical_json_bytes(normalized))


def build_local_evidence(
    *,
    subject_commit: str,
    subject_tree: str,
    run_at: str,
    command: str,
    log_bytes: bytes,
    result: LocalScenarioResult,
) -> dict[str, Any]:
    evidence: dict[str, Any] = {
        "version": "PIC-EVIDENCE-1.0.0",
        "evidenceClass": "local-isolated-committed-candidate",
        "subjectCommit": subject_commit,
        "subjectTree": subject_tree,
        "contractVersion": "PIC-1.0.0",
        "contractDigest": file_sha256(CONTRACT),
        "profileVersion": "PIC-RUNTIME-1.0.0",
        "profileDigest": file_sha256(PROFILE),
        "scenarioSetVersion": "PIC-TARGET-SCENARIOS-1.0.0",
        "scenarioSetDigest": file_sha256(SCENARIOS),
        "runAt": run_at,
        "command": command,
        "commandExitCode": 0,
        "logDigest": sha256_bytes(log_bytes),
        "evidenceDigest": "0" * 64,
        "productionEligible": False,
        "productionRuntimeClaim": "none",
        "evidenceScope": "contract-reference-adapter-only",
        "productionDomainObjectsCreated": 0,
        "realBusinessObjectsCreated": 0,
        "productionConsumerInstalled": False,
        "productionConsumerWatermarkAdvanced": False,
        "publicTaskLifecycleClaim": "none",
        "conditionalSkipCount": 0,
        "targetSandboxConnected": False,
        "overallResult": "pass" if (
            result.failed_count == 0 and result.skipped_count == 0
        ) else "fail",
        "failedCount": result.failed_count,
        "skippedCount": result.skipped_count,
        "scenarioResults": result.scenario_results,
        "sandboxCleanupResult": "pass",
        "orphanCount": 0,
        "approvedRetainedCount": 0,
        "syntheticExternalObjectsCreated": result.synthetic_created,
        "syntheticExternalObjectsClosed": result.synthetic_closed,
        "syntheticExternalObjectsRevoked": result.synthetic_revoked,
        "referenceRouteWatermarkFrom": 0,
        "referenceRouteWatermarkTo": result.reference_route_watermark_to,
        "finalExternalState": result.final_external_state,
    }
    evidence["evidenceDigest"] = calculate_evidence_digest(evidence)
    return evidence


def validate_evidence(evidence: dict[str, Any]) -> list[str]:
    issues: list[str] = []
    schema = json.loads(EVIDENCE_SCHEMA.read_text(encoding="utf-8"))
    required = set(schema["required"])
    properties = set(schema["properties"])
    if set(evidence) - properties:
        issues.append("PIC_EVIDENCE_UNKNOWN_FIELDS")
    if required - set(evidence):
        issues.append("PIC_EVIDENCE_REQUIRED_FIELDS_MISSING")
    if not HEX40.fullmatch(str(evidence.get("subjectCommit", ""))):
        issues.append("PIC_EVIDENCE_SUBJECT_COMMIT_INVALID")
    if not HEX40.fullmatch(str(evidence.get("subjectTree", ""))):
        issues.append("PIC_EVIDENCE_SUBJECT_TREE_INVALID")
    for field in (
        "contractDigest",
        "profileDigest",
        "scenarioSetDigest",
        "logDigest",
        "evidenceDigest",
    ):
        if not HEX64.fullmatch(str(evidence.get(field, ""))):
            issues.append(f"PIC_EVIDENCE_DIGEST_INVALID:{field}")
    if evidence.get("evidenceDigest") != calculate_evidence_digest(evidence):
        issues.append("PIC_EVIDENCE_DIGEST_MISMATCH")
    scenarios = evidence.get("scenarioResults")
    locked = json.loads(SCENARIOS.read_text(encoding="utf-8"))["requiredScenarios"]
    if not isinstance(scenarios, list) or [item.get("id") for item in scenarios] != [
        item["id"] for item in locked
    ]:
        issues.append("PIC_EVIDENCE_SCENARIO_VECTOR_INVALID")
    else:
        expected_by_id = {item["id"]: item["sideEffectExpected"] for item in locked}
        for item in scenarios:
            expected = expected_by_id[item["id"]]
            if (
                item.get("status") not in {"pass", "fail"}
                or item.get("sideEffectExpected") != expected
            ):
                issues.append("PIC_EVIDENCE_SCENARIO_STATUS_INVALID")
            if item.get("status") == "pass" and item.get(
                    "sideEffectCount") != (1 if expected else 0):
                issues.append("PIC_EVIDENCE_SCENARIO_EFFECT_INVALID")
            if item.get("status") == "pass" and expected and not HEX64.fullmatch(
                    str(item.get("externalReferenceDigest", ""))):
                issues.append("PIC_EVIDENCE_EXTERNAL_REFERENCE_MISSING")
            if not expected and "externalReferenceDigest" in item:
                issues.append("PIC_EVIDENCE_FALSE_EFFECT_REFERENCE_FORBIDDEN")
    actual_failed = sum(
        item.get("status") in {"fail", "unsupported"}
        for item in scenarios if isinstance(item, dict)
    ) if isinstance(scenarios, list) else -1
    actual_skipped = sum(
        item.get("status") == "skipped"
        for item in scenarios if isinstance(item, dict)
    ) if isinstance(scenarios, list) else -1
    if evidence.get("failedCount") != actual_failed or evidence.get(
            "skippedCount") != actual_skipped:
        issues.append("PIC_EVIDENCE_SCENARIO_COUNTS_INVALID")
    passing_conditions = (
        evidence.get("failedCount") == 0
        and evidence.get("skippedCount") == 0
        and evidence.get("conditionalSkipCount") == 0
        and evidence.get("sandboxCleanupResult") == "pass"
        and evidence.get("orphanCount") == 0
        and evidence.get("approvedRetainedCount") == 0
        and evidence.get("syntheticExternalObjectsCreated")
        == evidence.get("syntheticExternalObjectsClosed")
        + evidence.get("syntheticExternalObjectsRevoked")
    )
    expected_overall = "pass" if passing_conditions else "fail"
    if evidence.get("overallResult") != expected_overall:
        issues.append("PIC_EVIDENCE_PASS_INVARIANTS_INVALID")
    privacy_boundary = {
        "productionEligible": False,
        "productionRuntimeClaim": "none",
        "evidenceScope": "contract-reference-adapter-only",
        "productionDomainObjectsCreated": 0,
        "realBusinessObjectsCreated": 0,
        "productionConsumerInstalled": False,
        "productionConsumerWatermarkAdvanced": False,
        "publicTaskLifecycleClaim": "none",
    }
    for field, expected in privacy_boundary.items():
        if evidence.get(field) != expected:
            issues.append(f"PIC_EVIDENCE_BOUNDARY_INVALID:{field}")
    evidence_class = evidence.get("evidenceClass")
    target_fields = {
        "handoffId",
        "handoffRevision",
        "previousRevisionDigest",
        "handoffBindingDigest",
        "handoffExpectedDigest",
        "handoffIssuedAt",
        "handoffNotBefore",
        "handoffExpiresAt",
        "signerKeyId",
        "trustRootDigest",
        "signatureVerified",
        "approvedAuthorityDigest",
        "peerCertificateSpkiDigest",
        "peerCertificateChainDigest",
        "sandboxRunId",
    }
    if evidence_class == "local-isolated-committed-candidate":
        if evidence.get("targetSandboxConnected") is not False:
            issues.append("PIC_EVIDENCE_LOCAL_TARGET_CONNECTION_FORBIDDEN")
        if target_fields & set(evidence):
            issues.append("PIC_EVIDENCE_LOCAL_TARGET_FIELDS_FORBIDDEN")
    elif evidence_class == "target-managed-non-production-sandbox":
        if evidence.get("targetSandboxConnected") is not True:
            issues.append("PIC_EVIDENCE_TARGET_CONNECTION_REQUIRED")
        if target_fields - set(evidence):
            issues.append("PIC_EVIDENCE_TARGET_FIELDS_MISSING")
        if evidence.get("signatureVerified") is not True:
            issues.append("PIC_EVIDENCE_TARGET_SIGNATURE_REQUIRED")
        for field in (
            "handoffBindingDigest",
            "handoffExpectedDigest",
            "trustRootDigest",
            "approvedAuthorityDigest",
            "peerCertificateSpkiDigest",
            "peerCertificateChainDigest",
        ):
            if not HEX64.fullmatch(str(evidence.get(field, ""))):
                issues.append(f"PIC_EVIDENCE_TARGET_DIGEST_INVALID:{field}")
        if evidence.get("handoffBindingDigest") != evidence.get(
            "handoffExpectedDigest"
        ):
            issues.append("PIC_EVIDENCE_TARGET_HANDOFF_BINDING_MISMATCH")
        previous = evidence.get("previousRevisionDigest")
        revision = evidence.get("handoffRevision")
        if revision == 1 and previous is not None:
            issues.append("PIC_EVIDENCE_TARGET_REVISION_CHAIN_INVALID")
        if revision != 1 and not re.fullmatch(
            r"sha256:[0-9a-f]{64}", str(previous)
        ):
            issues.append("PIC_EVIDENCE_TARGET_REVISION_CHAIN_INVALID")
    else:
        issues.append("PIC_EVIDENCE_CLASS_INVALID")
    return sorted(set(issues))


def _run_gate(
    gate_id: str,
    command: list[str],
    *,
    cwd: Path,
) -> ExecutableGateResult:
    if gate_id not in REQUIRED_GATE_IDS:
        raise ValueError("PIC_LOCAL_EXECUTABLE_GATE_ID_INVALID")
    environment = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1"}
    completed = subprocess.run(
        command,
        cwd=cwd,
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
        timeout=900,
    )
    sys.stdout.buffer.write(completed.stdout)
    return ExecutableGateResult(
        gate_id=gate_id,
        command=tuple(command),
        exit_code=completed.returncode,
        output=completed.stdout,
    )


def _git_value(*arguments: str) -> str:
    return subprocess.check_output(
        ["git", *arguments], cwd=ROOT, text=True
    ).strip()


def resolve_git_subject(
    subject_commit: str | None,
    subject_tree: str | None,
) -> tuple[str, str]:
    actual_commit = _git_value("rev-parse", "HEAD")
    actual_tree = _git_value("rev-parse", "HEAD^{tree}")
    if (
        subject_commit is not None
        and subject_commit != actual_commit
        or subject_tree is not None
        and subject_tree != actual_tree
    ):
        raise ValueError("PIC_EVIDENCE_SUBJECT_OVERRIDE_MISMATCH")
    return actual_commit, actual_tree


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--subject-commit")
    parser.add_argument("--subject-tree")
    parser.add_argument("--development-allow-dirty", action="store_true")
    parser.add_argument("--skip-executable-gates", action="store_true")
    args = parser.parse_args(argv)

    if args.skip_executable_gates:
        raise RuntimeError("PIC_LOCAL_EXECUTABLE_GATES_REQUIRED")

    if args.development_allow_dirty:
        raise RuntimeError("PIC_LOCAL_EVIDENCE_REQUIRES_TRACKED_CLEAN_CANDIDATE")

    if _git_value("status", "--porcelain"):
        raise RuntimeError("PIC_LOCAL_EVIDENCE_REQUIRES_TRACKED_CLEAN_CANDIDATE")
    subject_commit, subject_tree = resolve_git_subject(
        args.subject_commit, args.subject_tree
    )
    gate_commands = {
        "contract-package": [
            sys.executable,
            "-B",
            str(ROOT / "scripts/check_public_integration_contracts.py"),
            str(ROOT),
        ],
        "reference-adapters": [
            str(ROOT / "backend/mvnw"),
            "-q",
            "-f",
            str(ROOT / "backend/pom.xml"),
            "-Dtest=PublicIntegrationStateModelTest,PublicIntegrationHttpReferenceAdapterTest",
            "test",
        ],
        "postgresql-atomicity": [
            str(ROOT / "scripts/run_audit_postgresql_tests.sh")
        ],
    }
    gate_results = {
        gate_id: _run_gate(gate_id, command, cwd=ROOT)
        for gate_id, command in gate_commands.items()
    }
    logs = b"".join(
        gate_results[gate_id].output for gate_id in sorted(gate_results)
    )
    result = run_local_scenarios(gate_results=gate_results)
    failed_gates = [
        gate_id for gate_id, gate in gate_results.items() if not gate.passed
    ]
    if failed_gates:
        raise RuntimeError(
            "PIC_LOCAL_GATE_FAILED:" + ",".join(sorted(failed_gates))
        )
    run_at = dt.datetime.now(dt.UTC).replace(microsecond=0).isoformat().replace(
        "+00:00", "Z"
    )
    evidence = build_local_evidence(
        subject_commit=subject_commit,
        subject_tree=subject_tree,
        run_at=run_at,
        command="scripts/run_public_integration_sandbox_tests.py --evidence <temporary>",
        log_bytes=logs,
        result=result,
    )
    issues = validate_evidence(evidence)
    if issues:
        raise RuntimeError(";".join(issues))
    output = Path(args.evidence)
    output.write_bytes(canonical_json_bytes(evidence) + b"\n")
    print(
        "public-integration-local: PASS "
        f"(scenarios={len(result.scenario_results)}; failed=0; skipped=0; "
        "productionEligible=false; runtimeClaim=none)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
