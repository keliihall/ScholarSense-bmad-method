from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock


PROJECT_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = PROJECT_ROOT / "scripts/assemble-release-manifest-input.py"
SPEC = importlib.util.spec_from_file_location("assemble_release_manifest_input_cli", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class AssembleReleaseManifestInputCliTests(unittest.TestCase):
    def test_v2_uses_digest_addressed_oci_readback_as_target_evidence_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            oras = root / "oras"
            oras.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            oras.chmod(0o755)
            output = root / "release-input.json"
            captured: dict[str, object] = {}

            def fake_pull(_oras: Path, uri: str, destination: Path) -> None:
                destination.mkdir(parents=True, exist_ok=True)
                if uri == os.environ["PUBLIC_INTEGRATION_TARGET_EVIDENCE_URI"]:
                    (destination / module.TARGET_EVIDENCE_FILENAME).write_text(
                        "{}", encoding="utf-8"
                    )

            def fake_assemble(*_args, **kwargs):
                captured.update(kwargs)
                captured["target_bytes"] = Path(
                    kwargs["public_integration_target_evidence_path"]
                ).read_text(encoding="utf-8")
                return {"buildManifest": {"sourceCommit": "f" * 40}}

            environment = {
                "RUNNER_TEMP": str(root),
                "ORAS": str(oras),
                "ARTIFACT_URI": "ghcr.io/example/a@sha256:" + "1" * 64,
                "SBOM_URI": "ghcr.io/example/b@sha256:" + "2" * 64,
                "ATTESTATION_URI": "ghcr.io/example/c@sha256:" + "3" * 64,
                "WEB_URI": "ghcr.io/example/d@sha256:" + "4" * 64,
                "PUBLIC_INTEGRATION_TARGET_EVIDENCE_URI": (
                    "ghcr.io/example/pic@sha256:" + "5" * 64
                ),
            }
            with mock.patch.dict(os.environ, environment, clear=True), mock.patch.object(
                module, "_pull", side_effect=fake_pull
            ), mock.patch.object(
                module, "assemble_release_manifest_input", side_effect=fake_assemble
            ):
                self.assertEqual(
                    0,
                    module.main([
                        str(SCRIPT), "1.9.0", str(output), "--manifest-version", "2"
                    ]),
                )

            self.assertEqual("{}", captured["target_bytes"])
            self.assertNotIn("PUBLIC_INTEGRATION_TARGET_EVIDENCE_FILE", environment)

    def test_v3_reads_both_candidate_evidence_sets_from_digest_addressed_oci(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            oras = root / "oras"
            oras.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            oras.chmod(0o755)
            output = root / "release-input.json"
            dcc_key = root / "dcc-target-authority.key"
            dcc_key.write_bytes(b"test-only-dcc-target-authority-key")
            dcc_key.chmod(0o600)
            captured: dict[str, object] = {}

            def fake_pull(_oras: Path, uri: str, destination: Path) -> None:
                destination.mkdir(parents=True, exist_ok=True)
                if uri == os.environ["PUBLIC_INTEGRATION_TARGET_EVIDENCE_URI"]:
                    (destination / module.TARGET_EVIDENCE_FILENAME).write_text(
                        '{"kind":"pic"}', encoding="utf-8"
                    )
                if uri == os.environ["DATA_CATALOG_TARGET_EVIDENCE_URI"]:
                    (destination / module.DATA_CATALOG_TARGET_EVIDENCE_FILENAME).write_text(
                        '{"kind":"dcc"}', encoding="utf-8"
                    )

            def fake_assemble(*_args, **kwargs):
                captured.update(kwargs)
                captured["pic_bytes"] = Path(
                    kwargs["public_integration_target_evidence_path"]
                ).read_text(encoding="utf-8")
                captured["dcc_bytes"] = Path(
                    kwargs["data_catalog_target_evidence_path"]
                ).read_text(encoding="utf-8")
                return {"buildManifest": {"sourceCommit": "f" * 40}}

            environment = {
                "RUNNER_TEMP": str(root),
                "ORAS": str(oras),
                "ARTIFACT_URI": "ghcr.io/example/a@sha256:" + "1" * 64,
                "SBOM_URI": "ghcr.io/example/b@sha256:" + "2" * 64,
                "ATTESTATION_URI": "ghcr.io/example/c@sha256:" + "3" * 64,
                "WEB_URI": "ghcr.io/example/d@sha256:" + "4" * 64,
                "PUBLIC_INTEGRATION_TARGET_EVIDENCE_URI": (
                    "ghcr.io/example/pic@sha256:" + "5" * 64
                ),
                "DATA_CATALOG_TARGET_EVIDENCE_URI": (
                    "ghcr.io/example/dcc@sha256:" + "6" * 64
                ),
                "DATA_CATALOG_TARGET_TRUSTED_SIGNING_KEY": str(dcc_key),
                "DATA_CATALOG_TARGET_MINIMUM_HANDOFF_REVISION": "7",
                "DATA_CATALOG_TARGET_EXPECTED_AUTHORITY": "stage.catalog-owner",
                "DATA_CATALOG_TARGET_EXPECTED_ENVIRONMENT": "stage",
            }
            with mock.patch.dict(os.environ, environment, clear=True), mock.patch.object(
                module, "_pull", side_effect=fake_pull
            ), mock.patch.object(
                module, "assemble_release_manifest_input", side_effect=fake_assemble
            ):
                self.assertEqual(
                    0,
                    module.main([
                        str(SCRIPT), "2.1.0", str(output), "--manifest-version", "3"
                    ]),
                )

            self.assertEqual('{"kind":"pic"}', captured["pic_bytes"])
            self.assertEqual('{"kind":"dcc"}', captured["dcc_bytes"])
            self.assertEqual(
                dcc_key,
                captured["data_catalog_target_trusted_signing_key_path"],
            )
            self.assertEqual(
                7,
                captured["data_catalog_target_minimum_handoff_revision"],
            )
            self.assertEqual(
                "stage.catalog-owner",
                captured["data_catalog_target_expected_authority"],
            )
            self.assertEqual(
                "stage",
                captured["data_catalog_target_expected_environment"],
            )


if __name__ == "__main__":
    unittest.main()
