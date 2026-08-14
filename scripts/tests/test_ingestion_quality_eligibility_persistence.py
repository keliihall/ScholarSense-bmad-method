import hashlib
import pathlib
import unittest

from scripts import check_ingestion_quality_eligibility_persistence as checker


ROOT = pathlib.Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / "backend/src/main/resources/db/migration"
FEATURE = MIGRATIONS / "ingestion-quality/V000015__ingestion-quality__quality_eligibility_v1.sql"
SUCCESSOR = MIGRATIONS / "ingestion-quality/V000016__ingestion-quality__quality_fuse_task_v1.sql"
FEATURE_SHA256 = "21a66478ce29bf71838f4375c7162f5bbd390d5db60661981c5acfa03c419edb"


class QualityEligibilityPersistenceContractTest(unittest.TestCase):
    def test_feature_is_preserved_and_v16_is_its_global_successor(self) -> None:
        inventory = checker.migration_inventory(ROOT)
        self.assertGreaterEqual(len(inventory), 16)
        self.assertEqual(
            list(range(1, len(inventory) + 1)),
            [version for version, _ in inventory],
        )
        self.assertEqual(FEATURE, inventory[14][1])
        self.assertEqual(SUCCESSOR, inventory[15][1])
        self.assertEqual(FEATURE_SHA256, hashlib.sha256(FEATURE.read_bytes()).hexdigest())
        predecessor = MIGRATIONS / (
            "ingestion-quality/V000014__ingestion-quality__data_batch_quality_snapshot_v1.sql"
        )
        self.assertEqual(
            "e7bcd0af9dad9c4d0effac8f9cdad073df86f4bfedf3fc9a6c5f9fc45fcd1571",
            hashlib.sha256(predecessor.read_bytes()).hexdigest(),
        )

    def test_atomic_owner_schema_and_privilege_surface_are_locked(self) -> None:
        sql = FEATURE.read_text(encoding="utf-8")
        for token in checker.REQUIRED_TOKENS:
            self.assertIn(token, sql)
        for forbidden in ("grant insert", "grant delete", "grant truncate"):
            self.assertNotIn(
                forbidden,
                "\n".join(
                    line.lower()
                    for line in sql.splitlines()
                    if "scholarsense_ingestion_quality_" in line
                ),
            )

    def test_migration_rejects_public_execute_and_mutable_history(self) -> None:
        issues = checker.validate(ROOT)
        self.assertEqual([], issues)


if __name__ == "__main__":
    unittest.main()
