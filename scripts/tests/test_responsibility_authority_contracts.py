from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_responsibility_authority_contracts import validate  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class ResponsibilityAuthorityContractsTest(unittest.TestCase):
    def test_controlled_contracts_fixtures_and_lock_pass(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))

    def test_policy_freezes_source_owner_and_hei_approval(self) -> None:
        policy = self.read(
            PROJECT_ROOT
            / "contracts/responsibility-authority/responsibility-policy-1.0.0.json"
        )
        self.assertEqual(
            "SRC-P0-RESPONSIBILITY-001 source owner", policy["providedBy"]
        )
        self.assertEqual("Hei", policy["approvedBy"])
        self.assertEqual("Asia/Shanghai", policy["schedule"]["timeZone"])
        self.assertEqual("06:00:00", policy["schedule"]["localTime"])
        self.assertEqual(
            "2026-07-30", policy["schedule"]["firstBusinessDate"]
        )
        self.assertEqual(
            "existing-platform-keyed-pseudonymization",
            policy["recipientReferenceBinding"]["algorithm"],
        )
        self.assertEqual(
            "identity-external-ref",
            policy["recipientReferenceBinding"]["platformPurpose"],
        )
        self.assertEqual("0.999", policy["reconciliation"]["minimumMatchRate"])
        self.assertEqual(0, policy["reconciliation"]["maximumActiveUnmappedCount"])

    def test_token_rotation_vectors_do_not_leak_raw_references(self) -> None:
        vectors = self.read(
            PROJECT_ROOT
            / "contracts/responsibility-authority/student-token-vectors-1.0.0.json"
        )
        self.assertEqual(
            vectors["vectors"][0]["equivalenceDomain"],
            vectors["vectors"][1]["equivalenceDomain"],
        )
        serialized = json.dumps(vectors, ensure_ascii=False)
        for forbidden in ("student-number", "studentNumber", "学号"):
            self.assertNotIn(forbidden, serialized)

    def test_boundary_and_failure_catalog_is_complete(self) -> None:
        catalog = self.read(
            PROJECT_ROOT
            / "contracts/responsibility-authority/fixtures/negative-fixtures-1.0.0.json"
        )
        case_ids = {case["id"] for case in catalog["cases"]}
        self.assertTrue(
            {
                "partial-snapshot",
                "unsealed-snapshot",
                "missing-partition",
                "count-mismatch",
                "digest-mismatch",
                "duplicate-relation",
                "overlapping-primary",
                "inactive-recipient",
                "non-r1-recipient",
                "wrong-college",
                "exact-effective-end",
                "stale-source-version",
                "watermark-gap",
                "same-key-different-payload",
                "wrong-token-purpose",
                "unknown-token-key-version",
                "below-threshold",
            }.issubset(case_ids)
        )
        boundaries = self.read(
            PROJECT_ROOT
            / "contracts/responsibility-authority/fixtures/boundary-fixtures-1.0.0.json"
        )
        by_id = {case["id"]: case for case in boundaries["cases"]}
        self.assertEqual("VALID", by_id["exact-effective-start"]["expectedValidity"])
        self.assertEqual(
            "INVALID", by_id["exact-effective-end"]["expectedValidity"]
        )
        self.assertTrue(by_id["exactly-99-9-percent"]["qualityPassed"])
        self.assertFalse(by_id["below-99-9-percent"]["qualityPassed"])

    def test_contract_lock_detects_drift(self) -> None:
        with self.fixture() as root:
            path = (
                root
                / "contracts/responsibility-authority/responsibility-relation.schema.json"
            )
            path.write_text(path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
            issues = validate(root)
            self.assertTrue(
                any(
                    issue.startswith(
                        "RESPONSIBILITY_AUTHORITY_CONTRACT_LOCK_DIGEST_MISMATCH"
                    )
                    for issue in issues
                ),
                issues,
            )

    @staticmethod
    def read(path: Path) -> dict:
        return json.loads(path.read_text(encoding="utf-8"))

    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        shutil.copytree(
            PROJECT_ROOT / "contracts/responsibility-authority",
            root / "contracts/responsibility-authority",
        )

        class Context:
            def __enter__(self):
                return root

            def __exit__(self, *_args):
                temporary.cleanup()

        return Context()


if __name__ == "__main__":
    unittest.main()
