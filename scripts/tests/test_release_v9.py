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
    REQUIRED_CONTROLLED_INPUT_IDS_V9,
    create_evidence_index,
    create_release_manifest,
    evidence_index_issues,
    release_manifest_issues,
)
from release_json import canonical_sha256, load_json, schema_issues  # noqa: E402
from scripts.tests.test_release_manifests import _reference  # noqa: E402
from scripts.tests.test_release_v8 import _v8_payload  # noqa: E402


PRESERVED_SHA256 = {
    "deploy/base/ingestion-quality-runtime-5.0.0.json": (
        "e818a992b61fa7d04bef7c240d5777272bb8b7751e9053b13c520a1bf6a7061b"
    ),
    "deploy/base/ingestion-quality-roles-5.0.0.json": (
        "63f1ba503b7520f06ecff080c8454b5a6fca2dc173dcae6906ead26fc6bc6910"
    ),
    "contracts/release/release-manifest-8.schema.json": (
        "4ef54e7216b642eed56cbc3a7fe8b109b9dd41280f4f2370c5950e9c5114374c"
    ),
    "contracts/release/evidence-index-8.schema.json": (
        "1e1f9f873c9f8c6229e8806f33104eba1e8d206a9ccc69223ba47d56e3c579ac"
    ),
}


def _raw_sha(relative: str) -> str:
    return hashlib.sha256((PROJECT_ROOT / relative).read_bytes()).hexdigest()


V9_INPUTS = {
    "QualityFinalizationWorkflow": (
        "QUALITY-FINALIZATION-CONTRACT-LOCK-1.0.0",
        "contracts/ingestion-quality/quality-finalization/"
        "quality-finalization-contract-lock-1.0.0.json",
    ),
    "PublicIntegrationQualityTaskClose": (
        "PIC-1.2.0",
        "contracts/public-integration/pic-1.2.0.json",
    ),
    "QualityFinalizationApi": (
        "QUALITY-RECOVERY-TASKS-API-1.2.0",
        "contracts/openapi/quality-recovery-tasks-1.2.openapi.json",
    ),
    "IngestionQualityFinalizationRuntime": (
        "INGESTION-QUALITY-RUNTIME-6.0.0",
        "deploy/base/ingestion-quality-runtime-6.0.0.json",
    ),
    "IngestionQualityFinalizationRoles": (
        "INGESTION-QUALITY-ROLES-6.0.0",
        "deploy/base/ingestion-quality-roles-6.0.0.json",
    ),
}


def _v9_payload() -> tuple[dict, dict]:
    payload, build = _v8_payload()
    for identity, (version, relative) in V9_INPUTS.items():
        payload["controlledInputs"].append(
            _reference(identity, version, _raw_sha(relative))
        )
    payload["runtimeEvidence"].append({
        "id": "ingestion-quality-finalization-executable-closure",
        "status": "passed",
        "ownerStory": "2.5c",
        "runtimeEvidenceClaim": "story-2.5c-observation-finalization-closure",
        "evidenceIds": ["backend-provenance", "frontend-formal-web-report"],
    })
    return payload, build


class ReleaseV9ContractTests(unittest.TestCase):
    def test_v9_binds_finalization_closure_and_preserves_v8(self) -> None:
        for relative, expected in PRESERVED_SHA256.items():
            self.assertEqual(expected, _raw_sha(relative), relative)

        payload, build = _v9_payload()
        manifest = create_release_manifest(payload, manifest_version="9")
        replay = create_release_manifest(copy.deepcopy(payload), manifest_version="9")

        self.assertEqual(45, len(REQUIRED_CONTROLLED_INPUT_IDS_V9))
        self.assertEqual("RELEASE-MANIFEST-9.0.0", manifest["version"])
        self.assertEqual(manifest, replay)
        self.assertEqual(canonical_sha256(manifest), canonical_sha256(replay))
        self.assertEqual([], release_manifest_issues(manifest, build))
        self.assertEqual([], schema_issues(
            manifest,
            load_json(PROJECT_ROOT / "contracts/release/release-manifest-9.schema.json"),
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
            _reference("release-manifest", "RELEASE-MANIFEST-9.0.0", digest),
            signature,
            "2026-08-14T00:00:00Z",
        )
        self.assertEqual("EVIDENCE-INDEX-9.0.0", index["version"])
        self.assertEqual([], evidence_index_issues(index, manifest))
        self.assertEqual([], schema_issues(
            index,
            load_json(PROJECT_ROOT / "contracts/release/evidence-index-9.schema.json"),
        ))

    def test_v6_runtime_reuses_worker_login_and_keeps_future_claims_none(self) -> None:
        runtime = json.loads((
            PROJECT_ROOT / "deploy/base/ingestion-quality-runtime-6.0.0.json"
        ).read_text(encoding="utf-8"))
        roles = json.loads((
            PROJECT_ROOT / "deploy/base/ingestion-quality-roles-6.0.0.json"
        ).read_text(encoding="utf-8"))

        self.assertEqual(
            "story-2.5c-observation-finalization-closure",
            runtime["runtimeEvidenceClaim"],
        )
        self.assertEqual(8, len(runtime["databaseStartupGate"]["distinctWorkloadLogins"]))
        self.assertEqual("recovering-to-eligible", runtime["finalizationActivation"]["authorizedTransition"])
        self.assertFalse(runtime["finalizationActivation"]["networkInsideOwnerTransaction"])
        self.assertEqual("P1D", runtime["executableClosure"]["observationDurations"]["dailyBatch"])
        self.assertEqual("none", runtime["deferredBoundaries"]["runtimeEvidenceClaim"])
        self.assertEqual(
            "ingestion-quality-runtime-6.0.0.json",
            roles["composition"]["successorRuntimeProfile"],
        )
        self.assertEqual(
            "PostgreSqlDataSourceStartupGate.verifyRecoveryWorker",
            roles["roles"]["recovery-validation-worker"]["probe"]["startupGate"],
        )

    def test_v9_rejects_rebinding_and_false_finalization_claim(self) -> None:
        payload, build = _v9_payload()
        manifest = create_release_manifest(payload, manifest_version="9")

        rebound = copy.deepcopy(manifest)
        next(item for item in rebound["controlledInputs"]
             if item["id"] == "QualityFinalizationWorkflow")["binarySha256"] = "0" * 64
        self.assertIn(
            "RELEASE_QUALITY_FINALIZATION_INPUT_INVALID: QualityFinalizationWorkflow",
            release_manifest_issues(rebound, build),
        )

        false_claim = copy.deepcopy(manifest)
        runtime = next(item for item in false_claim["runtimeEvidence"]
                       if item["id"] == "ingestion-quality-finalization-executable-closure")
        runtime["runtimeEvidenceClaim"] = "none"
        self.assertIn(
            "RELEASE_QUALITY_FINALIZATION_RUNTIME_BOUNDARY_INVALID",
            release_manifest_issues(false_claim, build),
        )


if __name__ == "__main__":
    unittest.main()
