from __future__ import annotations

import copy
import hashlib
import json
import sys
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "release"))
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from manifests import (  # noqa: E402
    REQUIRED_CONTROLLED_INPUT_IDS_V5,
    create_evidence_index,
    create_release_manifest,
    evidence_index_issues,
    release_manifest_issues,
)
from release_json import canonical_sha256, load_json, schema_issues  # noqa: E402
from scripts.tests.test_release_manifests import _reference  # noqa: E402
from scripts.tests.test_release_v4 import _v4_payload  # noqa: E402


PRESERVED_SHA256 = {
    "deploy/base/ingestion-quality-runtime-1.0.0.json": (
        "5276c333f311be31a2f285f13138e889e10196637c0fb2ed994d876896627e5c"
    ),
    "deploy/base/roles.json": (
        "58e01ac57e03e1ff03d4b6b4189d655df6dd7737ba51f253d91e0a954b1a77bc"
    ),
    "contracts/release/release-manifest-4.schema.json": (
        "6483e1421c50363beca5aad5a469ca2734daedf2c84636c75fc143bd834cb094"
    ),
    "contracts/release/evidence-index-4.schema.json": (
        "6bddb59a22d4408139d562793b566d2e5098eaef2f3bbce7b41c3acfd3927d7e"
    ),
}


def _raw_sha(relative: str) -> str:
    return hashlib.sha256((PROJECT_ROOT / relative).read_bytes()).hexdigest()


V5_INPUTS = {
    "IngestionBatchQuality": (
        "EXECUTABLE-QUALITY-CONTRACT-LOCK-1.0.0",
        "contracts/ingestion-quality/batch-quality/"
        "executable-quality-contract-lock-1.0.0.json",
    ),
    "QualitySnapshotHash": (
        "QSHM-CONTRACT-LOCK-1.0.0",
        "contracts/ingestion-quality/batch-quality/"
        "quality-snapshot-hash-contract-lock-1.0.0.json",
    ),
    "DataBatchQualityEvent": (
        "DATA-BATCH-QUALITY-EVENT-CONTRACT-LOCK-1.0.0",
        "contracts/events/ingestion-quality/"
        "data-batch-quality-event-contract-lock-1.0.0.json",
    ),
    "IngestionQualityRuntime": (
        "INGESTION-QUALITY-RUNTIME-2.0.0",
        "deploy/base/ingestion-quality-runtime-2.0.0.json",
    ),
    "IngestionQualityRoles": (
        "INGESTION-QUALITY-ROLES-2.0.0",
        "deploy/base/ingestion-quality-roles-2.0.0.json",
    ),
}


def _v5_payload() -> tuple[dict, dict]:
    payload, build = _v4_payload()
    for identity, (version, relative) in V5_INPUTS.items():
        payload["controlledInputs"].append(
            _reference(identity, version, _raw_sha(relative))
        )
    payload["runtimeEvidence"].append({
        "id": "ingestion-quality-quality-worker-deployment",
        "status": "deployment-input-required",
        "ownerStory": "2.3",
        "runtimeEvidenceClaim": "none",
    })
    return payload, build


class ReleaseV5ContractTests(unittest.TestCase):
    def test_v5_binds_quality_capability_and_preserves_predecessor_bytes(self) -> None:
        for relative, expected in PRESERVED_SHA256.items():
            self.assertEqual(expected, _raw_sha(relative), relative)

        payload, build = _v5_payload()
        manifest = create_release_manifest(payload, manifest_version="5")

        self.assertEqual(29, len(REQUIRED_CONTROLLED_INPUT_IDS_V5))
        self.assertEqual("RELEASE-MANIFEST-5.0.0", manifest["version"])
        self.assertEqual([], release_manifest_issues(manifest, build))
        self.assertEqual([], schema_issues(
            manifest,
            load_json(PROJECT_ROOT / "contracts/release/release-manifest-5.schema.json"),
        ))

        digest = canonical_sha256(manifest)
        signature = _reference(
            "manifest-signature", "COSIGN-BUNDLE-0.3", "f" * 64,
            kind="manifest-signature",
        )
        signature.update({
            "subjectBinarySha256": digest,
            "dependsOn": ["release-manifest"],
        })
        index = create_evidence_index(
            manifest,
            _reference("release-manifest", "RELEASE-MANIFEST-5.0.0", digest),
            signature,
            "2026-08-10T00:00:00Z",
        )
        self.assertEqual("EVIDENCE-INDEX-5.0.0", index["version"])
        self.assertEqual([], evidence_index_issues(index, manifest))
        self.assertEqual([], schema_issues(
            index,
            load_json(PROJECT_ROOT / "contracts/release/evidence-index-5.schema.json"),
        ))

    def test_quality_worker_successors_are_fail_closed_and_independently_scaled(self) -> None:
        runtime = json.loads((
            PROJECT_ROOT / "deploy/base/ingestion-quality-runtime-2.0.0.json"
        ).read_text(encoding="utf-8"))
        roles = json.loads((
            PROJECT_ROOT / "deploy/base/ingestion-quality-roles-2.0.0.json"
        ).read_text(encoding="utf-8"))

        self.assertEqual("deployment-input-required", runtime["status"])
        self.assertEqual("none", runtime["runtimeEvidenceClaim"])
        worker = runtime["sameArtifactRoles"]["quality-worker"]
        self.assertFalse(worker["businessHttp"])
        self.assertEqual(2, worker["minimumReplicas"])
        self.assertEqual("startup-fail-closed", worker["activation"]["failureMode"])
        self.assertEqual(
            "scholarsense_ingestion_quality_quality_worker",
            runtime["databaseStartupGate"]["workloadRoleBindings"]
            ["quality-worker"]["requiredGroupRole"],
        )
        self.assertEqual(
            "SCHOLARSENSE_INGESTION_QUALITY_QUALITY_WORKER_DATASOURCE_USERNAME",
            runtime["databaseStartupGate"]["expectedIdentitySource"],
        )
        self.assertEqual(2, roles["roles"]["quality-worker"]["minimumReplicas"])
        self.assertFalse(roles["roles"]["quality-worker"]["businessHttp"])
        self.assertEqual(
            "deployment-input-required",
            roles["roles"]["quality-worker"]["activationStatus"],
        )

    def test_v5_rejects_digest_rebinding_and_false_runtime_claims(self) -> None:
        payload, build = _v5_payload()
        manifest = create_release_manifest(payload, manifest_version="5")

        rebound = copy.deepcopy(manifest)
        next(
            item for item in rebound["controlledInputs"]
            if item["id"] == "IngestionQualityRuntime"
        )["binarySha256"] = "0" * 64
        self.assertIn(
            "RELEASE_INGESTION_QUALITY_INPUT_INVALID: IngestionQualityRuntime",
            release_manifest_issues(rebound, build),
        )

        false_claim = copy.deepcopy(manifest)
        runtime = next(
            item for item in false_claim["runtimeEvidence"]
            if item["id"] == "ingestion-quality-quality-worker-deployment"
        )
        runtime["status"] = "passed"
        runtime["evidenceIds"] = ["backend-provenance"]
        runtime.pop("runtimeEvidenceClaim")
        self.assertIn(
            "RELEASE_INGESTION_QUALITY_RUNTIME_BOUNDARY_INVALID",
            release_manifest_issues(false_claim, build),
        )


if __name__ == "__main__":
    unittest.main()
