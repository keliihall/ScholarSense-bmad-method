#!/usr/bin/env python3
"""Check the read-only CI and ordered protected release workflow contract."""

from __future__ import annotations

import re
import sys
from pathlib import Path


EXPECTED_RELEASE_ORDER = (
    "build-cas",
    "sbom-scan",
    "artifact-attestation",
    "formal-web",
    "release-manifest",
    "manifest-signature",
    "evidence-index",
    "independent-verifier",
    "promotion",
)
EXPECTED_NEEDS = {
    "sbom-scan": "build-cas",
    "artifact-attestation": "sbom-scan",
    "formal-web": "artifact-attestation",
    "release-manifest": "formal-web",
    "manifest-signature": "release-manifest",
    "evidence-index": "manifest-signature",
    "independent-verifier": "evidence-index",
    "promotion": "independent-verifier",
}
WRITE_PERMISSION = re.compile(r"(?m)^\s+(?:contents|packages|actions|checks|pull-requests|issues|id-token|attestations|artifact-metadata):\s*write\s*$")
JOB = re.compile(r"(?ms)^  ([a-z][a-z0-9-]*):\n(.*?)(?=^  [a-z][a-z0-9-]*:\n|\Z)")


def _read(path: Path, code: str) -> tuple[str, list[str]]:
    try:
        return path.read_text(encoding="utf-8"), []
    except (OSError, UnicodeError):
        return "", [code]


def _jobs(content: str) -> tuple[list[str], dict[str, str]]:
    marker = content.find("\njobs:\n")
    if marker < 0:
        return [], {}
    matches = JOB.findall(content[marker + 1 :])
    return [name for name, _body in matches], dict(matches)


