from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "release"))
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from assembly import assemble_evidence_index_input, assemble_release_manifest_input  # noqa: E402
from manifests import create_evidence_index, create_release_manifest, write_frozen_document  # noqa: E402
from release_json import canonical_bytes, canonical_sha256, load_json  # noqa: E402
from verifier import pulled_release_material_issues  # noqa: E402


def sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def write(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)


class ReleaseAssemblyTest(unittest.TestCase):
    def _roots(self, root: Path) -> tuple[Path, Path, Path, Path]:
        artifact = root / "artifact/release-out/build"
        sbom = root / "sbom/release-out/sbom"
        attestation = root / "attestation/release-out/attestation"
        web = root / "web"
        backend = b"backend-artifact"
        frontend = b"frontend-artifact"
        build = load_json(PROJECT_ROOT / "contracts/release/fixtures/valid/build-manifest.json")
        inventory = load_json(PROJECT_ROOT / "contracts/release/release-source-inventory-1.0.0.json")
        build["sourceCommit"] = "f" * 40
        build["sourceManifestSha256"] = inventory["normalizedManifestSha256"]
        build["artifacts"] = [
            {
                "name": "scholarsense-backend.jar",
                "mediaType": "application/java-archive",
                "size": len(backend),
                "binarySha256": sha256(backend),
            },
            {
                "name": "scholarsense-frontend.tar.gz",
                "mediaType": "application/gzip",
                "size": len(frontend),
                "binarySha256": sha256(frontend),
            },
        ]
        write(artifact / "scholarsense-backend.jar", backend)
        write(artifact / "scholarsense-frontend.tar.gz", frontend)
        write(artifact / "build-manifest.json", canonical_bytes(build))
        runtime_inventory = dict(inventory)
        runtime_inventory["sourceCommit"] = build["sourceCommit"]
        write(artifact / "release-source-inventory.json", canonical_bytes(runtime_inventory))
        write(artifact / "release-source.tar.gz", b"source-archive")
        for name in (
            "backend.cdx.json", "backend.spdx.json", "frontend.cdx.json", "frontend.spdx.json",
            "sbom-evidence.json",
        ):
            write(sbom / name, json.dumps({"name": name}).encode("utf-8"))
        for subject in ("scholarsense-backend", "scholarsense-frontend"):
            write(attestation / f"{subject}.attestations.json", b'{"attestations":[{}]}')
            write(attestation / f"{subject}.sigstore.json", b'{"mediaType":"application/vnd.dev.sigstore.bundle.v0.3+json"}')
        write(
            web / "formal-web-report.json",
            json.dumps({"subjectArtifactSha256": sha256(frontend), "result": "passed"}).encode("utf-8"),
        )
        return artifact, sbom, attestation, web

    def test_assembles_complete_existing_digest_addressed_release_references(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact, sbom, attestation, web = self._roots(root)
            payload = assemble_release_manifest_input(
                PROJECT_ROOT,
                "1.1.0",
                "ghcr.io/keliihall/scholarsense-release-candidate@sha256:" + "1" * 64,
                artifact,
                "ghcr.io/keliihall/scholarsense-release-evidence@sha256:" + "2" * 64,
                sbom,
                "ghcr.io/keliihall/scholarsense-release-evidence@sha256:" + "3" * 64,
                attestation,
                "ghcr.io/keliihall/scholarsense-release-evidence@sha256:" + "4" * 64,
                web,
                "2026-07-19T10:30:00+08:00",
            )
            manifest = create_release_manifest(payload)
            self.assertIn("backend-sbom-spdx", {item["id"] for item in manifest["evidence"]})
            self.assertIn("frontend-sbom-spdx", {item["id"] for item in manifest["evidence"]})
            self.assertEqual("1.1.0", manifest["releaseVersion"])
            self.assertEqual(2, len(manifest["artifacts"]))
            self.assertGreaterEqual(len(manifest["evidence"]), 14)
            self.assertTrue(all(item["uri"].startswith("oci://ghcr.io/") for item in manifest["evidence"]))

    def test_rejects_artifact_or_source_inventory_subject_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact, sbom, attestation, web = self._roots(root)
            (artifact / "scholarsense-frontend.tar.gz").write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "RELEASE_ASSEMBLY_ARTIFACT_DIGEST_MISMATCH"):
                assemble_release_manifest_input(
                    PROJECT_ROOT,
                    "1.1.0",
                    "ghcr.io/keliihall/a@sha256:" + "1" * 64,
                    artifact,
                    "ghcr.io/keliihall/b@sha256:" + "2" * 64,
                    sbom,
                    "ghcr.io/keliihall/c@sha256:" + "3" * 64,
                    attestation,
                    "ghcr.io/keliihall/d@sha256:" + "4" * 64,
                    web,
                    "2026-07-19T10:30:00+08:00",
                )

    def test_evidence_index_input_binds_actual_canonical_manifest_and_signature_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact, sbom, attestation, web = self._roots(root)
            release_input = assemble_release_manifest_input(
                PROJECT_ROOT,
                "1.1.0",
                "ghcr.io/keliihall/a@sha256:" + "1" * 64,
                artifact,
                "ghcr.io/keliihall/b@sha256:" + "2" * 64,
                sbom,
                "ghcr.io/keliihall/c@sha256:" + "3" * 64,
                attestation,
                "ghcr.io/keliihall/d@sha256:" + "4" * 64,
                web,
                "2026-07-19T10:30:00+08:00",
            )
            manifest = create_release_manifest(release_input)
            manifest_path = root / "release-manifest.json"
            signature_path = root / "release-manifest.sigstore.json"
            write_frozen_document(manifest_path, manifest)
            signature_path.write_bytes(b'{"signature":"controlled"}')
            self.assertEqual(canonical_bytes(manifest), manifest_path.read_bytes())
            index_input = assemble_evidence_index_input(
                "ghcr.io/keliihall/manifest@sha256:" + "5" * 64,
                manifest_path,
                "ghcr.io/keliihall/signature@sha256:" + "6" * 64,
                signature_path,
                "2026-07-19T10:31:00+08:00",
            )
            index = create_evidence_index(
                index_input["releaseManifest"],
                index_input["releaseManifestRef"],
                index_input["manifestSignature"],
                index_input["createdAt"],
            )
            self.assertEqual(canonical_sha256(manifest), index["subjectManifestSha256"])
            self.assertEqual(sha256(signature_path.read_bytes()), index["manifestSignature"]["binarySha256"])

            index_path = root / "evidence-index.json"
            write_frozen_document(index_path, index)
            uris = {
                "artifact": "ghcr.io/keliihall/a@sha256:" + "1" * 64,
                "sbom": "ghcr.io/keliihall/b@sha256:" + "2" * 64,
                "attestation": "ghcr.io/keliihall/c@sha256:" + "3" * 64,
                "web": "ghcr.io/keliihall/d@sha256:" + "4" * 64,
                "manifest": "ghcr.io/keliihall/manifest@sha256:" + "5" * 64,
                "signature": "ghcr.io/keliihall/signature@sha256:" + "6" * 64,
            }
            self.assertEqual(
                [],
                pulled_release_material_issues(
                    PROJECT_ROOT, artifact, sbom, attestation, web,
                    manifest_path, signature_path, index_path, uris,
                ),
            )
            (sbom / "backend.cdx.json").write_bytes(b"tampered")
            self.assertTrue(
                pulled_release_material_issues(
                    PROJECT_ROOT, artifact, sbom, attestation, web,
                    manifest_path, signature_path, index_path, uris,
                )
            )


if __name__ == "__main__":
    unittest.main()
