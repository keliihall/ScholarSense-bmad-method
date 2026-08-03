from __future__ import annotations

import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "release"))
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from manifests import (  # noqa: E402
    REQUIRED_CONTROLLED_INPUT_IDS_V1,
    REQUIRED_CONTROLLED_INPUT_IDS_V2,
    create_evidence_index,
    create_release_manifest,
    evidence_index_issues,
    release_manifest_issues,
)
from assembly import assemble_release_manifest_input  # noqa: E402
from release_json import canonical_sha256, load_json, schema_issues  # noqa: E402
from scripts.tests.test_release_manifests import _reference, _release_input  # noqa: E402
from scripts.tests import test_release_assembly as release_assembly_test  # noqa: E402


class ReleaseV2ContractTests(unittest.TestCase):
    def test_v1_bytes_and_19_inputs_are_immutable(self):
        self.assertEqual(
            "9ee461f8772441366396cb181dd358d3df848aa834837ba19ff76d484a5b1f6e",
            hashlib.sha256((
                PROJECT_ROOT / "contracts/release/release-manifest.schema.json"
            ).read_bytes()).hexdigest(),
        )
        self.assertEqual(
            "c9ad274343c42f3b73ed6f6ee62a8adfdb79c490f60fd6b4c95456b094b6629a",
            hashlib.sha256((
                PROJECT_ROOT / "contracts/release/evidence-index.schema.json"
            ).read_bytes()).hexdigest(),
        )
        self.assertEqual(19, len(REQUIRED_CONTROLLED_INPUT_IDS_V1))
        self.assertNotIn("PublicIntegration", REQUIRED_CONTROLLED_INPUT_IDS_V1)

    def test_v2_dispatch_requires_pic_as_twentieth_input_and_closed_dag_node(self):
        payload, build = _release_input()
        target = _target_conformance(build["sourceCommit"])
        payload["controlledInputs"].append(
            _reference(
                "PublicIntegration",
                "PIC-CONTRACT-LOCK-1.0.0",
                hashlib.sha256((
                    PROJECT_ROOT / "contracts/public-integration/"
                    "public-integration-contract-lock-1.0.0.json"
                ).read_bytes()).hexdigest(),
            )
        )
        payload["evidence"].append(target)
        manifest = create_release_manifest(payload, manifest_version="2")
        self.assertEqual("RELEASE-MANIFEST-2.0.0", manifest["version"])
        self.assertEqual(20, len(REQUIRED_CONTROLLED_INPUT_IDS_V2))
        self.assertEqual([], release_manifest_issues(manifest, build))
        self.assertEqual([], schema_issues(
            manifest,
            load_json(PROJECT_ROOT / "contracts/release/release-manifest-2.schema.json"),
        ))

        manifest_digest = canonical_sha256(manifest)
        signature = _reference(
            "manifest-signature", "COSIGN-BUNDLE-0.3", "f" * 64,
            kind="manifest-signature",
        )
        signature.update({
            "subjectBinarySha256": manifest_digest,
            "dependsOn": ["release-manifest"],
        })
        index = create_evidence_index(
            manifest,
            _reference(
                "release-manifest", "RELEASE-MANIFEST-2.0.0", manifest_digest
            ),
            signature,
            "2026-08-03T08:00:00Z",
        )
        self.assertEqual("EVIDENCE-INDEX-2.0.0", index["version"])
        self.assertEqual([], evidence_index_issues(index, manifest))
        self.assertEqual([], schema_issues(
            index,
            load_json(PROJECT_ROOT / "contracts/release/evidence-index-2.schema.json"),
        ))
        node = next(
            item for item in index["evidence"]
            if item["id"] == "PublicIntegrationTargetConformance"
        )
        self.assertEqual("candidate-evidence", node["stage"])
        self.assertEqual([], node["dependsOn"])
        self.assertEqual(target["subjectCommit"], node["subjectCommit"])
        self.assertEqual(target["subjectTree"], node["subjectTree"])
        self.assertEqual(target["scenarioSetSha256"], node["scenarioSetSha256"])

        tampered = copy.deepcopy(manifest)
        next(
            item for item in tampered["evidence"]
            if item["id"] == "PublicIntegrationTargetConformance"
        )["scenarioSetSha256"] = "0" * 64
        self.assertTrue(release_manifest_issues(tampered, build))

    def test_v1_rejects_pic_rebinding(self):
        payload, _build = _release_input()
        payload["controlledInputs"].append(
            _reference("PublicIntegration", "PIC-CONTRACT-LOCK-1.0.0", "e" * 64)
        )
        with self.assertRaisesRegex(ValueError, "RELEASE_CONTROLLED_INPUT_SET_INVALID"):
            create_release_manifest(payload)

    def test_v2_assembly_binds_external_target_evidence_without_rebinding_tsp_mpp(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact, sbom, attestation, web = (
                release_assembly_test.ReleaseAssemblyTest()._roots(root)
            )
            common = (
                PROJECT_ROOT,
                "1.9.0",
                "ghcr.io/keliihall/a@sha256:" + "1" * 64,
                artifact,
                "ghcr.io/keliihall/b@sha256:" + "2" * 64,
                sbom,
                "ghcr.io/keliihall/c@sha256:" + "3" * 64,
                attestation,
                "ghcr.io/keliihall/d@sha256:" + "4" * 64,
                web,
                "2026-08-03T08:00:00Z",
            )
            v1 = assemble_release_manifest_input(*common)
            target_path = root / "post-run/target-evidence.json"
            target_path.parent.mkdir()
            scenario_digest = hashlib.sha256((
                PROJECT_ROOT / "contracts/public-integration/"
                "public-integration-target-scenarios-1.0.0.json"
            ).read_bytes()).hexdigest()
            target_path.write_text(json.dumps({
                "version": "PIC-EVIDENCE-1.0.0",
                "evidenceClass": "target-managed-non-production-sandbox",
                "subjectCommit": "f" * 40,
                "subjectTree": "b" * 40,
                "scenarioSetDigest": scenario_digest,
                "overallResult": "pass",
                "failedCount": 0,
                "skippedCount": 0,
                "sandboxCleanupResult": "pass",
                "orphanCount": 0,
                "approvedRetainedCount": 0,
            }, sort_keys=True, separators=(",", ":")) + "\n")
            v2 = assemble_release_manifest_input(
                *common,
                manifest_version="2",
                public_integration_target_evidence_uri=(
                    "ghcr.io/keliihall/pic@sha256:" + "5" * 64
                ),
                public_integration_target_evidence_path=target_path,
            )
            self.assertEqual(19, len(v1["controlledInputs"]))
            self.assertEqual(20, len(v2["controlledInputs"]))
            v1_inputs = {item["id"]: item for item in v1["controlledInputs"]}
            v2_inputs = {item["id"]: item for item in v2["controlledInputs"]}
            for identity in ("TransferSla", "MetricPublication"):
                self.assertEqual(v1_inputs[identity], v2_inputs[identity])
            manifest = create_release_manifest(v2, manifest_version="2")
            self.assertEqual([], release_manifest_issues(
                manifest, v2["buildManifest"]
            ))


def _target_conformance(subject_commit: str) -> dict:
    subject_tree = "b" * 40
    candidate_binding = canonical_sha256({
        "subjectCommit": subject_commit,
        "subjectTree": subject_tree,
    })
    reference = _reference(
        "PublicIntegrationTargetConformance",
        "PIC-EVIDENCE-1.0.0",
        "d" * 64,
        kind="public-integration-target-conformance",
    )
    reference.update({
        "subjectBinarySha256": candidate_binding,
        "subjectCommit": subject_commit,
        "subjectTree": subject_tree,
        "scenarioSetSha256": "c" * 64,
    })
    reference["scenarioSetSha256"] = hashlib.sha256((
        PROJECT_ROOT / "contracts/public-integration/"
        "public-integration-target-scenarios-1.0.0.json"
    ).read_bytes()).hexdigest()
    return reference


if __name__ == "__main__":
    unittest.main()
