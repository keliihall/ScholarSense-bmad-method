#!/usr/bin/env python3
"""Check the read-only CI and ordered protected release workflow contract."""

from __future__ import annotations

import re
import sys
from pathlib import Path

from release_json import load_json


EXPECTED_RELEASE_ORDER = (
    "build-test",
    "build-cas",
    "sbom-scan",
    "artifact-signing",
    "formal-web-test",
    "formal-web",
    "release-manifest",
    "manifest-signing",
    "evidence-index",
    "independent-verifier",
    "promotion",
)
EXPECTED_NEEDS = {
    "build-cas": "build-test",
    "sbom-scan": "build-cas",
    "artifact-signing": "sbom-scan",
    "formal-web-test": "artifact-signing",
    "formal-web": "formal-web-test",
    "release-manifest": "formal-web",
    "evidence-index": "manifest-signing",
    "manifest-signing": "release-manifest",
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
    artifact_signing, artifact_signing_issues = _read(
        root / ".github/workflows/artifact-signing.yml",
        "ARTIFACT_SIGNING_WORKFLOW_MISSING",
    )
    issues.extend(artifact_signing_issues)
    manifest_signing, manifest_signing_issues = _read(
        root / ".github/workflows/manifest-signing.yml",
        "MANIFEST_SIGNING_WORKFLOW_MISSING",
    )
    issues.extend(manifest_signing_issues)
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
            if has_oidc and job not in {"artifact-signing", "manifest-signing"}:
                issues.append(f"RELEASE_OIDC_PERMISSION_OVERBROAD: {job}")
            for permission in ("attestations", "artifact-metadata"):
                if re.search(rf"(?m)^      {permission}:\s*write\s*$", body) and job != "artifact-signing":
                    issues.append(f"RELEASE_ATTESTATION_PERMISSION_OVERBROAD: {job}:{permission}")
        for required_transfer in (
            "actions/upload-artifact@65462800fd760344b1a7b4382951275a0abb4808",
            "actions/download-artifact@fa0a91b85d4f404e444e00e005971372dc801d16",
            "build-transfer.sha256",
            "formal-web-transfer.sha256",
        ):
            if required_transfer not in release:
                issues.append(f"RELEASE_VERIFIED_JOB_TRANSFER_MISSING: {required_transfer}")
        required_tokens = (
            "scripts/build-release.sh",
            "scripts/generate-sbom.sh",
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
        build = bodies.get("build-test", "")
        if "fetch-depth: 0" not in build:
            issues.append("RELEASE_BUILD_FULL_SOURCE_HISTORY_MISSING")
        if WRITE_PERMISSION.search(build):
            issues.append("RELEASE_BUILD_TEST_WRITE_PERMISSION_FORBIDDEN")
        if "scripts/build-release.sh" in bodies.get("build-cas", ""):
            issues.append("RELEASE_PUBLISHER_REBUILD_FORBIDDEN")
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
        if "GH_TOKEN: ${{ github.token }}" not in promotion:
            issues.append("RELEASE_PROMOTION_GITHUB_API_TOKEN_MISSING")
        verifier = bodies.get("independent-verifier", "")
        if WRITE_PERMISSION.search(verifier):
            issues.append("RELEASE_INDEPENDENT_VERIFIER_WRITE_PERMISSION_FORBIDDEN")
        formal = bodies.get("formal-web-test", "")
        for required in (
            "runs-on: [self-hosted, macOS, ARM64, scholarsense-test-env-1]",
            "actions/setup-node@",
            "npm ci --prefix frontend --ignore-scripts",
            "scripts/run-formal-web-evidence.sh",
        ):
            if required not in formal:
                issues.append(f"RELEASE_FORMAL_WEB_GATE_INCOMPLETE: {required}")
        if "-name '*.png'" not in bodies.get("formal-web", ""):
            issues.append("RELEASE_FORMAL_WEB_GATE_INCOMPLETE: published PNG evidence")
        for forbidden in ("capture-formal-web-goldens", "update-snapshots", "--update-snapshots"):
            if forbidden in formal:
                issues.append(f"RELEASE_FORMAL_WEB_ORACLE_MUTATION_FORBIDDEN: {forbidden}")
        if WRITE_PERMISSION.search(formal):
            issues.append("RELEASE_FORMAL_WEB_TEST_WRITE_PERMISSION_FORBIDDEN")
        if "scripts/run-formal-web-evidence.sh" in bodies.get("formal-web", ""):
            issues.append("RELEASE_FORMAL_WEB_PUBLISHER_RETEST_FORBIDDEN")
        for obsolete in ("artifact-attestation", "manifest-signature"):
            if obsolete in bodies:
                issues.append(f"RELEASE_LEGACY_SIGNER_JOB_FORBIDDEN: {obsolete}")
    if artifact_signing:
        for required in (
            "workflow_call:",
            "actions/attest@",
            "https://cyclonedx.org/bom",
            "https://spdx.dev/Document",
            "sign-blob --yes --bundle",
            "id-token: write",
            "attestations: write",
            "artifact-metadata: write",
        ):
            if required not in artifact_signing:
                issues.append(f"ARTIFACT_SIGNING_GATE_INCOMPLETE: {required}")
    if manifest_signing:
        for required in ("workflow_call:", "sign-blob --yes --bundle", "id-token: write"):
            if required not in manifest_signing:
                issues.append(f"MANIFEST_SIGNING_GATE_INCOMPLETE: {required}")
    if golden:
        if "workflow_dispatch:" not in golden or "pull_request:" in golden or "pull_request_target:" in golden:
            issues.append("GOLDEN_APPROVAL_TRIGGER_INVALID")
        if "github.ref == 'refs/heads/main'" not in golden:
            issues.append("GOLDEN_APPROVAL_PROTECTED_REF_GUARD_MISSING")
        order, bodies = _jobs(golden)
        if order != ["build-candidate", "capture-goldens"]:
            issues.append("GOLDEN_APPROVAL_JOB_ORDER_INVALID")
        build_candidate = bodies.get("build-candidate", "")
        try:
            cisb = load_json(root / "contracts/release/ci-supply-chain-baseline-1.0.0.json")
            runner = cisb["runner"]
            runner_label = runner["label"]
            runner_image_version = runner["imageVersion"]
            runtime_version_variable = runner["runtimeImageVersionVariable"]
            if runner.get("provider") != "GitHub-hosted" or not all(
                isinstance(value, str) and value
                for value in (runner_label, runner_image_version, runtime_version_variable)
            ):
                raise ValueError("runner")
        except (KeyError, OSError, TypeError, ValueError):
            issues.append("GOLDEN_APPROVAL_CISB_RUNNER_INVALID")
            runner_label = runner_image_version = runtime_version_variable = ""
        if f"    runs-on: {runner_label}" not in build_candidate.splitlines():
            issues.append("GOLDEN_APPROVAL_HOSTED_RUNNER_MISMATCH")
        expected_environment = f"  EXPECTED_RUNNER_IMAGE_VERSION: {runner_image_version}"
        expected_guard = (
            f'        run: test "${{{runtime_version_variable}:-missing}}" = '
            '"$EXPECTED_RUNNER_IMAGE_VERSION"'
        )
        if expected_environment not in golden.splitlines() or expected_guard not in build_candidate.splitlines():
            issues.append("GOLDEN_APPROVAL_HOSTED_RUNNER_IMAGE_GUARD_MISSING")
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
        if "GH_TOKEN: ${{ github.token }}" not in rollback:
            issues.append("ROLLBACK_GITHUB_API_TOKEN_MISSING")
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
