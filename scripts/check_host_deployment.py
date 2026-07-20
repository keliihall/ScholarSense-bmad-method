#!/usr/bin/env python3
"""Validate the static host owner without pretending unresolved runtime bindings are evidence."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def violations(root: Path, review: bool = False) -> list[str]:
    profile = json.loads((root / "deploy/base/host-integration-1.0.0.json").read_text())
    template = (root / "deploy/base/nginx-scholarsense.conf.template").read_text()
    issues: list[str] = []
    if 'add_header Content-Security-Policy "frame-ancestors ' not in template:
        issues.append("HOST_FRAME_ANCESTORS_HEADER_MISSING")
    if "frame-ancestors *" in template or "postMessage('*')" in template:
        issues.append("HOST_WILDCARD_SECURITY_POLICY")
    executable = "\n".join(
        line for line in template.splitlines() if not line.lstrip().startswith("#")
    ).casefold()
    if "x-frame-options" in executable:
        issues.append("HOST_X_FRAME_OPTIONS_CONFLICT")
    if profile.get("portalIframeSandbox") != ["allow-scripts", "allow-forms", "allow-same-origin"]:
        issues.append("HOST_SANDBOX_PERMISSION_DRIFT")
    if profile.get("handshakeTimeoutSeconds") != 5 or profile.get("messageReplayWindowSeconds") != 300:
        issues.append("HOST_TIMING_CONTRACT_DRIFT")
    if review:
        origins = profile.get("frameAncestors")
        waiver = _waiver(root, profile)
        controlled = profile.get("status") == "approved-controlled-completion"
        if controlled and not _waives(waiver, "production-static-config-deployment"):
            issues.append("HOST_EXTERNAL_EVIDENCE_WAIVER_INVALID")
        if profile.get("status") not in {"approved", "approved-controlled-completion"} \
                or not isinstance(origins, list) or not origins:
            issues.append("HOST_RUNTIME_BINDING_INCOMPLETE")
        elif any(not isinstance(origin, str) or not origin.startswith("https://") for origin in origins):
            issues.append("HOST_FRAME_ANCESTOR_INVALID")
        rendered_path = profile.get("renderedStaticResponseOwner")
        try:
            rendered = (root / rendered_path).read_text() if isinstance(rendered_path, str) else ""
        except OSError:
            rendered = ""
        expected = "frame-ancestors " + " ".join(origins) if isinstance(origins, list) else ""
        executable_rendered = "\n".join(
            line for line in rendered.splitlines() if not line.lstrip().startswith("#")
        ).casefold()
        if not rendered or "@@" in rendered or expected not in rendered:
            issues.append("HOST_STATIC_CONFIG_UNRENDERED")
        if "x-frame-options" in executable_rendered:
            issues.append("HOST_X_FRAME_OPTIONS_CONFLICT")
    return sorted(set(issues))


def _waiver(root: Path, profile: dict) -> dict:
    path = profile.get("externalEvidenceWaiver")
    if not isinstance(path, str):
        return {}
    try:
        value = json.loads((root / path).read_text())
    except (OSError, ValueError):
        return {}
    return value if isinstance(value, dict) else {}


def _waives(waiver: dict, requirement: str) -> bool:
    return waiver.get("status") == "approved" \
        and waiver.get("authority") == "explicit-user-instruction" \
        and requirement in waiver.get("waivedRequirements", [])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", default=Path(__file__).resolve().parents[1], type=Path)
    parser.add_argument("--review", action="store_true")
    args = parser.parse_args()
    issues = violations(args.root.resolve(), args.review)
    if issues:
        print("\n".join(issues))
        return 1
    print("host-deployment: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
