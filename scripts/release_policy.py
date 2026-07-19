#!/usr/bin/env python3
"""Provider-neutral release vulnerability, license, script, and signature policies."""

from __future__ import annotations

from datetime import datetime
import re
from typing import Any


def _date_time(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo is not None else None


def vulnerability_issues(
    findings: list[dict[str, Any]],
    policy: dict[str, Any],
    exceptions: list[dict[str, Any]],
    subject_sha256: str,
    now: datetime,
) -> list[str]:
    blocked = set(policy.get("blockedSeverities", []))
    if policy.get("unknownSeverityTreatment") == "block":
        blocked.add("UNKNOWN")
    issues: list[str] = []
    for finding in findings:
        severity = str(finding.get("severity", "UNKNOWN")).upper()
        if severity not in blocked:
            continue
        accepted = False
        for exception in exceptions:
            expiry = _date_time(exception.get("expiresAt"))
            if (
                exception.get("purl") == finding.get("purl")
                and exception.get("vulnerabilityId") == finding.get("vulnerabilityId")
                and exception.get("subjectSha256") == subject_sha256
                and isinstance(exception.get("approvedBy"), str)
                and bool(exception["approvedBy"].strip())
                and isinstance(exception.get("reason"), str)
                and len(exception["reason"].strip()) >= 10
                and expiry is not None
                and expiry > now
            ):
                accepted = True
                break
        if not accepted:
            issues.append(
                f"VULNERABILITY_BLOCKED: {finding.get('vulnerabilityId', 'UNKNOWN')} "
                f"{finding.get('purl', 'UNKNOWN')} {severity}"
            )
    return sorted(set(issues))


def license_issues(
    components: list[dict[str, Any]],
    policy: dict[str, Any],
    exceptions: list[dict[str, Any]] | None = None,
    subject_sha256: str | None = None,
    now: datetime | None = None,
) -> list[str]:
    allowed = set(policy.get("allowed", [])) | set(policy.get("allowedExpressions", []))
    denied = set(policy.get("denied", []))
    issues: list[str] = []
    for component in components:
        license_name = str(component.get("license") or "UNKNOWN")
        purl = component.get("purl", "UNKNOWN")
        accepted = False
        for exception in exceptions or []:
            expiry = _date_time(exception.get("expiresAt"))
            if (
                exception.get("purl") == purl
                and exception.get("licenseExpression") == license_name
                and exception.get("subjectSha256") == subject_sha256
                and isinstance(exception.get("approvedBy"), str)
                and bool(exception["approvedBy"].strip())
                and isinstance(exception.get("reason"), str)
                and len(exception["reason"].strip()) >= 10
                and now is not None
                and expiry is not None
                and expiry > now
            ):
                accepted = True
                break
        if not accepted and (license_name == "UNKNOWN" or license_name in denied or license_name not in allowed):
            issues.append(f"LICENSE_BLOCKED: {purl} {license_name}")
    return sorted(set(issues))


def install_script_inventory(
    lock: dict[str, Any],
    approved_paths: list[str],
) -> list[dict[str, str]]:
    approved = set(approved_paths)
    inventory: list[dict[str, str]] = []
    for path, metadata in sorted(lock.get("packages", {}).items()):
        if not isinstance(metadata, dict) or metadata.get("hasInstallScript") is not True:
            continue
        inventory.append(
            {
                "path": path,
                "version": str(metadata.get("version", "UNKNOWN")),
                "decision": "approved-for-execution" if path in approved else "blocked-not-executed",
            }
        )
    return inventory


def signature_bundle_issues(
    bundle: dict[str, Any],
    expected_subject_sha256: str,
    expected_certificate_identity: str,
    expected_oidc_issuer: str,
    expected_run_id: str,
    run_created_at: datetime,
) -> list[str]:
    issues: list[str] = []
    expectations = {
        "subjectSha256": expected_subject_sha256,
        "certificateIdentity": expected_certificate_identity,
        "oidcIssuer": expected_oidc_issuer,
        "runId": expected_run_id,
    }
    for field, expected in expectations.items():
        if bundle.get(field) != expected:
            issues.append(f"SIGNATURE_{field.upper()}_MISMATCH")
    integrated_at = _date_time(bundle.get("integratedAt"))
    if integrated_at is None:
        issues.append("SIGNATURE_INTEGRATED_AT_INVALID")
    elif integrated_at < run_created_at:
        issues.append("SIGNATURE_OLD_BUNDLE_REPLAY")
    return sorted(set(issues))


def promotion_record_set_issues(records: list[dict[str, Any]]) -> list[str]:
    issues: list[str] = []
    grouped: dict[tuple[Any, Any], list[dict[str, Any]]] = {}
    for index, record in enumerate(records):
        key = (record.get("releaseVersion"), record.get("targetEnvironment"))
        grouped.setdefault(key, []).append(record)
        if record.get("result") in {"promoted", "replayed"}:
            required = (
                "manifestSha256",
                "artifactOciDigest",
                "evidenceIndexSha256",
                "ledgerUri",
            )
            for field in required:
                if not record.get(field):
                    issues.append(f"PROMOTION_PARTIAL_STATE: record[{index}].{field}")
    for key, values in grouped.items():
        material = {
            (
                value.get("manifestSha256"),
                value.get("artifactOciDigest"),
                value.get("evidenceIndexSha256"),
            )
            for value in values
        }
        if len(material) > 1:
            issues.append(f"PROMOTION_IDEMPOTENCY_CONFLICT: {key[0]}+{key[1]}")
        winner_count = sum(value.get("result") == "promoted" for value in values)
        if winner_count > 1:
            issues.append(f"PROMOTION_CONCURRENT_MULTIPLE_WINNERS: {key[0]}+{key[1]}")
        for value in values:
            digest = value.get("artifactOciDigest")
            target_uri = value.get("targetArtifactUri")
            if target_uri is not None and isinstance(digest, str) and not str(target_uri).endswith(f"@{digest}"):
                issues.append(f"PROMOTION_STORE_LEDGER_DRIFT: {key[0]}+{key[1]}")
    return sorted(set(issues))


def source_identity_issues(
    expected_commit: str,
    actual_commit: str,
    actual_ref: str,
    expected_ref: str,
    workspace_dirty: bool,
) -> list[str]:
    issues: list[str] = []
    if re.fullmatch(r"[0-9a-f]{40}", expected_commit or "") is None:
        issues.append("SOURCE_COMMIT_INVALID")
    if actual_commit != expected_commit:
        issues.append("SOURCE_COMMIT_DRIFT")
    if actual_ref != expected_ref:
        issues.append("SOURCE_REF_DRIFT")
    if workspace_dirty:
        issues.append("SOURCE_WORKSPACE_DIRTY")
    return sorted(set(issues))


def sbom_reconciliation_issues(
    expected_purls: set[str],
    sbom: dict[str, Any],
    expected_subject_sha256: str,
) -> list[str]:
    issues: list[str] = []
    if sbom.get("subjectArtifactSha256") != expected_subject_sha256:
        issues.append("SBOM_SUBJECT_DIGEST_MISMATCH")
    components = sbom.get("components")
    if not isinstance(components, list):
        return sorted(set(issues + ["SBOM_COMPONENTS_INVALID"]))
    actual_purls = {
        item.get("purl")
        for item in components
        if isinstance(item, dict) and isinstance(item.get("purl"), str)
    }
    for purl in sorted(expected_purls - actual_purls):
        issues.append(f"SBOM_COMPONENT_MISSING: {purl}")
    for purl in sorted(actual_purls - expected_purls):
        issues.append(f"SBOM_COMPONENT_UNEXPECTED: {purl}")
    if len(actual_purls) != len(components):
        issues.append("SBOM_COMPONENT_DUPLICATE_OR_PURL_MISSING")
    return sorted(set(issues))
