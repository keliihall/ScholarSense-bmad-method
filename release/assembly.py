#!/usr/bin/env python3
"""Assemble immutable release lifecycle inputs from bytes already read back from CAS."""

from __future__ import annotations

import hashlib
import re
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
import sys

sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from release_json import canonical_bytes, canonical_sha256, load_json  # noqa: E402


OCI_URI = re.compile(r"^ghcr\.io/[a-z0-9_.-]+/[a-z0-9_./-]+@(?P<digest>sha256:[0-9a-f]{64})$")

DELEGATED_BASELINE = "_bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md"
BASELINES = {
    "AAB": ("AAB-1.0.0", "_bmad-output/planning-artifacts/app-applicability-baseline-2026-07-19.md"),
    "CISB": ("CISB-1.0.0", "contracts/release/ci-supply-chain-baseline-1.0.0.json"),
    "FPB": ("FPB-1.0.0", "contracts/performance/frontend-baseline-1.0.0.json"),
    "PAB": ("PAB-1.0.0", DELEGATED_BASELINE),
    "TEST-ENV": ("TEST-ENV-1.0.0", "contracts/performance/test-environment-1.0.0.json"),
    "UXB": ("UXB-1.0.0", DELEGATED_BASELINE),
    "VGB": ("VGB-1.0.0", "contracts/release/visual-baseline-vgb-1.0.0.json"),
}
CONTROLLED_INPUTS = {
    "AcademicCareNodeSet": ("ACN-1.0.0", DELEGATED_BASELINE),
    "Availability": ("AP-1.0.0", "contracts/performance/availability-policy-ap-1.0.0.json"),
    "BusinessCalendar": ("BC-1.0.0", DELEGATED_BASELINE),
    "CareActionCatalog": ("CAC-1.0.0", DELEGATED_BASELINE),
    "EvidenceSchema": ("ES-1.0.0", DELEGATED_BASELINE),
    "MetricPublication": ("MPP-1.0.0", DELEGATED_BASELINE),
    "PerformanceProfile": ("PP-1.0.0", "contracts/performance/performance-profile-pp-1.0.0.json"),
    "QualityRecovery": ("QRP-1.0.0", DELEGATED_BASELINE),
    "Queue": ("QP-1.0.0", DELEGATED_BASELINE),
    "RetentionSchedule": ("RS-1.0.0", DELEGATED_BASELINE),
    "RoleField": ("RFP-1.0.0", DELEGATED_BASELINE),
    "Rule": ("RC-1.0.0", "_bmad-output/planning-artifacts/rule-catalog.md"),
    "SeasonalProgramMatrix": ("SPM-1.0.0", DELEGATED_BASELINE),
    "StrategyGate": ("SGP-1.0.0", DELEGATED_BASELINE),
    "TransferSla": ("TSP-1.0.0", DELEGATED_BASELINE),
    "WorkVisit": ("WVP-1.0.0", DELEGATED_BASELINE),
}
LOCKS = {
    "backend-lock": ("BACKEND-LOCK-1.0.0", "contracts/release/backend-lock-1.0.0.json"),
    "frontend-lock": ("PACKAGE-LOCK-3", "frontend/package-lock.json"),
    "toolchain-lock": ("TOOLCHAIN-LOCK-1.0.0", "contracts/release/toolchain-lock-1.0.0.json"),
}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _oci(uri: str) -> tuple[str, str]:
    match = OCI_URI.fullmatch(uri)
    if match is None:
        raise ValueError("RELEASE_ASSEMBLY_IMMUTABLE_OCI_URI_REQUIRED")
    return f"oci://{uri}", match.group("digest")


def _media_type(path: Path) -> str:
    if path.suffix == ".json":
        return "application/json"
    if path.suffix == ".md":
        return "text/markdown"
    if path.suffix == ".jar":
        return "application/java-archive"
    if path.name.endswith(".tar.gz"):
        return "application/gzip"
    return "application/octet-stream"


def _reference(
    identity: str,
    version: str,
    uri: str,
    path: Path,
    *,
    kind: str | None = None,
    subject_sha256: str | None = None,
    binary_sha256: str | None = None,
    media_type: str | None = None,
) -> dict[str, Any]:
    immutable_uri, oci_digest = _oci(uri)
    if not path.is_file() or path.is_symlink():
        raise ValueError(f"RELEASE_ASSEMBLY_INPUT_MISSING: {identity}")
    reference: dict[str, Any] = {
        "id": identity,
        "version": version,
        "uri": immutable_uri,
        "mediaType": media_type or _media_type(path),
        "size": path.stat().st_size,
        "binarySha256": binary_sha256 or _sha256(path),
        "ociDigest": oci_digest,
    }
    if kind is not None:
        reference["kind"] = kind
    if subject_sha256 is not None:
        reference["subjectBinarySha256"] = subject_sha256
    return reference


