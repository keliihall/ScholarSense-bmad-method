from __future__ import annotations

import copy
import hashlib
import ipaddress
import os
import sys
import tempfile
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "release"))
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from assembly import (  # noqa: E402
    _load_dcc_target_signing_key,
    assemble_release_manifest_input,
)
from manifests import (  # noqa: E402
    REQUIRED_CONTROLLED_INPUT_IDS_V3,
    create_evidence_index,
    create_release_manifest,
    evidence_index_issues,
    release_manifest_issues,
)
from release_json import canonical_bytes, canonical_sha256, load_json, schema_issues  # noqa: E402
from run_data_catalog_target_tests import (  # noqa: E402
    TargetPreflight,
    evidence_digest,
    execute_target,
    sign_target_observation,
    validate_report,
)
from scripts.tests.test_release_manifests import _reference, _release_input  # noqa: E402
from scripts.tests import test_release_assembly as release_assembly_test  # noqa: E402
from scripts.tests.test_release_v2 import _target_conformance, _valid_target_evidence  # noqa: E402
from scripts import run_public_integration_sandbox_tests as pic_runner  # noqa: E402
from check_data_catalog_contracts import canonical_digest  # noqa: E402


DCC_TARGET_KEY = b"release-v3-dcc-target-authority-test-key"


def _raw_sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _dcc_reference(subject_commit: str, subject_tree: str) -> dict:
    candidate_binding = canonical_sha256({
        "subjectCommit": subject_commit,
        "subjectTree": subject_tree,
    })
    reference = _reference(
        "DataCatalogTargetConformance", "DCC-TARGET-REPORT-1.0.0",
        "d" * 64, kind="data-catalog-target-conformance",
    )
    reference.update({
        "subjectBinarySha256": candidate_binding,
        "subjectCommit": subject_commit,
        "subjectTree": subject_tree,
        "catalogSha256": _raw_sha(PROJECT_ROOT / "contracts/data-catalog/dcc-1.0.0.json"),
        "qualityGateSha256": _raw_sha(PROJECT_ROOT / "contracts/data-catalog/qg-1.0.0.json"),
        "sourceCount": 17,
        "handoffRevision": 7,
        "handoffDigest": "sha256:" + "f" * 64,
        "authority": "approved-campus-source",
        "environment": "stage",
    })
    return reference


def _v3_payload() -> tuple[dict, dict]:
    payload, build = _release_input()
    payload["controlledInputs"].extend([
        _reference(
            "PublicIntegration", "PIC-CONTRACT-LOCK-1.0.0",
            _raw_sha(PROJECT_ROOT / "contracts/public-integration/public-integration-contract-lock-1.0.0.json"),
        ),
        _reference(
            "DataContractCatalog", "DCC-1.0.0",
            _raw_sha(PROJECT_ROOT / "contracts/data-catalog/dcc-1.0.0.json"),
        ),
        _reference(
            "QualityGate", "QG-1.0.0",
            _raw_sha(PROJECT_ROOT / "contracts/data-catalog/qg-1.0.0.json"),
        ),
    ])
    payload["evidence"].extend([
        _target_conformance(build["sourceCommit"]),
        _dcc_reference(build["sourceCommit"], "b" * 40),
    ])
    payload["runtimeEvidence"].append({
        "id": "data-catalog-target-conformance",
        "status": "passed",
        "evidenceIds": ["DataCatalogTargetConformance"],
    })
    return payload, build


