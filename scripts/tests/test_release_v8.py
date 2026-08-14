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
    REQUIRED_CONTROLLED_INPUT_IDS_V8,
    create_evidence_index,
    create_release_manifest,
    evidence_index_issues,
    release_manifest_issues,
)
from release_json import canonical_sha256, load_json, schema_issues  # noqa: E402
from scripts.tests.test_release_manifests import _reference  # noqa: E402
from scripts.tests.test_release_v7 import _v7_payload  # noqa: E402


PRESERVED_SHA256 = {
    "deploy/base/ingestion-quality-runtime-4.0.0.json": (
        "a4a842f1befedd00ab3f97fa432267cb6141e18a3ccbcc6fb81131695acc4e1f"
    ),
    "deploy/base/ingestion-quality-roles-4.0.0.json": (
        "1a7e293255924aac4424a1971dbfe31568816714a76f61300b7f0df533862bcb"
    ),
    "contracts/release/release-manifest-7.schema.json": (
        "d4b5dd27df962500632bf1dc8e07414957a3d130ca1acecf134e016bd5f160ea"
    ),
    "contracts/release/evidence-index-7.schema.json": (
        "d784a37cba2f984944bdc8dc34e027922c5ae263f5bed1d2284baf64833894b1"
    ),
}


def _raw_sha(relative: str) -> str:
    return hashlib.sha256((PROJECT_ROOT / relative).read_bytes()).hexdigest()


V8_INPUTS = {
    "QualityRecoveryWorkflow": (
        "QUALITY-RECOVERY-CONTRACT-LOCK-1.1.0",
        "contracts/ingestion-quality/quality-recovery/"
        "quality-recovery-contract-lock-1.1.0.json",
    ),
    "QualityRecoveryApi": (
        "QUALITY-RECOVERY-TASKS-API-1.1.0",
        "contracts/openapi/quality-recovery-tasks-1.1.openapi.json",
    ),
    "IngestionQualityRecoveryRuntime": (
        "INGESTION-QUALITY-RUNTIME-5.0.0",
        "deploy/base/ingestion-quality-runtime-5.0.0.json",
    ),
    "IngestionQualityRecoveryRoles": (
        "INGESTION-QUALITY-ROLES-5.0.0",
        "deploy/base/ingestion-quality-roles-5.0.0.json",
    ),
}


def _v8_payload() -> tuple[dict, dict]:
    payload, build = _v7_payload()
    for identity, (version, relative) in V8_INPUTS.items():
        payload["controlledInputs"].append(
            _reference(identity, version, _raw_sha(relative))
        )
    payload["runtimeEvidence"].append({
        "id": "ingestion-quality-recovery-executable-closure",
        "status": "passed",
        "ownerStory": "2.5b",
        "runtimeEvidenceClaim": "story-2.5b-executable-closure",
        "evidenceIds": ["backend-provenance", "frontend-formal-web-report"],
    })
    return payload, build


class ReleaseV8ContractTests(unittest.TestCase):
    def test_v8_binds_recovery_closure_and_preserves_predecessors(self) -> None:
        for relative, expected in PRESERVED_SHA256.items():
            self.assertEqual(expected, _raw_sha(relative), relative)

        payload, build = _v8_payload()
        manifest = create_release_manifest(payload, manifest_version="8")

        self.assertEqual(40, len(REQUIRED_CONTROLLED_INPUT_IDS_V8))
        self.assertEqual("RELEASE-MANIFEST-8.0.0", manifest["version"])
        self.assertEqual([], release_manifest_issues(manifest, build))
        self.assertEqual([], schema_issues(
            manifest,
            load_json(PROJECT_ROOT / "contracts/release/release-manifest-8.schema.json"),
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
            _reference("release-manifest", "RELEASE-MANIFEST-8.0.0", digest),
            signature,
            "2026-08-13T00:00:00Z",
        )
        self.assertEqual("EVIDENCE-INDEX-8.0.0", index["version"])
        self.assertEqual([], evidence_index_issues(index, manifest))
        self.assertEqual([], schema_issues(
            index,
            load_json(PROJECT_ROOT / "contracts/release/evidence-index-8.schema.json"),
        ))

    def test_v8_installs_sample_hrap_action_and_owner_transaction(self) -> None:
        runtime = json.loads((
            PROJECT_ROOT / "deploy/base/ingestion-quality-runtime-5.0.0.json"
        ).read_text(encoding="utf-8"))
        roles = json.loads((
            PROJECT_ROOT / "deploy/base/ingestion-quality-roles-5.0.0.json"
        ).read_text(encoding="utf-8"))

        self.assertEqual("installed-and-verified", runtime["status"])
        self.assertEqual(
            "story-2.5b-executable-closure", runtime["runtimeEvidenceClaim"]
        )
        self.assertEqual(
            "quality-fuse.recover", runtime["actionActivation"]["actionType"]
        )
        self.assertEqual(
            "RECOVERY-SAMPLE-PROVIDER-1.0.0",
            runtime["executableClosure"]["sampleProvider"],
        )
        self.assertEqual(
            "identity-access",
            runtime["executableClosure"]["highRiskApprovalOwner"],
        )
        self.assertEqual(8, len(runtime["databaseStartupGate"]["distinctWorkloadLogins"]))
        self.assertEqual(
            "PostgreSqlDataSourceStartupGate.verifyRecoveryWorker",
            roles["roles"]["recovery-validation-worker"]["probe"]["startupGate"],
        )

    def test_v8_rejects_digest_rebinding_and_false_runtime_claims(self) -> None:
        payload, build = _v8_payload()
        manifest = create_release_manifest(payload, manifest_version="8")

        rebound = copy.deepcopy(manifest)
        next(
            item for item in rebound["controlledInputs"]
            if item["id"] == "QualityRecoveryWorkflow"
        )["binarySha256"] = "0" * 64
        self.assertIn(
            "RELEASE_QUALITY_RECOVERY_INPUT_INVALID: QualityRecoveryWorkflow",
            release_manifest_issues(rebound, build),
        )

        false_claim = copy.deepcopy(manifest)
        runtime = next(
            item for item in false_claim["runtimeEvidence"]
            if item["id"] == "ingestion-quality-recovery-executable-closure"
        )
        runtime["status"] = "deployment-input-required"
        runtime["runtimeEvidenceClaim"] = "none"
        runtime.pop("evidenceIds")
        self.assertIn(
            "RELEASE_QUALITY_RECOVERY_RUNTIME_BOUNDARY_INVALID",
            release_manifest_issues(false_claim, build),
        )


if __name__ == "__main__":
    unittest.main()
