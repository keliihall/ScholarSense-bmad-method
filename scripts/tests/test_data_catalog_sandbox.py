from __future__ import annotations

import copy
import sys
import unittest
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from run_data_catalog_sandbox_tests import (  # noqa: E402
    calendar_records,
    execute,
    project_calendar,
    validate_calendar,
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


if __name__ == "__main__":
    unittest.main()
