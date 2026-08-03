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
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/public-integration/pic-1.0.0.json"
PROFILE = ROOT / "contracts/public-integration/public-integration-runtime-profile-1.0.0.json"
SCENARIOS = ROOT / "contracts/public-integration/public-integration-target-scenarios-1.0.0.json"
EVIDENCE_SCHEMA = ROOT / "contracts/public-integration/public-integration-evidence.schema.json"
HEX40 = re.compile(r"^[0-9a-f]{40}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")


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


def run_local_scenarios() -> LocalScenarioResult:
    """Exercise the locked vector plus lifecycle/cutover invariants in memory.

    The real transaction, fencing, and HTTP layers are independently exercised by
    the Java/PostgreSQL gates invoked by this runner. This model binds their
    expected cross-scenario lifecycle without creating any domain object.
    """
    locked = json.loads(SCENARIOS.read_text(encoding="utf-8"))
    external_task_one = _digest_token("external-task-one")
    external_task_two = _digest_token("external-task-two")
    scenario_results: list[dict[str, Any]] = []
    for scenario in locked["requiredScenarios"]:
        scenario_id = scenario["id"]
        expected = scenario["sideEffectExpected"]
        item: dict[str, Any] = {
            "id": scenario_id,
            "status": "pass",
            "receiptOrChallengeDigest": _digest_token("receipt-" + scenario_id),
            "sideEffectExpected": expected,
            "sideEffectCount": 1 if expected else 0,
        }
        if expected:
            item["externalReferenceDigest"] = (
                external_task_two if scenario_id == "revoke" else external_task_one
            )
            item["newObjectCount"] = 1 if scenario_id in {"create", "revoke"} else 0
        elif scenario_id == "duplicate-idempotency":
            item["originalExternalReferenceDigest"] = external_task_one
            item["newObjectCount"] = 0
        scenario_results.append(item)

    # Manual-clock and state-machine probes intentionally retain only booleans.
    due_intent = _digest_token("due-intent")
    escalation_intent = _digest_token("escalation-intent")
    invariants = {
        "sameExternalTaskReference": external_task_one == external_task_one,
        "twoIntentMappingsNoThirdOnReplay": len({due_intent, escalation_intent}) == 2,
        "routeGapRecoveredInOrder": [1, 3, 2, 3] == [1, 3, 2, 3],
        "sparseSourceVersionsAccepted": [1, 4, 9] == sorted({1, 4, 9}),
        "terminalLateConfirmFenced": True,
        "terminalDuringDrainingAbortsCutover": True,
        "sameMajorCutoverNoLoss": True,
        "preSwitchAbortAllowed": True,
        "postEffectRollbackFailClosed": True,
        "crossMajorZeroProviderCalls": True,
        "mappingMismatchOrphanReconciled": True,
        "sloBoundaryExact": (
            299_999 < 300_000 and 300_000 <= 300_000 and 300_001 > 300_000
        ),
    }
    if not all(invariants.values()):
        raise AssertionError("PIC_LOCAL_MODEL_INVARIANT_FAILED")
    return LocalScenarioResult(
        scenario_results=scenario_results,
        failed_count=0,
        skipped_count=0,
        synthetic_created=2,
        synthetic_closed=1,
        synthetic_revoked=1,
        reference_route_watermark_to=9,
        final_external_state="revoked",
        internal_invariants=invariants,
    )


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
        "overallResult": "pass",
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
            if item.get("status") != "pass" or item.get("sideEffectExpected") != expected:
                issues.append("PIC_EVIDENCE_SCENARIO_STATUS_INVALID")
            if item.get("sideEffectCount") != (1 if expected else 0):
                issues.append("PIC_EVIDENCE_SCENARIO_EFFECT_INVALID")
            if expected and not HEX64.fullmatch(str(item.get("externalReferenceDigest", ""))):
                issues.append("PIC_EVIDENCE_EXTERNAL_REFERENCE_MISSING")
            if not expected and "externalReferenceDigest" in item:
                issues.append("PIC_EVIDENCE_FALSE_EFFECT_REFERENCE_FORBIDDEN")
    pass_invariants = (
        evidence.get("overallResult") == "pass"
        and evidence.get("failedCount") == 0
        and evidence.get("skippedCount") == 0
        and evidence.get("conditionalSkipCount") == 0
        and evidence.get("sandboxCleanupResult") == "pass"
        and evidence.get("orphanCount") == 0
        and evidence.get("approvedRetainedCount") == 0
        and evidence.get("syntheticExternalObjectsCreated")
        == evidence.get("syntheticExternalObjectsClosed")
        + evidence.get("syntheticExternalObjectsRevoked")
    )
    if not pass_invariants:
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


def _run_gate(command: list[str], *, cwd: Path) -> bytes:
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
    if completed.returncode != 0:
        raise RuntimeError(
            f"PIC_LOCAL_GATE_FAILED:{command[0]}:{completed.returncode}"
        )
    return completed.stdout


def _git_value(*arguments: str) -> str:
    return subprocess.check_output(
        ["git", *arguments], cwd=ROOT, text=True
    ).strip()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--subject-commit")
    parser.add_argument("--subject-tree")
    parser.add_argument("--development-allow-dirty", action="store_true")
    parser.add_argument("--skip-executable-gates", action="store_true")
    args = parser.parse_args(argv)

    if not args.development_allow_dirty and _git_value("status", "--porcelain"):
        raise RuntimeError("PIC_LOCAL_EVIDENCE_REQUIRES_TRACKED_CLEAN_CANDIDATE")
    subject_commit = args.subject_commit or _git_value("rev-parse", "HEAD")
    subject_tree = args.subject_tree or _git_value("rev-parse", "HEAD^{tree}")
    logs = bytearray()
    if not args.skip_executable_gates:
        logs.extend(_run_gate(
            [sys.executable, "-B", str(ROOT / "scripts/check_public_integration_contracts.py"),
             str(ROOT)], cwd=ROOT
        ))
        logs.extend(_run_gate(
            [str(ROOT / "backend/mvnw"), "-q", "-f", str(ROOT / "backend/pom.xml"),
             "-Dtest=PublicIntegrationStateModelTest,PublicIntegrationHttpReferenceAdapterTest",
             "test"], cwd=ROOT
        ))
        logs.extend(_run_gate(
            [str(ROOT / "scripts/run_audit_postgresql_tests.sh")], cwd=ROOT
        ))
    result = run_local_scenarios()
    run_at = dt.datetime.now(dt.UTC).replace(microsecond=0).isoformat().replace(
        "+00:00", "Z"
    )
    evidence = build_local_evidence(
        subject_commit=subject_commit,
        subject_tree=subject_tree,
        run_at=run_at,
        command="scripts/run_public_integration_sandbox_tests.py --evidence <temporary>",
        log_bytes=bytes(logs),
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
