from __future__ import annotations

import copy
import hashlib
import os
import sys
import tempfile
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
    sign_source_evidence,
    sign_target_observation,
    validate_report,
    validated_report_path,
    write_report_exclusive,
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

    def test_target_runner_rejects_unprotected_or_source_tree_signing_keys(self) -> None:
        # Linux CI places tempfile roots below root-owned sticky /tmp. That
        # ancestor is safe while a writable non-sticky ancestor is not.
        with tempfile.TemporaryDirectory(dir="/tmp") as directory:
            root = Path(directory).resolve()
            source = root / "source"
            source.mkdir()
            protected = root / "protected.key"
            protected.write_bytes(KEY)
            protected.chmod(0o600)
            self.assertEqual(
                KEY,
                target_runner.load_protected_key(
                    protected,
                    error_code="DCC_TARGET_SIGNING_KEY_INVALID",
                    forbidden_root=source,
                ),
            )
            sticky_parent = root / "sticky"
            sticky_parent.mkdir()
            sticky_parent.chmod(0o1777)
            sticky_protected = sticky_parent / "protected.key"
            sticky_protected.write_bytes(KEY)
            sticky_protected.chmod(0o600)
            self.assertEqual(
                KEY,
                target_runner.load_protected_key(
                    sticky_protected,
                    error_code="DCC_TARGET_SIGNING_KEY_INVALID",
                    forbidden_root=source,
                ),
            )

            exposed = root / "exposed.key"
            exposed.write_bytes(KEY)
            exposed.chmod(0o644)
            short = root / "short.key"
            short.write_bytes(b"x" * 31)
            short.chmod(0o600)
            oversized = root / "oversized.key"
            oversized.write_bytes(b"x" * 4097)
            oversized.chmod(0o600)
            in_tree = source / "in-tree.key"
            in_tree.write_bytes(KEY)
            in_tree.chmod(0o600)
            symlink = root / "linked.key"
            os.symlink(protected, symlink)
            linked_parent = root / "linked-parent"
            os.symlink(root, linked_parent)
            ancestor_symlink = linked_parent / protected.name
            writable_parent = root / "writable-parent"
            writable_parent.mkdir()
            writable_parent.chmod(0o777)
            writable_ancestor = writable_parent / "protected.key"
            writable_ancestor.write_bytes(KEY)
            writable_ancestor.chmod(0o600)

            for candidate in (
                Path("relative.key"), exposed, short, oversized, in_tree, symlink,
                ancestor_symlink, writable_ancestor,
            ):
                with self.subTest(candidate=candidate.name), self.assertRaisesRegex(
                    ValueError, "DCC_TARGET_SIGNING_KEY_INVALID"
                ):
                    target_runner.load_protected_key(
                        candidate,
                        error_code="DCC_TARGET_SIGNING_KEY_INVALID",
                        forbidden_root=source,
                    )

    def target_response(self, source_id: str) -> dict:
        catalog = load_json(ROOT / "contracts/data-catalog/dcc-1.0.0.json")
        descriptor = next(item for item in catalog["sources"] if item["sourceId"] == source_id)
        response = {
            "sourceId": source_id,
            "inputDigest": "sha256:" + "d" * 64,
            "scenarios": [
                {
                    "id": scenario_id,
                    "result": "pass",
                    "observationDigest": "sha256:" + hashlib.sha256(
                        f"{source_id}:{scenario_id}".encode("ascii")
                    ).hexdigest(),
                }
                for scenario_id in descriptor["contractTests"]
            ],
            "cleanupResult": "pass",
        }
        response["authorityAttestation"] = sign_target_observation(response, KEY)
        return response

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

        oversized_revision = self.handoff()
        oversized_revision["revision"] = 1 << 53
        with self.assertRaisesRegex(ValueError, "DCC_HANDOFF_SCHEMA_INVALID"):
            self.preflight(oversized_revision)
        with self.assertRaisesRegex(ValueError, "DCC_HANDOFF_REVISION_FLOOR_INVALID"):
            preflight_handoff(
                self.handoff(), ROOT,
                authority="approved-campus-source", environment="stage",
                candidate_commit=COMMIT, candidate_tree=TREE,
                minimum_revision=1 << 53, now=NOW, trusted_signing_key=KEY,
            )

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
            return self.target_response(source)
        report = execute_target(preflight, resolver=lambda host, port: {"192.0.2.10"}, connector=connector)
        self.assertEqual(17, report["sourceCount"])
        self.assertTrue(all(item["result"] == "pass" for item in report["sources"]))
        self.assertNotIn("records", report)
        self.assertEqual([], validate_report(report, ROOT, trusted_signing_key=KEY))

        for field, replacement in {
            "authority": "other-approved-source",
            "environment": "test",
            "candidateCommit": "c" * 40,
            "occurredAt": "2026-08-05T00:01:00Z",
            "handoffRevision": 8,
            "handoffDigest": "sha256:" + "0" * 64,
        }.items():
            with self.subTest(unsigned_relabel=field):
                relabeled = copy.deepcopy(report)
                relabeled[field] = replacement
                for source in relabeled["sources"]:
                    source[field] = replacement
                    source["evidenceDigest"] = target_runner.evidence_digest(source)
                relabeled["evidenceDigest"] = target_runner.evidence_digest(relabeled)
                self.assertIn(
                    "DCC_TARGET_ATTESTATION_INVALID",
                    validate_report(relabeled, ROOT, trusted_signing_key=KEY),
                )

        wrong_schema = copy.deepcopy(report)
        wrong_schema["sources"][0]["schemaVersion"] = "ATTACKER-1.0.0"
        wrong_schema["sources"][0]["evidenceDigest"] = target_runner.evidence_digest(
            wrong_schema["sources"][0]
        )
        wrong_schema["evidenceDigest"] = target_runner.evidence_digest(wrong_schema)
        self.assertIn(
            "DCC_TARGET_SOURCE_EVIDENCE_INVALID",
            validate_report(wrong_schema, ROOT, trusted_signing_key=KEY),
        )
        relabeled_report_time = copy.deepcopy(report)
        relabeled_report_time["occurredAt"] = "2026-08-05T00:01:00Z"
        relabeled_report_time["evidenceDigest"] = target_runner.evidence_digest(
            relabeled_report_time
        )
        self.assertIn(
            "DCC_TARGET_SOURCE_EVIDENCE_INVALID",
            validate_report(relabeled_report_time, ROOT, trusted_signing_key=KEY),
        )
        self.assertIn(
            "DCC_TARGET_HANDOFF_REVISION_ROLLBACK",
            validate_report(
                report,
                ROOT,
                trusted_signing_key=KEY,
                minimum_handoff_revision=8,
            ),
        )
        failing = copy.deepcopy(self.handoff())
        preflight = self.preflight(failing)
        with self.assertRaisesRegex(ValueError, "SCENARIO_FAILED"):
            execute_target(preflight, resolver=lambda host, port: {"192.0.2.10"},
                    connector=lambda endpoint, ip, token: self._failed_response(
                        endpoint.rsplit("/", 1)[1]
                    ))

    def _failed_response(self, source_id: str) -> dict:
        response = self.target_response(source_id)
        response["scenarios"][0]["result"] = "skip"
        response["authorityAttestation"] = sign_target_observation(response, KEY)
        return response

    def test_scenario_ids_shape_nested_pii_and_authority_signature_fail_closed(self) -> None:
        preflight = self.preflight(self.handoff())

        def expect_rejected(mutator, code: str = "SCENARIO_FAILED") -> None:
            def connector(endpoint: str, _ip: str, _token: str | None) -> dict:
                response = self.target_response(endpoint.rsplit("/", 1)[1])
                mutator(response)
                response["authorityAttestation"] = sign_target_observation(response, KEY)
                return response

            with self.assertRaisesRegex(ValueError, code):
                execute_target(
                    preflight,
                    resolver=lambda _host, _port: {"192.0.2.10"},
                    connector=connector,
                )

        expect_rejected(lambda response: response["scenarios"].pop())
        expect_rejected(lambda response: response["scenarios"][0].update({
            "details": {"studentName": "synthetic-but-forbidden"},
        }))
        expect_rejected(lambda response: response["scenarios"][0].update({
            "id": "endpoint-self-reported-pass",
        }))

        def tampered_signature(endpoint: str, _ip: str, _token: str | None) -> dict:
            response = self.target_response(endpoint.rsplit("/", 1)[1])
            response["authorityAttestation"]["signature"] = "hmac-sha256:" + "0" * 64
            return response

        with self.assertRaisesRegex(ValueError, "ATTESTATION_INVALID"):
            execute_target(
                preflight,
                resolver=lambda _host, _port: {"192.0.2.10"},
                connector=tampered_signature,
            )

    def test_full_source_signature_matches_the_java_canonical_vector(self) -> None:
        evidence = {
            "sourceId": "SRC-P0-STUDENT-001",
            "contractVersion": "DCC-1.0.0",
            "schemaVersion": "STUDENT-SLICE-1.0.0",
            "qualityGateVersion": "QG-1.0.0",
            "environment": "stage",
            "authority": "approved-campus-source",
            "candidateCommit": "a" * 40,
            "candidateTree": "b" * 40,
            "handoffRevision": 7,
            "handoffDigest": "sha256:" + "e" * 64,
            "inputDigest": "sha256:" + "c" * 64,
            "scenarios": [
                {
                    "id": scenario,
                    "result": "pass",
                    "observationDigest": "sha256:" + "d" * 64,
                }
                for scenario in ("provider", "consumer", "correction")
            ],
            "result": "pass",
            "occurredAt": "2026-08-05T00:00:00Z",
            "cleanupResult": "pass",
            "runtimeEvidenceClaim": "target-verified",
        }
        self.assertEqual(
            "sha256:516b15529c0b310d39df1c5f4726a1164a897a5a5cb05eb8648d174ea98808d6",
            sign_source_evidence(evidence, KEY),
        )

    def test_dirty_candidate_is_rejected_before_commit_tree_binding(self) -> None:
        with mock.patch.object(target_runner, "_git", return_value=" M controlled-file"):
            with self.assertRaisesRegex(ValueError, "CANDIDATE_DIRTY"):
                candidate_identity(ROOT)

    def test_report_must_be_new_absolute_and_outside_the_candidate_tree(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            external = Path(directory).resolve()
            existing = external / "existing.json"
            existing.write_text("occupied", encoding="utf-8")
            linked = external / "linked.json"
            os.symlink(existing, linked)
            for candidate in (
                Path("relative-report.json"),
                ROOT / "report.json",
                existing,
                linked,
            ):
                with self.subTest(candidate=candidate), self.assertRaisesRegex(
                    ValueError, "DCC_TARGET_REPORT_PATH_INVALID"
                ):
                    validated_report_path(candidate, ROOT)

            output = validated_report_path(external / "report.json", ROOT)
            write_report_exclusive(output, {"result": "pass"})
            self.assertEqual(b'{"result":"pass"}\n', output.read_bytes())
            with self.assertRaisesRegex(ValueError, "DCC_TARGET_REPORT_WRITE_FAILED"):
                write_report_exclusive(output, {"result": "replay"})

    def test_main_rechecks_candidate_after_network_before_writing_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            external = Path(directory).resolve()
            report = external / "target-report.json"
            handoff = external / "handoff.json"
            key = external / "target.key"
            handoff.write_text("{}", encoding="utf-8")
            key.write_bytes(KEY)
            key.chmod(0o600)
            with (
                mock.patch.object(target_runner, "load_json", return_value={}),
                mock.patch.object(target_runner, "load_protected_key", return_value=KEY),
                mock.patch.object(
                    target_runner,
                    "preflight_handoff",
                    return_value=mock.Mock(trusted_signing_key=KEY),
                ),
                mock.patch.object(target_runner, "execute_target", return_value={"result": "pass"}),
                mock.patch.object(target_runner, "validate_report", return_value=[]),
                mock.patch.object(
                    target_runner,
                    "candidate_identity",
                    side_effect=((COMMIT, TREE), ("c" * 40, TREE)),
                ),
            ):
                exit_code = target_runner.main([
                    "--handoff", str(handoff),
                    "--trusted-signing-key", str(key),
                    "--authority", "approved-campus-source",
                    "--environment", "stage",
                    "--minimum-revision", "7",
                    "--report", str(report),
                    "--project-root", str(ROOT),
                ])

            self.assertEqual(1, exit_code)
            self.assertFalse(report.exists())


if __name__ == "__main__":
    unittest.main()
