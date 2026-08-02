from __future__ import annotations

import copy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_access_invalidation_sandbox_tests import (  # noqa: E402
    build_evidence,
    run,
    validate_raw_evidence,
)


TRACE = "0123456789abcdef0123456789abcdef"


def valid_raw() -> dict[str, object]:
    return {
        "traceId": TRACE,
        "eventId": "019c0000-0000-7000-8000-000000000201",
        "aggregateVersion": 1,
        "sourceVisibleAt": "2026-07-31T12:00:01Z",
        "causeEventId": "019c0000-0000-7000-8000-000000000200",
        "causeTraceId": TRACE,
        "lineageId": "lin_" + "a" * 40,
        "impactJobStatus": "completed",
        "impactJobCursorLineageId": "lin_" + "a" * 40,
        "readBackValidity": "INVALID",
        "readBackReasonCode": "RESPONSIBILITY_RECONCILIATION_DIFFERENCES",
        "readBackEvaluatedAt": "2026-07-31T12:00:08Z",
        "withinFifteenMinutes": True,
        "outboxStatus": "published",
        "outboxTraceId": TRACE,
        "deliveryOutcome": "published",
        "watermarkBeforeRelay": 0,
        "consumerId": "authorization-current-scope",
        "consumerWatermark": 1,
        "consumerLastEventId": "019c0000-0000-7000-8000-000000000201",
        "consumerTraceId": TRACE,
        "localFenceState": "invalidated",
        "localFenceTraceId": TRACE,
        "reconciliationOutcome": "healthy",
        "reconciliationTraceId": TRACE,
        "propagationStatus": "complete",
        "requiredConsumerCount": 1,
        "appliedRequiredConsumerCount": 1,
        "propagationTraceId": TRACE,
        "plannedConsumersAdvanced": 0,
        "databaseVersion": "PostgreSQL 18.4 on test",
    }


class AccessInvalidationSandboxEvidenceTest(unittest.TestCase):
    def test_command_timeout_fails_the_controlled_run(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, mock.patch(
            "run_access_invalidation_sandbox_tests.subprocess.run",
            side_effect=subprocess.TimeoutExpired(
                cmd=["slow-command"],
                timeout=900,
                output=b"partial output\n",
            ),
        ):
            with self.assertRaisesRegex(RuntimeError, "timed out"):
                run(["slow-command"], Path(temporary))

    def test_accepts_executed_converged_same_trace_shape(self) -> None:
        validate_raw_evidence(valid_raw())

    def test_rejects_split_trace_artifacts(self) -> None:
        raw = valid_raw()
        raw["consumerTraceId"] = "f" * 32

        with self.assertRaisesRegex(ValueError, "same-trace"):
            validate_raw_evidence(raw)

    def test_rejects_non_denied_or_refreshed_pending_evidence(self) -> None:
        raw = valid_raw()
        raw["readBackValidity"] = "VALID"
        raw["withinFifteenMinutes"] = False

        with self.assertRaisesRegex(ValueError, "readBackValidity"):
            validate_raw_evidence(raw)

    def test_recomputes_fifteen_minute_window_from_timestamps(self) -> None:
        raw = valid_raw()
        raw["readBackEvaluatedAt"] = "2026-07-31T12:16:01Z"

        with self.assertRaisesRegex(ValueError, "withinFifteenMinutes"):
            validate_raw_evidence(raw)

    def test_build_uses_observed_values_instead_of_a_pass_literal(self) -> None:
        result = {
            "command": "test-command",
            "exitCode": 0,
            "succeeded": True,
        }
        raw = valid_raw()

        evidence = build_evidence(
            copy.deepcopy(raw), result, result, result
        )

        self.assertEqual(
            "deny",
            evidence["sameTraceSequence"][4]["authorizationResult"],
        )
        self.assertEqual(
            raw["consumerWatermark"],
            evidence["sameTraceSequence"][3]["continuousWatermark"],
        )
        self.assertNotIn("consumerResult", evidence)


if __name__ == "__main__":
    unittest.main()
