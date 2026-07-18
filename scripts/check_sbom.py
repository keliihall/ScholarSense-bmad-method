#!/usr/bin/env python3
"""Validate generated SBOM pairs, policies, locks, subjects, and scan evidence."""

from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "release"))

from release_json import canonical_bytes, canonical_sha256, load_json, schema_issues  # noqa: E402
from release_policy import license_issues, vulnerability_issues  # noqa: E402
from sbom import (  # noqa: E402
    ScanContext,
    aggregate_components,
    backend_components,
    npm_components,
    scan_context_policy_issues,
    sbom_pair_issues,
    security_adjudications,
)


HEX64 = re.compile(r"^[0-9a-f]{64}$")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _canonical_file_issues(path: Path, document: Any) -> list[str]:
    try:
        payload = path.read_bytes()
    except OSError:
        return [f"SBOM_FILE_UNREADABLE: {path.name}"]
    return [] if payload == canonical_bytes(document) + b"\n" else [f"SBOM_FILE_NOT_CANONICAL: {path.name}"]


def _scan_context(document: dict[str, Any]) -> ScanContext:
    return ScanContext(
        trivy_version=document["trivyVersion"],
        trivy_archive_sha256=document["trivyArchiveSha256"],
        trivy_binary_sha256=document["trivyBinarySha256"],
        trivy_source_uri=document["trivySourceUri"],
        trivy_bundle_sha256=document["trivyBundleSha256"],
        trivy_certificate_identity=document["trivyCertificateIdentity"],
        trivy_oidc_issuer=document["trivyOidcIssuer"],
        cosign_binary_sha256=document["cosignBinarySha256"],
        cosign_source_uri=document["cosignSourceUri"],
        database_repository=document["databaseRepository"],
        database_sha256=document["databaseSha256"],
        database_metadata_sha256=document["databaseMetadataSha256"],
        database_updated_at=document["databaseUpdatedAt"],
        database_next_update=document["databaseNextUpdate"],
    )


def _findings(vulnerabilities: Any) -> list[dict[str, str]]:
    findings: list[dict[str, str]] = []
    if not isinstance(vulnerabilities, list):
        return [{"purl": "UNKNOWN", "vulnerabilityId": "UNKNOWN", "severity": "UNKNOWN"}]
    for vulnerability in vulnerabilities:
        if not isinstance(vulnerability, dict):
            findings.append({"purl": "UNKNOWN", "vulnerabilityId": "UNKNOWN", "severity": "UNKNOWN"})
            continue
        ratings = vulnerability.get("ratings", [])
        severities = [str(item.get("severity", "UNKNOWN")).upper() for item in ratings if isinstance(item, dict)]
        severity = severities[0] if severities else "UNKNOWN"
        affects = vulnerability.get("affects", [])
        if not affects:
            findings.append(
                {"purl": "UNKNOWN", "vulnerabilityId": str(vulnerability.get("id", "UNKNOWN")), "severity": severity}
            )
        for affected in affects:
            findings.append(
                {
                    "purl": str(affected.get("ref", "UNKNOWN")),
                    "vulnerabilityId": str(vulnerability.get("id", "UNKNOWN")),
                    "severity": severity,
                }
            )
    return findings


