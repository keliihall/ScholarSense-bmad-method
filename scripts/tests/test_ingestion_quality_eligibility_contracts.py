import json
import shutil
import tempfile
import unittest
from pathlib import Path

from scripts import check_ingestion_quality_eligibility_contracts as checker


ROOT = Path(__file__).resolve().parents[2]


class IngestionQualityEligibilityContractTest(unittest.TestCase):
    def test_repository_contract_is_valid(self) -> None:
        self.assertEqual([], checker.check(ROOT))

    def test_unknown_dependency_and_non_source_rule_are_rejected(self) -> None:
        with self.copy() as root:
            path = root / checker.REGISTRY
            registry = self.load(path)
            registry["rules"][0]["members"][0]["dependencyId"] = "DEP-UNKNOWN-001"
            registry["rules"].append(
                {
                    "ruleId": "CORROBORATE-001",
                    "ruleVersion": "1.0.0",
                    "composition": {"operator": "all-of", "threshold": None},
                    "members": [registry["rules"][0]["members"][0]],
                }
            )
            path.write_text(json.dumps(registry), encoding="utf-8")
            issues = checker.check(root)
            self.assertTrue(any("unknown dependency" in issue for issue in issues))
            self.assertTrue(any("source-backed rule set" in issue for issue in issues))

    def test_duplicate_binding_and_threshold_bounds_are_rejected(self) -> None:
        with self.copy() as root:
            path = root / checker.SYNTHETIC
            fixture = self.load(path)
            fixture["cases"][0]["members"].append(fixture["cases"][0]["members"][0])
            fixture["cases"][1]["threshold"] = 3
            path.write_text(json.dumps(fixture), encoding="utf-8")
            issues = checker.check(root)
            self.assertTrue(any("duplicate member" in issue for issue in issues))
            self.assertTrue(any("threshold" in issue for issue in issues))

    def test_batch_aggregate_version_or_time_ordering_is_rejected(self) -> None:
        with self.copy() as root:
            path = root / checker.ORDERING
            policy = self.load(path)
            policy["sourceOrdering"]["sequenceKey"] = ["batchAggregateVersion"]
            policy["sourceOrdering"]["forbiddenOrderingFields"].remove("occurredAt")
            path.write_text(json.dumps(policy), encoding="utf-8")
            issues = checker.check(root)
            self.assertTrue(any("cross-batch sequence" in issue for issue in issues))
            self.assertTrue(any("forbidden ordering" in issue for issue in issues))

    def test_technical_poison_cannot_advance_or_fuse(self) -> None:
        with self.copy() as root:
            path = root / checker.ORDERING
            policy = self.load(path)
            poison = next(case for case in policy["scenarios"] if case["case"] == "poison")
            poison["cursorAdvances"] = True
            poison["businessStateChanges"] = True
            path.write_text(json.dumps(policy), encoding="utf-8")
            issues = checker.check(root)
            self.assertTrue(any("poison fail-closed" in issue for issue in issues))

    def test_projection_requires_owned_source_and_all_member_authorization(self) -> None:
        with self.copy() as root:
            path = root / checker.PROJECTION
            projection = self.load(path)
            projection["authorization"]["requiresEveryExposedMemberAuthorized"] = False
            projection["authorization"]["partialMemberResult"] = "full-tree"
            path.write_text(json.dumps(projection), encoding="utf-8")
            issues = checker.check(root)
            self.assertTrue(any("projection authorization" in issue for issue in issues))

    def test_lock_detects_contract_mutation(self) -> None:
        with self.copy() as root:
            path = root / checker.RETENTION
            policy = self.load(path)
            policy["retentionPeriod"] = "P30D"
            path.write_text(json.dumps(policy), encoding="utf-8")
            issues = checker.check(root)
            self.assertTrue(any("retention mapping" in issue for issue in issues))
            self.assertTrue(any("contract lock mismatch" in issue for issue in issues))

    def test_event_requires_complete_registry_bound_composition(self) -> None:
        with self.copy() as root:
            path = root / checker.EVENT_FIXTURE
            event = self.load(path)
            event["data"]["qualityEligibility"]["members"].pop()
            path.write_text(json.dumps(event), encoding="utf-8")
            issues = checker.check(root)
            self.assertTrue(any("complete digest-bound" in issue for issue in issues))

    def test_ordering_and_unactivated_handoffs_are_fail_closed(self) -> None:
        with self.copy() as root:
            ordering_path = root / checker.EVENT_ORDERING
            ordering = self.load(ordering_path)
            ordering["scenarios"] = ordering["scenarios"][:-1]
            ordering_path.write_text(json.dumps(ordering), encoding="utf-8")
            recovery_path = root / checker.RECOVERY_COMMAND
            recovery = self.load(recovery_path)
            recovery["story24RuntimeActivation"] = "installed"
            recovery_path.write_text(json.dumps(recovery), encoding="utf-8")
            issues = checker.check(root)
            self.assertTrue(any("duplicate/old/gap/poison/backfill" in issue for issue in issues))
            self.assertTrue(any("Story 2.5 recovery" in issue for issue in issues))

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
