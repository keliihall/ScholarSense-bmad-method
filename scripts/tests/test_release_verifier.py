from __future__ import annotations

import base64
import copy
import json
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "release"))

from verifier import attestation_query_issues, immutable_oci_uri_issues  # noqa: E402


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


if __name__ == "__main__":
    unittest.main()
