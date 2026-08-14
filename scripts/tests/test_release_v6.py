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
    REQUIRED_CONTROLLED_INPUT_IDS_V6,
    create_evidence_index,
    create_release_manifest,
    evidence_index_issues,
    release_manifest_issues,
)
from release_json import canonical_sha256, load_json, schema_issues  # noqa: E402
from scripts.tests.test_release_manifests import _reference  # noqa: E402
from scripts.tests.test_release_v5 import _v5_payload  # noqa: E402


PRESERVED_SHA256 = {
    "deploy/base/ingestion-quality-runtime-2.0.0.json": (
        "5b7f696d4052ce0fc6903bdd854490a6b8dd2c952aa3cbfb02dd2837409a4f8a"
    ),
    "deploy/base/ingestion-quality-roles-2.0.0.json": (
        "a8bd9b73096c7cc92552d266b286602f3ec5b97fb34b3a4f5c5cd5b4d1a05f43"
    ),
    "contracts/release/release-manifest-5.schema.json": (
        "1f48da71b67698a4a5250927ea462e53706ecf8f72a6c82aea90fff9502f8bb9"
    ),
    "contracts/release/evidence-index-5.schema.json": (
        "3fdeb8e2d92bbda7984ad340d55bc207e15f851164c5fa516d510e918a33e536"
    ),
}


def _raw_sha(relative: str) -> str:
    return hashlib.sha256((PROJECT_ROOT / relative).read_bytes()).hexdigest()


V6_INPUTS = {
    "QualityEligibility": (
        "QUALITY-ELIGIBILITY-CONTRACT-LOCK-1.0.0",
        "contracts/ingestion-quality/rule-dependency/"
        "quality-eligibility-contract-lock-1.0.0.json",
    ),
    "IngestionQualityEligibilityRuntime": (
        "INGESTION-QUALITY-RUNTIME-3.0.0",
        "deploy/base/ingestion-quality-runtime-3.0.0.json",
    ),
    "IngestionQualityEligibilityRoles": (
        "INGESTION-QUALITY-ROLES-3.0.0",
        "deploy/base/ingestion-quality-roles-3.0.0.json",
    ),
}


def _v6_payload() -> tuple[dict, dict]:
    payload, build = _v5_payload()
    for identity, (version, relative) in V6_INPUTS.items():
        payload["controlledInputs"].append(
            _reference(identity, version, _raw_sha(relative))
        )
    payload["runtimeEvidence"].append({
        "id": "ingestion-quality-eligibility-deployment",
        "status": "deployment-input-required",
        "ownerStory": "2.4",
        "runtimeEvidenceClaim": "none",
    })
    return payload, build


class ReleaseV6ContractTests(unittest.TestCase):
    def test_v6_binds_eligibility_capability_and_preserves_predecessors(self) -> None:
        for relative, expected in PRESERVED_SHA256.items():
            self.assertEqual(expected, _raw_sha(relative), relative)

        payload, build = _v6_payload()
        manifest = create_release_manifest(payload, manifest_version="6")

        self.assertEqual(32, len(REQUIRED_CONTROLLED_INPUT_IDS_V6))
        self.assertEqual("RELEASE-MANIFEST-6.0.0", manifest["version"])
        self.assertEqual([], release_manifest_issues(manifest, build))
        self.assertEqual([], schema_issues(
            manifest,
            load_json(PROJECT_ROOT / "contracts/release/release-manifest-6.schema.json"),
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
            _reference("release-manifest", "RELEASE-MANIFEST-6.0.0", digest),
            signature,
            "2026-08-10T00:00:00Z",
        )
        self.assertEqual("EVIDENCE-INDEX-6.0.0", index["version"])
        self.assertEqual([], evidence_index_issues(index, manifest))
        self.assertEqual([], schema_issues(
            index,
            load_json(PROJECT_ROOT / "contracts/release/evidence-index-6.schema.json"),
        ))

    def test_v6_runtime_declares_independent_fail_closed_workloads(self) -> None:
        runtime = json.loads((
            PROJECT_ROOT / "deploy/base/ingestion-quality-runtime-3.0.0.json"
        ).read_text(encoding="utf-8"))
        roles = json.loads((
            PROJECT_ROOT / "deploy/base/ingestion-quality-roles-3.0.0.json"
        ).read_text(encoding="utf-8"))

        self.assertEqual("deployment-input-required", runtime["status"])
        self.assertEqual("none", runtime["runtimeEvidenceClaim"])
        self.assertEqual(set(runtime["sameArtifactRoles"]), {
            "web-api", "eligibility-consumer", "audit-relay-worker",
            "retention-executor",
        })
        consumer = runtime["sameArtifactRoles"]["eligibility-consumer"]
        self.assertFalse(consumer["businessHttp"])
        self.assertEqual(2, consumer["minimumReplicas"])
        self.assertEqual("startup-fail-closed", consumer["activation"]["failureMode"])
        self.assertEqual(
            "scholarsense_ingestion_quality_eligibility_consumer",
            runtime["databaseStartupGate"]["workloadRoleBindings"]
            ["eligibility-consumer"]["requiredGroupRole"],
        )
        self.assertEqual(
            "ingestion-quality-runtime-3.0.0.json",
            roles["roles"]["eligibility-consumer"]["capabilityProfiles"][-1],
        )
        self.assertEqual("none", runtime["unactivatedBoundaries"]["runtimeEvidenceClaim"])

    def test_v6_rejects_digest_rebinding_and_false_runtime_claims(self) -> None:
        payload, build = _v6_payload()
        manifest = create_release_manifest(payload, manifest_version="6")

        rebound = copy.deepcopy(manifest)
        next(
            item for item in rebound["controlledInputs"]
            if item["id"] == "QualityEligibility"
        )["binarySha256"] = "0" * 64
        self.assertIn(
            "RELEASE_QUALITY_ELIGIBILITY_INPUT_INVALID: QualityEligibility",
            release_manifest_issues(rebound, build),
        )

        false_claim = copy.deepcopy(manifest)
        runtime = next(
            item for item in false_claim["runtimeEvidence"]
            if item["id"] == "ingestion-quality-eligibility-deployment"
        )
        runtime["status"] = "passed"
        runtime["evidenceIds"] = ["backend-provenance"]
        runtime.pop("runtimeEvidenceClaim")
        self.assertIn(
            "RELEASE_QUALITY_ELIGIBILITY_RUNTIME_BOUNDARY_INVALID",
            release_manifest_issues(false_claim, build),
        )


if __name__ == "__main__":
    unittest.main()
