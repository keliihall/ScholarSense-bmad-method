import json
import shutil
import tempfile
import unittest
from pathlib import Path

from scripts import check_ingestion_quality_finalization_contracts as checker


ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "contracts/ingestion-quality/quality-finalization"
OBSERVATION_POLICY = BASE / "recovery-observation-policy-1.0.0.json"
ARCHITECTURE = BASE / "quality-finalization-architecture-1.0.0.json"
STATE_VECTORS = BASE / "fixtures/valid/recovery-finalization-state-vectors-1.0.0.json"
NEGATIVE_VECTORS = BASE / "fixtures/invalid/recovery-finalization-negative-fixtures-1.0.0.json"
HRAP_SUCCESSOR = ROOT / "contracts/authorization/high-risk/high-risk-quality-finalization-1.0.0.json"
PIC_SUCCESSOR = ROOT / "contracts/public-integration/pic-1.2.0.json"
OPENAPI_SUCCESSOR = ROOT / "contracts/openapi/quality-recovery-tasks-1.2.openapi.json"
TASK_CLOSE = BASE / "quality-recovery-task-close-1.0.0.json"


class IngestionQualityFinalizationContractTest(unittest.TestCase):
    def load(self, path: Path):
        return json.loads(path.read_text(encoding="utf-8"))

    def test_observation_policy_locks_duration_sequence_and_failure_semantics(self) -> None:
        policy = self.load(OBSERVATION_POLICY)
        self.assertEqual("QOFP-1.0.0", policy["policyVersion"])
        self.assertEqual(
            {"minimumConsecutivePassedBatches": 3, "observationDuration": "PT60M"},
            policy["sourceClasses"]["streaming"],
        )
        self.assertEqual(
            {
                "minimumConsecutivePassedBatches": 2,
                "observationDuration": "P1D",
                "acceptedWireAliases": ["PT24H"],
            },
            policy["sourceClasses"]["dailyBatch"],
        )
        self.assertEqual(
            ["sourceId", "sourceVersionOrdinal", "lineageRevision"],
            policy["batchQualification"]["sequenceKey"],
        )
        self.assertEqual("not-pass", policy["absenceAndGap"]["noData"])
        self.assertEqual("business-relapse", policy["absenceAndGap"]["verifiedQualityFailure"])
        self.assertEqual("retry-without-relapse", policy["absenceAndGap"]["providerUnavailable"])

    def test_repository_contract_is_valid(self) -> None:
        self.assertEqual([], checker.check(ROOT))

    def test_mutation_of_observation_boundary_and_predecessor_is_detected(self) -> None:
        with self.copy() as root:
            policy_path = root / checker.OBSERVATION_POLICY
            policy = self.load(policy_path)
            policy["sourceClasses"]["streaming"]["minimumConsecutivePassedBatches"] = 2
            policy_path.write_text(json.dumps(policy), encoding="utf-8")
            predecessor_path = root / checker.V20
            predecessor_path.write_text(
                predecessor_path.read_text(encoding="utf-8") + "\n-- mutation\n",
                encoding="utf-8",
            )
            issues = checker.check(root)
            self.assertTrue(any("exact streaming and daily" in issue for issue in issues))
            self.assertTrue(any("successor digest lock" in issue for issue in issues))
            self.assertTrue(any("predecessor digest lock" in issue for issue in issues))

    def test_fresh_d4_is_exact_single_use_and_bound_to_current_final_facts(self) -> None:
        successor = self.load(HRAP_SUCCESSOR)
        self.assertEqual(
            {
                "actionType": "quality-fuse.recover",
                "objectType": "RECOVERY_TASK",
                "currentState": "recovering",
                "targetState": "eligible",
            },
            successor["exactBinding"],
        )
        self.assertEqual(240, successor["approval"]["pendingMinutes"])
        self.assertEqual(15, successor["executionConfirmation"]["leaseMinutes"])
        self.assertTrue(successor["executionConfirmation"]["singleUseJti"])
        self.assertFalse(successor["predecessorApprovalOrTokenReusable"])
        self.assertEqual(
            [
                "objectVersion",
                "finalPreviewDigest",
                "observationDecisionDigest",
                "qualityRecoveryPolicyVersion",
                "memberSetDigest",
                "watermarksDigest",
                "authorizationGeneration",
                "currentActorNaturalPersonDigest",
            ],
            successor["freshBindings"],
        )

    def test_owner_transaction_and_failure_wins_are_unambiguous(self) -> None:
        contract = self.load(ARCHITECTURE)
        self.assertEqual("ingestion-quality", contract["owner"])
        self.assertEqual(
            [
                "source-advisory-lock",
                "final-idempotency",
                "rule-version-and-member-facts",
                "episode-and-task",
                "eligibilities-by-id",
                "observation-decision",
                "approval-and-execution-jti",
            ],
            contract["locking"]["order"],
        )
        self.assertEqual("verified-failure-or-new-policy", contract["locking"]["winner"])
        self.assertFalse(contract["ownerTransaction"]["networkIo"])
        self.assertEqual(
            [
                "final-observation-decision",
                "all-eligibility-history-and-current",
                "episode-close-history-and-current",
                "same-task-close-history-and-current",
                "window-outcomes",
                "audit",
                "eligibility-and-task-events-and-outbox",
                "execution-jti",
                "idempotent-response",
            ],
            contract["ownerTransaction"]["atomicWrites"],
        )

    def test_task_close_is_additive_same_identity_and_transport_orthogonal(self) -> None:
        pic = self.load(PIC_SUCCESSOR)
        close = self.load(TASK_CLOSE)
        self.assertEqual(["create", "update", "close"], pic["qualityTask"]["operations"])
        self.assertEqual("same-task-id-work-item-key-and-generation", close["identity"])
        self.assertEqual("terminal-never-reopen", close["closedState"])
        self.assertEqual("generation-plus-one-new-episode-and-task", close["relapseWithinP1D"])
        self.assertEqual(
            "independent-transport-sidecar-never-rolls-back-eligible-or-local-close",
            close["deliverySemantics"],
        )
        self.assertEqual("none", pic["qualityTask"]["runtimeEvidenceClaim"])

    def test_openapi_successor_is_strict_opaque_and_additive(self) -> None:
        contract = self.load(OPENAPI_SUCCESSOR)
        self.assertEqual("1.2.0", contract["info"]["version"])
        self.assertEqual(
            "659628eef04202936c00c0c131997547766447a5239ceb34d80766936299a1dc",
            contract["x-predecessor"]["sha256"],
        )
        self.assertEqual(4, len(contract["paths"]))
        command = contract["components"]["schemas"]["FinalCommand"]
        self.assertFalse(command["additionalProperties"])
        self.assertEqual(
            {
                "expectedRecoveryVersion",
                "expectedTaskVersion",
                "finalObservationWatermark",
            },
            set(command["properties"]),
        )

    def test_vectors_cover_exact_boundaries_replay_and_closed_failures(self) -> None:
        valid = {case["caseId"] for case in self.load(STATE_VECTORS)["cases"]}
        negative = {case["caseId"] for case in self.load(NEGATIVE_VECTORS)["cases"]}
        self.assertEqual(
            {
                "streaming-two-batches-not-ready",
                "streaming-three-batches-at-sixty-minutes-ready",
                "streaming-four-batches-ready",
                "daily-one-batch-not-ready",
                "daily-two-batches-at-one-day-ready",
                "daily-three-batches-ready",
                "duration-minus-one-microsecond-not-ready",
                "duration-plus-one-microsecond-ready",
                "window-equality-handoff",
                "window-plus-one-microsecond-history-only",
                "same-key-same-digest-replay",
                "closed-task-relapse-creates-next-generation",
            },
            valid,
        )
        self.assertEqual(
            {
                "no-data-is-not-pass",
                "business-sequence-gap",
                "poisoned-pair",
                "stale-slo",
                "unknown-provider",
                "provider-outage-is-not-business-relapse",
                "predecessor-token-reuse",
                "maker-equals-final-confirmer",
                "object-version-drift",
                "member-set-drift",
                "watermark-drift",
                "policy-drift",
                "same-key-different-digest",
                "task-reopen",
                "partial-terminal-write",
            },
            negative,
        )

    def copy(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in checker.COPY_PATHS:
            source = ROOT / relative
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)

        class Copied:
            def __enter__(self):
                return root

            def __exit__(self, *_):
                temporary.cleanup()

        return Copied()


if __name__ == "__main__":
    unittest.main()
