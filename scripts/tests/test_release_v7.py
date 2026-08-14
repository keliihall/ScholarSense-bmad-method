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
    REQUIRED_CONTROLLED_INPUT_IDS_V7,
    create_evidence_index,
    create_release_manifest,
    evidence_index_issues,
    release_manifest_issues,
)
from release_json import canonical_sha256, load_json, schema_issues  # noqa: E402
from scripts.tests.test_release_manifests import _reference  # noqa: E402
from scripts.tests.test_release_v6 import _v6_payload  # noqa: E402


PRESERVED_SHA256 = {
    "deploy/base/ingestion-quality-runtime-3.0.0.json": (
        "cdfa4a0ff993ecb8c8a6380fcbe319a21b2b960b74711ef3d57bb41a0e8263a5"
    ),
    "deploy/base/ingestion-quality-roles-3.0.0.json": (
        "01e670bbd03c6507d2344f705040fbff0a2fad4b5d784789fdadc00e78906cf8"
    ),
    "contracts/release/release-manifest-6.schema.json": (
        "d49391835cb8e56c76a3d769f164a86ff38e1a95e2af826dd2e5ae42ccc855e5"
    ),
    "contracts/release/evidence-index-6.schema.json": (
        "ec808e03af39f80d17f2c458022b0c3d94a11e48d1051bb2afb66aefdff825fc"
    ),
}


def _raw_sha(relative: str) -> str:
    return hashlib.sha256((PROJECT_ROOT / relative).read_bytes()).hexdigest()


V7_INPUTS = {
    "QualityFuseTask": (
        "QUALITY-FUSE-CONTRACT-LOCK-1.0.0",
        "contracts/ingestion-quality/quality-fuse/"
        "quality-fuse-contract-lock-1.0.0.json",
    ),
    "PublicIntegrationQualityTask": (
        "PIC-1.1.0", "contracts/public-integration/pic-1.1.0.json",
    ),
    "IngestionQualityFuseRuntime": (
        "INGESTION-QUALITY-RUNTIME-4.0.0",
        "deploy/base/ingestion-quality-runtime-4.0.0.json",
    ),
    "IngestionQualityFuseRoles": (
        "INGESTION-QUALITY-ROLES-4.0.0",
        "deploy/base/ingestion-quality-roles-4.0.0.json",
    ),
}


def _v7_payload() -> tuple[dict, dict]:
    payload, build = _v6_payload()
    for identity, (version, relative) in V7_INPUTS.items():
        payload["controlledInputs"].append(
            _reference(identity, version, _raw_sha(relative))
        )
    payload["runtimeEvidence"].append({
        "id": "ingestion-quality-fuse-task-deployment",
        "status": "deployment-input-required",
        "ownerStory": "2.5a",
        "runtimeEvidenceClaim": "none",
    })
    return payload, build


class ReleaseV7ContractTests(unittest.TestCase):
    def test_v7_binds_fuse_task_capability_and_preserves_all_predecessors(self) -> None:
        for relative, expected in PRESERVED_SHA256.items():
            self.assertEqual(expected, _raw_sha(relative), relative)

        payload, build = _v7_payload()
        manifest = create_release_manifest(payload, manifest_version="7")

        self.assertEqual(36, len(REQUIRED_CONTROLLED_INPUT_IDS_V7))
        self.assertEqual("RELEASE-MANIFEST-7.0.0", manifest["version"])
        self.assertEqual([], release_manifest_issues(manifest, build))
        self.assertEqual([], schema_issues(
            manifest,
            load_json(PROJECT_ROOT / "contracts/release/release-manifest-7.schema.json"),
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
            _reference("release-manifest", "RELEASE-MANIFEST-7.0.0", digest),
            signature,
            "2026-08-11T00:00:00Z",
        )
        self.assertEqual("EVIDENCE-INDEX-7.0.0", index["version"])
        self.assertEqual([], evidence_index_issues(index, manifest))
        self.assertEqual([], schema_issues(
            index,
            load_json(PROJECT_ROOT / "contracts/release/evidence-index-7.schema.json"),
        ))

    def test_v7_runtime_adds_exclusive_task_relay_without_false_activation(self) -> None:
        runtime = json.loads((
            PROJECT_ROOT / "deploy/base/ingestion-quality-runtime-4.0.0.json"
        ).read_text(encoding="utf-8"))
        roles = json.loads((
            PROJECT_ROOT / "deploy/base/ingestion-quality-roles-4.0.0.json"
        ).read_text(encoding="utf-8"))

        self.assertEqual("deployment-input-required", runtime["status"])
        self.assertEqual("none", runtime["runtimeEvidenceClaim"])
        consumer = runtime["sameArtifactRoles"]["eligibility-consumer"]
        self.assertIn(
            "QualityFuseWorkItemKeyPort",
            consumer["activation"]["requiredProviderPorts"],
        )
        self.assertIn(
            "SCHOLARSENSE_INGESTION_QUALITY_FUSE_WORK_ITEM_HMAC_KEY_PATH",
            consumer["requiredEnvironment"],
        )
        self.assertIn(
            "SCHOLARSENSE_INGESTION_QUALITY_FUSE_WORK_ITEM_HMAC_KEY_VERSION",
            consumer["requiredEnvironment"],
        )
        relay = runtime["sameArtifactRoles"]["quality-task-relay"]
        self.assertFalse(relay["businessHttp"])
        self.assertEqual(2, relay["minimumReplicas"])
        self.assertEqual("target-activation-deferred", relay["activation"]["status"])
        self.assertEqual("startup-fail-closed", relay["activation"]["failureMode"])
        self.assertEqual(7, len(
            runtime["databaseStartupGate"]["distinctWorkloadLogins"]
        ))
        self.assertEqual(
            "scholarsense_ingestion_quality_task_relay",
            runtime["databaseStartupGate"]["workloadRoleBindings"]
            ["quality-task-relay"]["requiredGroupRole"],
        )
        self.assertEqual(
            "ingestion-quality-runtime-4.0.0.json",
            roles["roles"]["quality-task-relay"]["capabilityProfiles"][-1],
        )
        self.assertEqual("none", runtime["unactivatedBoundaries"]["runtimeEvidenceClaim"])

    def test_v7_rejects_digest_rebinding_and_false_runtime_claims(self) -> None:
        payload, build = _v7_payload()
        manifest = create_release_manifest(payload, manifest_version="7")

        rebound = copy.deepcopy(manifest)
        next(
            item for item in rebound["controlledInputs"]
            if item["id"] == "QualityFuseTask"
        )["binarySha256"] = "0" * 64
        self.assertIn(
            "RELEASE_QUALITY_FUSE_INPUT_INVALID: QualityFuseTask",
            release_manifest_issues(rebound, build),
        )

        false_claim = copy.deepcopy(manifest)
        runtime = next(
            item for item in false_claim["runtimeEvidence"]
            if item["id"] == "ingestion-quality-fuse-task-deployment"
        )
        runtime["status"] = "passed"
        runtime["evidenceIds"] = ["backend-provenance"]
        runtime.pop("runtimeEvidenceClaim")
        self.assertIn(
            "RELEASE_QUALITY_FUSE_RUNTIME_BOUNDARY_INVALID",
            release_manifest_issues(false_claim, build),
        )


if __name__ == "__main__":
    unittest.main()
