from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_audit_ledger_contracts import validate  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class AuditLedgerContractsTest(unittest.TestCase):
    def test_controlled_ledger_contract_family_passes(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))

    def test_policy_freezes_exact_boundaries_and_recovery_hysteresis(self) -> None:
        mutations = {
            "degraded-age": ("degraded", "oldestUnconfirmedAgeSeconds", 61),
            "blocked-count": ("blocked", "unconfirmedCount", 49_999),
            "healthy-count": ("recovery", "unconfirmedCountExclusiveMax", 10_001),
        }
        for label, (section, field, value) in mutations.items():
            with self.subTest(label=label), self.fixture() as root:
                path = root / "contracts/audit-ledger/audit-ingestion-policy-1.0.0.json"
                document = self.read(path)
                document["availabilityThresholds"][section][field] = value
                self.write(path, document)
                self.assert_reason(root, "AUDIT_LEDGER_POLICY_SEMANTICS_INVALID")

    def test_hash_profile_freezes_canonical_order_genesis_and_material(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/audit-ledger/hash-profile-1.0.0.json"
            document = self.read(path)
            document["hashMaterialFields"].remove("aggregateVersion")
            self.write(path, document)
            self.assert_reason(root, "AUDIT_LEDGER_HASH_PROFILE_INVALID")

        with self.fixture() as root:
            path = root / "contracts/audit-ledger/hash-profile-1.0.0.json"
            document = self.read(path)
            document["genesis"]["entryHash"] = "f" * 64
            self.write(path, document)
            self.assert_reason(root, "AUDIT_LEDGER_HASH_PROFILE_INVALID")

    def test_findings_and_alerts_reject_sensitive_plaintext_and_unknown_fields(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/audit-ledger/fixtures/valid/integrity-finding.json"
            document = self.read(path)
            document["studentId"] = "2026000001"
            self.write(path, document)
            self.assert_reason(root, "AUDIT_LEDGER_FIXTURE_SCHEMA_REJECTED")
            self.assert_reason(root, "AUDIT_LEDGER_SENSITIVE_FIELD_FORBIDDEN")

    def test_unknown_major_and_illegal_sequence_or_hash_are_rejected(self) -> None:
        cases = {
            "unknown-major": ("schemaVersion", "AUDIT-LEDGER-RECORD-2.0.0"),
            "zero-sequence": ("ledgerSequence", 0),
            "bad-hash": ("entryHash", "not-a-hash"),
        }
        for label, (field, value) in cases.items():
            with self.subTest(label=label), self.fixture() as root:
                path = root / "contracts/audit-ledger/fixtures/valid/ledger-record.json"
                document = self.read(path)
                document[field] = value
                self.write(path, document)
                self.assert_reason(root, "AUDIT_LEDGER_FIXTURE_SCHEMA_REJECTED")

    def test_negative_fixture_catalog_proves_collision_privacy_and_boundaries(self) -> None:
        suite = self.read(PROJECT_ROOT / "contracts/audit-ledger/fixtures/negative-fixtures-1.0.0.json")
        ids = {case["id"] for case in suite["cases"]}
        self.assertTrue({
            "unknown-ledger-major",
            "idempotency-collision",
            "finding-sensitive-key",
            "degraded-boundary-below",
            "blocked-boundary-below",
            "duplicate-json-key",
        }.issubset(ids))

    def test_negative_fixture_mutations_are_executed_not_only_named(self) -> None:
        mutations = {
            "unknown-major-not-applied": (
                "unknown-ledger-major",
                "schemaVersion=AUDIT-LEDGER-RECORD-2.0.0",
                "schemaVersion=AUDIT-LEDGER-RECORD-1.0.0",
            ),
            "collision-not-created": (
                "idempotency-collision",
                "same-auditId-different-sourceEventId",
                "different-auditId-different-sourceEventId",
            ),
            "privacy-key-not-sensitive": (
                "finding-sensitive-key",
                "studentId=2026000001",
                "safeDigest=2026000001",
            ),
            "boundary-is-no-longer-below": (
                "degraded-boundary-below",
                "oldestAge=59,count=9999",
                "oldestAge=60,count=9999",
            ),
        }
        for label, (case_id, original, replacement) in mutations.items():
            with self.subTest(label=label), self.fixture() as root:
                path = root / "contracts/audit-ledger/fixtures/negative-fixtures-1.0.0.json"
                document = self.read(path)
                case = next(value for value in document["cases"] if value["id"] == case_id)
                self.assertEqual(original, case["mutation"])
                case["mutation"] = replacement
                self.write(path, document)
                self.assert_reason(root, "AUDIT_LEDGER_NEGATIVE_FIXTURES_INVALID")

    def test_independent_lock_detects_drift_without_changing_historical_audit_lock(self) -> None:
        historical = (PROJECT_ROOT / "contracts/audit/audit-contract-lock-1.0.0.json").read_bytes()
        with self.fixture() as root:
            path = root / "contracts/audit-ledger/ledger-record.schema.json"
            path.write_text(path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
            self.assert_reason(root, "AUDIT_LEDGER_CONTRACT_LOCK_DIGEST_MISMATCH")
        self.assertEqual(
            historical,
            (PROJECT_ROOT / "contracts/audit/audit-contract-lock-1.0.0.json").read_bytes(),
        )

    def test_checker_is_wired_into_the_core_verification_entrypoint(self) -> None:
        verify_core = (PROJECT_ROOT / "scripts/verify_core.sh").read_text(encoding="utf-8")
        historical = verify_core.index("scripts/check_audit_contracts.py")
        ledger = verify_core.index("scripts/check_audit_ledger_contracts.py")
        self.assertLess(historical, ledger)

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
        shutil.copytree(PROJECT_ROOT / "contracts/audit-ledger", root / "contracts/audit-ledger")
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
