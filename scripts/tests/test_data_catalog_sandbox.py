from __future__ import annotations

import copy
import sys
import unittest
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from release_json import load_json  # noqa: E402
from run_data_catalog_sandbox_tests import (  # noqa: E402
    calendar_records,
    execute,
    project_calendar,
    validate_calendar,
    validate_reconcile_sequence,
    validate_timetable,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class DataCatalogSandboxTest(unittest.TestCase):
    def test_all_seventeen_sources_execute_and_fixtures_never_claim_runtime(self) -> None:
        issues, report = execute(PROJECT_ROOT)
        self.assertEqual([], issues)
        self.assertEqual(17, report["sourceCount"])
        self.assertEqual("none", report["runtimeEvidenceClaim"])
        self.assertEqual("pass", report["result"])
        self.assertFalse(report["privacy"]["studentPlaintextStored"])

    def test_calendar_exact_window_gap_overlap_type_and_version_fail_closed(self) -> None:
        anchor = date(2026, 8, 4)
        records = calendar_records(anchor, ["workday", "weekend", "statutory-holiday", "makeup-workday", "school-holiday", "emergency-closure"])
        self.assertEqual([], validate_calendar(records, anchor))
        self.assertIn("DCC_CALENDAR_COVERAGE_INVALID", validate_calendar(records[:-1], anchor))
        duplicate = records + [copy.deepcopy(records[0])]
        self.assertIn("DCC_CALENDAR_COVERAGE_INVALID", validate_calendar(duplicate, anchor))
        duplicate[-1]["sourceVersion"] = 0
        _, projection_errors = project_calendar(duplicate)
        self.assertIn("DCC_SOURCE_VERSION_REGRESSION", projection_errors)
        unknown = copy.deepcopy(records)
        unknown[0]["dayType"] = "invented"
        self.assertIn("DCC_CALENDAR_DAY_TYPE_INVALID", validate_calendar(unknown, anchor))
        shifted = copy.deepcopy(records)
        shifted[0]["dayStartsAt"] = "2026-05-05T17:00:00Z"
        shifted[0]["dayEndsAt"] = "2026-05-06T17:00:00Z"
        self.assertIn("DCC_CALENDAR_INTERVAL_INVALID", validate_calendar(shifted, anchor))
        correction = dict(records[90], sourceVersion=2, supersedesVersion=1, dayType="emergency-closure")
        projected, errors = project_calendar(records + [correction])
        self.assertEqual([], errors)
        self.assertEqual([], validate_calendar(projected, anchor))
        _, errors = project_calendar(records + [dict(correction, supersedesVersion=None)])
        self.assertIn("DCC_CORRECTION_CHAIN_INVALID", errors)

    def test_timetable_half_open_and_privacy_boundaries(self) -> None:
        record = {"activityStartsAt": "2026-08-04T00:00:00Z", "activityEndsAt": "2026-08-04T01:00:00Z", "sourceVersion": 1}
        self.assertEqual([], validate_timetable(record))
        self.assertIn("DCC_TIMETABLE_INTERVAL_INVALID", validate_timetable(dict(record, activityEndsAt=record["activityStartsAt"])))
        self.assertIn("DCC_FORBIDDEN_FIELD", validate_timetable(dict(record, grade="A")))
        self.assertEqual([], validate_timetable(dict(record, sourceVersion=2, supersedesVersion=1,
                activityState="rescheduled", campusCode="CAMPUS-02")))
        self.assertIn("DCC_SOURCE_VERSION_REGRESSION", validate_timetable(dict(record, sourceVersion=0)))

    def test_correction_revocation_out_of_order_and_watermark_are_executed(self) -> None:
        samples = load_json(
            PROJECT_ROOT
            / "contracts/data-catalog/fixtures/valid/minimal-slice-records-1.0.0.json"
        )["records"]
        campus = samples["SRC-P0-CAMPUS-ACCESS-001"]
        corrected = dict(
            campus,
            eventId="event.synthetic.campus.002",
            eventState="corrected",
            correctsEventId=campus["eventId"],
            occurredAt="2026-08-04T00:59:00Z",
            receivedAt="2026-08-04T01:00:03Z",
            sourceVersion=2,
            watermark="wm.synthetic.campus.002",
        )
        revoked = dict(
            corrected,
            eventId="event.synthetic.campus.003",
            eventState="revoked",
            correctsEventId=corrected["eventId"],
            sourceVersion=3,
            watermark="wm.synthetic.campus.003",
        )
        self.assertEqual(
            [],
            validate_reconcile_sequence(
                "SRC-P0-CAMPUS-ACCESS-001", [campus, corrected, revoked]
            ),
        )
        regressed_watermark = dict(corrected, watermark="wm.synthetic.campus.000")
        self.assertIn(
            "DCC_WATERMARK_REGRESSION",
            validate_reconcile_sequence(
                "SRC-P0-CAMPUS-ACCESS-001", [campus, regressed_watermark]
            ),
        )
        self.assertIn(
            "DCC_CORRECTION_CHAIN_INVALID",
            validate_reconcile_sequence(
                "SRC-P0-CAMPUS-ACCESS-001",
                [campus, dict(corrected, correctsEventId=None)],
            ),
        )

        leave = samples["SRC-P0-LEAVE-001"]
        leave_revoked = dict(
            leave,
            approvalState="revoked",
            sourceVersion=2,
            supersedesVersion=1,
        )
        self.assertEqual(
            [], validate_reconcile_sequence("SRC-P0-LEAVE-001", [leave, leave_revoked])
        )
        self.assertIn(
            "DCC_CORRECTION_CHAIN_INVALID",
            validate_reconcile_sequence(
                "SRC-P0-LEAVE-001", [leave, dict(leave_revoked, supersedesVersion=None)]
            ),
        )


if __name__ == "__main__":
    unittest.main()
