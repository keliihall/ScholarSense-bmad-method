from __future__ import annotations

import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_ingestion_batch_contracts import (  # noqa: E402
    EXECUTABLE_LOCKED_FILES,
    _catalog_issues,
    evaluate_vector,
    lock_issues,
    metric_vector_issues,
    policy_issues,
    validate,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
BATCH_QUALITY = PROJECT_ROOT / "contracts/ingestion-quality/batch-quality"
DATA_CATALOG = PROJECT_ROOT / "contracts/data-catalog"
UUID_V7_PATTERN = (
    "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    "[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
MANIFEST_FRESHNESS_BINDING = {
    "sourceOccurredAt": "manifest.sourceOccurredAt",
    "scheduledDueAt": "manifest.scheduledDueAt",
    "receivedAt": "manifest.receivedAt",
    "laneId": "manifest.laneId",
}


class IngestionBatchContractTest(unittest.TestCase):
    def test_executable_policy_schema_vectors_successor_and_lock_pass(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))

    def test_historical_dcc_qg_schema_and_lock_are_byte_identical(self) -> None:
        expected = {
            "dcc-1.0.0.json": "755c28e7bbecdcb5f30e50b8bb8e8c9d549663fe65d0a20e88aebf90ba4a1586",
            "qg-1.0.0.json": "1789c6099ef3a48b87714394c903449c8f75b9ce92cba300f752da33f7e2bced",
            "quality-gate.schema.json": "1cc21fdc51ca6b60151fc89f2adc8cf7f252ae775e64cbf1bd43929447ae7ff5",
            "data-catalog-contract-lock-1.0.1.json": "0192c0f777e40e647c73f5dd455054c718f0d2d8efac9c67a36b025566857463",
        }
        for relative, digest in expected.items():
            self.assertEqual(
                digest,
                hashlib.sha256((DATA_CATALOG / relative).read_bytes()).hexdigest(),
                relative,
            )

    def test_dcc_successor_closes_all_business_keys_without_aliasing_evidence(self) -> None:
        catalog = self.load(DATA_CATALOG / "dcc-1.1.0.json")
        by_id = {item["sourceId"]: item for item in catalog["sources"]}
        self.assertEqual("DCC-1.1.0", catalog["contractVersion"])
        self.assertEqual(17, len(by_id))
        self.assertEqual(
            ["relationId", "sourceVersion"],
            by_id["SRC-P0-RESPONSIBILITY-001"]["businessKeys"],
        )
        self.assertEqual(
            ["subjectRef", "windowStartsAt"],
            by_id["SRC-P1-NETWORK-001"]["businessKeys"],
        )
        self.assertEqual(
            ["listFactId"],
            by_id["SRC-P1-CARE-LIST-001"]["businessKeys"],
        )

        for descriptor in by_id.values():
            schema = self.load(DATA_CATALOG / descriptor["schemaRef"])
            for key in descriptor["businessKeys"]:
                self.assertIn(key, schema["properties"], descriptor["sourceId"])
                self.assertIn(key, schema["required"], descriptor["sourceId"])

        responsibility = self.load(
            DATA_CATALOG / by_id["SRC-P0-RESPONSIBILITY-001"]["schemaRef"]
        )
        care_list = self.load(DATA_CATALOG / by_id["SRC-P1-CARE-LIST-001"]["schemaRef"])
        self.assertEqual(UUID_V7_PATTERN, responsibility["properties"]["relationId"]["pattern"])
        self.assertEqual(UUID_V7_PATTERN, care_list["properties"]["listFactId"]["pattern"])
        self.assertIn("relationEvidenceRef", responsibility["properties"])
        self.assertIn("evidenceRef", care_list["properties"])
        self.assertNotEqual("relationEvidenceRef", "relationId")
        self.assertNotEqual("evidenceRef", "listFactId")

    def test_common_metrics_are_closed_structured_and_exact(self) -> None:
        policy = self.load(BATCH_QUALITY / "executable-quality-policy-1.0.0.json")
        metrics = {item["metricId"]: item for item in policy["commonMetrics"]}
        self.assertEqual(
            {
                "PRIMARY_KEY_COMPLETENESS_BP",
                "P0_SUBJECT_MAPPING_BP",
                "REQUIRED_FIELD_VALIDITY_BP",
                "VALID_RECORD_RATE_BP",
                "CORE_FIELD_COVERAGE_BP",
                "FRESHNESS_WITHIN_SLO_BP",
                "UNRESOLVED_INTERVAL_CONFLICT_COUNT",
                "DUPLICATE_BUSINESS_KEY_COUNT",
                "VERSION_REGRESSION_COUNT",
                "SOURCE_CONTINUITY_GATE",
                "SCHEMA_ALLOWLIST_COMPATIBILITY_BP",
                "FORBIDDEN_FIELD_COUNT",
            },
            set(metrics),
        )
        for metric_id, metric in metrics.items():
            self.assertEqual(f"QMDP-1.0.0/{metric_id}", metric["formulaId"])
            self.assertEqual("1.0.0", metric["formulaVersion"])
            self.assertIsInstance(metric["calculation"], dict)
            self.assertIsInstance(metric["applicability"], dict)
            self.assertNotIn("expression", metric["calculation"])
            self.assertNotIsInstance(metric["calculation"].get("numerator"), str)
            self.assertNotIsInstance(metric["calculation"].get("denominator"), str)
            self.assertTrue(metric["hardGate"])

        for metric_id in (
            "UNRESOLVED_INTERVAL_CONFLICT_COUNT",
            "DUPLICATE_BUSINESS_KEY_COUNT",
            "VERSION_REGRESSION_COUNT",
            "FORBIDDEN_FIELD_COUNT",
        ):
            self.assertEqual({"kind": "constant", "value": 1}, metrics[metric_id]["calculation"]["denominator"])
            self.assertEqual("count", metrics[metric_id]["unit"])

        continuity = metrics["SOURCE_CONTINUITY_GATE"]
        self.assertEqual("applicable-member-count", continuity["calculation"]["denominator"]["operandId"])
        self.assertEqual("passing-member-count", continuity["calculation"]["numerator"]["operandId"])
        self.assertEqual("member-count", continuity["unit"])

    def test_all_17_sources_have_locked_gates_lanes_and_authority(self) -> None:
        policy = self.load(BATCH_QUALITY / "executable-quality-policy-1.0.0.json")
        catalog = self.load(DATA_CATALOG / "dcc-1.1.0.json")
        owners = {item["sourceId"]: item["responsibleRole"] for item in catalog["sources"]}
        source_profiles = {item["sourceId"]: item for item in policy["sources"]}
        self.assertEqual(set(owners), set(source_profiles))

        seen_formula_ids: set[str] = set()
        for source_id, profile in source_profiles.items():
            self.assertEqual(owners[source_id], profile["owner"])
            self.assertTrue(profile["sourceGates"])
            self.assertTrue(profile["freshnessLanes"])
            for lane in profile["freshnessLanes"]:
                self.assertFalse(lane["allowNoActivity"])
                self.assertEqual("Asia/Shanghai", lane["timezone"])
            for gate in profile["sourceGates"]:
                expected = f"QMDP-1.0.0/{source_id}/{gate['gateId']}"
                self.assertEqual(expected, gate["formulaId"])
                self.assertEqual("1.0.0", gate["formulaVersion"])
                self.assertNotIn(gate["formulaId"], seen_formula_ids)
                seen_formula_ids.add(gate["formulaId"])

        p0_mapping = next(
            item for item in policy["commonMetrics"] if item["metricId"] == "P0_SUBJECT_MAPPING_BP"
        )
        self.assertEqual(
            {
                "SRC-P0-STUDENT-001",
                "SRC-P0-ACCOMMODATION-001",
                "SRC-P0-CARD-001",
                "SRC-P0-CAMPUS-ACCESS-001",
                "SRC-P0-DORM-ACCESS-001",
                "SRC-P0-LEAVE-001",
                "SRC-P0-TIMETABLE-001",
            },
            set(p0_mapping["applicability"]["sourceIds"]),
        )

    def test_work_visit_field_group_is_the_exact_closed_schema_required_set(self) -> None:
        policy = self.load(BATCH_QUALITY / "executable-quality-policy-1.0.0.json")
        work_visit_schema = self.load(
            DATA_CATALOG / "sources/src-p1-work-visit-001.schema.json"
        )
        profile = next(
            item for item in policy["sources"] if item["sourceId"] == "SRC-P1-WORK-VISIT-001"
        )
        gate = next(
            item for item in profile["sourceGates"]
            if item["gateId"] == "work-visit-required-field-group"
        )
        self.assertEqual(work_visit_schema["required"], gate["fieldSet"])

        candidate = copy.deepcopy(policy)
        candidate_profile = next(
            item for item in candidate["sources"]
            if item["sourceId"] == "SRC-P1-WORK-VISIT-001"
        )
        candidate_profile["sourceGates"][0]["fieldSet"][1] = "nonexistentField"
        self.assertTrue(
            any(
                item.startswith("BATCH_QUALITY_SOURCE_FIELD_BINDING_INVALID")
                for item in policy_issues(PROJECT_ROOT, candidate)
            )
        )

    def test_freshness_fields_are_source_schema_bound_or_explicit_manifest_fields(self) -> None:
        policy = self.load(BATCH_QUALITY / "executable-quality-policy-1.0.0.json")
        catalog = self.load(DATA_CATALOG / "dcc-1.1.0.json")
        self.assertEqual(MANIFEST_FRESHNESS_BINDING, policy["freshnessManifestBinding"])
        descriptors = {item["sourceId"]: item for item in catalog["sources"]}
        for profile in policy["sources"]:
            schema = self.load(DATA_CATALOG / descriptors[profile["sourceId"]]["schemaRef"])
            properties = set(schema["properties"])
            for lane in profile["freshnessLanes"]:
                for key in ("laterField", "earlierField"):
                    field = lane["rule"].get(key)
                    if isinstance(field, str) and field.startswith("record."):
                        self.assertIn(
                            field.removeprefix("record."),
                            properties,
                            f"{profile['sourceId']}/{lane['laneId']}/{field}",
                        )
                    elif isinstance(field, str) and field.startswith("manifest."):
                        self.assertIn(
                            field,
                            MANIFEST_FRESHNESS_BINDING.values(),
                            f"{profile['sourceId']}/{lane['laneId']}/{field}",
                        )

        candidate = copy.deepcopy(policy)
        candidate["sources"][0]["freshnessLanes"][0]["rule"]["laterField"] = (
            "record.nonexistentAt"
        )
        self.assertTrue(
            any(
                item.startswith("BATCH_QUALITY_FRESHNESS_FIELD_BINDING_INVALID")
                for item in policy_issues(PROJECT_ROOT, candidate)
            )
        )

    def test_source_applicable_common_metric_sets_are_exact_and_fail_closed(self) -> None:
        policy = self.load(BATCH_QUALITY / "executable-quality-policy-1.0.0.json")
        candidate = copy.deepcopy(policy)
        removed = candidate["sources"][0]["applicableCommonMetricIds"].pop(0)
        issues = policy_issues(PROJECT_ROOT, candidate)
        self.assertIn(
            f"BATCH_QUALITY_APPLICABLE_COMMON_METRIC_SET_INVALID: SRC-P0-STUDENT-001",
            issues,
            removed,
        )

    def test_vectors_with_unknown_source_or_gate_do_not_fall_back_to_common_metrics(self) -> None:
        policy = self.load(BATCH_QUALITY / "executable-quality-policy-1.0.0.json")
        vectors = self.load(BATCH_QUALITY / "fixtures/valid/quality-metric-vectors-1.0.0.json")
        candidate = copy.deepcopy(vectors)
        candidate["cases"][0]["sourceId"] = "SRC-P0-FAKE-001"
        candidate["cases"][0]["gateId"] = "fake-gate"
        self.assertIn("QUALITY_VECTOR_METRIC_UNKNOWN", metric_vector_issues(policy, candidate))
        with self.assertRaisesRegex(KeyError, "QUALITY_VECTOR_METRIC_UNKNOWN"):
            evaluate_vector(policy, candidate["cases"][0])

    def test_dcc_successor_only_contains_the_three_approved_descriptor_deltas(self) -> None:
        catalog = self.load(DATA_CATALOG / "dcc-1.1.0.json")
        candidate = copy.deepcopy(catalog)
        candidate["sources"][0]["purpose"] = "silently-changed-purpose"
        self.assertIn(
            "BATCH_QUALITY_DCC_PREDECESSOR_PARITY_INVALID: SRC-P0-STUDENT-001",
            _catalog_issues(PROJECT_ROOT, candidate),
        )

    def test_purpose_constraints_are_not_numeric_metrics(self) -> None:
        policy = self.load(BATCH_QUALITY / "executable-quality-policy-1.0.0.json")
        constraints = {item["constraintId"]: item for item in policy["nonMetricConstraints"]}
        self.assertEqual(
            {"not-econ-hit-evidence", "not-student-evaluation-feature"},
            set(constraints),
        )
        all_metric_ids = {item["metricId"] for item in policy["commonMetrics"]}
        all_gate_ids = {
            gate["gateId"]
            for source in policy["sources"]
            for gate in source["sourceGates"]
        }
        self.assertTrue(set(constraints).isdisjoint(all_metric_ids | all_gate_ids))
        for constraint in constraints.values():
            self.assertEqual("consumer-purpose-deny", constraint["kind"])
            self.assertNotIn("threshold", constraint)

    def test_vectors_cover_boundaries_zero_denominator_na_timezone_and_override(self) -> None:
        policy = self.load(BATCH_QUALITY / "executable-quality-policy-1.0.0.json")
        vectors = self.load(BATCH_QUALITY / "fixtures/valid/quality-metric-vectors-1.0.0.json")
        self.assertEqual([], metric_vector_issues(policy, vectors))
        cases = {item["caseId"]: item for item in vectors["cases"]}
        expected_cases = {
            "ratio-boundary-pass",
            "ratio-one-unit-below-fail",
            "ratio-one-unit-above-pass",
            "zero-denominator-evaluation-error",
            "count-zero-pass",
            "count-one-fail",
            "duration-boundary-pass",
            "duration-one-millisecond-over-fail",
            "not-applicable-closed-predicate",
            "shanghai-cutoff-inclusive-pass",
            "source-override-boundary-pass",
            "source-override-one-unit-below-fail",
        }
        self.assertTrue(expected_cases.issubset(cases))
        for case in cases.values():
            actual = evaluate_vector(policy, case)
            self.assertEqual(case["expectedJava"], actual, case["caseId"])
            self.assertEqual(case["expectedSql"], actual, case["caseId"])
        self.assertEqual(
            "QUALITY_POLICY_ZERO_DENOMINATOR",
            cases["zero-denominator-evaluation-error"]["expectedJava"]["reasonCode"],
        )

    def test_source_override_vectors_cover_the_exact_dcc_source_set(self) -> None:
        policy = self.load(BATCH_QUALITY / "executable-quality-policy-1.0.0.json")
        catalog = self.load(DATA_CATALOG / "dcc-1.1.0.json")
        vectors = self.load(
            BATCH_QUALITY / "fixtures/valid/quality-metric-vectors-1.0.0.json"
        )
        expected_sources = {item["sourceId"] for item in catalog["sources"]}
        scoped_sources = {
            case["sourceId"]
            for case in vectors["cases"]
            if "sourceId" in case and "gateId" in case
        }

        self.assertEqual(17, len(expected_sources))
        self.assertEqual(expected_sources, scoped_sources)
        self.assertNotIn(
            "QUALITY_VECTOR_SOURCE_OVERRIDE_COVERAGE_INVALID",
            metric_vector_issues(policy, vectors),
        )

    def test_source_override_vectors_reject_each_missing_or_replaced_source(self) -> None:
        policy = self.load(BATCH_QUALITY / "executable-quality-policy-1.0.0.json")
        catalog = self.load(DATA_CATALOG / "dcc-1.1.0.json")
        vectors = self.load(
            BATCH_QUALITY / "fixtures/valid/quality-metric-vectors-1.0.0.json"
        )
        expected_sources = {item["sourceId"] for item in catalog["sources"]}

        for source_id in sorted(expected_sources):
            missing = copy.deepcopy(vectors)
            missing["cases"] = [
                case
                for case in missing["cases"]
                if case.get("sourceId") != source_id
            ]
            self.assertIn(
                "QUALITY_VECTOR_SOURCE_OVERRIDE_COVERAGE_INVALID",
                metric_vector_issues(policy, missing),
                f"missing {source_id}",
            )

            replacement = next(
                case
                for case in vectors["cases"]
                if case.get("sourceId") not in {None, source_id}
                and "gateId" in case
            )
            replaced = copy.deepcopy(vectors)
            for case in replaced["cases"]:
                if case.get("sourceId") == source_id:
                    case_id = case["caseId"]
                    case.clear()
                    case.update(copy.deepcopy(replacement))
                    case["caseId"] = case_id
            self.assertIn(
                "QUALITY_VECTOR_SOURCE_OVERRIDE_COVERAGE_INVALID",
                metric_vector_issues(policy, replaced),
                f"replaced {source_id}",
            )

    def test_invalid_fixtures_and_binary_floats_fail_closed(self) -> None:
        policy = self.load(BATCH_QUALITY / "executable-quality-policy-1.0.0.json")
        invalid = self.load(BATCH_QUALITY / "fixtures/invalid/negative-fixtures-1.0.0.json")
        for case in invalid["cases"]:
            candidate = copy.deepcopy(case["document"])
            self.assertIn(case["expectedCode"], metric_vector_issues(policy, candidate), case["caseId"])

        vectors = self.load(BATCH_QUALITY / "fixtures/valid/quality-metric-vectors-1.0.0.json")
        candidate = copy.deepcopy(vectors)
        candidate["cases"][0]["input"]["numerator"] = 99.5
        self.assertIn("QUALITY_VECTOR_BINARY_FLOAT_FORBIDDEN", metric_vector_issues(policy, candidate))

    def test_policy_mutations_fail_closed_without_relying_on_the_lock(self) -> None:
        policy = self.load(BATCH_QUALITY / "executable-quality-policy-1.0.0.json")
        mutations = []

        formula = copy.deepcopy(policy)
        formula["sources"][0]["sourceGates"][0]["formulaId"] += "-renamed"
        mutations.append((formula, "BATCH_QUALITY_SOURCE_GATE_IDENTITY_INVALID"))

        threshold = copy.deepcopy(policy)
        threshold["sources"][0]["sourceGates"][0]["thresholdNumerator"] = 9800
        mutations.append((threshold, "BATCH_QUALITY_SOURCE_GATE_SEMANTICS_INVALID"))

        operand = copy.deepcopy(policy)
        operand["sources"][0]["sourceGates"][0]["calculation"]["numerator"] = {
            "kind": "measured",
            "operandId": "manifest-declared-record-count",
        }
        mutations.append((operand, "BATCH_QUALITY_SOURCE_GATE_SEMANTICS_INVALID"))

        no_activity = copy.deepcopy(policy)
        no_activity["sources"][0]["freshnessLanes"][0]["allowNoActivity"] = True
        mutations.append((no_activity, "BATCH_QUALITY_NO_ACTIVITY_OR_TIMEZONE_INVALID"))

        lane_sla = copy.deepcopy(policy)
        lane_sla["sources"][0]["freshnessLanes"][0]["rule"]["maxDurationMilliseconds"] = 14400001
        mutations.append((lane_sla, "BATCH_QUALITY_FRESHNESS_LANE_SEMANTICS_INVALID"))

        binding = copy.deepcopy(policy)
        binding["controlledInputs"]["dataCatalog"]["canonicalDigest"] = "sha256:" + "0" * 64
        mutations.append((binding, "BATCH_QUALITY_DCC_BINDING_INVALID"))

        for candidate, expected in mutations:
            with self.subTest(expected=expected):
                self.assertTrue(
                    any(item.startswith(expected) for item in policy_issues(PROJECT_ROOT, candidate)),
                    expected,
                )

    def test_raw_byte_lock_detects_whitespace_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in EXECUTABLE_LOCKED_FILES:
                source = PROJECT_ROOT / relative
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(source.read_bytes())
            lock_relative = "contracts/ingestion-quality/batch-quality/executable-quality-contract-lock-1.0.0.json"
            source_lock = PROJECT_ROOT / lock_relative
            destination_lock = root / lock_relative
            destination_lock.parent.mkdir(parents=True, exist_ok=True)
            destination_lock.write_bytes(source_lock.read_bytes())
            target = root / sorted(EXECUTABLE_LOCKED_FILES)[0]
            target.write_bytes(target.read_bytes() + b"\n")
            self.assertTrue(
                any(item.startswith("BATCH_QUALITY_LOCK_DIGEST_MISMATCH") for item in lock_issues(root))
            )

    @staticmethod
    def load(path: Path) -> dict:
        return json.loads(path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