def _controlled_references(
    source_root: Path,
    uri: str,
    mapping: dict[str, tuple[str, str]],
    *,
    approved: bool = False,
) -> list[dict[str, Any]]:
    result = []
    for identity in sorted(mapping):
        version, relative = mapping[identity]
        item = _reference(identity, version, uri, source_root / relative)
        if approved:
            item["status"] = "approved"
        result.append(item)
    return result


def assemble_release_manifest_input(
    source_root: Path,
    release_version: str,
    artifact_uri: str,
    artifact_root: Path,
    sbom_uri: str,
    sbom_root: Path,
    attestation_uri: str,
    attestation_root: Path,
    web_uri: str,
    web_root: Path,
    frozen_at: str,
) -> dict[str, Any]:
    source_root = source_root.resolve()
    build_path = artifact_root / "build-manifest.json"
    build_manifest = load_json(build_path)
    if not isinstance(build_manifest, dict):
        raise ValueError("RELEASE_ASSEMBLY_BUILD_MANIFEST_INVALID")
    if build_path.read_bytes() != canonical_bytes(build_manifest):
        raise ValueError("RELEASE_ASSEMBLY_BUILD_MANIFEST_NOT_CANONICAL_BYTES")

    source_inventory_path = artifact_root / "release-source-inventory.json"
    source_archive_path = artifact_root / "release-source.tar.gz"
    source_inventory = load_json(source_inventory_path)
    if (
        not isinstance(source_inventory, dict)
        or source_inventory.get("sourceCommit") != build_manifest.get("sourceCommit")
        or source_inventory.get("normalizedManifestSha256") != build_manifest.get("sourceManifestSha256")
    ):
        raise ValueError("RELEASE_ASSEMBLY_SOURCE_INVENTORY_SUBJECT_MISMATCH")

    build_artifacts = {
        item.get("name"): item
        for item in build_manifest.get("artifacts", [])
        if isinstance(item, dict)
    }
    artifact_specs = (
        ("backend", "scholarsense-backend.jar"),
        ("frontend", "scholarsense-frontend.tar.gz"),
    )
    artifacts: list[dict[str, Any]] = []
    subject_digests: dict[str, str] = {}
    for identity, name in artifact_specs:
        path = artifact_root / name
        expected = build_artifacts.get(name)
        if not isinstance(expected, dict) or not path.is_file():
            raise ValueError(f"RELEASE_ASSEMBLY_ARTIFACT_MISSING: {identity}")
        actual_digest = _sha256(path)
        if actual_digest != expected.get("binarySha256") or path.stat().st_size != expected.get("size"):
            raise ValueError(f"RELEASE_ASSEMBLY_ARTIFACT_DIGEST_MISMATCH: {identity}")
        item = _reference(
            identity,
            release_version,
            artifact_uri,
            path,
            kind="artifact",
            media_type=expected.get("mediaType"),
        )
        item["name"] = name
        artifacts.append(item)
        subject_digests[identity] = actual_digest

    report_path = web_root / "formal-web-report.json"
    report = load_json(report_path)
    if not isinstance(report, dict) or report.get("subjectArtifactSha256") != subject_digests["frontend"] or report.get("result") != "passed":
        raise ValueError("RELEASE_ASSEMBLY_FORMAL_WEB_SUBJECT_MISMATCH")

    evidence: list[dict[str, Any]] = []
    for identity in ("backend", "frontend"):
        subject = subject_digests[identity]
        evidence.extend(
            [
                _reference(f"{identity}-sbom-cyclonedx", "CYCLONEDX-1.7", sbom_uri, sbom_root / f"{identity}.cdx.json", kind="sbom-cyclonedx", subject_sha256=subject),
                _reference(f"{identity}-sbom-spdx", "SPDX-2.3", sbom_uri, sbom_root / f"{identity}.spdx.json", kind="sbom-spdx", subject_sha256=subject),
                _reference(f"{identity}-vulnerability-scan", "1.0.0", sbom_uri, sbom_root / "sbom-evidence.json", kind="vulnerability-scan", subject_sha256=subject),
                _reference(f"{identity}-provenance", "1.0.0", attestation_uri, attestation_root / f"scholarsense-{identity}.attestations.json", kind="provenance", subject_sha256=subject),
                _reference(f"{identity}-sbom-attestation", "1.0.0", attestation_uri, attestation_root / f"scholarsense-{identity}.attestations.json", kind="sbom-attestation", subject_sha256=subject),
                _reference(f"{identity}-artifact-signature", "COSIGN-BUNDLE-0.3", attestation_uri, attestation_root / f"scholarsense-{identity}.sigstore.json", kind="artifact-signature", subject_sha256=subject),
            ]
        )
    evidence.extend(
        [
            _reference("frontend-formal-web-report", "FORMAL-WEB-REPORT-1.0.0", web_uri, report_path, kind="formal-web-report", subject_sha256=subject_digests["frontend"]),
            _reference("frontend-visual-baseline", "VGB-1.0.0", artifact_uri, source_root / "contracts/release/visual-baseline-vgb-1.0.0.json", kind="visual-baseline", subject_sha256=subject_digests["frontend"]),
            _reference("frontend-ui-token-manifest", "UI-TOKEN-MANIFEST-1.0.0", artifact_uri, source_root / "contracts/release/ui-token-manifest-1.0.0.json", kind="ui-token-manifest", subject_sha256=subject_digests["frontend"]),
            _reference("frontend-brand-asset-manifest", "BRAND-ASSET-MANIFEST-1.0.0", artifact_uri, source_root / "contracts/release/brand-asset-manifest-1.0.0.json", kind="brand-asset-manifest", subject_sha256=subject_digests["frontend"]),
        ]
    )
    frontend_kinds = {"formal-web-report", "visual-baseline", "ui-token-manifest", "brand-asset-manifest"}
    supply_chain_kinds = {
        "artifact-signature",
        "provenance",
        "sbom-attestation",
        "sbom-cyclonedx",
        "sbom-spdx",
        "vulnerability-scan",
    }
    return {
        "releaseVersion": release_version,
        "buildManifest": build_manifest,
        "buildManifestRef": _reference(
            "build-manifest",
            str(build_manifest.get("version")),
            artifact_uri,
            build_path,
            binary_sha256=canonical_sha256(build_manifest),
        ),
        "sourceInventoryRef": _reference(
            "release-source-inventory",
            "RELEASE-SOURCE-INVENTORY-1.0.0",
            artifact_uri,
            source_inventory_path,
        ),
        "sourceArchiveRef": _reference(
            "release-source-archive",
            str(build_manifest.get("sourceCommit")),
            artifact_uri,
            source_archive_path,
            media_type="application/vnd.scholarsense.release-source.v1+gzip",
        ),
        "baselineApprovals": _controlled_references(source_root, artifact_uri, BASELINES, approved=True),
        "runtimeEvidence": [
            {
                "id": "supply-chain-evidence",
                "status": "passed",
                "evidenceIds": [item["id"] for item in evidence if item["kind"] in supply_chain_kinds],
            },
            {
                "id": "formal-web-evidence",
                "status": "passed",
                "evidenceIds": [item["id"] for item in evidence if item["kind"] in frontend_kinds],
            },
            {
                "id": "app-webview",
                "status": "not-applicable",
                "decisionId": "USER-2026-07-19-SCHOOL-APP-NA",
                "runtimeEvidenceClaim": "none",
            },
            {
                "id": "future-app-device",
                "status": "pending-story-execution",
                "ownerStory": "7.1/7.x",
                "runtimeEvidenceClaim": "none",
            },
        ],
        "controlledInputs": _controlled_references(source_root, artifact_uri, CONTROLLED_INPUTS),
        "locks": _controlled_references(source_root, artifact_uri, LOCKS),
        "artifacts": artifacts,
        "evidence": evidence,
        "frozenAt": frozen_at,
    }