def _target_report(commit: str, tree: str) -> dict:
    catalog = load_json(PROJECT_ROOT / "contracts/data-catalog/dcc-1.0.0.json")
    quality = load_json(PROJECT_ROOT / "contracts/data-catalog/qg-1.0.0.json")
    endpoints = {
        item["sourceId"]: f"https://source.internal.example/{item['sourceId']}"
        for item in catalog["sources"]
    }
    handoff = {
        "authority": "approved-campus-source",
        "environment": "stage",
        "candidateCommit": commit,
        "candidateTree": tree,
        "revision": 7,
        "signature": "hmac-sha256:" + "a" * 64,
        "sourceEndpoints": endpoints,
    }
    preflight = TargetPreflight(
        handoff,
        (ipaddress.ip_network("192.0.2.0/24"),),
        canonical_digest(catalog),
        canonical_digest(quality),
        {item["sourceId"]: item["schemaVersion"] for item in catalog["sources"]},
        {item["sourceId"]: tuple(item["contractTests"]) for item in catalog["sources"]},
        DCC_TARGET_KEY,
    )

    def connector(endpoint: str, _ip: str, _token: str | None) -> dict:
        source_id = endpoint.rsplit("/", 1)[1]
        descriptor = next(item for item in catalog["sources"] if item["sourceId"] == source_id)
        response = {
            "sourceId": source_id,
            "inputDigest": "sha256:" + "e" * 64,
            "scenarios": [
                {
                    "id": scenario_id,
                    "result": "pass",
                    "observationDigest": "sha256:" + hashlib.sha256(
                        f"{source_id}:{scenario_id}".encode("ascii")
                    ).hexdigest(),
                }
                for scenario_id in descriptor["contractTests"]
            ],
            "cleanupResult": "pass",
        }
        response["authorityAttestation"] = sign_target_observation(
            response, DCC_TARGET_KEY
        )
        return response

    return execute_target(
        preflight,
        resolver=lambda _host, _port: {"192.0.2.10"},
        connector=connector,
    )


