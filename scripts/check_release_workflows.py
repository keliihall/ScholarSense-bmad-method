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
)
EXPECTED_NEEDS = {
    "sbom-scan": "build-cas",
    "artifact-attestation": "sbom-scan",
    "formal-web": "artifact-attestation",
    "release-manifest": "formal-web",
    "manifest-signature": "release-manifest",
    "evidence-index": "manifest-signature",
    "independent-verifier": "evidence-index",
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
            "release/generate_manifests.py release",
            "release/generate_manifests.py index",
            "scripts/verify-release.sh",
        )
        for token in required_tokens:
            if token not in release:
                issues.append(f"RELEASE_LIFECYCLE_STEP_MISSING: {token}")
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
