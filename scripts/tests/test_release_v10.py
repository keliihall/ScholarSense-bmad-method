from __future__ import annotations

import copy
import hashlib
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "release"))
sys.path.insert(0, str(ROOT / "scripts"))

from manifests import (  # noqa: E402
    REQUIRED_CONTROLLED_INPUT_IDS_V10,
    create_evidence_index,
    create_release_manifest,
    evidence_index_issues,
    release_manifest_issues,
)
from release_json import canonical_sha256, load_json, schema_issues  # noqa: E402
from assembly import LOCKS, LOCKS_V10  # noqa: E402
from scripts.tests.test_release_manifests import _reference  # noqa: E402
from scripts.tests.test_release_v9 import _v9_payload  # noqa: E402


PRESERVED_SHA256 = {
    "contracts/release/release-manifest-9.schema.json": (
        "b24be7b2e029b57240b86d5fe808e161e48c01bf7d2e051956fe937d0edb9ff2"
    ),
    "contracts/release/evidence-index-9.schema.json": (
        "5d8bea4fe0b148518119d97f897a1ee32e29a07f6ec5ab3d716c4f88fe12c55a"
    ),
    "contracts/release/backend-lock-1.0.0.json": (
        "c71982099779c38bcdb2ccf922367ae329f12f79ec6de6606063dc12e8032b01"
    ),
}

V10_INPUTS = {
    "ObservabilityContract": (
        "OBS-1.0.0",
        "contracts/observability/observability-contract-1.0.0.json",
    ),
    "ObservabilityEventCompatibility": (
        "OBS-EVENT-COMPAT-1.0.0",
        "contracts/observability/event-trace-context-compatibility-1.0.0.json",
    ),
    "ObservabilityRuntimeBundle": (
        "OBSERVABILITY-RUNTIME-BUNDLE-1.0.0",
        "contracts/config/observability-runtime-bundle-1.0.0.json",
    ),
}


def _raw_sha(relative: str) -> str:
    return hashlib.sha256((ROOT / relative).read_bytes()).hexdigest()


def _v10_payload() -> tuple[dict, dict]:
    payload, build = _v9_payload()
    for identity, (version, relative) in V10_INPUTS.items():
        payload["controlledInputs"].append(
            _reference(identity, version, _raw_sha(relative))
        )
    payload["locks"] = [
        _reference(
            "backend-lock",
            "BACKEND-LOCK-2.0.0",
            _raw_sha("contracts/release/backend-lock-2.0.0.json"),
        ) if item["id"] == "backend-lock" else item
        for item in payload["locks"]
    ]
    payload["runtimeEvidence"].append({
        "id": "platform-observability-executable-closure",
        "status": "passed",
        "ownerStory": "2.6a",
        "runtimeEvidenceClaim": "story-2.6a-observability-closure",
        "evidenceIds": ["backend-provenance", "backend-sbom-cyclonedx"],
    })
    return payload, build


class ReleaseV10ContractTests(unittest.TestCase):
    def test_backend_lock_v2_is_scoped_only_to_v10_assembly(self) -> None:
        self.assertEqual("BACKEND-LOCK-1.0.0", LOCKS["backend-lock"][0])
        self.assertEqual("BACKEND-LOCK-2.0.0", LOCKS_V10["backend-lock"][0])

    def test_v10_binds_observability_supply_chain_and_preserves_v9(self) -> None:
        for relative, expected in PRESERVED_SHA256.items():
            self.assertEqual(expected, _raw_sha(relative), relative)
        payload, build = _v10_payload()
        manifest = create_release_manifest(payload, manifest_version="10")
        replay = create_release_manifest(copy.deepcopy(payload), manifest_version="10")
        self.assertEqual(48, len(REQUIRED_CONTROLLED_INPUT_IDS_V10))
        self.assertEqual("RELEASE-MANIFEST-10.0.0", manifest["version"])
        self.assertEqual(manifest, replay)
        self.assertEqual([], release_manifest_issues(manifest, build))
        self.assertEqual([], schema_issues(
            manifest,
            load_json(ROOT / "contracts/release/release-manifest-10.schema.json"),
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
            _reference("release-manifest", "RELEASE-MANIFEST-10.0.0", digest),
            signature,
            "2026-08-15T00:00:00Z",
        )
        self.assertEqual("EVIDENCE-INDEX-10.0.0", index["version"])
        self.assertEqual([], evidence_index_issues(index, manifest))
        self.assertEqual([], schema_issues(
            index,
            load_json(ROOT / "contracts/release/evidence-index-10.schema.json"),
        ))

    def test_v10_rejects_observability_lock_and_runtime_rebinding(self) -> None:
        payload, build = _v10_payload()
        manifest = create_release_manifest(payload, manifest_version="10")

        rebound = copy.deepcopy(manifest)
        next(item for item in rebound["controlledInputs"]
             if item["id"] == "ObservabilityContract")["binarySha256"] = "0" * 64
        self.assertIn(
            "RELEASE_OBSERVABILITY_INPUT_INVALID: ObservabilityContract",
            release_manifest_issues(rebound, build),
        )

        stale_lock = copy.deepcopy(manifest)
        next(item for item in stale_lock["locks"]
             if item["id"] == "backend-lock")["version"] = "BACKEND-LOCK-1.0.0"
        self.assertIn(
            "RELEASE_BACKEND_LOCK_V2_INVALID",
            release_manifest_issues(stale_lock, build),
        )

        false_claim = copy.deepcopy(manifest)
        runtime = next(item for item in false_claim["runtimeEvidence"]
                       if item["id"] == "platform-observability-executable-closure")
        runtime["runtimeEvidenceClaim"] = "none"
        self.assertIn(
            "RELEASE_OBSERVABILITY_RUNTIME_BOUNDARY_INVALID",
            release_manifest_issues(false_claim, build),
        )

        swapped_kinds = copy.deepcopy(manifest)
        provenance = next(item for item in swapped_kinds["evidence"]
                          if item["id"] == "backend-provenance")
        cyclonedx = next(item for item in swapped_kinds["evidence"]
                         if item["id"] == "backend-sbom-cyclonedx")
        provenance["kind"], cyclonedx["kind"] = cyclonedx["kind"], provenance["kind"]
        self.assertIn(
            "RELEASE_OBSERVABILITY_RUNTIME_BOUNDARY_INVALID",
            release_manifest_issues(swapped_kinds, build),
        )

        swapped_subjects = copy.deepcopy(manifest)
        backend = next(item for item in swapped_subjects["evidence"]
                       if item["id"] == "backend-provenance")
        frontend = next(item for item in swapped_subjects["evidence"]
                        if item["id"] == "frontend-provenance")
        backend["subjectBinarySha256"], frontend["subjectBinarySha256"] = (
            frontend["subjectBinarySha256"], backend["subjectBinarySha256"]
        )
        self.assertIn(
            "RELEASE_OBSERVABILITY_RUNTIME_BOUNDARY_INVALID",
            release_manifest_issues(swapped_subjects, build),
        )


if __name__ == "__main__":
    unittest.main()