def validate_release_workflows(project_root: Path) -> list[str]:
    root = project_root.resolve()
    ci, issues = _read(root / ".github/workflows/ci.yml", "CI_WORKFLOW_MISSING")
    release, release_read_issues = _read(root / ".github/workflows/release.yml", "RELEASE_WORKFLOW_MISSING")
    issues.extend(release_read_issues)
    rollback, rollback_read_issues = _read(root / ".github/workflows/rollback.yml", "ROLLBACK_WORKFLOW_MISSING")
    issues.extend(rollback_read_issues)
    golden, golden_read_issues = _read(
        root / ".github/workflows/golden-approval.yml",
        "GOLDEN_APPROVAL_WORKFLOW_MISSING",
    )
    issues.extend(golden_read_issues)
    if ci:
        if "pull_request:" not in ci:
            issues.append("CI_PULL_REQUEST_TRIGGER_MISSING")
        if "pull_request_target:" in ci:
            issues.append("CI_PULL_REQUEST_TARGET_FORBIDDEN")
        if WRITE_PERMISSION.search(ci):
            issues.append("CI_WRITE_PERMISSION_FORBIDDEN")
        if "id-token:" in ci or "${{ secrets." in ci or "${{ github.token }}" in ci:
            issues.append("CI_SECRET_CONTEXT_FORBIDDEN")
        if "scripts/verify.sh" not in ci or "scripts/check_release_contracts.py" not in ci:
            issues.append("CI_FULL_VERIFY_GATE_MISSING")
    if release:
        if "workflow_dispatch:" not in release or "pull_request:" in release or "pull_request_target:" in release:
            issues.append("RELEASE_TRIGGER_INVALID")
        if "github.ref == 'refs/heads/main'" not in release:
            issues.append("RELEASE_PROTECTED_REF_GUARD_MISSING")
        order, bodies = _jobs(release)
        positions = {name: index for index, name in enumerate(order)}
        for expected in EXPECTED_RELEASE_ORDER:
            if expected not in positions:
                issues.append(f"RELEASE_JOB_MISSING: {expected}")
        for earlier, later in zip(EXPECTED_RELEASE_ORDER, EXPECTED_RELEASE_ORDER[1:]):
            if earlier in positions and later in positions and positions[earlier] >= positions[later]:
                issues.append(f"RELEASE_JOB_ORDER_INVALID: {earlier}->{later}")
        for job, need in EXPECTED_NEEDS.items():
            body = bodies.get(job, "")
            if not re.search(rf"(?m)^    needs:[^\n]*\b{re.escape(need)}\b[^\n]*$", body):
                issues.append(f"RELEASE_JOB_ORDER_INVALID: {job} needs {need}")
        for job, body in bodies.items():
            has_oidc = bool(re.search(r"(?m)^      id-token:\s*write\s*$", body))
            if has_oidc and job not in {"artifact-attestation", "manifest-signature"}:
                issues.append(f"RELEASE_OIDC_PERMISSION_OVERBROAD: {job}")
            for permission in ("attestations", "artifact-metadata"):
                if re.search(rf"(?m)^      {permission}:\s*write\s*$", body) and job != "artifact-attestation":
                    issues.append(f"RELEASE_ATTESTATION_PERMISSION_OVERBROAD: {job}:{permission}")
        attest = bodies.get("artifact-attestation", "")
        for permission in ("id-token", "attestations", "artifact-metadata"):
            if not re.search(rf"(?m)^      {permission}:\s*write\s*$", attest):
                issues.append(f"RELEASE_ATTESTATION_PERMISSION_MISSING: {permission}")
        for forbidden in ("actions/upload-artifact", "actions/download-artifact"):
            if forbidden in release:
                issues.append(f"RELEASE_MUTABLE_JOB_TRANSFER_FORBIDDEN: {forbidden}")
        required_tokens = (
            "scripts/build-release.sh",
            "scripts/generate-sbom.sh",
            "actions/attest@",
            "sign-blob --yes --bundle",
            "scripts/run-formal-web-evidence.sh",
            "scripts/assemble-release-manifest-input.py",
            "release/generate_manifests.py release",
            "scripts/assemble-evidence-index-input.py",
            "release/generate_manifests.py index",
            "scripts/verify-release.sh",
            "scripts/promote-release.sh",
        )
        for token in required_tokens:
            if token not in release:
                issues.append(f"RELEASE_LIFECYCLE_STEP_MISSING: {token}")
        build = bodies.get("build-cas", "")
        if "fetch-depth: 0" not in build:
            issues.append("RELEASE_BUILD_FULL_SOURCE_HISTORY_MISSING")
        sbom = bodies.get("sbom-scan", "")
        if "mkdir -p release-out/artifact" not in sbom:
            issues.append("RELEASE_SBOM_READBACK_PARENT_CREATION_MISSING")
        if "npm ci --prefix frontend --ignore-scripts" not in sbom:
            issues.append("RELEASE_SBOM_NPM_CACHE_PREWARM_MISSING")
        promotion = bodies.get("promotion", "")
        if "environment: ${{ inputs.target_environment }}" not in promotion:
            issues.append("RELEASE_PROMOTION_PROTECTED_ENVIRONMENT_MISSING")
        if not re.search(r"(?m)^      contents:\s*write\s*$", promotion):
            issues.append("RELEASE_PROMOTION_LEDGER_PERMISSION_MISSING")
        if not re.search(r"(?m)^      packages:\s*write\s*$", promotion):
            issues.append("RELEASE_PROMOTION_STORE_PERMISSION_MISSING")
        verifier = bodies.get("independent-verifier", "")
        if WRITE_PERMISSION.search(verifier):
            issues.append("RELEASE_INDEPENDENT_VERIFIER_WRITE_PERMISSION_FORBIDDEN")
        formal = bodies.get("formal-web", "")
        for required in (
            "runs-on: [self-hosted, macOS, ARM64, scholarsense-test-env-1]",
            "actions/setup-node@",
            "npm ci --prefix frontend --ignore-scripts",
            "scripts/run-formal-web-evidence.sh",
            "-name '*.png'",
        ):
            if required not in formal:
                issues.append(f"RELEASE_FORMAL_WEB_GATE_INCOMPLETE: {required}")
        for forbidden in ("capture-formal-web-goldens", "update-snapshots", "--update-snapshots"):
            if forbidden in formal:
                issues.append(f"RELEASE_FORMAL_WEB_ORACLE_MUTATION_FORBIDDEN: {forbidden}")
    if golden:
        if "workflow_dispatch:" not in golden or "pull_request:" in golden or "pull_request_target:" in golden:
            issues.append("GOLDEN_APPROVAL_TRIGGER_INVALID")
        if "github.ref == 'refs/heads/main'" not in golden:
            issues.append("GOLDEN_APPROVAL_PROTECTED_REF_GUARD_MISSING")
        order, bodies = _jobs(golden)
        if order != ["build-candidate", "capture-goldens"]:
            issues.append("GOLDEN_APPROVAL_JOB_ORDER_INVALID")
        capture = bodies.get("capture-goldens", "")
        if not re.search(r"(?m)^    needs:\s*build-candidate\s*$", capture):
            issues.append("GOLDEN_APPROVAL_BUILD_DEPENDENCY_MISSING")
        for required in (
            "runs-on: [self-hosted, macOS, ARM64, scholarsense-test-env-1]",
            "environment: stage",
            "capture-formal-web-goldens.mjs",
            "safe_extract_frontend",
            "--registry-config",
        ):
            if required not in capture:
                issues.append(f"GOLDEN_APPROVAL_GATE_INCOMPLETE: {required}")
        if not re.search(r"(?m)^      packages:\s*write\s*$", capture):
            issues.append("GOLDEN_APPROVAL_STORE_PERMISSION_MISSING")
        for forbidden in ("run-formal-web-evidence.mjs", "update-snapshots", "--update-snapshots"):
            if forbidden in capture:
                issues.append(f"GOLDEN_APPROVAL_MUTATION_PATH_INVALID: {forbidden}")
    if rollback:
        if "workflow_dispatch:" not in rollback or "pull_request:" in rollback or "pull_request_target:" in rollback:
            issues.append("ROLLBACK_TRIGGER_INVALID")
        if "github.ref == 'refs/heads/main'" not in rollback:
            issues.append("ROLLBACK_PROTECTED_REF_GUARD_MISSING")
        if "environment: production" not in rollback:
            issues.append("ROLLBACK_PROTECTED_ENVIRONMENT_MISSING")
        if "scripts/rollback-release.sh" not in rollback:
            issues.append("ROLLBACK_CURRENT_GATE_ENTRYPOINT_MISSING")
        if "scripts/build-release.sh" in rollback or "build-cas:" in rollback:
            issues.append("ROLLBACK_REBUILD_FORBIDDEN")
        if re.search(r"(?m)^\s+id-token:\s*write\s*$", rollback):
            issues.append("ROLLBACK_NEW_SIGNING_IDENTITY_FORBIDDEN")
    return sorted(set(issues))


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) == 2 else Path(".")
    issues = validate_release_workflows(root)
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print("release-workflows: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