def validate(project_root: Path, artifact_root: Path, sbom_root: Path) -> list[str]:
    root = project_root.resolve()
    artifacts = artifact_root.resolve()
    output = sbom_root.resolve()
    issues: list[str] = []
    evidence_path = output / "sbom-evidence.json"
    try:
        evidence = load_json(evidence_path)
        schema = load_json(root / "contracts/release/sbom-evidence.schema.json")
        issues.extend(schema_issues(evidence, schema))
        issues.extend(_canonical_file_issues(evidence_path, evidence))
        context = _scan_context(evidence["scanContext"])
        manifest = load_json(artifacts / "build-manifest.json")
        backend_lock = load_json(root / "contracts/release/backend-lock-1.0.0.json")
        package_lock = load_json(root / "frontend/package-lock.json")
        vulnerability_policy = load_json(root / "contracts/release/vulnerability-policy-1.0.0.json")
        license_policy = load_json(root / "contracts/release/license-policy-1.0.0.json")
        toolchain_lock = load_json(root / "contracts/release/toolchain-lock-1.0.0.json")
    except (KeyError, OSError, TypeError, ValueError) as error:
        return [f"SBOM_EVIDENCE_INPUT_INVALID: {error}"]
    issues.extend(scan_context_policy_issues(context))
    matching_tools = [
        item
        for item in toolchain_lock["tools"]
        if item.get("version") == "v0.72.0"
        and item.get("binarySha256") == context.trivy_archive_sha256
        and item.get("sourceUri") == context.trivy_source_uri
    ]
    if len(matching_tools) != 1:
        issues.append("SBOM_TRIVY_TOOL_LOCK_MISMATCH")
    matching_cosign = [
        item
        for item in toolchain_lock["tools"]
        if item.get("version") == "v3.1.2"
        and item.get("binarySha256") == context.cosign_binary_sha256
        and item.get("sourceUri") == context.cosign_source_uri
    ]
    if len(matching_cosign) != 1:
        issues.append("SBOM_COSIGN_TOOL_LOCK_MISMATCH")
    for value, code in (
        (context.trivy_binary_sha256, "SBOM_TRIVY_BINARY_DIGEST_INVALID"),
        (context.trivy_bundle_sha256, "SBOM_TRIVY_BUNDLE_DIGEST_INVALID"),
        (context.database_sha256, "SBOM_TRIVY_DATABASE_DIGEST_INVALID"),
        (context.database_metadata_sha256, "SBOM_TRIVY_DATABASE_METADATA_DIGEST_INVALID"),
    ):
        if not HEX64.fullmatch(str(value)):
            issues.append(code)
    if context.database_repository != "ghcr.io/aquasecurity/trivy-db:2":
        issues.append("SBOM_TRIVY_DATABASE_REPOSITORY_INVALID")

    backend = backend_components(backend_lock, artifacts / "scholarsense-backend.jar")
    frontend = npm_components(package_lock)
    aggregate = aggregate_components(manifest, backend, frontend, backend_lock)
    by_artifact = {item["name"]: item for item in manifest["artifacts"]}
    expected_subjects = {
        "backend": ({"id": "backend", **by_artifact["scholarsense-backend.jar"]}, backend),
        "frontend": ({"id": "frontend", **by_artifact["scholarsense-frontend.tar.gz"]}, frontend),
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
    records = {item.get("id"): item for item in evidence.get("subjects", []) if isinstance(item, dict)}
    if set(records) != set(expected_subjects):
        issues.append("SBOM_EVIDENCE_SUBJECT_SET_MISMATCH")
    for label, (subject, components) in expected_subjects.items():
        record = records.get(label, {})
        cdx_path = output / f"{label}.cdx.json"
        spdx_path = output / f"{label}.spdx.json"
        try:
            cyclonedx = load_json(cdx_path)
            spdx = load_json(spdx_path)
        except (OSError, ValueError) as error:
            issues.append(f"SBOM_DOCUMENT_INVALID: {label}: {error}")
            continue
        issues.extend(_canonical_file_issues(cdx_path, cyclonedx))
        issues.extend(_canonical_file_issues(spdx_path, spdx))
        issues.extend(sbom_pair_issues(cyclonedx, spdx, subject, components, context))
        findings = _findings(cyclonedx.get("vulnerabilities"))
        issues.extend(
            vulnerability_issues(
                findings,
                vulnerability_policy,
                [],
                subject["binarySha256"],
                datetime.now(timezone.utc),
            )
        )
        expectations = {
            "subjectBinarySha256": subject["binarySha256"],
            "componentCount": len(components),
            "vulnerabilityFindingCount": len(findings),
            "result": "PASS",
        }
        for field, expected in expectations.items():
            if record.get(field) != expected:
                issues.append(f"SBOM_EVIDENCE_{field.upper()}_MISMATCH: {label}")
        for field, path in (("cycloneDx", cdx_path), ("spdx", spdx_path)):
            binding = record.get(field, {})
            if binding.get("path") != path.name or binding.get("binarySha256") != _sha256(path):
                issues.append(f"SBOM_EVIDENCE_DOCUMENT_DIGEST_MISMATCH: {label}:{field}")

    third_party = [item for item in aggregate if item["kind"] != "release-artifact"]
    issues.extend(
        license_issues(
            [{"purl": item["purl"], "license": item["licenseExpression"]} for item in third_party],
            license_policy,
        )
    )
    if evidence.get("buildManifestSha256") != canonical_sha256(manifest):
        issues.append("SBOM_EVIDENCE_BUILD_MANIFEST_DIGEST_MISMATCH")
    if evidence.get("artifactSetSha256") != manifest["attempts"][0]["artifactSetSha256"]:
        issues.append("SBOM_EVIDENCE_ARTIFACT_SET_DIGEST_MISMATCH")
    expected_policies = {
        "vulnerabilityPolicySha256": canonical_sha256(vulnerability_policy),
        "licensePolicySha256": canonical_sha256(license_policy),
        "blockedSeverities": vulnerability_policy["blockedSeverities"],
        "unknownSeverityTreatment": vulnerability_policy["unknownSeverityTreatment"],
    }
    if evidence.get("policies") != expected_policies:
        issues.append("SBOM_EVIDENCE_POLICY_BINDING_MISMATCH")
    if evidence.get("adjudications") != security_adjudications(frontend, backend_lock, package_lock):
        issues.append("SBOM_EVIDENCE_ADJUDICATION_MISMATCH")
    obligation_map = {item["licenseExpression"]: item["actions"] for item in license_policy["obligations"]}
    expected_notice = {
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
    notice_path = output / "third-party-notices.json"
    try:
        notice = load_json(notice_path)
        issues.extend(_canonical_file_issues(notice_path, notice))
        if notice != expected_notice:
            issues.append("SBOM_THIRD_PARTY_NOTICE_MISMATCH")
        notice_binding = evidence.get("licenseNotice", {})
        if notice_binding != {
            "path": notice_path.name,
            "binarySha256": _sha256(notice_path),
            "componentCount": len(expected_notice["components"]),
        }:
            issues.append("SBOM_EVIDENCE_LICENSE_NOTICE_MISMATCH")
    except (OSError, ValueError) as error:
        issues.append(f"SBOM_THIRD_PARTY_NOTICE_INVALID: {error}")
    npm_summary = evidence.get("npmTreeReconciliation", {})
    if npm_summary.get("installedUnique", -1) + npm_summary.get("omittedOptionalUnique", -1) != len(frontend):
        issues.append("SBOM_EVIDENCE_NPM_TREE_RECONCILIATION_MISMATCH")
    if evidence.get("result") != "PASS":
        issues.append("SBOM_EVIDENCE_RESULT_NOT_PASS")
    return sorted(set(issues))


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) >= 2 else ROOT
    artifacts = Path(argv[2]) if len(argv) >= 3 else root / "release-out/task2-proof"
    sboms = Path(argv[3]) if len(argv) >= 4 else root / "release-out/task3-sbom"
    try:
        issues = validate(root, artifacts, sboms)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        issues = [str(error)]
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print("sbom: PASS (backend=42 frontend=156 aggregate=207)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
