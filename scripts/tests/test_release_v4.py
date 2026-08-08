from __future__ import annotations

import copy
import hashlib
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "release"))
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from manifests import (  # noqa: E402
    REQUIRED_CONTROLLED_INPUT_IDS_V4,
    create_evidence_index,
    create_release_manifest,
    evidence_index_issues,
    release_manifest_issues,
)
from release_json import (  # noqa: E402
    canonical_bytes,
    canonical_sha256,
    load_json,
    schema_issues,
)
from verifier import pulled_release_material_issues  # noqa: E402
from scripts.tests.test_release_manifests import _reference  # noqa: E402
from scripts.tests.test_release_v3 import _v3_payload  # noqa: E402


def _raw_sha(relative: str) -> str:
    return hashlib.sha256((PROJECT_ROOT / relative).read_bytes()).hexdigest()


def _v4_payload() -> tuple[dict, dict]:
    payload, build = _v3_payload()
    payload["controlledInputs"].extend([
        _reference(
            "SubjectRegistry",
            "SUBJECT-REGISTRY-LOCK-1.0.0",
            _raw_sha(
                "contracts/subject-registry/"
                "subject-registry-contract-lock-1.0.0.json"
            ),
        ),
        _reference(
            "SubjectRegistryRuntime",
            "SUBJECT-REGISTRY-RUNTIME-1.0.0",
            _raw_sha("deploy/base/subject-registry-runtime-1.0.0.json"),
        ),
    ])
    payload["runtimeEvidence"].append({
        "id": "subject-registry-production-kms",
        "status": "deployment-input-required",
        "ownerStory": "2.2",
        "runtimeEvidenceClaim": "none",
    })
    return payload, build


class ReleaseV4ContractTests(unittest.TestCase):
    def test_v4_binds_subject_registry_inputs_and_preserves_runtime_truth(self) -> None:
        payload, build = _v4_payload()
        manifest = create_release_manifest(payload, manifest_version="4")

        self.assertEqual(24, len(REQUIRED_CONTROLLED_INPUT_IDS_V4))
        self.assertEqual("RELEASE-MANIFEST-4.0.0", manifest["version"])
        self.assertEqual([], release_manifest_issues(manifest, build))
        self.assertEqual([], schema_issues(
            manifest,
            load_json(
                PROJECT_ROOT / "contracts/release/release-manifest-4.schema.json"
            ),
        ))

        digest = canonical_sha256(manifest)
        signature = _reference(
            "manifest-signature",
            "COSIGN-BUNDLE-0.3",
            "f" * 64,
            kind="manifest-signature",
        )
        signature.update({
            "subjectBinarySha256": digest,
            "dependsOn": ["release-manifest"],
        })
        index = create_evidence_index(
            manifest,
            _reference("release-manifest", "RELEASE-MANIFEST-4.0.0", digest),
            signature,
            "2026-08-07T00:00:00Z",
        )
        self.assertEqual("EVIDENCE-INDEX-4.0.0", index["version"])
        self.assertEqual([], evidence_index_issues(index, manifest))
        self.assertEqual([], schema_issues(
            index,
            load_json(
                PROJECT_ROOT / "contracts/release/evidence-index-4.schema.json"
            ),
        ))

    def test_v4_rejects_missing_or_rebound_subject_registry_inputs(self) -> None:
        payload, build = _v4_payload()
        manifest = create_release_manifest(payload, manifest_version="4")

        missing = copy.deepcopy(manifest)
        missing["controlledInputs"] = [
            item for item in missing["controlledInputs"]
            if item["id"] != "SubjectRegistry"
        ]
        self.assertTrue(
            any(
                issue.startswith("RELEASE_CONTROLLED_INPUT_SET_INVALID:")
                for issue in release_manifest_issues(missing, build)
            )
        )

        rebound = copy.deepcopy(manifest)
        next(
            item for item in rebound["controlledInputs"]
            if item["id"] == "SubjectRegistryRuntime"
        )["binarySha256"] = "0" * 64
        self.assertIn(
            "RELEASE_SUBJECT_REGISTRY_INPUT_INVALID: SubjectRegistryRuntime",
            release_manifest_issues(rebound, build),
        )

    def test_v4_rejects_false_production_kms_claims(self) -> None:
        payload, build = _v4_payload()
        manifest = create_release_manifest(payload, manifest_version="4")
        runtime = next(
            item for item in manifest["runtimeEvidence"]
            if item["id"] == "subject-registry-production-kms"
        )
        runtime["status"] = "passed"
        runtime["evidenceIds"] = ["backend-provenance"]
        runtime.pop("runtimeEvidenceClaim")

        self.assertIn(
            "RELEASE_SUBJECT_REGISTRY_RUNTIME_BOUNDARY_INVALID",
            release_manifest_issues(manifest, build),
        )

    def test_v4_independent_verifier_reconstructs_the_successor_profile(self) -> None:
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
                "version": "RELEASE-MANIFEST-4.0.0",
                "releaseVersion": "2.2.0",
                "frozenAt": "2026-08-07T00:00:00Z",
            }
            index = {"createdAt": "2026-08-07T00:01:00Z"}
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
                    return_value={"material": "v4"},
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
                        data_catalog_target_expected_authority=(
                            "dcc-stage-authority"
                        ),
                        data_catalog_target_expected_environment="stage",
                    ),
                )
            self.assertEqual(
                "4", assemble_manifest.call_args.kwargs["manifest_version"]
            )
            create_manifest.assert_called_once_with(
                {"material": "v4"}, manifest_version="4"
            )


if __name__ == "__main__":
    unittest.main()
