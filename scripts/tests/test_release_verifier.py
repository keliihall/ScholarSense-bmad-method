from __future__ import annotations

import base64
import copy
import hashlib
import io
import json
import sys
import tarfile
import tempfile
import unittest
from unittest import mock
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "release"))

from verifier import (  # noqa: E402
    attestation_query_issues,
    cryptographically_verify_github_attestations,
    immutable_oci_uri_issues,
)


def _attestation(subject: str, predicate_type: str) -> dict:
    statement = {
        "_type": "https://in-toto.io/Statement/v1",
        "subject": [{"name": "artifact", "digest": {"sha256": subject}}],
        "predicateType": predicate_type,
        "predicate": {},
    }
    payload = base64.b64encode(json.dumps(statement, separators=(",", ":")).encode("utf-8")).decode("ascii")
    return {"bundle": {"dsseEnvelope": {"payload": payload}}}


class IndependentReleaseVerifierTest(unittest.TestCase):
    def test_github_attestations_are_cryptographically_verified_with_frozen_identity(self) -> None:
        subject = "a" * 64
        predicate = "https://slsa.dev/provenance/v1"
        verified = [{"verificationResult": {"statement": json.loads(base64.b64decode(_attestation(subject, predicate)["bundle"]["dsseEnvelope"]["payload"]))}}]
        result = mock.Mock(returncode=0, stdout=json.dumps(verified), stderr="")
        with mock.patch("verifier.subprocess.run", return_value=result) as run:
            self.assertEqual(
                [],
                cryptographically_verify_github_attestations(
                    Path("artifact.bin"),
                    subject,
                    "keliihall/ScholarSense-bmad-method",
                    "keliihall/ScholarSense-bmad-method/.github/workflows/artifact-signing.yml",
                    "f" * 40,
                    {predicate},
                ),
            )
        command = run.call_args.args[0]
        self.assertEqual(command[:3], ["gh", "attestation", "verify"])
        self.assertIn("--signer-workflow", command)
        self.assertIn("--source-digest", command)
        self.assertIn("--predicate-type", command)

    def test_failed_or_tampered_cryptographic_attestation_is_rejected(self) -> None:
        with mock.patch(
            "verifier.subprocess.run",
            return_value=mock.Mock(returncode=1, stdout="", stderr="signature invalid"),
        ):
            issues = cryptographically_verify_github_attestations(
                Path("artifact.bin"),
                "a" * 64,
                "keliihall/ScholarSense-bmad-method",
                "keliihall/ScholarSense-bmad-method/.github/workflows/artifact-signing.yml",
                "f" * 40,
                {"https://slsa.dev/provenance/v1"},
            )
        self.assertIn("VERIFIER_ATTESTATION_SIGNATURE_INVALID", issues)

    def test_attestation_query_binds_subject_and_both_required_predicates(self) -> None:
        subject = "a" * 64
        query = {
            "attestations": [
                _attestation(subject, "https://slsa.dev/provenance/v1"),
                _attestation(subject, "https://cyclonedx.org/bom"),
            ]
        }
        self.assertEqual(
            [],
            attestation_query_issues(
                query,
                subject,
                {"https://slsa.dev/provenance/v1", "https://cyclonedx.org/bom"},
            ),
        )
        wrong = copy.deepcopy(query)
        wrong["attestations"][0] = _attestation("b" * 64, "https://slsa.dev/provenance/v1")
        self.assertTrue(attestation_query_issues(wrong, subject, {"https://slsa.dev/provenance/v1"}))
        replay = {"attestations": [_attestation(subject, "https://slsa.dev/provenance/v0.2")]}
        self.assertTrue(attestation_query_issues(replay, subject, {"https://slsa.dev/provenance/v1"}))

    def test_remote_references_are_digest_only_and_never_tags(self) -> None:
        self.assertEqual([], immutable_oci_uri_issues("ghcr.io/keliihall/release@sha256:" + "a" * 64))
        for uri in (
            "ghcr.io/keliihall/release:latest",
            "ghcr.io/keliihall/release:1.0.0",
            "https://example.invalid/release",
            "ghcr.io/keliihall/release@sha256:" + "a" * 63,
        ):
            with self.subTest(uri=uri):
                self.assertTrue(immutable_oci_uri_issues(uri))

    def test_source_archive_is_hash_bound_and_safely_extracted_from_one_open_file(self) -> None:
        from verifier import extract_source_archive

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "source.tar.gz"
            with tarfile.open(archive, "w:gz") as bundle:
                payload = b"controlled"
                member = tarfile.TarInfo("contracts/release/policy.json")
                member.size = len(payload)
                bundle.addfile(member, io.BytesIO(payload))
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            destination = root / "source"
            extract_source_archive(archive, destination, digest)
            self.assertEqual(b"controlled", (destination / "contracts/release/policy.json").read_bytes())
            with self.assertRaisesRegex(ValueError, "VERIFIER_SOURCE_ARCHIVE_DIGEST_MISMATCH"):
                extract_source_archive(archive, root / "wrong", "0" * 64)

            escape = root / "escape.tar.gz"
            with tarfile.open(escape, "w:gz") as bundle:
                member = tarfile.TarInfo("../escape")
                member.size = 1
                bundle.addfile(member, io.BytesIO(b"x"))
            with self.assertRaisesRegex(ValueError, "VERIFIER_SOURCE_ARCHIVE_ENTRY_INVALID"):
                extract_source_archive(
                    escape,
                    root / "escaped",
                    hashlib.sha256(escape.read_bytes()).hexdigest(),
                )


if __name__ == "__main__":
    unittest.main()
