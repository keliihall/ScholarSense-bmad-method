from __future__ import annotations

import copy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "release"))

from manifests import (  # noqa: E402
    REQUIRED_BASELINE_IDS,
    REQUIRED_CONTROLLED_INPUT_IDS,
    create_evidence_index,
    create_release_manifest,
    evidence_index_issues,
    release_manifest_issues,
    write_frozen_document,
)
from release_json import canonical_sha256, load_json, schema_issues  # noqa: E402
from version_binding import InMemoryManifestVersionLedger, ManifestVersionBindingService, VersionBindingError  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = PROJECT_ROOT / "contracts/release"


def _reference(identity: str, version: str, digest: str, *, kind: str | None = None) -> dict:
    reference = {
        "id": identity,
        "version": version,
        "uri": f"oci://ghcr.io/keliihall/scholarsense/evidence@sha256:{digest}",
        "mediaType": "application/json",
        "size": 1,
        "binarySha256": digest,
        "ociDigest": f"sha256:{digest}",
    }
    if kind is not None:
        reference["kind"] = kind
    return reference


def _release_input() -> tuple[dict, dict]:
    build = load_json(CONTRACTS / "fixtures/valid/build-manifest.json")
    artifact_digests = {
        "backend": build["artifacts"][0]["binarySha256"],
        "frontend": build["artifacts"][1]["binarySha256"],
    }
    artifacts = []
    for identity, source in zip(("backend", "frontend"), build["artifacts"], strict=True):
        item = _reference(identity, "1.0.0", source["binarySha256"], kind="artifact")
        item["name"] = source["name"]
        item["mediaType"] = source["mediaType"]
        item["size"] = source["size"]
        artifacts.append(item)
    evidence = []
    common = (
        "sbom-cyclonedx",
        "sbom-spdx",
        "vulnerability-scan",
        "provenance",
        "sbom-attestation",
        "artifact-signature",
    )
    frontend_only = ("formal-web-report", "visual-baseline", "ui-token-manifest", "brand-asset-manifest")
    counter = 2
    for subject_id, subject_digest in artifact_digests.items():
        for kind in common + (frontend_only if subject_id == "frontend" else ()):
            digest = f"{counter:x}" * 64
            counter += 1
            item = _reference(f"{subject_id}-{kind}", "1.0.0", digest[:64], kind=kind)
            item["subjectBinarySha256"] = subject_digest
            evidence.append(item)
    baselines = [
        _reference(identity, "1.0.0", (f"{index:x}" * 64)[:64])
        | {"status": "approved"}
        for index, identity in enumerate(sorted(REQUIRED_BASELINE_IDS), start=2)
    ]
    controlled = [
        _reference(identity, "1.0.0", (f"{index:x}" * 64)[:64])
        for index, identity in enumerate(sorted(REQUIRED_CONTROLLED_INPUT_IDS), start=2)
    ]
    payload = {
        "releaseVersion": "1.0.0",
        "buildManifest": build,
        "buildManifestRef": _reference("build-manifest", "BUILD-MANIFEST-1.0.0", canonical_sha256(build)),
        "sourceInventoryRef": _reference("release-source-inventory", "RELEASE-SOURCE-INVENTORY-1.0.0", "a" * 64),
        "sourceArchiveRef": _reference("release-source-archive", "a" * 40, "b" * 64),
        "baselineApprovals": baselines,
        "runtimeEvidence": [
            {"id": "supply-chain-evidence", "status": "passed", "evidenceIds": [item["id"] for item in evidence if item["kind"] in common]},
            {"id": "formal-web-evidence", "status": "passed", "evidenceIds": [item["id"] for item in evidence if item["kind"] in frontend_only]},
            {"id": "app-webview", "status": "not-applicable", "decisionId": "USER-2026-07-19-SCHOOL-APP-NA", "runtimeEvidenceClaim": "none"},
            {"id": "future-app-device", "status": "pending-story-execution", "ownerStory": "7.1/7.x", "runtimeEvidenceClaim": "none"},
        ],
        "controlledInputs": controlled,
        "locks": [
            _reference("toolchain-lock", "TOOLCHAIN-LOCK-1.0.0", "b" * 64),
            _reference("backend-lock", "BACKEND-LOCK-1.0.0", "c" * 64),
            _reference("frontend-lock", "PACKAGE-LOCK-3", "d" * 64),
        ],
        "artifacts": artifacts,
        "evidence": evidence,
        "frozenAt": "2026-07-19T08:00:00+08:00",
    }
    return payload, build


