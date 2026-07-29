from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_identity_authority_contracts import ROLE_IDS, validate  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class IdentityAuthorityContractsTest(unittest.TestCase):
    def test_controlled_contracts_and_sandbox_mapping_pass(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))

    def test_mapping_is_complete_but_cannot_be_used_as_production_approval(self) -> None:
        mapping = json.loads(
            (
                PROJECT_ROOT
                / "contracts/identity-authority/sandbox-role-mapping-1.0.0.json"
            ).read_text(encoding="utf-8")
        )
        self.assertEqual(ROLE_IDS, {entry["targetRoleId"] for entry in mapping["entries"]})
        self.assertFalse(mapping["productionEligible"])
        self.assertEqual("sandbox", mapping["environment"])
        self.assertEqual(
            "SRC-P0-RESPONSIBILITY-001 controlled sandbox source owner",
            mapping["providedBy"],
        )
        self.assertEqual("Hei", mapping["approvedBy"])

    def test_organization_contract_carries_versioned_display_name(self) -> None:
        batch = json.loads(
            (
                PROJECT_ROOT
                / "contracts/identity-authority/fixtures/valid/incremental-batch.json"
            ).read_text(encoding="utf-8")
        )
        organizations = [
            record["payload"]
            for record in batch["records"]
            if record["recordKind"] == "organization"
        ]
        self.assertTrue(organizations)
        self.assertTrue(all(value["displayName"].strip() for value in organizations))

    def test_signed_heartbeat_freezes_successful_no_change_semantics(self) -> None:
        heartbeat = json.loads(
            (
                PROJECT_ROOT
                / "contracts/identity-authority/fixtures/valid/heartbeat.json"
            ).read_text(encoding="utf-8")
        )
        self.assertEqual("heartbeat", heartbeat["resultType"])
        self.assertEqual(heartbeat["fromWatermark"], heartbeat["toWatermark"])
        self.assertEqual([], heartbeat["records"])

    def test_runtime_sandbox_evidence_is_complete_and_redacted(self) -> None:
        evidence = json.loads(
            (
                PROJECT_ROOT
                / "_bmad-output/implementation-artifacts/evidence/"
                "1-6a-identity-authority-sandbox-trace.json"
            ).read_text(encoding="utf-8")
        )
        expected = {
            "account-added",
            "account-changed",
            "account-deactivated",
            "role-added-and-multi-role",
            "role-removed",
            "organization-added",
            "organization-renamed",
            "organization-reparented",
            "organization-deactivated",
            "employment-window-ended",
            "unknown-role",
            "subject-binding-conflict",
            "authentication-failure",
            "invalid-signature",
        }
        self.assertEqual("PASS", evidence["consumerResult"])
        self.assertFalse(evidence["productionEligible"])
        self.assertFalse(evidence["credentialsPersisted"])
        self.assertEqual(expected, set(evidence["scenarios"]))
        self.assertEqual(14, len(evidence["requests"]))
        self.assertEqual(
            14, len({request["traceId"] for request in evidence["requests"]})
        )
        self.assertEqual(
            [
                "controlled-provider",
                "identity-sync-worker",
                "PostgreSQL-18.4",
                "current-authorization-read-back",
            ],
            evidence["endToEndPath"],
        )
        self.assertEqual(
            evidence["endToEndTraceId"],
            evidence["endToEndRequest"]["traceId"],
        )
        serialized = json.dumps(evidence, ensure_ascii=False).lower()
        for forbidden in ("controlled-sandbox-workload-v1", "signature-key-v1"):
            self.assertNotIn(forbidden, serialized)

    def test_mapping_digest_and_unknown_role_fail_closed(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/identity-authority/sandbox-role-mapping-1.0.0.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["entries"][0]["targetRoleId"] = "R8-FORGED"
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "IDENTITY_AUTHORITY_MAPPING_INVALID")

    def test_contract_lock_detects_schema_drift(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/identity-authority/account.schema.json"
            path.write_text(path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
            self.assert_reason(root, "IDENTITY_AUTHORITY_CONTRACT_LOCK_DIGEST_MISMATCH")

    def test_negative_catalog_covers_ordering_binding_and_org_failures(self) -> None:
        catalog = json.loads(
            (
                PROJECT_ROOT
                / "contracts/identity-authority/fixtures/negative-fixtures-1.0.0.json"
            ).read_text(encoding="utf-8")
        )
        self.assertIn("cursor-gap", {case["id"] for case in catalog["cases"]})
        self.assertIn("duplicate-binding", {case["id"] for case in catalog["cases"]})
        self.assertIn("cyclic-organization", {case["id"] for case in catalog["cases"]})
        payloads = json.loads(
            (
                PROJECT_ROOT
                / "contracts/identity-authority/fixtures/negative/"
                "payloads-1.0.0.json"
            ).read_text(encoding="utf-8")
        )["cases"]
        self.assertTrue(
            all(
                set(value["payload"])
                >= {
                    "schemaVersion",
                    "sourceId",
                    "batchId",
                    "records",
                    "signatureDigest",
                }
                for value in payloads.values()
            )
        )

    def test_one_year_source_facts_reference_one_rebuildable_batch_archive(self) -> None:
        migration = (
            PROJECT_ROOT
            / "backend/src/main/resources/db/migration/identity-access/"
            "V000006__identity-access__authoritative_identity_org_v1.sql"
        ).read_text(encoding="utf-8")
        source_fact = migration.split(
            "create table identity_access.ia_identity_source_fact (", 1
        )[1].split("\n);", 1)[0]
        archive = migration.split(
            "create table identity_access.ia_identity_source_archive (", 1
        )[1].split("\n);", 1)[0]
        self.assertIn(
            "references identity_access.ia_identity_source_archive(batch_id)",
            source_fact,
        )
        self.assertNotIn("encrypted_normalized_payload", source_fact)
        for field in (
            "encrypted_payload bytea not null",
            "encrypted_data_key bytea not null",
            "encryption_nonce bytea not null",
            "encryption_key_ref varchar(255) not null",
            "encryption_key_version varchar(64) not null",
        ):
            self.assertIn(field, archive)

    def assert_reason(self, root: Path, reason: str) -> None:
        actual = validate(root)
        self.assertTrue(
            any(value.startswith(reason) for value in actual),
            f"expected {reason}, got {actual}",
        )

    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        shutil.copytree(
            PROJECT_ROOT / "contracts/identity-authority",
            root / "contracts/identity-authority",
        )

        class Context:
            def __enter__(self):
                return root

            def __exit__(self, *_args):
                temporary.cleanup()

        return Context()


if __name__ == "__main__":
    unittest.main()
