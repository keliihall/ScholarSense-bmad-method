from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_subject_registry_contracts import (  # noqa: E402
    EXPECTED_ADAPTERS,
    EXPECTED_CONTROLLED_FILES,
    evaluate_mapping_fixture,
    evaluate_ordering_fixture,
    execute_compatibility_fixture,
    execute_negative_fixture,
    validate,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
CONTRACT_ROOT = PROJECT_ROOT / "contracts/subject-registry"


class SubjectRegistryContractsTest(unittest.TestCase):
    def test_contract_set_and_lock_are_exact(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))
        lock = json.loads(
            (CONTRACT_ROOT / "subject-registry-contract-lock-1.0.0.json").read_text()
        )
        self.assertEqual(EXPECTED_CONTROLLED_FILES, set(lock["digests"]))

    def test_mapping_policy_freezes_canonical_id_and_source_adapters(self) -> None:
        policy = json.loads(
            (CONTRACT_ROOT / "subject-mapping-policy-1.0.0.json").read_text()
        )
        self.assertEqual("StudentRef", policy["canonicalSubjectId"])
        self.assertEqual("wire-alias-of-StudentRef", policy["subjectRefSemantics"])
        self.assertEqual(
            EXPECTED_ADAPTERS,
            {item["sourceId"] for item in policy["sourceAdapters"]},
        )
        self.assertEqual(
            ["no-match", "unique", "ambiguous"], policy["resolutionOutcomes"]
        )
        self.assertFalse(policy["matchingRules"]["fuzzyMatchingAllowed"])
        self.assertFalse(policy["matchingRules"]["crossSourceFallbackAllowed"])

    def test_only_authoritative_unique_student_proof_may_issue_student_ref(self) -> None:
        fixtures = json.loads(
            (CONTRACT_ROOT / "fixtures/valid/mapping-fixtures-1.0.0.json").read_text()
        )
        outcomes = {
            case["caseId"]: evaluate_mapping_fixture(case) for case in fixtures["cases"]
        }
        self.assertEqual("ISSUE_STUDENT_REF", outcomes["authoritative-unique"])
        self.assertEqual("ISOLATE_NO_MATCH", outcomes["no-match"])
        self.assertEqual("ISOLATE_AMBIGUOUS", outcomes["ambiguous"])
        self.assertEqual("ISOLATE_REISSUE_UNPROVEN", outcomes["identifier-reissued"])

    def test_mapping_policy_fails_closed_when_approval_or_version_drifts(self) -> None:
        policy = json.loads(
            (CONTRACT_ROOT / "subject-mapping-policy-1.0.0.json").read_text()
        )
        unapproved = copy.deepcopy(policy)
        unapproved["approval"]["status"] = "draft"
        unknown_version = copy.deepcopy(policy)
        unknown_version["policyVersion"] = "SMP-9.9.9"
        self.assertIn("SUBJECT_REGISTRY_POLICY_NOT_APPROVED", validate(PROJECT_ROOT, policy=unapproved))
        self.assertIn("SUBJECT_REGISTRY_POLICY_VERSION_UNKNOWN", validate(PROJECT_ROOT, policy=unknown_version))

    def test_recomputation_policy_has_no_quality_recovery_defaults(self) -> None:
        policy = json.loads(
            (CONTRACT_ROOT / "subject-recomputation-policy-1.0.0.json").read_text()
        )
        encoded = json.dumps(policy, ensure_ascii=False).lower()
        self.assertNotIn("windowdays", encoded)
        self.assertNotIn("subjectwindowssample", encoded)
        self.assertNotIn("makerchecker", encoded)
        self.assertEqual("required-from-rule-or-scenario-contract", policy["actionableBoundary"]["mode"])
        self.assertEqual(7, len(policy["jobIdentity"]["fields"]))

    def test_ordering_fixture_covers_duplicate_old_gap_backfill_and_completion(self) -> None:
        fixtures = json.loads(
            (CONTRACT_ROOT / "fixtures/ordering/ordering-fixtures-1.0.0.json").read_text()
        )
        outcomes = [evaluate_ordering_fixture(case) for case in fixtures["cases"]]
        self.assertEqual(
            ["APPLY", "DUPLICATE", "OLD_VERSION", "GAP_PAUSED", "BACKFILL_APPLY", "COMPLETED"],
            outcomes,
        )

    def test_all_negative_and_compatibility_fixtures_execute_to_exact_codes(self) -> None:
        negative = json.loads(
            (CONTRACT_ROOT / "fixtures/invalid/negative-fixtures-1.0.0.json").read_text()
        )
        self.assertEqual(
            [case["expectedCode"] for case in negative["cases"]],
            [execute_negative_fixture(case, PROJECT_ROOT) for case in negative["cases"]],
        )
        compatibility = json.loads(
            (CONTRACT_ROOT / "fixtures/compatibility/compatibility-fixtures-1.0.0.json").read_text()
        )
        self.assertEqual(
            [case["expected"] for case in compatibility["cases"]],
            [execute_compatibility_fixture(case) for case in compatibility["cases"]],
        )

    def test_forbidden_sensitive_fields_never_enter_wire_contracts(self) -> None:
        forbidden = {"rawIdentifier", "protectedIdentifierToken", "ciphertext", "keyValue", "evidenceBody"}
        for path in (
            PROJECT_ROOT / "contracts/events/subject-registry",
            PROJECT_ROOT / "contracts/openapi/subject-mapping.openapi.json",
        ):
            files = [path] if path.is_file() else list(path.rglob("*.json"))
            for file in files:
                document = json.loads(file.read_text())
                self.assertTrue(forbidden.isdisjoint(_keys(document)), file)


def _keys(value: object) -> set[str]:
    if isinstance(value, dict):
        return set(value) | {key for child in value.values() for key in _keys(child)}
    if isinstance(value, list):
        return {key for child in value for key in _keys(child)}
    return set()


if __name__ == "__main__":
    unittest.main()
