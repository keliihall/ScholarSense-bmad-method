from __future__ import annotations

import copy
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_audit_retention_contracts import validate  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class AuditRetentionContractsTest(unittest.TestCase):
    def test_controlled_contracts_and_semantics_pass(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))

    def test_search_projection_rejects_hidden_fields_and_token_material(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/audit-retention/fixtures/valid/search-response.json"
            value = self.read(path)
            value["items"][0]["actorSearchToken"] = "v1.secret"
            self.write(path, value)
            self.assert_reason(root, "AUDIT_RETENTION_FORBIDDEN_FIELD")

    def test_schedule_and_hold_boundaries_are_frozen(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/audit-retention/fixtures/valid/retention-schedule.json"
            value = self.read(path)
            value["retentionYears"] = 2
            self.write(path, value)
            self.assert_reason(root, "AUDIT_RETENTION_SCHEDULE_INVALID")

        with self.fixture() as root:
            path = root / "contracts/audit-retention/fixtures/valid/legal-hold.json"
            value = self.read(path)
            value["endAt"] = value["startAt"]
            self.write(path, value)
            self.assert_reason(root, "AUDIT_RETENTION_HOLD_WINDOW_INVALID")

    def test_execution_cannot_forge_a_production_receipt(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/audit-retention/fixtures/valid/retention-execution.json"
            value = self.read(path)
            value["nonProductionEvidence"] = False
            value["scopeType"] = "cross-domain"
            self.write(path, value)
            self.assert_reason(root, "AUDIT_RETENTION_PRODUCTION_RECEIPT_FORBIDDEN")

    def test_unknown_major_policy_drift_and_idempotency_drift_fail_closed(self) -> None:
        cases = (
            ("retention-schedule.json", "scheduleVersion", "RS-2.0.0", "AUDIT_RETENTION_UNKNOWN_MAJOR"),
            ("retention-execution.json", "scheduleVersion", "RS-1.0.1", "AUDIT_RETENTION_POLICY_DRIFT"),
            ("retention-execution.json", "requestDigest", "0" * 64, "AUDIT_RETENTION_IDEMPOTENCY_INVALID"),
        )
        for filename, field, replacement, reason in cases:
            with self.subTest(filename=filename, field=field), self.fixture() as root:
                path = root / "contracts/audit-retention/fixtures/valid" / filename
                value = self.read(path)
                value[field] = replacement
                self.write(path, value)
                self.assert_reason(root, reason)

    def test_lock_detects_contract_drift_and_old_contract_locks_remain_unchanged(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/audit-retention/search-request.schema.json"
            path.write_text(path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
            self.assert_reason(root, "AUDIT_RETENTION_LOCK_DIGEST_MISMATCH")

        lock = self.read(PROJECT_ROOT / "contracts/audit-retention/audit-retention-contract-lock-1.0.0.json")
        self.assertEqual(
            {
                "audit": self.sha256(PROJECT_ROOT / "contracts/audit/audit-contract-lock-1.0.0.json"),
                "auditLedger": self.sha256(
                    PROJECT_ROOT / "contracts/audit-ledger/audit-ledger-contract-lock-1.0.0.json"
                ),
            },
            lock["consumedLockDigests"],
        )

    def assert_reason(self, root: Path, reason: str) -> None:
        issues = validate(root)
        self.assertTrue(any(issue.startswith(reason) for issue in issues), f"expected {reason}, got {issues}")

    @staticmethod
    def read(path: Path):
        return json.loads(path.read_text(encoding="utf-8"))

    @staticmethod
    def write(path: Path, value) -> None:
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    @staticmethod
    def sha256(path: Path) -> str:
        import hashlib

        return hashlib.sha256(path.read_bytes()).hexdigest()

    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        shutil.copytree(PROJECT_ROOT / "contracts/audit-retention", root / "contracts/audit-retention")
        shutil.copytree(PROJECT_ROOT / "contracts/audit", root / "contracts/audit")
        shutil.copytree(PROJECT_ROOT / "contracts/audit-ledger", root / "contracts/audit-ledger")

        class Context:
            def __enter__(self):
                return root

            def __exit__(self, *_args):
                temporary.cleanup()

        return Context()


if __name__ == "__main__":
    unittest.main()
