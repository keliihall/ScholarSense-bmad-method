from __future__ import annotations

import copy
import sys
import unittest
from unittest import mock
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_data_catalog_contracts import EXPECTED_SOURCES, canonical_digest  # noqa: E402
from release_json import load_json  # noqa: E402
import run_data_catalog_target_tests as target_runner  # noqa: E402
from run_data_catalog_target_tests import (  # noqa: E402
    candidate_identity,
    execute_target,
    preflight_handoff,
    sign_handoff,
    validate_report,
)


ROOT = Path(__file__).resolve().parents[2]
NOW = datetime(2026, 8, 4, 12, tzinfo=timezone.utc)
KEY = b"target-handoff-test-key-not-a-production-secret"
COMMIT = "a" * 40
TREE = "b" * 40


class DataCatalogTargetTest(unittest.TestCase):
    def handoff(self) -> dict:
        document = {
            "handoffVersion": "DCC-TARGET-HANDOFF-1.0.0",
            "authority": "approved-campus-source",
            "environment": "stage",
            "approvedSchemes": ["https"],
            "approvedHosts": ["source.internal.example"],
            "approvedIpCidrs": ["192.0.2.0/24"],
            "sourceEndpoints": {source: f"https://source.internal.example/conformance/{source}" for source in EXPECTED_SOURCES},
            "expectedCatalogDigest": canonical_digest(load_json(ROOT / "contracts/data-catalog/dcc-1.0.0.json")),
            "candidateCommit": COMMIT,
            "candidateTree": TREE,
            "revision": 7,
            "issuedAt": (NOW - timedelta(minutes=5)).isoformat(),
            "expiresAt": (NOW + timedelta(hours=1)).isoformat(),
        }
        document["signature"] = sign_handoff(document, KEY)
        return document

    def preflight(self, document: dict):
        return preflight_handoff(document, ROOT, authority="approved-campus-source", environment="stage",
                candidate_commit=COMMIT, candidate_tree=TREE, minimum_revision=7, now=NOW,
                trusted_signing_key=KEY)

    def test_tamper_expiry_rollback_authority_and_digest_fail_before_dns(self) -> None:
        mutations = []
        for field, value in (("authority", "wrong"), ("revision", 6), ("expectedCatalogDigest", "sha256:" + "0" * 64), ("expiresAt", (NOW - timedelta(seconds=1)).isoformat())):
            document = self.handoff()
            document[field] = value
            document["signature"] = sign_handoff(document, KEY)
            mutations.append(document)
        tampered = self.handoff()
        tampered["candidateTree"] = "c" * 40
        mutations.append(tampered)
        dns_calls = 0
        for document in mutations:
            with self.subTest(document=document):
                with self.assertRaises(ValueError):
                    preflight = self.preflight(document)
                    dns_calls += 1
                    execute_target(preflight, resolver=lambda host, port: {"192.0.2.10"})
        self.assertEqual(0, dns_calls)

    def test_dns_rebinding_or_unapproved_address_fails_before_connector(self) -> None:
        preflight = self.preflight(self.handoff())
        connector_calls = 0
        answers = iter(({"192.0.2.10"}, {"192.0.2.11"}))
        with self.assertRaisesRegex(ValueError, "DNS_REBINDING"):
            execute_target(preflight, resolver=lambda host, port: next(answers),
                    connector=lambda endpoint, ip, token: self.fail("connector must not run"))
        with self.assertRaisesRegex(ValueError, "IP_NOT_APPROVED"):
            execute_target(preflight, resolver=lambda host, port: {"203.0.113.10"},
                    connector=lambda endpoint, ip, token: self.fail("connector must not run"))
        self.assertEqual(0, connector_calls)

    def test_all_seventeen_real_results_must_pass_and_output_is_privacy_bounded(self) -> None:
        preflight = self.preflight(self.handoff())
        def connector(endpoint: str, ip: str, token: str | None) -> dict:
            source = endpoint.rsplit("/", 1)[1]
            return {"sourceId": source, "inputDigest": "sha256:" + "d" * 64,
                    "scenarios": [{"id": "provider-contract", "result": "pass"}], "cleanupResult": "pass"}
        report = execute_target(preflight, resolver=lambda host, port: {"192.0.2.10"}, connector=connector)
        self.assertEqual(17, report["sourceCount"])
        self.assertTrue(all(item["result"] == "pass" for item in report["sources"]))
        self.assertNotIn("records", report)
        self.assertEqual([], validate_report(report, ROOT))
        failing = copy.deepcopy(self.handoff())
        preflight = self.preflight(failing)
        with self.assertRaisesRegex(ValueError, "SCENARIO_FAILED"):
            execute_target(preflight, resolver=lambda host, port: {"192.0.2.10"},
                    connector=lambda endpoint, ip, token: {"sourceId": endpoint.rsplit("/", 1)[1],
                        "inputDigest": "sha256:" + "d" * 64,
                        "scenarios": [{"id": "provider-contract", "result": "skip"}], "cleanupResult": "pass"})

    def test_dirty_candidate_is_rejected_before_commit_tree_binding(self) -> None:
        with mock.patch.object(target_runner, "_git", return_value=" M controlled-file"):
            with self.assertRaisesRegex(ValueError, "CANDIDATE_DIRTY"):
                candidate_identity(ROOT)


if __name__ == "__main__":
    unittest.main()
