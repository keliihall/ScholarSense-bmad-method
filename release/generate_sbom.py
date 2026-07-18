#!/usr/bin/env python3
"""Generate subject-bound CycloneDX/SPDX SBOMs through pinned Trivy."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from release_json import canonical_bytes, canonical_sha256, load_json  # noqa: E402
from release_policy import license_issues, vulnerability_issues  # noqa: E402
from sbom import (  # noqa: E402
    GITHUB_OIDC_ISSUER,
    ScanContext,
    TRIVY_CERTIFICATE_IDENTITY,
    aggregate_components,
    backend_components,
    build_cyclonedx,
    build_spdx,
    npm_components,
    npm_tree_reconciliation,
    sbom_pair_issues,
    security_adjudications,
)


DB_REPOSITORY = "ghcr.io/aquasecurity/trivy-db:2"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _run(arguments: list[str], environment: dict[str, str]) -> None:
    result = subprocess.run(
        arguments,
        check=False,
        text=True,
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    sys.stdout.write(result.stdout)
    if result.returncode != 0:
        raise RuntimeError(f"SBOM_COMMAND_FAILED: {' '.join(arguments)}")


def _tool_context(
    root: Path,
    trivy: Path,
    archive: Path,
    bundle: Path,
    cache: Path,
    tool_name: str,
    cosign: Path,
    cosign_tool_name: str,
) -> ScanContext:
    lock = load_json(root / "contracts/release/toolchain-lock-1.0.0.json")
    matches = [item for item in lock["tools"] if item.get("name") == tool_name]
    if len(matches) != 1:
        raise ValueError("SBOM_TRIVY_TOOL_LOCK_ENTRY_INVALID")
    tool = matches[0]
    if tool.get("version") != "v0.72.0":
        raise ValueError("SBOM_TRIVY_VERSION_FORBIDDEN")
    if _sha256(archive) != tool.get("binarySha256"):
        raise ValueError("SBOM_TRIVY_ARCHIVE_SHA256_MISMATCH")
    version = subprocess.run(
        [str(trivy), "--version"], check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT
    )
    if version.returncode != 0 or version.stdout.strip() != "Version: 0.72.0":
        raise ValueError("SBOM_TRIVY_BINARY_VERSION_MISMATCH")
    cosign_matches = [item for item in lock["tools"] if item.get("name") == cosign_tool_name]
    if len(cosign_matches) != 1:
        raise ValueError("SBOM_COSIGN_TOOL_LOCK_ENTRY_INVALID")
    cosign_tool = cosign_matches[0]
    if cosign_tool.get("version") != "v3.1.2" or _sha256(cosign) != cosign_tool.get("binarySha256"):
        raise ValueError("SBOM_COSIGN_BINARY_MISMATCH")
    cosign_version = subprocess.run(
        [str(cosign), "version"], check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT
    )
    if cosign_version.returncode != 0 or "GitVersion:    v3.1.2" not in cosign_version.stdout:
        raise ValueError("SBOM_COSIGN_VERSION_MISMATCH")
    verification = subprocess.run(
        [
            str(cosign),
            "verify-blob",
            "--bundle",
            str(bundle),
            "--certificate-identity",
            TRIVY_CERTIFICATE_IDENTITY,
            "--certificate-oidc-issuer",
            GITHUB_OIDC_ISSUER,
            str(archive),
        ],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    sys.stdout.write(verification.stdout)
    if verification.returncode != 0 or "Verified OK" not in verification.stdout:
        raise ValueError("SBOM_TRIVY_SIGSTORE_BUNDLE_INVALID")
    metadata_path = cache / "db/metadata.json"
    database_path = cache / "db/trivy.db"
    metadata = load_json(metadata_path)
    now = datetime.now(timezone.utc)
    try:
        next_update = datetime.fromisoformat(metadata["NextUpdate"].replace("Z", "+00:00"))
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError("SBOM_TRIVY_DATABASE_METADATA_INVALID") from error
    if next_update <= now:
        raise ValueError("SBOM_TRIVY_DATABASE_EXPIRED")
    return ScanContext(
        trivy_version="0.72.0",
        trivy_archive_sha256=_sha256(archive),
        trivy_binary_sha256=_sha256(trivy),
        trivy_source_uri=tool["sourceUri"],
        trivy_bundle_sha256=_sha256(bundle),
        trivy_certificate_identity=TRIVY_CERTIFICATE_IDENTITY,
        trivy_oidc_issuer=GITHUB_OIDC_ISSUER,
        cosign_binary_sha256=_sha256(cosign),
        cosign_source_uri=cosign_tool["sourceUri"],
        database_repository=DB_REPOSITORY,
        database_sha256=_sha256(database_path),
        database_metadata_sha256=_sha256(metadata_path),
        database_updated_at=metadata["UpdatedAt"],
        database_next_update=metadata["NextUpdate"],
    )


def _subject(manifest: dict[str, Any], artifact_id: str, name: str) -> dict[str, Any]:
    matches = [item for item in manifest["artifacts"] if item.get("name") == name]
    if len(matches) != 1:
        raise ValueError(f"SBOM_BUILD_MANIFEST_ARTIFACT_INVALID: {name}")
    artifact = matches[0]
    return {"id": artifact_id, **artifact}


def _scan_draft(
    trivy: Path,
    cache: Path,
    draft: dict[str, Any],
    directory: Path,
    label: str,
    expected_purls: set[str],
    environment: dict[str, str],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    draft_path = directory / f"{label}.draft.cdx.json"
    raw_cdx_path = directory / f"{label}.trivy.cdx.json"
    raw_spdx_path = directory / f"{label}.trivy.spdx.json"
    draft_path.write_bytes(canonical_bytes(draft) + b"\n")
    common = [
        str(trivy),
        "sbom",
        "--cache-dir",
        str(cache),
        "--skip-db-update",
        "--scanners",
        "vuln",
    ]
    _run([*common, "--format", "cyclonedx", "--output", str(raw_cdx_path), str(draft_path)], environment)
    _run([*common, "--format", "spdx-json", "--output", str(raw_spdx_path), str(draft_path)], environment)
    raw_cdx = load_json(raw_cdx_path)
    raw_spdx = load_json(raw_spdx_path)
    cdx_purls = {item.get("purl") for item in raw_cdx.get("components", []) if item.get("purl")}
    spdx_purls = {
        reference.get("referenceLocator")
        for package in raw_spdx.get("packages", [])
        for reference in package.get("externalRefs", [])
        if reference.get("referenceType") == "purl"
    }
    if cdx_purls != expected_purls:
        raise ValueError(f"SBOM_TRIVY_CYCLONEDX_COMPONENT_DRIFT: {label}")
    if spdx_purls != expected_purls:
        raise ValueError(f"SBOM_TRIVY_SPDX_COMPONENT_DRIFT: {label}")
    vulnerabilities = raw_cdx.get("vulnerabilities", [])
    if not isinstance(vulnerabilities, list):
        raise ValueError(f"SBOM_TRIVY_VULNERABILITIES_INVALID: {label}")
    return vulnerabilities, raw_spdx


def _findings(vulnerabilities: list[dict[str, Any]]) -> list[dict[str, str]]:
    findings: list[dict[str, str]] = []
    for vulnerability in vulnerabilities:
        ratings = vulnerability.get("ratings", [])
        severities = [str(item.get("severity", "UNKNOWN")).upper() for item in ratings if isinstance(item, dict)]
        severity = severities[0] if severities else "UNKNOWN"
        for affected in vulnerability.get("affects", []):
            if isinstance(affected, dict):
                findings.append(
                    {
                        "purl": str(affected.get("ref", "UNKNOWN")),
                        "vulnerabilityId": str(vulnerability.get("id", "UNKNOWN")),
                        "severity": severity,
                    }
                )
    return findings


def generate(
    root: Path,
    artifacts: Path,
    output: Path,
    trivy: Path,
    archive: Path,
    bundle: Path,
    cache: Path,
    tool_name: str,
    docker_config: Path,
    npm_tree_path: Path,
    cosign: Path,
    cosign_tool_name: str,
) -> dict[str, Any]:
    project_root = root.resolve()
    artifact_root = artifacts.resolve()
    destination = output.resolve()
    if destination.exists():
        raise ValueError("SBOM_OUTPUT_ALREADY_EXISTS")
    context = _tool_context(
        project_root,
        trivy.resolve(),
        archive.resolve(),
        bundle.resolve(),
        cache.resolve(),
        tool_name,
        cosign.resolve(),
        cosign_tool_name,
    )
    manifest_path = artifact_root / "build-manifest.json"
    manifest = load_json(manifest_path)
    for artifact in manifest["artifacts"]:
        path = artifact_root / artifact["name"]
        if not path.is_file() or path.stat().st_size != artifact["size"] or _sha256(path) != artifact["binarySha256"]:
            raise ValueError(f"SBOM_ARTIFACT_MANIFEST_MISMATCH: {artifact['name']}")
    backend_lock = load_json(project_root / "contracts/release/backend-lock-1.0.0.json")
    package_lock = load_json(project_root / "frontend/package-lock.json")
    npm_tree = load_json(npm_tree_path.resolve())
    npm_tree_summary, npm_tree_issues = npm_tree_reconciliation(package_lock, npm_tree)
    if npm_tree_issues:
        raise ValueError(npm_tree_issues[0])
    vulnerability_policy = load_json(project_root / "contracts/release/vulnerability-policy-1.0.0.json")
    license_policy = load_json(project_root / "contracts/release/license-policy-1.0.0.json")
    backend = backend_components(backend_lock, artifact_root / "scholarsense-backend.jar")
    frontend = npm_components(package_lock)
    aggregate = aggregate_components(manifest, backend, frontend, backend_lock)
    subjects = {
        "backend": (_subject(manifest, "backend", "scholarsense-backend.jar"), backend),
        "frontend": (_subject(manifest, "frontend", "scholarsense-frontend.tar.gz"), frontend),
        "aggregate": (
            {
                "id": "aggregate",
                "name": "scholarsense-release",
                "mediaType": "application/vnd.scholarsense.release",
                "size": sum(item["size"] for item in manifest["artifacts"]),
                "binarySha256": manifest["attempts"][0]["artifactSetSha256"],
            },
            aggregate,
        ),
    }
    third_party = [item for item in aggregate if item["kind"] != "release-artifact"]
    policy_issues = license_issues(
        [{"purl": item["purl"], "license": item["licenseExpression"]} for item in third_party],
        license_policy,
    )
    if policy_issues:
        raise ValueError(policy_issues[0])

    destination.parent.mkdir(parents=True, exist_ok=True)
    environment = os.environ.copy()
    environment["DOCKER_CONFIG"] = str(docker_config.resolve())
    with tempfile.TemporaryDirectory(prefix=".sbom-", dir=destination.parent) as temporary:
        temporary_root = Path(temporary)
        staging = temporary_root / "complete"
        staging.mkdir()
        subject_evidence: list[dict[str, Any]] = []
        for label, (subject, components) in subjects.items():
            draft = build_cyclonedx(subject, components, context, [])
            vulnerabilities, _ = _scan_draft(
                trivy,
                cache,
                draft,
                temporary_root,
                label,
                {item["purl"] for item in components},
                environment,
            )
            findings = _findings(vulnerabilities)
            vulnerability_policy_issues = vulnerability_issues(
                findings,
                vulnerability_policy,
                [],
                subject["binarySha256"],
                datetime.now(timezone.utc),
            )
            if vulnerability_policy_issues:
                raise ValueError(vulnerability_policy_issues[0])
            cyclonedx = build_cyclonedx(subject, components, context, vulnerabilities)
            spdx = build_spdx(subject, components, context)
            pair_issues = sbom_pair_issues(cyclonedx, spdx, subject, components, context)
            if pair_issues:
                raise ValueError(pair_issues[0])
            cdx_path = staging / f"{label}.cdx.json"
            spdx_path = staging / f"{label}.spdx.json"
            cdx_path.write_bytes(canonical_bytes(cyclonedx) + b"\n")
            spdx_path.write_bytes(canonical_bytes(spdx) + b"\n")
            subject_evidence.append(
                {
                    "id": label,
                    "subjectBinarySha256": subject["binarySha256"],
                    "componentCount": len(components),
                    "vulnerabilityFindingCount": len(findings),
                    "cycloneDx": {"path": cdx_path.name, "binarySha256": _sha256(cdx_path)},
                    "spdx": {"path": spdx_path.name, "binarySha256": _sha256(spdx_path)},
                    "result": "PASS",
                }
            )
        evidence = {
            "version": "SBOM-EVIDENCE-1.0.0",
            "buildManifestSha256": canonical_sha256(manifest),
            "artifactSetSha256": manifest["attempts"][0]["artifactSetSha256"],
            "scanContext": {
                "trivyVersion": context.trivy_version,
                "trivyArchiveSha256": context.trivy_archive_sha256,
                "trivyBinarySha256": context.trivy_binary_sha256,
                "trivySourceUri": context.trivy_source_uri,
                "trivyBundleSha256": context.trivy_bundle_sha256,
                "trivyCertificateIdentity": context.trivy_certificate_identity,
                "trivyOidcIssuer": context.trivy_oidc_issuer,
                "cosignBinarySha256": context.cosign_binary_sha256,
                "cosignSourceUri": context.cosign_source_uri,
                "databaseRepository": context.database_repository,
                "databaseSha256": context.database_sha256,
                "databaseMetadataSha256": context.database_metadata_sha256,
                "databaseUpdatedAt": context.database_updated_at,
                "databaseNextUpdate": context.database_next_update,
            },
            "policies": {
                "vulnerabilityPolicySha256": canonical_sha256(vulnerability_policy),
                "licensePolicySha256": canonical_sha256(license_policy),
                "blockedSeverities": vulnerability_policy["blockedSeverities"],
                "unknownSeverityTreatment": vulnerability_policy["unknownSeverityTreatment"],
            },
            "subjects": subject_evidence,
            "adjudications": security_adjudications(frontend, backend_lock, package_lock),
            "npmTreeReconciliation": npm_tree_summary,
            "result": "PASS",
        }
        obligation_map = {
            item["licenseExpression"]: item["actions"] for item in license_policy["obligations"]
        }
        notice = {
            "version": "THIRD-PARTY-NOTICES-1.0.0",
            "components": [
                {
                    "purl": item["purl"],
                    "licenseExpression": item["licenseExpression"],
                    "sourceUri": item["sourceUri"],
                    "obligations": obligation_map[item["licenseExpression"]],
                }
                for item in sorted(third_party, key=lambda component: component["purl"])
            ],
        }
        notice_path = staging / "third-party-notices.json"
        notice_path.write_bytes(canonical_bytes(notice) + b"\n")
        evidence["licenseNotice"] = {
            "path": notice_path.name,
            "binarySha256": _sha256(notice_path),
            "componentCount": len(notice["components"]),
        }
        evidence_path = staging / "sbom-evidence.json"
        evidence_path.write_bytes(canonical_bytes(evidence) + b"\n")
        os.replace(staging, destination)
        return evidence


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=PROJECT_ROOT)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--trivy", type=Path, required=True)
    parser.add_argument("--trivy-archive", type=Path, required=True)
    parser.add_argument("--trivy-bundle", type=Path, required=True)
    parser.add_argument("--cache-dir", type=Path, required=True)
    parser.add_argument("--tool-name", required=True)
    parser.add_argument("--docker-config", type=Path, required=True)
    parser.add_argument("--npm-tree", type=Path, required=True)
    parser.add_argument("--cosign", type=Path, required=True)
    parser.add_argument("--cosign-tool-name", required=True)
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    try:
        evidence = generate(
            args.root,
            args.artifacts,
            args.output,
            args.trivy,
            args.trivy_archive,
            args.trivy_bundle,
            args.cache_dir,
            args.tool_name,
            args.docker_config,
            args.npm_tree,
            args.cosign,
            args.cosign_tool_name,
        )
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        print(error, file=sys.stderr)
        return 1
    print(
        "generate-sbom: PASS "
        f"subjects={len(evidence['subjects'])} artifactSet={evidence['artifactSetSha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
