import json
import shutil
import tempfile
import unittest
from pathlib import Path

from scripts import check_ingestion_quality_snapshot_events as checker


ROOT = Path(__file__).resolve().parents[2]


class IngestionQualitySnapshotEventContractTest(unittest.TestCase):
    def test_repository_contract_is_valid(self) -> None:
        self.assertEqual([], checker.check(ROOT))

    def test_missing_self_contained_snapshot_and_runtime_claim_are_rejected(self) -> None:
        with self.copy() as root:
            path = root / checker.ASSESSED
            event = json.loads(path.read_text(encoding="utf-8"))
            event["data"].pop("qualitySnapshot")
            event["data"]["runtimeEvidenceClaim"] = "target-verified"
            path.write_text(json.dumps(event), encoding="utf-8")
            issues = checker.check(root)
            self.assertTrue(any("data keys" in issue for issue in issues))
            self.assertTrue(any("lock mismatch" in issue for issue in issues))

    def test_poison_cursor_advance_and_lock_drift_are_rejected(self) -> None:
        with self.copy() as root:
            path = root / checker.ORDERING
            fixture = json.loads(path.read_text(encoding="utf-8"))
            next(case for case in fixture["scenarios"] if case["case"] == "poison")[
                "cursorAdvances"
            ] = True
            path.write_text(json.dumps(fixture), encoding="utf-8")
            issues = checker.check(root)
            self.assertTrue(any("gap/poison/backfill" in issue for issue in issues))
            self.assertTrue(any("lock mismatch" in issue for issue in issues))

    def test_activated_or_latest_lookup_handoff_is_rejected(self) -> None:
        with self.copy() as root:
            path = root / checker.HANDOFF
            fixture = json.loads(path.read_text(encoding="utf-8"))
            fixture["runtimeEvidenceClaim"] = "target-verified"
            fixture["immutableEvidenceCopy"]["lookupSemantics"] = "read latest"
            path.write_text(json.dumps(fixture), encoding="utf-8")
            issues = checker.check(root)
            self.assertTrue(any("handoff" in issue for issue in issues))
            self.assertTrue(any("lock mismatch" in issue for issue in issues))

    def test_zero_prefix_trace_uses_the_nonzero_suffix_as_span_id(self) -> None:
        event = json.loads((ROOT / checker.ASSESSED).read_text(encoding="utf-8"))
        trace_id = "00000000000000001122334455667788"
        event["data"]["traceId"] = trace_id
        event["data"]["qualitySnapshot"]["traceId"] = trace_id
        event["traceparent"] = f"00-{trace_id}-1122334455667788-01"

        self.assertEqual([], checker.event_issues(event, "assessed"))

        event["traceparent"] = f"00-{trace_id}-{'0' * 16}-01"
        self.assertIn("assessed: traceparent binding", checker.event_issues(event, "assessed"))

    def copy(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        target = root / checker.BASE
        target.parent.mkdir(parents=True)
        shutil.copytree(ROOT / checker.BASE, target)

        class Copied:
            def __enter__(self):
                return root

            def __exit__(self, *_):
                temporary.cleanup()

        return Copied()


if __name__ == "__main__":
    unittest.main()
