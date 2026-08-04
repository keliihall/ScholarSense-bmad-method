from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_data_catalog_contracts import (  # noqa: E402
    EXPECTED_DEPENDENCIES,
    EXPECTED_SOURCES,
    execute_compatibility_fixture,
    execute_negative_fixture,
    validate,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
CONTRACT_ROOT = PROJECT_ROOT / "contracts/data-catalog"


class DataCatalogContractTest(unittest.TestCase):
    def test_locked_package_passes_and_exact_sets_are_derived(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))
        catalog = json.loads((CONTRACT_ROOT / "dcc-1.0.0.json").read_text())
        registry = json.loads(
            (CONTRACT_ROOT / "dependency-registry-1.0.0.json").read_text()
        )
        self.assertEqual(EXPECTED_SOURCES, {item["sourceId"] for item in catalog["sources"]})
        self.assertEqual(
            EXPECTED_DEPENDENCIES,
            {item["sourceId"]: item["dependencyId"] for item in registry["bindings"]},
        )

    def test_negative_fixtures_execute_declared_inputs_and_codes(self) -> None:
        fixture = json.loads(
            (CONTRACT_ROOT / "fixtures/invalid/negative-fixtures-1.0.0.json").read_text()
        )
        for case in fixture["cases"]:
            with self.subTest(case=case["id"]):
                self.assertIn("input", case)
                self.assertEqual(case["expectedCode"], execute_negative_fixture(case, PROJECT_ROOT))

        invented = copy.deepcopy(fixture["cases"][0])
        invented["mutation"] = "invented"
        self.assertEqual("DCC_FIXTURE_INVALID", execute_negative_fixture(invented, PROJECT_ROOT))

    def test_compatibility_allows_optional_only_and_rejects_breaking_same_major(self) -> None:
        for name in ("optional-addition-1.1.0.json", "breaking-without-major.json"):
            document = json.loads((CONTRACT_ROOT / "fixtures/compatibility" / name).read_text())
            with self.subTest(name=name):
                self.assertEqual(document["expectedCode"], execute_compatibility_fixture(document))

    def test_non_rule_sources_never_receive_dependency_ids(self) -> None:
        catalog = json.loads((CONTRACT_ROOT / "dcc-1.0.0.json").read_text())
        mapped = set(EXPECTED_DEPENDENCIES)
        for descriptor in catalog["sources"]:
            if descriptor["sourceId"] not in mapped:
                self.assertNotIn("dependencyId", descriptor)
                self.assertEqual("purpose-isolated", descriptor["consumerMode"])

    def test_calendar_and_timetable_minimal_schemas_are_privacy_bounded(self) -> None:
        calendar = json.loads(
            (CONTRACT_ROOT / "sources/src-p0-calendar-001.schema.json").read_text()
        )
        timetable = json.loads(
            (CONTRACT_ROOT / "sources/src-p0-timetable-001.schema.json").read_text()
        )
        self.assertEqual("Asia/Shanghai", calendar["properties"]["businessTimezone"]["const"])
        self.assertEqual(
            {"workday", "weekend", "statutory-holiday", "makeup-workday", "school-holiday", "emergency-closure"},
            set(calendar["properties"]["dayType"]["enum"]),
        )
        forbidden = {"grade", "ranking", "thesis", "fullAcademicRecord"}
        self.assertTrue(forbidden.isdisjoint(timetable["properties"]))
        self.assertFalse(timetable["additionalProperties"])


if __name__ == "__main__":
    unittest.main()