class ReleaseV3ContractTests(unittest.TestCase):
    def test_release_assembly_rejects_unprotected_or_source_tree_signing_keys(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            source = root / "source"
            source.mkdir()
            protected = root / "protected.key"
            protected.write_bytes(DCC_TARGET_KEY)
            protected.chmod(0o600)
            self.assertEqual(
                DCC_TARGET_KEY,
                _load_dcc_target_signing_key(protected, source),
            )

            exposed = root / "exposed.key"
            exposed.write_bytes(DCC_TARGET_KEY)
            exposed.chmod(0o644)
            short = root / "short.key"
            short.write_bytes(b"x" * 31)
            short.chmod(0o600)
            oversized = root / "oversized.key"
            oversized.write_bytes(b"x" * 4097)
            oversized.chmod(0o600)
            in_tree = source / "in-tree.key"
            in_tree.write_bytes(DCC_TARGET_KEY)
            in_tree.chmod(0o600)
            symlink = root / "linked.key"
            os.symlink(protected, symlink)

            for candidate in (
                Path("relative.key"), exposed, short, oversized, in_tree, symlink,
            ):
                with self.subTest(candidate=candidate.name), self.assertRaisesRegex(
                    ValueError, "RELEASE_ASSEMBLY_DCC_TARGET_SIGNING_KEY_INVALID"
                ):
                    _load_dcc_target_signing_key(candidate, source)

    def test_v1_v2_schema_and_fixture_bytes_remain_immutable(self) -> None:
        expected = {
            "release-manifest.schema.json": "9ee461f8772441366396cb181dd358d3df848aa834837ba19ff76d484a5b1f6e",
            "evidence-index.schema.json": "c9ad274343c42f3b73ed6f6ee62a8adfdb79c490f60fd6b4c95456b094b6629a",
            "release-manifest-2.schema.json": "a012807699695cab091808fc5fbb1f4ebe7db56aa8ce8789cec79f4f68c50aac",
            "evidence-index-2.schema.json": "959134c8e3d481131d7c76ea403b5ae6304bf8f8eb58a77f8a79a83910f7dfde",
            "fixtures/valid/release-manifest.json": "717acd02b76737c709002e68804b0b8d98e5ca844fc0f05ae7c04ae9d4676f10",
            "fixtures/valid/evidence-index.json": "ac45ac4b53f3e5bf663254e2320a83bf60edc9896b0a2f84ea6aa116d1724e8d",
            "fixtures/valid/release-manifest-2.json": "fef2472545834b7417a1a0e1d97130318deed3672d8e68c18bf1263365a6d2f7",
            "fixtures/valid/evidence-index-2.json": "be396b5e339ced0651126f906fc1dcd0100114a59dab6cb33e59a3992ab3fea7",
        }
        for relative, digest in expected.items():
            with self.subTest(relative=relative):
                self.assertEqual(
                    digest,
                    _raw_sha(PROJECT_ROOT / "contracts/release" / relative),
                )

    def test_v3_dispatch_binds_exact_inputs_runtime_gate_and_closed_dag_nodes(self) -> None:
        payload, build = _v3_payload()
        manifest = create_release_manifest(payload, manifest_version="3")
        self.assertEqual(22, len(REQUIRED_CONTROLLED_INPUT_IDS_V3))
        self.assertEqual("RELEASE-MANIFEST-3.0.0", manifest["version"])
        self.assertEqual([], release_manifest_issues(manifest, build))
        self.assertEqual([], schema_issues(
            manifest,
            load_json(PROJECT_ROOT / "contracts/release/release-manifest-3.schema.json"),
        ))
        digest = canonical_sha256(manifest)
        signature = _reference(
            "manifest-signature", "COSIGN-BUNDLE-0.3", "f" * 64,
            kind="manifest-signature",
        )
        signature.update({
            "subjectBinarySha256": digest, "dependsOn": ["release-manifest"],
        })
        index = create_evidence_index(
            manifest,
            _reference("release-manifest", "RELEASE-MANIFEST-3.0.0", digest),
            signature,
            "2026-08-04T12:00:00Z",
        )
        self.assertEqual("EVIDENCE-INDEX-3.0.0", index["version"])
        self.assertEqual([], evidence_index_issues(index, manifest))
        self.assertEqual([], schema_issues(
            index,
            load_json(PROJECT_ROOT / "contracts/release/evidence-index-3.schema.json"),
        ))
        candidates = {
            item["id"]: item for item in index["evidence"]
            if item["stage"] == "candidate-evidence"
        }
        self.assertEqual(
            {"PublicIntegrationTargetConformance", "DataCatalogTargetConformance"},
            set(candidates),
        )
        self.assertTrue(all(not item["dependsOn"] for item in candidates.values()))

        fixture_rebind = copy.deepcopy(manifest)
        next(
            item for item in fixture_rebind["evidence"]
            if item["id"] == "DataCatalogTargetConformance"
        )["sourceCount"] = 16
        self.assertTrue(release_manifest_issues(fixture_rebind, build))

        missing_handoff_binding = copy.deepcopy(manifest)
        next(
            item for item in missing_handoff_binding["evidence"]
            if item["id"] == "DataCatalogTargetConformance"
        ).pop("handoffDigest")
        self.assertTrue(release_manifest_issues(missing_handoff_binding, build))

    def test_v3_assembly_requires_external_17_of_17_candidate_bound_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            artifact, sbom, attestation, web = (
                release_assembly_test.ReleaseAssemblyTest()._roots(root)
            )
            inventory = load_json(artifact / "release-source-inventory.json")
            commit = load_json(artifact / "build-manifest.json")["sourceCommit"]
            tree = inventory["gitTreeOid"]
            pic_path = root / "target/pic.json"
            dcc_path = root / "target/dcc.json"
            dcc_key_path = root / "target/dcc-authority.key"
            pic_path.parent.mkdir()
            dcc_key_path.write_bytes(DCC_TARGET_KEY)
            dcc_key_path.chmod(0o600)
            pic_path.write_bytes(
                pic_runner.canonical_json_bytes(_valid_target_evidence(commit, tree)) + b"\n"
            )
            report = _target_report(commit, tree)
            self.assertEqual(
                [],
                validate_report(
                    report, PROJECT_ROOT, trusted_signing_key=DCC_TARGET_KEY
                ),
            )
            dcc_path.write_bytes(canonical_bytes(report) + b"\n")
            payload = assemble_release_manifest_input(
                PROJECT_ROOT,
                "2.1.0",
                "ghcr.io/keliihall/a@sha256:" + "1" * 64,
                artifact,
                "ghcr.io/keliihall/b@sha256:" + "2" * 64,
                sbom,
                "ghcr.io/keliihall/c@sha256:" + "3" * 64,
                attestation,
                "ghcr.io/keliihall/d@sha256:" + "4" * 64,
                web,
                "2026-08-04T12:00:00Z",
                manifest_version="3",
                public_integration_target_evidence_uri=(
                    "ghcr.io/keliihall/pic@sha256:" + "5" * 64
                ),
                public_integration_target_evidence_path=pic_path,
                data_catalog_target_evidence_uri=(
                    "ghcr.io/keliihall/dcc@sha256:" + "6" * 64
                ),
                data_catalog_target_evidence_path=dcc_path,
                data_catalog_target_trusted_signing_key_path=dcc_key_path,
                data_catalog_target_minimum_handoff_revision=7,
                data_catalog_target_expected_authority="approved-campus-source",
                data_catalog_target_expected_environment="stage",
            )
            manifest = create_release_manifest(payload, manifest_version="3")
            self.assertEqual([], release_manifest_issues(
                manifest, payload["buildManifest"],
            ))

            with self.assertRaisesRegex(
                ValueError, "RELEASE_ASSEMBLY_DCC_TARGET_EVIDENCE_INVALID"
            ):
                assemble_release_manifest_input(
                    PROJECT_ROOT,
                    "2.1.0",
                    "ghcr.io/keliihall/a@sha256:" + "1" * 64,
                    artifact,
                    "ghcr.io/keliihall/b@sha256:" + "2" * 64,
                    sbom,
                    "ghcr.io/keliihall/c@sha256:" + "3" * 64,
                    attestation,
                    "ghcr.io/keliihall/d@sha256:" + "4" * 64,
                    web,
                    "2026-08-04T12:00:00Z",
                    manifest_version="3",
                    public_integration_target_evidence_uri=(
                        "ghcr.io/keliihall/pic@sha256:" + "5" * 64
                    ),
                    public_integration_target_evidence_path=pic_path,
                    data_catalog_target_evidence_uri=(
                        "ghcr.io/keliihall/dcc@sha256:" + "6" * 64
                    ),
                    data_catalog_target_evidence_path=dcc_path,
                    data_catalog_target_trusted_signing_key_path=dcc_key_path,
                    data_catalog_target_minimum_handoff_revision=7,
                    data_catalog_target_expected_authority="other-campus-source",
                    data_catalog_target_expected_environment="stage",
                )

            with self.assertRaisesRegex(
                ValueError, "RELEASE_ASSEMBLY_DCC_TARGET_EVIDENCE_INVALID"
            ):
                assemble_release_manifest_input(
                    PROJECT_ROOT,
                    "2.1.0",
                    "ghcr.io/keliihall/a@sha256:" + "1" * 64,
                    artifact,
                    "ghcr.io/keliihall/b@sha256:" + "2" * 64,
                    sbom,
                    "ghcr.io/keliihall/c@sha256:" + "3" * 64,
                    attestation,
                    "ghcr.io/keliihall/d@sha256:" + "4" * 64,
                    web,
                    "2026-08-04T12:00:00Z",
                    manifest_version="3",
                    public_integration_target_evidence_uri=(
                        "ghcr.io/keliihall/pic@sha256:" + "5" * 64
                    ),
                    public_integration_target_evidence_path=pic_path,
                    data_catalog_target_evidence_uri=(
                        "ghcr.io/keliihall/dcc@sha256:" + "6" * 64
                    ),
                    data_catalog_target_evidence_path=dcc_path,
                    data_catalog_target_trusted_signing_key_path=dcc_key_path,
                    data_catalog_target_minimum_handoff_revision=8,
                    data_catalog_target_expected_authority="approved-campus-source",
                    data_catalog_target_expected_environment="stage",
                )

            failed = copy.deepcopy(report)
            failed["sources"][0]["result"] = "fail"
            failed["sources"][0]["evidenceDigest"] = evidence_digest(failed["sources"][0])
            failed["evidenceDigest"] = evidence_digest(failed)
            dcc_path.write_bytes(canonical_bytes(failed) + b"\n")
            with self.assertRaisesRegex(
                ValueError, "RELEASE_ASSEMBLY_DCC_TARGET_EVIDENCE_INVALID"
            ):
                assemble_release_manifest_input(
                    PROJECT_ROOT,
                    "2.1.0",
                    "ghcr.io/keliihall/a@sha256:" + "1" * 64,
                    artifact,
                    "ghcr.io/keliihall/b@sha256:" + "2" * 64,
                    sbom,
                    "ghcr.io/keliihall/c@sha256:" + "3" * 64,
                    attestation,
                    "ghcr.io/keliihall/d@sha256:" + "4" * 64,
                    web,
                    "2026-08-04T12:00:00Z",
                    manifest_version="3",
                    public_integration_target_evidence_uri=(
                        "ghcr.io/keliihall/pic@sha256:" + "5" * 64
                    ),
                    public_integration_target_evidence_path=pic_path,
                    data_catalog_target_evidence_uri=(
                        "ghcr.io/keliihall/dcc@sha256:" + "6" * 64
                    ),
                    data_catalog_target_evidence_path=dcc_path,
                    data_catalog_target_trusted_signing_key_path=dcc_key_path,
                    data_catalog_target_minimum_handoff_revision=7,
                    data_catalog_target_expected_authority="approved-campus-source",
                    data_catalog_target_expected_environment="stage",
                )

            wrong_schema = _target_report(commit, tree)
            wrong_schema["sources"][0]["schemaVersion"] = "ATTACKER-1.0.0"
            wrong_schema["sources"][0]["evidenceDigest"] = evidence_digest(
                wrong_schema["sources"][0]
            )
            wrong_schema["evidenceDigest"] = evidence_digest(wrong_schema)
            dcc_path.write_bytes(canonical_bytes(wrong_schema) + b"\n")
            with self.assertRaisesRegex(
                ValueError, "RELEASE_ASSEMBLY_DCC_TARGET_EVIDENCE_INVALID"
            ):
                assemble_release_manifest_input(
                    PROJECT_ROOT,
                    "2.1.0",
                    "ghcr.io/keliihall/a@sha256:" + "1" * 64,
                    artifact,
                    "ghcr.io/keliihall/b@sha256:" + "2" * 64,
                    sbom,
                    "ghcr.io/keliihall/c@sha256:" + "3" * 64,
                    attestation,
                    "ghcr.io/keliihall/d@sha256:" + "4" * 64,
                    web,
                    "2026-08-04T12:00:00Z",
                    manifest_version="3",
                    public_integration_target_evidence_uri=(
                        "ghcr.io/keliihall/pic@sha256:" + "5" * 64
                    ),
                    public_integration_target_evidence_path=pic_path,
                    data_catalog_target_evidence_uri=(
                        "ghcr.io/keliihall/dcc@sha256:" + "6" * 64
                    ),
                    data_catalog_target_evidence_path=dcc_path,
                    data_catalog_target_trusted_signing_key_path=dcc_key_path,
                    data_catalog_target_minimum_handoff_revision=7,
                    data_catalog_target_expected_authority="approved-campus-source",
                    data_catalog_target_expected_environment="stage",
                )

            tampered_attestation = _target_report(commit, tree)
            tampered_attestation["sources"][0]["signatureDigest"] = (
                "sha256:" + "0" * 64
            )
            tampered_attestation["sources"][0]["evidenceDigest"] = evidence_digest(
                tampered_attestation["sources"][0]
            )
            tampered_attestation["evidenceDigest"] = evidence_digest(
                tampered_attestation
            )
            dcc_path.write_bytes(canonical_bytes(tampered_attestation) + b"\n")
            with self.assertRaisesRegex(
                ValueError, "RELEASE_ASSEMBLY_DCC_TARGET_EVIDENCE_INVALID"
            ):
                assemble_release_manifest_input(
                    PROJECT_ROOT,
                    "2.1.0",
                    "ghcr.io/keliihall/a@sha256:" + "1" * 64,
                    artifact,
                    "ghcr.io/keliihall/b@sha256:" + "2" * 64,
                    sbom,
                    "ghcr.io/keliihall/c@sha256:" + "3" * 64,
                    attestation,
                    "ghcr.io/keliihall/d@sha256:" + "4" * 64,
                    web,
                    "2026-08-04T12:00:00Z",
                    manifest_version="3",
                    public_integration_target_evidence_uri=(
                        "ghcr.io/keliihall/pic@sha256:" + "5" * 64
                    ),
                    public_integration_target_evidence_path=pic_path,
                    data_catalog_target_evidence_uri=(
                        "ghcr.io/keliihall/dcc@sha256:" + "6" * 64
                    ),
                    data_catalog_target_evidence_path=dcc_path,
                    data_catalog_target_trusted_signing_key_path=dcc_key_path,
                    data_catalog_target_minimum_handoff_revision=7,
                    data_catalog_target_expected_authority="approved-campus-source",
                    data_catalog_target_expected_environment="stage",
                )


if __name__ == "__main__":
    unittest.main()