class ReleaseManifestLifecycleTest(unittest.TestCase):
    def test_release_version_has_one_global_manifest_digest(self) -> None:
        service = ManifestVersionBindingService(InMemoryManifestVersionLedger())
        self.assertEqual("bound", service.bind("1.0.0", "a" * 64))
        self.assertEqual("replayed", service.bind("1.0.0", "a" * 64))
        with self.assertRaisesRegex(VersionBindingError, "RELEASE_VERSION_MANIFEST_CONFLICT"):
            service.bind("1.0.0", "b" * 64)

    def test_generator_freezes_only_a_complete_selected_artifact_evidence_set(self) -> None:
        payload, build = _release_input()
        manifest = create_release_manifest(payload)
        schema = load_json(CONTRACTS / "release-manifest.schema.json")
        self.assertEqual([], schema_issues(manifest, schema))
        self.assertEqual([], release_manifest_issues(manifest, build))
        self.assertEqual(build["attempts"], manifest["buildAttempts"])
        self.assertNotIn("manifestSignature", manifest)
        self.assertNotIn("evidenceIndex", manifest)
        self.assertFalse(any(key.startswith("promotion") for key in manifest))

    def test_generator_rejects_pending_blocking_gate_missing_signature_and_wrong_subject(self) -> None:
        payload, _build = _release_input()
        pending = copy.deepcopy(payload)
        pending["runtimeEvidence"][0]["status"] = "pending-story-execution"
        with self.assertRaisesRegex(ValueError, "RELEASE_RUNTIME_GATE_NOT_PASSED"):
            create_release_manifest(pending)

        missing = copy.deepcopy(payload)
        missing["evidence"] = [item for item in missing["evidence"] if item["kind"] != "artifact-signature"]
        with self.assertRaisesRegex(ValueError, "RELEASE_REQUIRED_EVIDENCE_MISSING"):
            create_release_manifest(missing)

        rebound = copy.deepcopy(payload)
        rebound["evidence"][0]["subjectBinarySha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "RELEASE_EVIDENCE_SUBJECT_UNKNOWN"):
            create_release_manifest(rebound)

    def test_manifest_rejects_a_single_generic_sbom_reference(self) -> None:
        payload, _build = _release_input()
        generic = copy.deepcopy(payload)
        for item in generic["evidence"]:
            if item["kind"] in {"sbom-cyclonedx", "sbom-spdx"}:
                item["kind"] = "sbom"
        with self.assertRaisesRegex(ValueError, "RELEASE_(EVIDENCE_KIND_INVALID|REQUIRED_EVIDENCE_MISSING)"):
            create_release_manifest(generic)

    def test_baseline_approval_and_runtime_applicability_are_separate_and_honest(self) -> None:
        payload, _build = _release_input()
        manifest = create_release_manifest(payload)
        approvals = {item["id"]: item["status"] for item in manifest["baselineApprovals"]}
        runtime = {item["id"]: item for item in manifest["runtimeEvidence"]}
        self.assertEqual(set(REQUIRED_BASELINE_IDS), set(approvals))
        self.assertTrue(all(status == "approved" for status in approvals.values()))
        self.assertEqual("not-applicable", runtime["app-webview"]["status"])
        self.assertEqual("none", runtime["app-webview"]["runtimeEvidenceClaim"])
        self.assertEqual("pending-story-execution", runtime["future-app-device"]["status"])
        self.assertEqual("none", runtime["future-app-device"]["runtimeEvidenceClaim"])

    def test_runtime_gate_links_are_kind_and_subject_scoped(self) -> None:
        payload, build = _release_input()
        payload["runtimeEvidence"][1]["evidenceIds"].append("backend-sbom-cyclonedx")
        with self.assertRaisesRegex(ValueError, "RELEASE_RUNTIME_EVIDENCE_SEMANTICS_INVALID"):
            create_release_manifest(payload)

    def test_release_manifest_rejects_descendant_or_nonexistent_references(self) -> None:
        payload, build = _release_input()
        manifest = create_release_manifest(payload)
        for forbidden in ("manifestSignature", "evidenceIndex", "promotionUri"):
            candidate = copy.deepcopy(manifest)
            candidate[forbidden] = "oci://ghcr.io/example/forbidden@sha256:" + "f" * 64
            self.assertTrue(release_manifest_issues(candidate, build), forbidden)
        mutable = copy.deepcopy(manifest)
        mutable["evidence"][0]["uri"] = "https://example.invalid/latest"
        self.assertTrue(release_manifest_issues(mutable, build))

    def test_frozen_document_allows_identical_replay_but_never_rebinds_bytes(self) -> None:
        payload, _build = _release_input()
        manifest = create_release_manifest(payload)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "release-manifest.json"
            write_frozen_document(path, manifest)
            write_frozen_document(path, manifest)
            changed = copy.deepcopy(manifest)
            changed["releaseVersion"] = "1.0.1"
            with self.assertRaisesRegex(RuntimeError, "MANIFEST_ALREADY_FROZEN"):
                write_frozen_document(path, changed)


class EvidenceIndexLifecycleTest(unittest.TestCase):
    def test_host_sso_evidence_payload_schema_is_digest_bound_and_secret_closed(self) -> None:
        schema = load_json(CONTRACTS / "host-sso-runtime-evidence.schema.json")
        valid = load_json(CONTRACTS / "fixtures/valid/host-sso-runtime-evidence.json")
        self.assertEqual([], schema_issues(valid, schema))
        leaked = copy.deepcopy(valid)
        leaked["portalConfiguration"]["clientSecret"] = "must-never-be-recorded"
        self.assertTrue(schema_issues(leaked, schema))

    def test_generator_carries_host_sso_runtime_evidence_bound_to_the_frontend_artifact(self) -> None:
        payload, build = _release_input()
        frontend_digest = next(
            item["binarySha256"] for item in payload["artifacts"] if item["id"] == "frontend"
        )
        host_sso = _reference(
            "host-sso-runtime-evidence",
            "HOST-SSO-RUNTIME-EVIDENCE-1.0.0",
            "e" * 64,
            kind="host-sso-runtime-evidence",
        )
        host_sso["subjectBinarySha256"] = frontend_digest
        payload["evidence"].append(host_sso)
        manifest = create_release_manifest(payload)
        self.assertEqual([], schema_issues(
            manifest, load_json(CONTRACTS / "release-manifest.schema.json")
        ))
        digest = canonical_sha256(manifest)
        signature = _reference(
            "manifest-signature", "COSIGN-BUNDLE-0.3", "f" * 64,
            kind="manifest-signature",
        )
        signature.update({"subjectBinarySha256": digest, "dependsOn": ["release-manifest"]})
        index = create_evidence_index(
            manifest,
            _reference("release-manifest", "RELEASE-MANIFEST-1.0.0", digest),
            signature,
            "2026-07-20T10:00:00+08:00",
        )
        self.assertEqual([], schema_issues(
            index, load_json(CONTRACTS / "evidence-index.schema.json")
        ))
        node = next(item for item in index["evidence"] if item["id"] == host_sso["id"])
        self.assertEqual("artifact-evidence", node["stage"])
        self.assertEqual(["frontend"], node["dependsOn"])

        rebound = copy.deepcopy(manifest)
        next(
            item for item in rebound["evidence"] if item["id"] == host_sso["id"]
        )["subjectBinarySha256"] = build["artifacts"][0]["binarySha256"]
        self.assertIn(
            "RELEASE_HOST_SSO_EVIDENCE_SUBJECT_INVALID: host-sso-runtime-evidence",
            release_manifest_issues(rebound, build),
        )

    def test_manifest_signature_is_created_after_freeze_and_index_subject_is_manifest_digest(self) -> None:
        payload, _build = _release_input()
        manifest = create_release_manifest(payload)
        manifest_digest = canonical_sha256(manifest)
        signature = _reference("manifest-signature", "COSIGN-BUNDLE-0.3", "f" * 64, kind="manifest-signature")
        signature["subjectBinarySha256"] = manifest_digest
        signature["dependsOn"] = ["release-manifest"]
        index = create_evidence_index(
            manifest,
            _reference("release-manifest", "RELEASE-MANIFEST-1.0.0", manifest_digest),
            signature,
            "2026-07-19T08:01:00+08:00",
        )
        schema = load_json(CONTRACTS / "evidence-index.schema.json")
        self.assertEqual([], schema_issues(index, schema))
        self.assertEqual([], evidence_index_issues(index, manifest))
        self.assertEqual(manifest_digest, index["subjectManifestSha256"])
        self.assertEqual(["release-manifest"], index["manifestSignature"]["dependsOn"])

    def test_index_rejects_reverse_dependency_wrong_signature_subject_and_version_rebind(self) -> None:
        payload, _build = _release_input()
        manifest = create_release_manifest(payload)
        digest = canonical_sha256(manifest)
        signature = _reference("manifest-signature", "COSIGN-BUNDLE-0.3", "f" * 64, kind="manifest-signature")
        signature.update({"subjectBinarySha256": digest, "dependsOn": ["release-manifest"]})
        index = create_evidence_index(
            manifest,
            _reference("release-manifest", "RELEASE-MANIFEST-1.0.0", digest),
            signature,
            "2026-07-19T08:01:00+08:00",
        )
        reverse = copy.deepcopy(index)
        reverse["evidence"][-1]["dependsOn"] = ["manifest-signature"]
        self.assertTrue(evidence_index_issues(reverse, manifest))
        wrong_subject = copy.deepcopy(index)
        wrong_subject["manifestSignature"]["subjectBinarySha256"] = "0" * 64
        self.assertTrue(evidence_index_issues(wrong_subject, manifest))
        rebound = copy.deepcopy(index)
        rebound["versionBindings"].append({"releaseVersion": "1.0.0", "manifestSha256": "0" * 64})
        self.assertTrue(evidence_index_issues(rebound, manifest))

    def test_checker_accepts_generator_canonical_outputs_and_rejects_tamper(self) -> None:
        payload, build = _release_input()
        manifest = create_release_manifest(payload)
        digest = canonical_sha256(manifest)
        signature = _reference("manifest-signature", "COSIGN-BUNDLE-0.3", "f" * 64, kind="manifest-signature")
        signature.update({"subjectBinarySha256": digest, "dependsOn": ["release-manifest"]})
        index = create_evidence_index(
            manifest,
            _reference("release-manifest", "RELEASE-MANIFEST-1.0.0", digest),
            signature,
            "2026-07-19T08:01:00+08:00",
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = root / "release-manifest.json"
            build_path = root / "build-manifest.json"
            index_path = root / "evidence-index.json"
            write_frozen_document(manifest_path, manifest)
            write_frozen_document(build_path, build)
            write_frozen_document(index_path, index)
            for mode, document, subject in (
                ("release", manifest_path, build_path),
                ("index", index_path, manifest_path),
            ):
                result = subprocess.run(
                    [sys.executable, str(PROJECT_ROOT / "scripts/check_release_manifests.py"), mode, str(document), str(subject)],
                    check=False,
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                )
                self.assertEqual(0, result.returncode, result.stderr)
            index_path.write_bytes(index_path.read_bytes().replace(digest.encode("ascii"), ("0" * 64).encode("ascii"), 1))
            rejected = subprocess.run(
                [sys.executable, str(PROJECT_ROOT / "scripts/check_release_manifests.py"), "index", str(index_path), str(manifest_path)],
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            self.assertNotEqual(0, rejected.returncode)


if __name__ == "__main__":
    unittest.main()
