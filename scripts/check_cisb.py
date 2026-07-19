#!/usr/bin/env python3
"""Validate the frozen CI/supply-chain platform baseline without trusting defaults."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Any

from check_workflow_security import validate_workflow
from release_json import load_json


PLACEHOLDER = re.compile(r"(?i)(?:^|[^a-z])(?:tbd|todo|unknown|no_vcs|latest|equivalent|placeholder|pending)(?:$|[^a-z])")
COMMIT_OID = re.compile(r"^[0-9a-f]{40}$")
SEMVER = re.compile(r"^v?\d+\.\d+\.\d+$")


def load_json_document(path: Path) -> dict[str, Any]:
    document = load_json(path)
    if not isinstance(document, dict):
        raise ValueError("JSON_OBJECT_REQUIRED")
    return document


def _get(document: dict[str, Any], path: str) -> Any:
    value: Any = document
    for part in path.split("."):
        if not isinstance(value, dict) or part not in value:
            return None
        value = value[part]
    return value


def _is_concrete(value: Any) -> bool:
    if value is None or value == "" or value == [] or value == {}:
        return False
    if isinstance(value, str) and PLACEHOLDER.search(value):
        return False
    return True


def validate_cisb(document: dict[str, Any], project_root: Path) -> list[str]:
    issues: list[str] = []
    required = (
        "version",
        "status",
        "authority.accountable",
        "authority.platformResponsible",
        "authority.securityResponsible",
        "authority.releaseResponsible",
        "authority.effectiveAt",
        "repository.provider",
        "repository.url",
        "repository.id",
        "repository.visibility",
        "repository.defaultBranch",
        "repository.sourceCommit",
        "ci.provider",
        "ci.workflow",
        "runner.provider",
        "runner.label",
        "runner.imageVersion",
        "runner.runtimeImageVersionVariable",
        "formalWebRunner.provider",
        "formalWebRunner.baselineUri",
        "formalWebRunner.runnerImageSha256",
        "formalWebRunner.registration",
        "formalWebRunner.isolation",
        "formalWebRunner.cleanup",
        "identities.build",
        "identities.attestation",
        "identities.artifactSigner",
        "identities.manifestSigner",
        "identities.promotion",
        "identities.verifier",
        "artifactStore.provider",
        "artifactStore.candidateNamespace",
        "artifactStore.stageNamespace",
        "artifactStore.productionNamespace",
        "artifactStore.addressing",
        "artifactStore.transport",
        "artifactStore.encryptionAtRestEvidence",
        "artifactStore.accessControlEvidence",
        "attestation.provider",
        "attestation.subjectQuery",
        "signing.provider",
        "signing.oidcIssuer",
        "signing.artifactCertificateIdentity",
        "signing.manifestCertificateIdentity",
        "goldenApproval.policy",
        "goldenApproval.accountablePrincipal",
        "goldenApproval.webQaGate",
        "goldenApproval.approvalHistoryApi",
        "promotion.adapter",
        "promotion.stageEnvironmentId",
        "promotion.productionEnvironmentId",
        "promotion.idempotencyKey",
        "promotion.casMechanism",
        "promotion.ledgerUri",
        "promotion.reconciliationApi",
        "promotion.rollbackApi",
        "promotion.approvalHistoryApi",
        "capabilityEvidence.repository",
        "capabilityEvidence.protectedEnvironments",
        "capabilityEvidence.artifactCasReadback",
        "capabilityEvidence.attestationSubjectQuery",
        "capabilityEvidence.signingVerification",
        "capabilityEvidence.promotionCas",
        "capabilityEvidence.verifier",
        "toolchain.trivy",
        "toolchain.cosign",
        "toolchain.oras",
        "toolchain.actions.attest",
        "toolchain.actions.checkout",
    )
    for path in required:
        if not _is_concrete(_get(document, path)):
            issues.append(f"CISB_PLATFORM_BASELINE_INCOMPLETE: {path}")

    if document.get("version") != "CISB-1.0.0":
        issues.append("CISB_PLATFORM_BASELINE_INCOMPLETE: version")
    if document.get("status") != "approved":
        issues.append("CISB_PLATFORM_BASELINE_INCOMPLETE: status")
    authority = document.get("authority", {})
    if authority.get("accountable") != "Hei":
        issues.append("CISB_PLATFORM_BASELINE_INCOMPLETE: authority.accountable")
    if not COMMIT_OID.fullmatch(str(_get(document, "repository.sourceCommit") or "")):
        issues.append("CISB_PLATFORM_BASELINE_INCOMPLETE: repository.sourceCommit")
    for tool in ("trivy", "cosign", "oras"):
        if not SEMVER.fullmatch(str(_get(document, f"toolchain.{tool}") or "")):
            issues.append(f"CISB_PLATFORM_BASELINE_INCOMPLETE: toolchain.{tool}")
    actions = _get(document, "toolchain.actions")
    if isinstance(actions, dict):
        for name, commit in actions.items():
            if not COMMIT_OID.fullmatch(str(commit)):
                issues.append(f"CISB_PLATFORM_BASELINE_INCOMPLETE: toolchain.actions.{name}")

    if _get(document, "runner.runtimeImageVersionVariable") != "ImageVersion":
        issues.append("CISB_PLATFORM_BASELINE_INCOMPLETE: runner.runtimeImageVersionVariable")
    artifact_signer = _get(document, "identities.artifactSigner")
    manifest_signer = _get(document, "identities.manifestSigner")
    if artifact_signer == manifest_signer:
        issues.append("CISB_SIGNER_IDENTITY_SEPARATION_REQUIRED")
    if _get(document, "signing.artifactCertificateIdentity") != artifact_signer:
        issues.append("CISB_ARTIFACT_SIGNER_IDENTITY_MISMATCH")
    if _get(document, "signing.manifestCertificateIdentity") != manifest_signer:
        issues.append("CISB_MANIFEST_SIGNER_IDENTITY_MISMATCH")
    if _get(document, "goldenApproval.policy") != "single-accountable-plus-independent-automated-web-qa":
        issues.append("CISB_GOLDEN_APPROVAL_POLICY_INVALID")

    workflow_value = _get(document, "ci.workflow")
    if isinstance(workflow_value, str) and _is_concrete(workflow_value):
        workflow = project_root / workflow_value
        if not workflow.is_file():
            issues.append(f"CISB_PLATFORM_BASELINE_INCOMPLETE: ci.workflow ({workflow_value})")
        else:
            issues.extend(validate_workflow(workflow))
    return sorted(set(issues))


def main(argv: list[str]) -> int:
    root = Path(argv[1]).resolve() if len(argv) >= 2 else Path(".").resolve()
    path = Path(argv[2]) if len(argv) >= 3 else root / "contracts/release/ci-supply-chain-baseline-1.0.0.json"
    try:
        issues = validate_cisb(load_json_document(path), root)
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print("cisb: PASS (CISB-1.0.0)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
