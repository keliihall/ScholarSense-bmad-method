from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_audit_contracts import validate  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class AuditContractsTest(unittest.TestCase):
    def test_controlled_audit_contracts_and_fixtures_pass(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))

    def test_fact_schema_rejects_unknown_fields(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/audit/fixtures/valid/local-audit-fact.json"
            value = self.read(path)
            value["rawStudentId"] = "2026000001"
            self.write(path, value)

            self.assert_reason(root, "AUDIT_FIXTURE_SCHEMA_REJECTED")

    def test_semantic_checker_enforces_uuid_v7_and_utc(self) -> None:
        cases = {
            "uuid-v4": ("auditId", "550e8400-e29b-41d4-a716-446655440000", "AUDIT_ID_NOT_UUID_V7"),
            "local-offset": ("occurredAt", "2026-07-20T10:00:00+08:00", "AUDIT_TIME_NOT_UTC"),
        }
        for label, (field, replacement, reason) in cases.items():
            with self.subTest(label=label), self.fixture() as root:
                path = root / "contracts/audit/fixtures/valid/local-audit-fact.json"
                value = self.read(path)
                value[field] = replacement
                self.write(path, value)
                self.assert_reason(root, reason)

    def test_semantic_checker_rejects_unknown_catalog_codes_and_incomplete_authorization(self) -> None:
        cases = {
            "action": ("action", "identity.session.unregistered", "AUDIT_ACTION_UNREGISTERED"),
            "outcome": ("outcome", "probably", "AUDIT_OUTCOME_UNREGISTERED"),
            "reason": ("reasonCode", "FREE_TEXT_REASON", "AUDIT_REASON_UNREGISTERED"),
        }
        for label, (field, replacement, reason) in cases.items():
            with self.subTest(label=label), self.fixture() as root:
                path = root / "contracts/audit/fixtures/valid/local-audit-fact.json"
                value = self.read(path)
                value[field] = replacement
                self.write(path, value)
                self.assert_reason(root, reason)

        with self.fixture() as root:
            path = root / "contracts/audit/fixtures/valid/local-audit-fact.json"
            value = self.read(path)
            value["authorizationContext"].pop("decision")
            self.write(path, value)
            self.assert_reason(root, "AUDIT_FIXTURE_SCHEMA_REJECTED")

    def test_identity_module_vocabulary_rejects_sensitive_shaped_codes(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/audit/fixtures/valid/local-audit-fact.json"
            value = self.read(path)
            value["purpose"] = "STUDENT_2026000001"
            self.write(path, value)
            self.assert_reason(root, "AUDIT_IDENTITY_VOCABULARY_INVALID")

    def test_action_catalog_rejects_illegal_outcome_reason_combinations(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/audit/fixtures/valid/local-audit-fact.json"
            value = self.read(path)
            value["outcome"] = "accepted"
            value["reasonCode"] = "IDENTITY_SESSION_EXPIRED"
            self.write(path, value)
            self.assert_reason(root, "AUDIT_RESULT_COMBINATION_UNREGISTERED")

    def test_time_order_uses_parsed_instants_instead_of_lexical_fraction_comparison(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/audit/fixtures/valid/local-audit-fact.json"
            value = self.read(path)
            value["occurredAt"] = "2026-07-20T02:00:00.10Z"
            value["recordedAt"] = "2026-07-20T02:00:00.2Z"
            self.write(path, value)
            issues = validate(root)
            self.assertFalse(any(issue.startswith("AUDIT_TIME_WINDOW_INVALID") for issue in issues), issues)

    def test_outbox_data_must_contain_the_complete_matching_fact(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/audit/fixtures/valid/local-audit-outbox.json"
            value = self.read(path)
            value["envelope"]["data"].pop("reasonCode")
            self.write(path, value)
            self.assert_reason(root, "AUDIT_OUTBOX_FACT_MISMATCH")
            self.assert_reason(root, "AUDIT_FIXTURE_SCHEMA_REJECTED")

        with self.fixture() as root:
            path = root / "contracts/audit/fixtures/valid/local-audit-outbox.json"
            value = self.read(path)
            value["envelope"]["data"]["traceId"] = "not-a-trace-id"
            self.write(path, value)
            self.assert_reason(root, "AUDIT_FIXTURE_SCHEMA_REJECTED")

    def test_outbox_subject_is_bound_to_the_row_audit_id(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/audit/fixtures/valid/local-audit-outbox.json"
            value = self.read(path)
            value["envelope"]["subject"] = "audit/019bf18e-6c00-7000-8000-000000000099"
            self.write(path, value)
            self.assert_reason(root, "AUDIT_OUTBOX_ENVELOPE_MISMATCH")

    def test_not_applicable_authorization_cannot_claim_policy_or_scopes(self) -> None:
        for field, replacement in (("policyVersion", "ISP-1.0.0"),
                                   ("scopeCodes", ["CURRENT_SESSION"])):
            with self.subTest(field=field), self.fixture() as root:
                path = root / "contracts/audit/fixtures/valid/local-audit-fact.json"
                value = self.read(path)
                value["actorType"] = "anonymous"
                value["actorSearchToken"] = None
                value["roleIds"] = []
                value["objectType"] = None
                value["objectSearchToken"] = None
                value["aggregateType"] = None
                value["aggregateIdSearchToken"] = None
                value["aggregateVersion"] = None
                value["authorizationContext"] = {
                    "decision": "not-applicable",
                    "policyVersion": None,
                    "scopeCodes": [],
                    "grantSearchTokens": [],
                    "notApplicableReason": "PRE_AUTHENTICATION",
                }
                value["authorizationContext"][field] = replacement
                self.write(path, value)
                self.assert_reason(root, "AUDIT_ANONYMOUS_CONTEXT_FORGED")

    def test_minimization_checker_rejects_raw_sensitive_values_and_keys(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/audit/fixtures/valid/local-audit-fact.json"
            value = self.read(path)
            value["authorizationContext"]["rawIp"] = "192.0.2.15"
            self.write(path, value)
            self.assert_reason(root, "AUDIT_SENSITIVE_FIELD_FORBIDDEN")

        with self.fixture() as root:
            path = root / "contracts/audit/fixtures/valid/local-audit-fact.json"
            value = self.read(path)
            value["actorSearchToken"] = "student-2026000001"
            self.write(path, value)
            self.assert_reason(root, "AUDIT_SEARCH_TOKEN_INVALID")

    def test_clock_binding_references_pp_single_source_without_copying_maximum_skew(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/audit/trusted-clock-runtime-binding-1.0.0.json"
            value = self.read(path)
            value["maximumSkewMs"] = 100
            self.write(path, value)
            self.assert_reason(root, "AUDIT_CLOCK_SKEW_DUPLICATED")

    def test_action_catalog_covers_identity_and_reserves_future_fr8_actions(self) -> None:
        catalog = self.read(PROJECT_ROOT / "contracts/audit/action-catalog-1.0.0.json")
        actions = {entry["code"]: entry for entry in catalog["actions"]}
        self.assertEqual("active", actions["identity.session.login"]["status"])
        self.assertEqual("active", actions["identity.session.view"]["status"])
        for code in {
            "audit.search.execute",
            "report.export.download",
            "clue.follow-up.record",
            "collaboration.transfer.submit",
            "rule.version.publish",
            "rule.whitelist.change",
            "report.dashboard.drill-down",
            "ingestion.quality.fuse",
            "authorization.access.deny",
        }:
            self.assertEqual("reserved", actions[code]["status"], code)

    def test_contract_lock_detects_silent_drift(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/audit/local-audit-fact.schema.json"
            path.write_text(path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
            self.assert_reason(root, "AUDIT_CONTRACT_LOCK_DIGEST_MISMATCH")

    def assert_reason(self, root: Path, reason: str) -> None:
        actual = validate(root)
        self.assertTrue(any(value.startswith(reason) for value in actual), f"expected {reason}, got {actual}")

    @staticmethod
    def read(path: Path):
        return json.loads(path.read_text(encoding="utf-8"))

    @staticmethod
    def write(path: Path, value) -> None:
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")

    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        shutil.copytree(PROJECT_ROOT / "contracts/audit", root / "contracts/audit")
        (root / "contracts/performance").mkdir(parents=True)
        shutil.copy2(
            PROJECT_ROOT / "contracts/performance/performance-profile-pp-1.0.0.json",
            root / "contracts/performance/performance-profile-pp-1.0.0.json",
        )

        class Context:
            def __enter__(self):
                return root

            def __exit__(self, *_args):
                temporary.cleanup()

        return Context()


if __name__ == "__main__":
    unittest.main()
