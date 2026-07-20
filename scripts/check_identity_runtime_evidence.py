#!/usr/bin/env python3
"""Fail closed when Story 1.2 is promoted without real host/SSO evidence."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any


PROFILE = Path("contracts/identity/identity-runtime-profile-1.0.0.json")
WAIVER = Path("deploy/base/story-1.2-external-evidence-waiver-1.0.0.json")
REQUIRED_EVIDENCE = {
    "hostSsoIntegration",
    "portalConfiguration",
    "browserMatrix",
    "redactedTrace",
    "clockSynchronization",
}
WAIVED_REQUIREMENTS = {
    "production-static-config-deployment",
    "real-portal-idp-sandbox",
    "production-kms-shared-database-bindings",
    "frozen-chrome-edge-matrix",
    "independent-runtime-evidence-signatures",
}
CONTROLLED_EVIDENCE_GAPS = {
    "signedHostSsoIntegrationEvidence",
    "realPortalAndIdpSandboxTrace",
    "frozenChromeAndEdgeMatrix",
    "signedClockSynchronizationEvidence",
}
WAIVER_PROHIBITIONS = {
    "do-not-claim-real-school-environment-execution",
    "do-not-fabricate-credentials-signatures-or-browser-results",
    "do-not-use-this-waiver-as-production-promotion-evidence",
}
COOKIE_POLICY = "__Host-; Secure; HttpOnly; SameSite=Lax; Path=/; no Domain"
REFERENCE = re.compile(r"^(?:config|secret|evidence)://(?:dev|test|stage|prod|signed)/[A-Za-z0-9._:/-]+$")
EVIDENCE_REFERENCE = re.compile(r"^evidence://signed/[A-Za-z0-9._/-]+$")
SENSITIVE_KEY = re.compile(r"(?:client.?secret|access.?token|refresh.?token|id.?token|password|private.?key)", re.I)


def validate(project_root: Path, require_complete: bool) -> list[str]:
    path = project_root.resolve() / PROFILE
    try:
        profile = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return ["IDENTITY_RUNTIME_PROFILE_INVALID"]
    if not isinstance(profile, dict):
        return ["IDENTITY_RUNTIME_PROFILE_INVALID"]

    issues: list[str] = []
    expected = {
        "version": "IDENTITY-RUNTIME-PROFILE-1.0.0",
        "sourceBaselineCommit": "a0c8a9cba10d963c41623d27a8480dbbbddea393",
        "hostIntegrationProfileVersion": "HIP-1.0.0",
        "identitySessionPolicyVersion": "ISP-1.0.0",
        "testEnvironmentVersion": "TEST-ENV-1.0.0",
    }
    if any(profile.get(key) != value for key, value in expected.items()):
        issues.append("IDENTITY_RUNTIME_PROFILE_VERSION_INVALID")
    if profile.get("cookiePolicy") != COOKIE_POLICY:
        issues.append("IDENTITY_RUNTIME_COOKIE_POLICY_INVALID")
    if profile.get("crossSiteDecision") not in {
        "pending-runtime-hostnames",
        "same-site",
        "cross-site-top-level-authentication",
    }:
        issues.append("IDENTITY_RUNTIME_CROSS_SITE_DECISION_INVALID")

    required = profile.get("requiredRuntimeBindings")
    bindings = profile.get("runtimeBindings")
    missing = profile.get("missingRequirements")
    evidence = profile.get("evidence")
    if not _unique_strings(required) or not isinstance(bindings, dict) or not _unique_strings(missing):
        issues.append("IDENTITY_RUNTIME_PROFILE_SHAPE_INVALID")
        return sorted(set(issues))
    if not isinstance(evidence, dict):
        issues.append("IDENTITY_RUNTIME_PROFILE_SHAPE_INVALID")
        evidence = {}

    if _contains_secret_material(profile):
        issues.append("IDENTITY_RUNTIME_SECRET_MATERIAL_FORBIDDEN")
    if any(key not in required or not isinstance(value, str) or not REFERENCE.fullmatch(value)
           for key, value in bindings.items()):
        issues.append("IDENTITY_RUNTIME_BINDING_REFERENCE_INVALID")
    if any(key not in REQUIRED_EVIDENCE or not isinstance(value, str) or not EVIDENCE_REFERENCE.fullmatch(value)
           for key, value in evidence.items()):
        issues.append("IDENTITY_RUNTIME_EVIDENCE_REFERENCE_INVALID")

    status = profile.get("status")
    if status not in {"pending", "controlled-complete", "complete"}:
        issues.append("IDENTITY_RUNTIME_STATUS_INVALID")
    complete_bindings = set(bindings) == set(required)
    complete_evidence = set(evidence) == REQUIRED_EVIDENCE
    if status == "complete" and (missing or not complete_bindings or not complete_evidence):
        issues.append("IDENTITY_RUNTIME_COMPLETE_CLAIM_INVALID")
    controlled = status == "controlled-complete"
    waiver_valid = _valid_waiver(project_root, profile)
    if controlled:
        expected_missing = set(required) | CONTROLLED_EVIDENCE_GAPS
        if bindings or evidence or set(missing) != expected_missing:
            issues.append("IDENTITY_RUNTIME_CONTROLLED_CLAIM_INVALID")
        if not waiver_valid:
            issues.append("IDENTITY_RUNTIME_EXTERNAL_EVIDENCE_WAIVER_INVALID")
    if require_complete:
        if status not in {"complete", "controlled-complete"}:
            issues.append("IDENTITY_RUNTIME_EVIDENCE_INCOMPLETE")
        if not complete_bindings and not (controlled and waiver_valid):
            issues.append("IDENTITY_RUNTIME_BINDINGS_INCOMPLETE")
        if not complete_evidence and not (controlled and waiver_valid):
            issues.append("IDENTITY_RUNTIME_SIGNED_EVIDENCE_INCOMPLETE")
        if profile.get("crossSiteDecision") == "pending-runtime-hostnames":
            issues.append("IDENTITY_RUNTIME_CROSS_SITE_DECISION_PENDING")
    return sorted(set(issues))


def _valid_waiver(project_root: Path, profile: dict[str, Any]) -> bool:
    if profile.get("externalEvidenceWaiver") != str(WAIVER):
        return False
    try:
        waiver = json.loads((project_root.resolve() / WAIVER).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    return isinstance(waiver, dict) \
        and waiver.get("version") == "STORY-1.2-EXTERNAL-EVIDENCE-WAIVER-1.0.0" \
        and waiver.get("status") == "approved" \
        and waiver.get("authority") == "explicit-user-instruction" \
        and waiver.get("storyKey") == "1-2-接入统一门户-sso-与可恢复应用壳" \
        and waiver.get("sourceBaselineCommit") == "a0c8a9cba10d963c41623d27a8480dbbbddea393" \
        and waiver.get("disposition") == "waive-only-requirements-that-need-external-support" \
        and set(waiver.get("waivedRequirements", [])) == WAIVED_REQUIREMENTS \
        and set(waiver.get("prohibitions", [])) == WAIVER_PROHIBITIONS \
        and waiver.get("effectiveUntilExternalSupportAvailable") is True


def _unique_strings(value: Any) -> bool:
    return isinstance(value, list) and all(isinstance(item, str) and item for item in value) \
        and len(value) == len(set(value))


def _contains_secret_material(value: Any, key: str = "") -> bool:
    if SENSITIVE_KEY.search(key):
        return True
    if isinstance(value, dict):
        return any(_contains_secret_material(child, str(child_key)) for child_key, child in value.items())
    if isinstance(value, list):
        return any(_contains_secret_material(child, key) for child in value)
    return False


def main(argv: list[str]) -> int:
    require_complete = "--review" in argv[1:]
    roots = [argument for argument in argv[1:] if not argument.startswith("--")]
    root = Path(roots[0]) if roots else Path(".")
    issues = validate(root, require_complete=require_complete)
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    mode = "review" if require_complete else "local-development"
    print(f"identity-runtime-evidence: PASS ({mode})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