def assemble_evidence_index_input(
    manifest_uri: str,
    manifest_path: Path,
    signature_uri: str,
    signature_path: Path,
    created_at: str,
) -> dict[str, Any]:
    release_manifest = load_json(manifest_path)
    if not isinstance(release_manifest, dict):
        raise ValueError("RELEASE_ASSEMBLY_MANIFEST_INVALID")
    canonical = canonical_bytes(release_manifest)
    if manifest_path.read_bytes() != canonical:
        raise ValueError("RELEASE_ASSEMBLY_MANIFEST_NOT_CANONICAL_BYTES")
    manifest_digest = canonical_sha256(release_manifest)
    manifest_reference = _reference(
        "release-manifest",
        "RELEASE-MANIFEST-1.0.0",
        manifest_uri,
        manifest_path,
        binary_sha256=manifest_digest,
    )
    signature_reference = _reference(
        "manifest-signature",
        "COSIGN-BUNDLE-0.3",
        signature_uri,
        signature_path,
        kind="manifest-signature",
        subject_sha256=manifest_digest,
        media_type="application/vnd.dev.sigstore.bundle.v0.3+json",
    )
    signature_reference["dependsOn"] = ["release-manifest"]
    return {
        "releaseManifest": release_manifest,
        "releaseManifestRef": manifest_reference,
        "manifestSignature": signature_reference,
        "createdAt": created_at,
    }
