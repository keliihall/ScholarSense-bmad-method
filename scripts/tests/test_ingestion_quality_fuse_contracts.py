import json
import shutil
import tempfile
import unittest
from pathlib import Path

from scripts import check_ingestion_quality_fuse_contracts as checker


ROOT = Path(__file__).resolve().parents[2]


class IngestionQualityFuseContractTest(unittest.TestCase):
    def test_repository_contract_is_valid(self) -> None:
        self.assertEqual([], checker.check(ROOT))

    def test_latch_matrix_cannot_auto_unlock(self) -> None:
        with self.copy() as root:
            path = root / checker.POLICY
            policy = self.load(path)
            case = next(item for item in policy["transitions"] if item["caseId"] == "fused-pass")
            case["appliedState"] = "eligible"
            path.write_text(json.dumps(policy), encoding="utf-8")
            self.assertIssue(checker.check(root), "fused + pass")

    def test_only_verified_business_failure_opens_episode(self) -> None:
        with self.copy() as root:
            path = root / checker.POLICY
            policy = self.load(path)
            policy["triggerReasons"]["technical"] = ["EVENT_VERSION_GAP"]
            path.write_text(json.dumps(policy), encoding="utf-8")
            self.assertIssue(checker.check(root), "technical trigger")

    def test_unknown_q1_q2_mapping_is_rejected(self) -> None:
        with self.copy() as root:
            path = root / checker.DERIVATION
            matrix = self.load(path)
            matrix["legacyMappings"]["Q3"] = {"result": "eligible"}
            path.write_text(json.dumps(matrix), encoding="utf-8")
            self.assertIssue(checker.check(root), "Q1/Q2")

    def test_task_cardinality_and_generation_are_locked(self) -> None:
        with self.copy() as root:
            path = root / checker.TASK_POLICY
            policy = self.load(path)
            policy["cardinality"]["key"] = ["ruleId"]
            policy["lifecycle"]["closedThenFailed"] = "reuse-closed-generation"
            path.write_text(json.dumps(policy), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "source/dependency/generation")
            self.assertIssue(issues, "new generation")

    def test_work_item_hmac_rotation_contract_is_locked(self) -> None:
        with self.copy() as root:
            path = root / checker.TASK_POLICY
            policy = self.load(path)
            policy["identity"]["keyVersion"] = "always-current"
            path.write_text(json.dumps(policy), encoding="utf-8")
            self.assertIssue(checker.check(root), "HMAC key version")

    def test_delivery_state_cannot_pollute_quality_or_task_business_state(self) -> None:
        with self.copy() as root:
            path = root / checker.TASK_SCHEMA
            schema = self.load(path)
            schema["properties"]["deliveryStatus"] = {"type": "string"}
            path.write_text(json.dumps(schema), encoding="utf-8")
            self.assertIssue(checker.check(root), "delivery sidecar")

    def test_quality_route_and_owner_are_additive(self) -> None:
        with self.copy() as root:
            path = root / checker.ADAPTER_REGISTRY
            registry = self.load(path)
            quality = next(item for item in registry["descriptors"] if item["owner"] == "ingestion-quality")
            quality["events"] = ["scholarsense.clue-care.work-item.changed.v1"]
            path.write_text(json.dumps(registry), encoding="utf-8")
            self.assertIssue(checker.check(root), "quality task route")

    def test_predecessor_mutation_is_detected(self) -> None:
        with self.copy() as root:
            path = root / checker.PIC_PREDECESSOR
            predecessor = self.load(path)
            predecessor["version"] = "PIC-MUTATED"
            path.write_text(json.dumps(predecessor), encoding="utf-8")
            self.assertIssue(checker.check(root), "predecessor digest")

    def test_fixture_and_handoffs_keep_runtime_claims_honest(self) -> None:
        with self.copy() as root:
            path = root / checker.EVENT_FIXTURE
            event = self.load(path)
            event["data"]["runtimeEvidenceClaim"] = "production-active"
            path.write_text(json.dumps(event), encoding="utf-8")
            self.assertIssue(checker.check(root), "runtime evidence")

    def test_public_recovery_task_v1_shape_is_frozen(self) -> None:
        with self.copy() as root:
            path = root / checker.EVENT_FIXTURE
            event = self.load(path)
            event["data"]["task"]["trigger"]["reasonCode"] = "REQUIRED_MEMBER_FUSED"
            event["data"]["task"]["affectedRules"] = [
                {"ruleId": "ACC-SAFE-001", "ruleVersion": "1.0.0"}
            ]
            path.write_text(json.dumps(event), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "public task reason code")
            self.assertIssue(issues, "public affectedRules")

    def test_public_recovery_task_schema_rejects_internal_shapes(self) -> None:
        with self.copy() as root:
            path = root / checker.TASK_SCHEMA
            schema = self.load(path)
            schema["properties"]["affectedRules"] = {"type": "array"}
            schema["properties"]["trigger"] = {"type": "object"}
            path.write_text(json.dumps(schema), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "public trigger schema")
            self.assertIssue(issues, "public affectedRules schema")

    def test_policy_and_recovery_task_run_real_schema_validation(self) -> None:
        with self.copy() as root:
            policy_path = root / checker.POLICY
            policy = self.load(policy_path)
            policy["unexpectedAuthorityShortcut"] = True
            policy_path.write_text(json.dumps(policy), encoding="utf-8")
            task_path = root / checker.EVENT_FIXTURE
            event = self.load(task_path)
            event["data"]["task"]["currentEvidence"].pop("qshmVersion")
            task_path.write_text(json.dumps(event), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "policy JSON Schema validation")
            self.assertIssue(issues, "RecoveryTask JSON Schema validation")

    def test_recovery_task_openapi_keeps_source_scope_and_cursor_pair_closed(self) -> None:
        with self.copy() as root:
            path = root / checker.RECOVERY_TASK_OPENAPI
            contract = self.load(path)
            contract["components"]["parameters"]["SourceId"]["required"] = False
            contract["paths"]["/quality-recovery-tasks"]["get"].pop(
                "x-cross-field-constraints")
            path.write_text(json.dumps(contract), encoding="utf-8")
            self.assertIssue(checker.check(root), "OpenAPI 3.1.2")

    @staticmethod
    def assertIssue(issues: list[str], expected: str) -> None:
        if not any(expected in issue for issue in issues):
            raise AssertionError(f"missing issue containing {expected!r}: {issues}")

    @staticmethod
    def load(path: Path):
        return json.loads(path.read_text(encoding="utf-8"))

    def copy(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in checker.COPY_PATHS:
            source = ROOT / relative
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, target)
            else:
                shutil.copy2(source, target)

        class Copied:
            def __enter__(self):
                return root

            def __exit__(self, *_):
                temporary.cleanup()

        return Copied()


if __name__ == "__main__":
    unittest.main()
