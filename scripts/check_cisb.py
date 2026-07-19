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
JOB = re.compile(r"(?ms)^  ([a-z][a-z0-9-]*):\n(.*?)(?=^  [a-z][a-z0-9-]*:\n|\Z)")
IDENTITY_JOB = re.compile(
    r"^repo:[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+:ref:refs/heads/[A-Za-z0-9._/-]+"
    r"#workflow:(?P<workflow>[A-Za-z0-9_.-]+\.ya?ml)#job:(?P<job>[a-z][a-z0-9-]*)$"
)
WEB_QA_GATE = re.compile(
    r"^\.github/workflows/(?P<workflow>[A-Za-z0-9_.-]+\.ya?ml)#job:(?P<job>[a-z][a-z0-9-]*)$"
)
WRITE_PERMISSION = re.compile(
    r"(?m)^      (?:contents|packages|actions|checks|pull-requests|issues|id-token|attestations|artifact-metadata):\s*write\s*$"
)


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


def _workflow_jobs(project_root: Path, workflow_name: str) -> dict[str, str]:
    path = project_root / ".github/workflows" / workflow_name
    try:
        content = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        return {}
    marker = content.find("\njobs:\n")
    if marker < 0:
        return {}
    return dict(JOB.findall(content[marker + 1 :]))


def _identity_job_issues(document: dict[str, Any], project_root: Path, field: str) -> list[str]:
    value = _get(document, field)
    match = IDENTITY_JOB.fullmatch(value) if isinstance(value, str) else None
    if match is None:
        return [f"CISB_IDENTITY_JOB_INVALID: {field}"]
    if match.group("job") not in _workflow_jobs(project_root, match.group("workflow")):
        return [f"CISB_IDENTITY_JOB_NOT_FOUND: {field}"]
    return []


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
        "identities.webQa",
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

    for field in ("identities.build", "identities.attestation", "identities.webQa", "identities.verifier"):
        issues.extend(_identity_job_issues(document, project_root, field))
    web_qa_gate = _get(document, "goldenApproval.webQaGate")
    gate_match = WEB_QA_GATE.fullmatch(web_qa_gate) if isinstance(web_qa_gate, str) else None
    if gate_match is None:
        issues.append("CISB_WEB_QA_GATE_INVALID")
    else:
        jobs = _workflow_jobs(project_root, gate_match.group("workflow"))
        body = jobs.get(gate_match.group("job"), "")
        expected_identity_suffix = (
            f"#workflow:{gate_match.group('workflow')}#job:{gate_match.group('job')}"
        )
        if (
            not body
            or "scripts/run-formal-web-evidence.sh" not in body
            or WRITE_PERMISSION.search(body)
            or not str(_get(document, "identities.webQa") or "").endswith(expected_identity_suffix)
        ):
            issues.append("CISB_WEB_QA_GATE_NOT_INDEPENDENT")

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
