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
    pulled_release_material_issues,
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
    def test_v1_and_v2_pulled_material_keep_their_historical_profiles(self) -> None:
        from verifier import canonical_bytes

        base_uris = {
            "artifact": "ghcr.io/keliihall/a@sha256:" + "1" * 64,
            "sbom": "ghcr.io/keliihall/b@sha256:" + "2" * 64,
            "attestation": "ghcr.io/keliihall/c@sha256:" + "3" * 64,
            "web": "ghcr.io/keliihall/d@sha256:" + "4" * 64,
            "manifest": "ghcr.io/keliihall/e@sha256:" + "5" * 64,
            "signature": "ghcr.io/keliihall/f@sha256:" + "6" * 64,
        }
        for version in ("1", "2"):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                manifest = {
                    "version": f"RELEASE-MANIFEST-{version}.0.0",
                    "releaseVersion": "historical",
                    "frozenAt": "2026-08-03T00:00:00Z",
                }
                index = {"createdAt": "2026-08-03T00:01:00Z"}
                manifest_path = root / "release-manifest.json"
                signature_path = root / "release-manifest.sigstore.json"
                index_path = root / "evidence-index.json"
                manifest_path.write_bytes(canonical_bytes(manifest))
                signature_path.write_bytes(b"signature")
                index_path.write_bytes(canonical_bytes(index))
                uris = dict(base_uris)
                target_arguments = {}
                if version == "2":
                    target_root = root / "pic"
                    target_root.mkdir()
                    uris["public-integration-target"] = (
                        "ghcr.io/keliihall/pic@sha256:" + "7" * 64
                    )
                    target_arguments["public_integration_target_root"] = target_root
                with (
                    mock.patch(
                        "assembly.assemble_release_manifest_input",
                        return_value={"material": version},
                    ) as assemble_manifest,
                    mock.patch(
                        "manifests.create_release_manifest", return_value=manifest
                    ) as create_manifest,
                    mock.patch(
                        "assembly.assemble_evidence_index_input",
                        return_value={
                            "releaseManifest": manifest,
                            "releaseManifestRef": {},
                            "manifestSignature": {},
                            "createdAt": index["createdAt"],
                        },
                    ),
                    mock.patch("manifests.create_evidence_index", return_value=index),
                ):
                    self.assertEqual(
                        [],
                        pulled_release_material_issues(
                            root,
                            root,
                            root,
                            root,
                            root,
                            manifest_path,
                            signature_path,
                            index_path,
                            uris,
                            **target_arguments,
                        ),
                    )
                kwargs = assemble_manifest.call_args.kwargs
                self.assertEqual(version, kwargs["manifest_version"])
                if version == "1":
                    self.assertNotIn(
                        "public_integration_target_evidence_path", kwargs
                    )
                else:
                    self.assertEqual(
                        target_root
                        / "public-integration-target-conformance-evidence-1.0.0.json",
                        kwargs["public_integration_target_evidence_path"],
                    )
                create_manifest.assert_called_once_with(
                    {"material": version}, manifest_version=version
                )

    def test_v3_pulled_material_reconstructs_both_target_nodes_with_protected_floor(self) -> None:
        from verifier import canonical_bytes

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = root / "release-manifest.json"
            signature_path = root / "release-manifest.sigstore.json"
            index_path = root / "evidence-index.json"
            pic_root = root / "pic"
            dcc_root = root / "dcc"
            signing_key = root / "protected-dcc.key"
            pic_root.mkdir()
            dcc_root.mkdir()
            signature_path.write_bytes(b"signature")
            signing_key.write_bytes(b"protected")
            manifest = {
                "version": "RELEASE-MANIFEST-3.0.0",
                "releaseVersion": "2.1.0",
                "frozenAt": "2026-08-05T00:00:00Z",
            }
            index = {"createdAt": "2026-08-05T00:01:00Z"}
            manifest_path.write_bytes(canonical_bytes(manifest))
            index_path.write_bytes(canonical_bytes(index))
            uris = {
                "artifact": "ghcr.io/keliihall/a@sha256:" + "1" * 64,
                "sbom": "ghcr.io/keliihall/b@sha256:" + "2" * 64,
                "attestation": "ghcr.io/keliihall/c@sha256:" + "3" * 64,
                "web": "ghcr.io/keliihall/d@sha256:" + "4" * 64,
                "manifest": "ghcr.io/keliihall/e@sha256:" + "5" * 64,
                "signature": "ghcr.io/keliihall/f@sha256:" + "6" * 64,
                "public-integration-target": (
                    "ghcr.io/keliihall/pic@sha256:" + "7" * 64
                ),
                "data-catalog-target": (
                    "ghcr.io/keliihall/dcc@sha256:" + "8" * 64
                ),
            }
            with (
                mock.patch(
                    "assembly.assemble_release_manifest_input",
                    return_value={"material": "v3"},
                ) as assemble_manifest,
                mock.patch(
                    "manifests.create_release_manifest", return_value=manifest
                ) as create_manifest,
                mock.patch(
                    "assembly.assemble_evidence_index_input",
                    return_value={
                        "releaseManifest": manifest,
                        "releaseManifestRef": {},
                        "manifestSignature": {},
                        "createdAt": index["createdAt"],
                    },
                ),
                mock.patch("manifests.create_evidence_index", return_value=index),
            ):
                self.assertEqual(
                    [],
                    pulled_release_material_issues(
                        root,
                        root,
                        root,
                        root,
                        root,
                        manifest_path,
                        signature_path,
                        index_path,
                        uris,
                        public_integration_target_root=pic_root,
                        data_catalog_target_root=dcc_root,
                        data_catalog_target_trusted_signing_key_path=signing_key,
                        data_catalog_target_minimum_handoff_revision=7,
                        data_catalog_target_expected_authority="dcc-stage-authority",
                        data_catalog_target_expected_environment="stage",
                    ),
                )
            assemble_manifest.assert_called_once()
            manifest_kwargs = assemble_manifest.call_args.kwargs
            self.assertEqual("3", manifest_kwargs["manifest_version"])
            self.assertEqual(
                pic_root
                / "public-integration-target-conformance-evidence-1.0.0.json",
                manifest_kwargs["public_integration_target_evidence_path"],
            )
            self.assertEqual(
                dcc_root / "data-catalog-target-conformance-evidence-1.0.0.json",
                manifest_kwargs["data_catalog_target_evidence_path"],
            )
            self.assertEqual(
                uris["public-integration-target"],
                manifest_kwargs["public_integration_target_evidence_uri"],
            )
            self.assertEqual(
                uris["data-catalog-target"],
                manifest_kwargs["data_catalog_target_evidence_uri"],
            )
            self.assertEqual(
                signing_key,
                manifest_kwargs["data_catalog_target_trusted_signing_key_path"],
            )
            self.assertEqual(
                7,
                manifest_kwargs["data_catalog_target_minimum_handoff_revision"],
            )
            self.assertEqual(
                "dcc-stage-authority",
                manifest_kwargs["data_catalog_target_expected_authority"],
            )
            self.assertEqual(
                "stage",
                manifest_kwargs["data_catalog_target_expected_environment"],
            )
            create_manifest.assert_called_once_with(
                {"material": "v3"}, manifest_version="3"
            )

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
