from __future__ import annotations

import copy
import hashlib
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_quality_snapshot_hash_contracts import (  # noqa: E402
    APPROVED_LOCK_RAW_SHA256,
    APPROVED_PROFILE_CANONICAL_DIGEST,
    APPROVED_PROFILE_RAW_SHA256,
    LOCKED_QSHM_FILES,
    UPSTREAM_BINDINGS,
    build_material,
    canonical_hash,
    canonical_material_bytes,
    material_issues,
    negative_fixture_issues,
    validate,
    vector_issues,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
CONTRACT_ROOT = PROJECT_ROOT / "contracts/ingestion-quality/batch-quality"
PROFILE = CONTRACT_ROOT / "quality-snapshot-hash-profile-1.0.0.json"
VECTORS = CONTRACT_ROOT / "fixtures/valid/quality-snapshot-hash-vectors-1.0.0.json"
NEGATIVE = (
    CONTRACT_ROOT
    / "fixtures/invalid/quality-snapshot-hash-negative-fixtures-1.0.0.json"
)
LOCK = CONTRACT_ROOT / "quality-snapshot-hash-contract-lock-1.0.0.json"
POLICY = CONTRACT_ROOT / "executable-quality-policy-1.0.0.json"


class QualitySnapshotHashContractTest(unittest.TestCase):
    def test_qshm_schema_profile_vectors_negative_lock_and_upstreams_pass(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))

    def test_profile_is_the_exact_approved_addendum_without_raw_count(self) -> None:
        profile = self.load(PROFILE)
        self.assertEqual("QSHM-1.0.0", profile["hashProfileVersion"])
        self.assertEqual("DEC-019-QSHM-ADDENDUM", profile["addendumId"])
        self.assertEqual("AUTH-2026-08-09-001", profile["approvalRef"])
        self.assertEqual(
            "scholarsense.ingestion-quality.quality-snapshot.immutable-hash.v1",
            profile["domainTag"],
        )
        self.assertEqual(
            [
                "snapshotId",
                "evaluatedAt",
                "traceId",
                "aggregateVersion",
                "immutableHash",
            ],
            profile["excludedSnapshotFields"],
        )
        self.assertNotIn("hashProfileDigest", profile)
        self.assertNotIn("rawCount", json.dumps(profile, ensure_ascii=False))
        self.assertEqual(APPROVED_PROFILE_RAW_SHA256, hashlib.sha256(PROFILE.read_bytes()).hexdigest())
        self.assertEqual(
            APPROVED_PROFILE_CANONICAL_DIGEST,
            "sha256:" + hashlib.sha256(canonical_material_bytes(profile)).hexdigest(),
        )
        lock = self.load(LOCK)
        self.assertEqual(APPROVED_PROFILE_CANONICAL_DIGEST, lock["profileCanonicalDigest"])
        self.assertEqual(APPROVED_LOCK_RAW_SHA256, hashlib.sha256(LOCK.read_bytes()).hexdigest())

    def test_golden_vectors_pin_exact_canonical_utf8_hex_and_lowercase_hash(self) -> None:
        profile = self.load(PROFILE)
        policy = self.load(POLICY)
        vectors = self.load(VECTORS)
        for case in vectors["cases"]:
            material = build_material(case["snapshot"], profile, policy)
            canonical = canonical_material_bytes(material)
            self.assertEqual(case["expectedCanonicalUtf8Hex"], canonical.hex(), case["caseId"])
            self.assertEqual(case["expectedImmutableHash"], canonical_hash(material), case["caseId"])
            self.assertRegex(case["expectedImmutableHash"], r"^sha256:[0-9a-f]{64}$")

    def test_profile_freezes_minimal_string_escaping_and_golden_exercises_it(self) -> None:
        profile = self.load(PROFILE)
        self.assertEqual(
            {
                "strategy": "minimal-json-escapes",
                "quotationMark": "\\\"",
                "reverseSolidus": "\\\\",
                "backspace": "\\b",
                "formFeed": "\\f",
                "lineFeed": "\\n",
                "carriageReturn": "\\r",
                "tab": "\\t",
                "otherControls": "lowercase-\\u00xx",
                "solidus": "unescaped",
                "nonAscii": "direct-utf8",
                "unicodeScalarsOnly": True,
            },
            profile["stringEscaping"],
        )
        policy = self.load(POLICY)
        root = self.load(VECTORS)["cases"][0]["snapshot"]
        encoded = canonical_material_bytes(build_material(root, profile, policy))
        self.assertIn(
            b'"watermark":"src\\nquote\\"slash\\\\tab\\tcontrol\\u0001"',
            encoded,
        )
        self.assertNotIn(b"\\u000a", encoded)

    def test_golden_successor_has_a_real_same_source_same_lineage_predecessor(self) -> None:
        profile = self.load(PROFILE)
        policy = self.load(POLICY)
        vectors = self.load(VECTORS)
        by_id = {case["caseId"]: case for case in vectors["cases"]}
        predecessor = by_id["offcampus-predecessor-golden"]
        successor = by_id["successor-not-applicable-golden"]
        self.assertIsNone(predecessor["snapshot"]["supersedesSnapshotId"])
        self.assertEqual(
            successor["snapshot"]["supersedesSnapshotId"],
            predecessor["snapshot"]["snapshotId"],
        )
        self.assertEqual(
            successor["snapshot"]["sourceId"],
            predecessor["snapshot"]["sourceId"],
        )
        self.assertEqual(
            successor["snapshot"]["lineageId"],
            predecessor["snapshot"]["lineageId"],
        )

        self_reference = copy.deepcopy(successor["snapshot"])
        self_reference["supersedesSnapshotId"] = self_reference["snapshotId"]
        self.assertEqual(
            ["QSHM_LINEAGE_INVALID"],
            material_issues(self_reference, profile, policy),
        )

        drifted = copy.deepcopy(vectors)
        drifted_predecessor = next(
            case for case in drifted["cases"]
            if case["caseId"] == "offcampus-predecessor-golden"
        )
        drifted_predecessor["snapshot"]["lineageId"] = (
            "019fe66d-7c00-7000-8000-000000000099"
        )
        self.assertTrue(
            any(
                issue.startswith("QSHM_VECTOR_LINEAGE_INVALID")
                for issue in vector_issues(PROJECT_ROOT, profile, policy, drifted)
            )
        )

    def test_unapproved_reason_code_cannot_enter_a_valid_snapshot_hash(self) -> None:
        profile = self.load(PROFILE)
        policy = self.load(POLICY)
        snapshot = copy.deepcopy(self.load(VECTORS)["cases"][0]["snapshot"])
        snapshot["metricResults"][0]["reasonCode"] = "UNCONTROLLED_REASON"
        self.assertEqual(
            ["QSHM_METRIC_VALUE_INVALID"],
            material_issues(snapshot, profile, policy),
        )

    def test_policy_order_is_rebuilt_and_input_order_is_not_trusted(self) -> None:
        profile = self.load(PROFILE)
        policy = self.load(POLICY)
        snapshot = copy.deepcopy(self.load(VECTORS)["cases"][0]["snapshot"])
        expected = build_material(snapshot, profile, policy)
        snapshot["metricResults"].reverse()
        snapshot["impactScopeCodes"].reverse()
        actual = build_material(snapshot, profile, policy)
        self.assertEqual(expected, actual)
        source = next(item for item in policy["sources"] if item["sourceId"] == snapshot["sourceId"])
        selected = set(source["applicableCommonMetricIds"])
        expected_formulas = [
            item["formulaId"] for item in policy["commonMetrics"]
            if item["metricId"] in selected
        ] + [item["formulaId"] for item in source["sourceGates"]]
        self.assertEqual(expected_formulas, [item["formulaId"] for item in actual["metricResults"]])
        shared = [
            item for item in actual["metricResults"]
            if item["metricId"] == "SOURCE_CONTINUITY_GATE"
        ]
        self.assertEqual(2, len(shared))
        self.assertEqual(2, len({item["formulaId"] for item in shared}))
        self.assertGreaterEqual(len(actual["metricResults"]), 3)
        self.assertEqual(
            ["CONTINUITY", "PRIMARY_KEY", "\ue000", "\U00010000"],
            actual["impactScopeCodes"],
        )

    def test_retry_only_fields_are_excluded_but_business_lineage_is_bound(self) -> None:
        profile = self.load(PROFILE)
        policy = self.load(POLICY)
        snapshot = copy.deepcopy(self.load(VECTORS)["cases"][0]["snapshot"])
        baseline = canonical_hash(build_material(snapshot, profile, policy))
        snapshot.update({
            "snapshotId": "019fe66d-7c00-7000-8000-000000000099",
            "evaluatedAt": "2026-08-10T01:02:03.000004Z",
            "traceId": "ffeeddccbbaa99887766554433221100",
            "aggregateVersion": 77,
            "immutableHash": "sha256:" + "f" * 64,
        })
        self.assertEqual(baseline, canonical_hash(build_material(snapshot, profile, policy)))
        snapshot["supersedesSnapshotId"] = "019fe66d-7c00-7000-8000-000000000003"
        self.assertNotEqual(baseline, canonical_hash(build_material(snapshot, profile, policy)))

    def test_every_included_top_level_and_metric_field_is_hash_covered(self) -> None:
        profile = self.load(PROFILE)
        policy = self.load(POLICY)
        snapshot = copy.deepcopy(self.load(VECTORS)["cases"][0]["snapshot"])
        material = build_material(snapshot, profile, policy)
        baseline = canonical_hash(material)
        for field in profile["includedTopLevelFields"]:
            candidate = copy.deepcopy(material)
            self.mutate(candidate, field)
            self.assertNotEqual(baseline, canonical_hash(candidate), field)
        for field in profile["metricResultFields"]:
            candidate = copy.deepcopy(material)
            self.mutate(candidate["metricResults"][0], field)
            self.assertNotEqual(baseline, canonical_hash(candidate), f"metricResults[].{field}")

    def test_equivalent_offsets_normalize_to_fixed_utc_microseconds(self) -> None:
        profile = self.load(PROFILE)
        policy = self.load(POLICY)
        snapshot = copy.deepcopy(self.load(VECTORS)["cases"][0]["snapshot"])
        baseline = build_material(snapshot, profile, policy)
        snapshot["observationWindow"]["startAt"] = "2026-08-08T00:00:00Z"
        snapshot["observationWindow"]["endAt"] = "2026-08-09T00:00:00Z"
        snapshot["cutoffAt"] = "2026-08-09T00:00:00Z"
        snapshot["effectiveAt"] = "2026-08-09T02:02:22Z"
        actual = build_material(snapshot, profile, policy)
        self.assertEqual(baseline, actual)
        self.assertEqual("2026-08-09T02:02:22.000000Z", actual["effectiveAt"])
        self.assertEqual("2026-08-09T00:00:00.000000Z", actual["cutoffAt"])
        self.assertEqual("2026-08-08T00:00:00.000000Z", actual["observationWindow"]["startAt"])
        self.assertEqual("2026-08-09T00:00:00.000000Z", actual["observationWindow"]["endAt"])
        for instant in (
            actual["observationWindow"]["startAt"],
            actual["observationWindow"]["endAt"],
            actual["cutoffAt"],
            actual["effectiveAt"],
        ):
            self.assertRegex(instant, r"Z$")
            self.assertRegex(instant, r"\.\d{6}Z$")
        adjacent = copy.deepcopy(snapshot)
        adjacent["cutoffAt"] = "2026-08-09T00:00:00.000001Z"
        self.assertNotEqual(
            canonical_hash(actual),
            canonical_hash(build_material(adjacent, profile, policy)),
        )

    def test_runtime_oracle_binds_policy_gate_source_governance_and_retention(self) -> None:
        profile = self.load(PROFILE)
        policy = self.load(POLICY)
        baseline = self.load(VECTORS)["cases"][0]["snapshot"]
        mutations = {
            "qualityMetricDecisionProfileDigest": "sha256:" + "1" * 64,
            "qualityGateDigest": "sha256:" + "2" * 64,
            "sourceSchemaVersion": "BC-1.0.1",
            "sourceSchemaDigest": "sha256:" + "3" * 64,
            "sourceOwnerRef": "other owner",
            "approvalRef": "AUTH-2026-08-09-001",
            "effectiveAt": "2026-08-09T10:02:23+08:00",
            "retentionScheduleVersion": "RS-1.0.1",
            "canonicalizationProfile": "SCHOLARSENSE-CANONICAL-JSON-1.0.1",
        }
        for field, value in mutations.items():
            candidate = copy.deepcopy(baseline)
            candidate[field] = value
            self.assertEqual(
                ["QSHM_CONTROL_BINDING_INVALID"],
                material_issues(candidate, profile, policy),
                field,
            )

    def test_nullable_values_are_explicit_and_not_optional(self) -> None:
        profile = self.load(PROFILE)
        policy = self.load(POLICY)
        snapshot = copy.deepcopy(self.load(VECTORS)["cases"][0]["snapshot"])
        material = build_material(snapshot, profile, policy)
        self.assertIn("supersedesSnapshotId", material)
        self.assertIsNone(material["supersedesSnapshotId"])
        nullable_metric = next(item for item in material["metricResults"] if item["valueBasisPoints"] is None)
        self.assertIn("reasonCode", nullable_metric)
        self.assertIn("valueBasisPoints", nullable_metric)
        del snapshot["supersedesSnapshotId"]
        self.assertIn("QSHM_FIELD_SET_INVALID", material_issues(snapshot, profile, policy))

    def test_not_applicable_is_exact_and_zero_denominator_cannot_hash(self) -> None:
        profile = self.load(PROFILE)
        policy = self.load(POLICY)
        snapshot = copy.deepcopy(self.load(VECTORS)["cases"][1]["snapshot"])
        material = build_material(snapshot, profile, policy)
        item = next(result for result in material["metricResults"] if not result["applicable"])
        self.assertEqual(
            {
                "applicable": False,
                "numerator": 0,
                "denominator": 0,
                "valueBasisPoints": None,
                "result": "not-applicable",
                "reasonCode": None,
            },
            {key: item[key] for key in (
                "applicable", "numerator", "denominator", "valueBasisPoints",
                "result", "reasonCode",
            )},
        )
        broken = copy.deepcopy(snapshot)
        applicable = next(result for result in broken["metricResults"] if result["applicable"])
        applicable["denominator"] = 0
        self.assertIn("QSHM_ZERO_DENOMINATOR", material_issues(broken, profile, policy))

    def test_policy_predicate_cannot_be_forged_to_remove_a_hard_gate(self) -> None:
        profile = self.load(PROFILE)
        policy = self.load(POLICY)
        snapshot = copy.deepcopy(self.load(VECTORS)["cases"][0]["snapshot"])
        always_gate = next(
            result for result in snapshot["metricResults"]
            if result["formulaId"] == "QMDP-1.0.0/PRIMARY_KEY_COMPLETENESS_BP"
        )
        always_gate.update({
            "applicable": False,
            "numerator": 0,
            "denominator": 0,
            "valueBasisPoints": None,
            "result": "not-applicable",
            "reasonCode": None,
        })
        snapshot["overallResult"] = "quality-passed"
        snapshot["assessedBatchStatus"] = "quality-passed"

        self.assertEqual(
            ["QSHM_APPLICABILITY_INVALID"],
            material_issues(snapshot, profile, policy),
        )

    def test_composite_passing_members_cannot_exceed_applicable_members(self) -> None:
        profile = self.load(PROFILE)
        policy = self.load(POLICY)
        snapshot = copy.deepcopy(self.load(VECTORS)["cases"][1]["snapshot"])
        composite = next(
            result for result in snapshot["metricResults"]
            if result["formulaId"] == "QMDP-1.0.0/SOURCE_CONTINUITY_GATE"
        )
        composite.update({
            "numerator": 2,
            "denominator": 1,
            "valueBasisPoints": 20_000,
            "result": "failed",
        })
        snapshot["overallResult"] = "quality-failed"
        snapshot["assessedBatchStatus"] = "quality-failed"

        self.assertEqual(
            ["QSHM_OPERAND_RELATION_INVALID"],
            material_issues(snapshot, profile, policy),
        )

    def test_subset_ratio_cannot_exceed_its_applicable_denominator(self) -> None:
        profile = self.load(PROFILE)
        policy = self.load(POLICY)
        snapshot = copy.deepcopy(self.load(VECTORS)["cases"][0]["snapshot"])
        metric = next(
            result for result in snapshot["metricResults"]
            if result["formulaId"] == "QMDP-1.0.0/PRIMARY_KEY_COMPLETENESS_BP"
        )
        metric.update({
            "numerator": 10_001,
            "denominator": 10_000,
            "valueBasisPoints": 10_001,
            "result": "passed",
        })
        snapshot["overallResult"] = "quality-passed"
        snapshot["assessedBatchStatus"] = "quality-passed"
        self.assertEqual(
            ["QSHM_OPERAND_RELATION_INVALID"],
            material_issues(snapshot, profile, policy),
        )

    def test_calendar_exact_one_ratio_preserves_duplicate_count_as_quality_failure(self) -> None:
        profile = self.load(PROFILE)
        policy = self.load(POLICY)
        snapshot = copy.deepcopy(self.load(VECTORS)["cases"][0]["snapshot"])
        calendar = next(
            result for result in snapshot["metricResults"]
            if result["formulaId"].endswith("/calendar-exactly-one-current-day-type")
        )
        calendar.update({
            "numerator": 2,
            "denominator": 1,
            "valueBasisPoints": 20_000,
            "result": "failed",
        })
        self.assertEqual([], material_issues(snapshot, profile, policy))

    def test_boolean_threshold_operands_cannot_alias_policy_integer_one(self) -> None:
        profile = self.load(PROFILE)
        policy = self.load(POLICY)
        baseline = self.load(VECTORS)["cases"][0]["snapshot"]
        for field in ("thresholdNumerator", "thresholdDenominator"):
            snapshot = copy.deepcopy(baseline)
            metric = next(
                item for item in snapshot["metricResults"]
                if item[field] == 1
            )
            metric[field] = True
            self.assertEqual(
                ["QSHM_METRIC_VALUE_INVALID"],
                material_issues(snapshot, profile, policy),
                field,
            )

    def test_negative_fixture_mutations_are_all_rejected_with_expected_codes(self) -> None:
        self.assertEqual([], negative_fixture_issues(PROJECT_ROOT))

    def test_duplicate_json_keys_float_negative_zero_and_unsafe_integer_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_scope(root)
            vectors = root / VECTORS.relative_to(PROJECT_ROOT)
            vectors.write_text('{"fixtureVersion":"QSHM-VECTORS-1.0.0","fixtureVersion":"duplicate"}', encoding="utf-8")
            issues = validate(root)
        self.assertIn("QSHM_VECTOR_DOCUMENT_INVALID", issues)

        negative = self.load(NEGATIVE)
        case_ids = {item["caseId"] for item in negative["cases"]}
        self.assertTrue({
            "float-forbidden", "negative-zero-forbidden", "unsafe-integer-forbidden",
            "uuid-invalid", "digest-invalid", "duplicate-key-top",
            "duplicate-key-nested", "duplicate-key-metric", "policy-digest-mismatch",
            "source-schema-digest-mismatch", "source-id-mismatch", "metric-definition-drift",
            "utf8-bom-forbidden", "lone-surrogate-forbidden",
        }.issubset(case_ids))

    def test_profile_and_lock_cannot_be_jointly_refreshed_to_launder_new_semantics(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_scope(root)
            profile_path = root / PROFILE.relative_to(PROJECT_ROOT)
            schema_path = root / (
                CONTRACT_ROOT / "quality-snapshot-hash-profile.schema.json"
            ).relative_to(PROJECT_ROOT)
            lock_path = root / LOCK.relative_to(PROJECT_ROOT)

            profile = self.load(profile_path)
            profile["approvedAt"] = "2026-08-09T19:24:56+08:00"
            profile_path.write_text(json.dumps(profile, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            schema = self.load(schema_path)
            schema["properties"]["approvedAt"]["const"] = profile["approvedAt"]
            schema_path.write_text(json.dumps(schema, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

            lock = self.load(lock_path)
            lock["profileCanonicalDigest"] = (
                "sha256:" + hashlib.sha256(canonical_material_bytes(profile)).hexdigest()
            )
            changed = {
                str(PROFILE.relative_to(PROJECT_ROOT)): profile_path,
                str(schema_path.relative_to(root)): schema_path,
            }
            for binding in lock["digests"]:
                target = changed.get(binding["path"])
                if target is not None:
                    binding["rawDigest"] = "sha256:" + hashlib.sha256(target.read_bytes()).hexdigest()
            lock_path.write_text(json.dumps(lock, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

            issues = validate(root)
        self.assertIn("QSHM_PROFILE_SEMANTICS_INVALID", issues)
        self.assertIn("QSHM_PROFILE_RAW_DIGEST_MISMATCH", issues)

    def test_schema_and_inner_digests_cannot_be_refreshed_past_outer_lock_pin(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_scope(root)
            schema_path = root / (
                CONTRACT_ROOT / "quality-snapshot-hash-material.schema.json"
            ).relative_to(PROJECT_ROOT)
            lock_path = root / LOCK.relative_to(PROJECT_ROOT)

            schema = self.load(schema_path)
            schema["properties"]["impactScopeCodes"]["items"]["maxLength"] = 65
            schema_path.write_text(json.dumps(schema, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            lock = self.load(lock_path)
            relative = str(schema_path.relative_to(root))
            binding = next(item for item in lock["digests"] if item["path"] == relative)
            binding["rawDigest"] = "sha256:" + hashlib.sha256(schema_path.read_bytes()).hexdigest()
            lock_path.write_text(json.dumps(lock, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

            issues = validate(root)
        self.assertIn("QSHM_LOCK_RAW_DIGEST_MISMATCH", issues)

    def test_additive_lock_detects_qshm_and_upstream_raw_or_canonical_drift(self) -> None:
        self.assertTrue(LOCKED_QSHM_FILES)
        self.assertTrue(UPSTREAM_BINDINGS)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_scope(root)
            target = root / "contracts/ingestion-quality/batch-quality/quality-snapshot-hash-profile-1.0.0.json"
            target.write_bytes(target.read_bytes() + b"\n")
            self.assertTrue(any(code.startswith("QSHM_LOCK_RAW_DIGEST_MISMATCH") for code in validate(root)))

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_scope(root)
            relative = next(iter(UPSTREAM_BINDINGS))
            target = root / relative
            target.write_bytes(target.read_bytes() + b"\n")
            self.assertTrue(any(code.startswith("QSHM_UPSTREAM_RAW_DIGEST_MISMATCH") for code in validate(root)))

    @staticmethod
    def load(path: Path) -> dict:
        return json.loads(path.read_text(encoding="utf-8"))

    @staticmethod
    def mutate(document: dict, field: str) -> None:
        if field == "domainTag":
            document[field] += ".changed"
        elif field == "hashProfileVersion":
            document[field] = "QSHM-1.0.1"
        elif field == "hashProfileDigest" or field.endswith("Digest"):
            document[field] = "sha256:" + "e" * 64
        elif field == "observationWindow":
            document[field]["endAt"] = "2026-08-10T00:00:00.000001Z"
        elif field == "metricResults":
            document[field][0]["numerator"] += 1
        elif field == "impactScopeCodes":
            document[field] = [*document[field], "ZZZ_CHANGED"]
        elif field == "supersedesSnapshotId":
            document[field] = "019fe66d-7c00-7000-8000-000000000003"
        elif isinstance(document[field], bool):
            document[field] = not document[field]
        elif isinstance(document[field], int):
            document[field] += 1
        elif document[field] is None:
            document[field] = "CHANGED"
        else:
            document[field] = f"{document[field]}-changed"

    @staticmethod
    def copy_scope(root: Path) -> None:
        paths = set(LOCKED_QSHM_FILES) | set(UPSTREAM_BINDINGS)
        for relative in paths:
            source = PROJECT_ROOT / relative
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)


if __name__ == "__main__":
    unittest.main()
