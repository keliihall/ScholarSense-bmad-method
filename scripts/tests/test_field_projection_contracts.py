from __future__ import annotations

import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import check_field_projection_contracts as field_projection_checker  # noqa: E402
from check_field_projection_contracts import fixture_issues, project_fixture, validate  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = PROJECT_ROOT / "contracts/field-projection"


class FieldProjectionContractTest(unittest.TestCase):
    def test_successor_contract_schema_fixture_lock_and_release_binding_pass(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))

    def test_binding_extends_the_frozen_rfp_without_copying_the_role_matrix(self) -> None:
        binding = self.load("field-projection-policy-binding-1.0.0.json")
        rfp = json.loads(
            (PROJECT_ROOT / "contracts/authorization/role-field-policy-rfp-1.0.0.json")
            .read_text(encoding="utf-8")
        )
        self.assertEqual("FIELD-PROJECTION-1.0.0", binding["schemaVersion"])
        self.assertEqual("RFP-1.0.0", binding["roleFieldPolicy"]["version"])
        self.assertEqual(
            hashlib.sha256(
                (PROJECT_ROOT / binding["roleFieldPolicy"]["path"]).read_bytes()
            ).hexdigest(),
            binding["roleFieldPolicy"]["sha256"],
        )
        self.assertNotIn("roles", binding)
        self.assertEqual({f"R{i}" for i in range(1, 8)}, {
            item["roleId"] for item in rfp["roles"]
        })

    def test_field_catalog_is_an_exact_allowlist_with_global_hidden_precedence(self) -> None:
        binding = self.load("field-projection-policy-binding-1.0.0.json")
        fields = {item["name"]: item for item in binding["fields"]}
        self.assertEqual(
            {"AuditSearchRecord", "SubjectMappingException", "TransferOrder"},
            {item["objectClass"] for item in binding["objectSchemas"]},
        )
        self.assertEqual("B", fields["recordId"]["fieldClass"])
        self.assertEqual("C", fields["studentContactPhone"]["fieldClass"])
        self.assertEqual("T", fields["traceId"]["fieldClass"])
        self.assertEqual(
            {
                "diagnosisText", "counselingText", "evidenceBody", "networkContent",
                "thirdPartyContact", "keyValue",
            },
            set(binding["globalHiddenFields"]),
        )
        for name in binding["globalHiddenFields"]:
            self.assertTrue(fields[name]["globalHidden"])

    def test_seven_role_matrix_and_conditional_rules_are_table_driven(self) -> None:
        binding = self.load("field-projection-policy-binding-1.0.0.json")
        fixture = self.load("fixtures/valid/field-projection-oracle-1.0.0.json")
        rfp = json.loads(
            (PROJECT_ROOT / "contracts/authorization/role-field-policy-rfp-1.0.0.json")
            .read_text(encoding="utf-8")
        )
        expected = fixture["roleMatrixOracle"]
        actual = {item["roleId"]: item["fieldVisibility"] for item in rfp["roles"]}
        self.assertEqual(expected, actual)
        self.assertEqual(
            {
                "studentContactPhone", "studentContactEmail", "referralReasonCode",
                "requestedServiceCode", "referralSummary", "supplementRequestText",
                "resultSummary",
            },
            set(binding["conditionalRules"]["R5"]["closedFieldUniverse"]),
        )
        self.assertEqual("subject-mapping-repair", binding["conditionalRules"]["R6"]["purpose"])

    def test_transfer_allowlist_half_open_window_and_two_sinks_share_one_oracle(self) -> None:
        binding = self.load("field-projection-policy-binding-1.0.0.json")
        fixture = self.load("fixtures/valid/field-projection-oracle-1.0.0.json")
        scenarios = {item["scenarioId"]: item for item in fixture["scenarios"]}

        start = project_fixture(binding, scenarios["R5-TRANSFER-A-START"])
        end = project_fixture(binding, scenarios["R5-TRANSFER-A-END"])
        other = project_fixture(binding, scenarios["R5-TRANSFER-B"])

        self.assertEqual(start["json"], start["exportSink"])
        self.assertEqual(
            {
                "studentContactPhone", "requestedServiceCode", "referralSummary",
                "resultSummary",
            },
            set(start["clearConditionalFields"]),
        )
        self.assertEqual("FIELD_PROJECTION_DENIED", end["error"]["code"])
        self.assertEqual({}, end["json"])
        self.assertEqual({}, other["json"])

    def test_unknowns_and_invalid_purpose_fail_closed(self) -> None:
        binding = self.load("field-projection-policy-binding-1.0.0.json")
        fixture = self.load("fixtures/valid/field-projection-oracle-1.0.0.json")
        scenario = fixture["scenarios"][0]
        for field, value in (
            ("objectClass", "UnknownObject"),
            ("purpose", "client-supplied-purpose"),
        ):
            candidate = copy.deepcopy(scenario)
            candidate[field] = value
            result = project_fixture(binding, candidate)
            self.assertEqual({}, result["json"])
            self.assertEqual("FIELD_PROJECTION_DENIED", result["error"]["code"])

        unknown_field = copy.deepcopy(scenario)
        unknown_field["values"]["notApproved"] = "secret"
        result = project_fixture(binding, unknown_field)
        self.assertNotIn("notApproved", result["json"])

    def test_masks_are_fixed_and_do_not_depend_on_value_length_or_charset(self) -> None:
        binding = self.load("field-projection-policy-binding-1.0.0.json")
        fixture = self.load("fixtures/valid/field-projection-oracle-1.0.0.json")
        base = next(item for item in fixture["scenarios"] if item["scenarioId"] == "R3-AUDIT-BUSINESS")
        observed = set()
        for original in ("", "x", "中文敏感值", "🚀" * 128):
            candidate = copy.deepcopy(base)
            candidate["values"]["actorDisplayRef"] = original
            observed.add(project_fixture(binding, candidate)["json"]["actorDisplayRef"])
        self.assertEqual({"[MASKED-IDENTITY]"}, observed)

    def test_invalid_fixtures_are_rejected_with_stable_error_codes(self) -> None:
        binding = self.load("field-projection-policy-binding-1.0.0.json")
        invalid = self.load("fixtures/invalid/negative-fixtures-1.0.0.json")
        for case in invalid["cases"]:
            self.assertIn(case["expectedCode"], fixture_issues(binding, case["document"]), case["caseId"])

    def test_lock_detects_byte_drift_and_predecessor_locks_are_unchanged(self) -> None:
        expected = {
            "contracts/authorization/authorization-contract-lock-1.0.0.json":
                "0efd0d0c2a6949fff786c421bf12471cfd293979c64743aea0641a6248286c84",
            "contracts/audit/audit-contract-lock-1.0.0.json":
                "24a4861ddbfa9ac11253a26f6e3c906f1735d73c4d774d713faf9cee9f8cb62f",
            "contracts/audit/audit-contract-lock-1.1.0.json":
                "a07833c0c75ed28f6d0a534d697af0b09d3d5d8e0476f3c251c2bb8acd0439b9",
            "contracts/audit/audit-contract-lock-1.2.0.json":
                "cabb6259c4c3c9405823dba93a29de16c81ef730fbb04592bb20a9ad61f48c19",
            "contracts/audit/audit-contract-lock-1.3.0.json":
                "cd47617b3447c3476fa8b1036a2e488c2d527404eaf0de0f5a64444a74a29b7c",
            "contracts/audit/audit-contract-lock-1.4.0.json":
                "5244325f843b534ff0535c83446d44399b1cf2af8c521ec80920b2863046ae5a",
        }
        for relative, digest in expected.items():
            self.assertEqual(digest, hashlib.sha256((PROJECT_ROOT / relative).read_bytes()).hexdigest())

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "contracts/field-projection"
            target.mkdir(parents=True)
            for source in CONTRACTS.rglob("*"):
                if source.is_file():
                    destination = target / source.relative_to(CONTRACTS)
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(source.read_bytes())
            policy = target / "field-projection-policy-binding-1.0.0.json"
            policy.write_bytes(policy.read_bytes() + b"\n")
            issues = validate(root, include_release=False, include_predecessors=False)
        self.assertTrue(any(item.startswith("FIELD_PROJECTION_LOCK_DIGEST_MISMATCH") for item in issues))

    def test_release_manifest_registers_projection_lock_and_runtime_boundaries(self) -> None:
        sys.path.insert(0, str(PROJECT_ROOT / "release"))
        from assembly import CONTROLLED_INPUTS  # noqa: PLC0415
        from manifests import REQUIRED_CONTROLLED_INPUT_IDS  # noqa: PLC0415

        self.assertEqual(
            (
                "FIELD-PROJECTION-CONTRACT-LOCK-1.0.0",
                "contracts/field-projection/field-projection-contract-lock-1.0.0.json",
            ),
            CONTROLLED_INPUTS["FieldProjection"],
        )
        self.assertIn("FieldProjection", REQUIRED_CONTROLLED_INPUT_IDS)
        manifest = json.loads(
            (PROJECT_ROOT / "contracts/release/fixtures/valid/release-manifest.json")
            .read_text(encoding="utf-8")
        )
        runtime = {item["id"]: item for item in manifest["runtimeEvidence"]}
        for identity in ("export-job-download", "transfer-task", "mobile-projection"):
            self.assertEqual("none", runtime[identity]["runtimeEvidenceClaim"])

    def test_quality_snapshot_successor_is_exact_object_scoped_and_additive(self) -> None:
        binding = self.load("field-projection-policy-binding-1.1.0.json")
        predecessor = self.load("field-projection-policy-binding-1.0.0.json")
        expected_by_class = {
            "B": {
                "snapshotId", "batchId", "sourceId", "assessedBatchStatus", "overallResult",
                "observationWindow.startAt", "observationWindow.endAt", "cutoffAt", "evaluatedAt",
                "watermark", "metricResults[].metricId", "metricResults[].result",
                "metricResults[].applicable", "metricResults[].numerator",
                "metricResults[].denominator", "metricResults[].valueBasisPoints",
                "metricResults[].unit", "metricResults[].operator",
                "metricResults[].thresholdNumerator", "metricResults[].thresholdDenominator",
                "metricResults[].boundary",
            },
            "E": {"metricResults[].reasonCode", "impactScopeCodes[]"},
            "G": {"sourceOwnerRef", "approvalRef", "effectiveAt", "retentionScheduleVersion"},
            "T": {
                "qualityMetricDecisionProfileVersion", "qualityMetricDecisionProfileDigest",
                "qualityGateVersion", "qualityGateDigest", "metricResults[].formulaId",
                "metricResults[].formulaVersion", "canonicalizationProfile", "manifestDigest",
                "sourceSchemaVersion", "sourceSchemaDigest", "immutableHash", "traceId",
                "lineageId", "supersedesSnapshotId", "aggregateVersion",
            },
        }
        quality_snapshot = next(
            item for item in binding["objectSchemas"]
            if item["objectClass"] == "QualitySnapshot"
        )
        fields = quality_snapshot["fields"]
        actual_by_class = {
            field_class: {item["path"] for item in fields if item["fieldClass"] == field_class}
            for field_class in "BICSENGT"
        }
        self.assertEqual(expected_by_class, {
            key: value for key, value in actual_by_class.items() if value
        })
        self.assertEqual(42, len(fields))
        self.assertEqual(42, len({item["path"] for item in fields}))
        self.assertEqual(["data-quality.read"], quality_snapshot["approvedPurposes"])
        self.assertEqual("OWNED_SOURCE", quality_snapshot["requiredScopeAnchor"])
        self.assertEqual("G", next(
            item["fieldClass"] for item in fields
            if item["path"] == "retentionScheduleVersion"
        ))
        old_fields = {item["name"]: item for item in predecessor["fields"]}
        self.assertEqual("B", old_fields["retentionScheduleVersion"]["fieldClass"])
        self.assertNotIn("QualitySnapshot", {
            item["objectClass"] for item in predecessor["objectSchemas"]
        })
        self.assertNotIn("DataBatch", json.dumps(binding, ensure_ascii=False))

    def test_quality_snapshot_r6_oracle_is_owned_clear_and_unknowns_are_omitted(self) -> None:
        binding = self.load("field-projection-policy-binding-1.1.0.json")
        fixture = self.load("fixtures/valid/field-projection-oracle-1.1.0.json")
        scenarios = {item["scenarioId"]: item for item in fixture["scenarios"]}

        owned = field_projection_checker.project_quality_snapshot_fixture(
            binding, scenarios["R6-OWNED-QUALITY-SNAPSHOT"]
        )
        unowned = field_projection_checker.project_quality_snapshot_fixture(
            binding, scenarios["R6-UNOWNED-QUALITY-SNAPSHOT"]
        )
        unknown = field_projection_checker.project_quality_snapshot_fixture(
            binding, scenarios["R6-OWNED-UNKNOWN-PATH"]
        )

        self.assertIsNone(owned["error"])
        self.assertEqual(42, len(owned["json"]))
        self.assertEqual(owned["json"], owned["exportSink"])
        self.assertEqual("FIELD_PROJECTION_DENIED", unowned["error"]["code"])
        self.assertEqual({}, unowned["json"])
        self.assertNotIn("studentOfficialRef", unknown["json"])
        self.assertNotIn("metricResults[].evidenceBody", unknown["json"])
        self.assertEqual(unknown["json"], unknown["exportSink"])

    def test_quality_snapshot_invalid_vectors_and_runtime_parity_fail_closed(self) -> None:
        binding = self.load("field-projection-policy-binding-1.1.0.json")
        invalid = self.load("fixtures/invalid/negative-fixtures-1.1.0.json")
        for case in invalid["cases"]:
            issues = field_projection_checker.quality_snapshot_fixture_issues(
                binding, case["document"]
            )
            self.assertIn(case["expectedCode"], issues, case["caseId"])

        self.assertEqual([], field_projection_checker.runtime_parity_issues(PROJECT_ROOT))

    def test_quality_snapshot_successor_lock_detects_drift_and_preserves_1_0_bytes(self) -> None:
        expected_predecessor = {
            "field-projection.schema.json":
                "2a21e1c7285c9956ff15123ba0b4df011fe4e99440a1c34414a75dd7d6118c28",
            "field-projection-fixture.schema.json":
                "da39d2bf426a4e5c84a329301e154d00333026eca19c1e5027607cc8a54f5edb",
            "field-projection-policy-binding-1.0.0.json":
                "5a0592296a43049bf745f9e826df65dc3f4b0479cea17fd822e98ac04d92f880",
            "field-projection-contract-lock-1.0.0.json":
                "9b81341bab67d0c9c04858c90946c1184d5e02de67ff5c7752b3439b913ffee6",
        }
        for relative, digest in expected_predecessor.items():
            self.assertEqual(
                digest,
                hashlib.sha256((CONTRACTS / relative).read_bytes()).hexdigest(),
            )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "contracts/field-projection"
            target.mkdir(parents=True)
            for source in CONTRACTS.rglob("*"):
                if source.is_file():
                    destination = target / source.relative_to(CONTRACTS)
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(source.read_bytes())
            successor = target / "field-projection-policy-binding-1.1.0.json"
            successor.write_bytes(successor.read_bytes() + b"\n")
            issues = validate(root, include_release=False, include_predecessors=False)
        self.assertTrue(any(
            item.startswith("FIELD_PROJECTION_SUCCESSOR_LOCK_DIGEST_MISMATCH")
            for item in issues
        ))

    @staticmethod
    def load(relative: str) -> dict:
        return json.loads((CONTRACTS / relative).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
