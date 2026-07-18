#!/usr/bin/env python3
"""Fail-closed static checks for release-facing GitHub Actions workflows."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ACTION_USE = re.compile(r"^\s*-?\s*uses:\s*([^\s#]+)", re.MULTILINE)
FULL_COMMIT = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[^@\s]+)?@[0-9a-f]{40}$")
IMAGE_LINE = re.compile(r"^\s*(?:container|image):\s*([^\s#]+)", re.MULTILINE)
SECRET_SINK = re.compile(
    r"(?im)^\s*(?:run:\s*)?.*(?:echo|printf|printenv|set\s+-x).*(?:secrets\.|github\.token|ACTIONS_ID_TOKEN_REQUEST_TOKEN)"
)
WRITE_PERMISSION = re.compile(r"(?m)^\s*(?:contents|packages|actions|checks|pull-requests|issues):\s*write\s*$")


def validate_workflow(path: Path) -> list[str]:
    try:
        content = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        return [f"WORKFLOW_UNREADABLE: {path}: {error}"]

    issues: list[str] = []
    for use in ACTION_USE.findall(content):
        if use.startswith("./"):
            continue
        if not FULL_COMMIT.fullmatch(use):
            issues.append(f"WORKFLOW_ACTION_NOT_PINNED: {use}")
    for image in IMAGE_LINE.findall(content):
        if image.startswith("${{"):
            issues.append(f"WORKFLOW_IMAGE_NOT_PINNED: {image}")
        elif "@sha256:" not in image:
            issues.append(f"WORKFLOW_IMAGE_NOT_PINNED: {image}")
    if re.search(r"(?m)^\s*permissions:\s*write-all\s*$", content):
        issues.append("WORKFLOW_WRITE_ALL_FORBIDDEN")
    if "pull_request_target" in content and WRITE_PERMISSION.search(content):
        issues.append("WORKFLOW_DANGEROUS_PR_WRITE_PERMISSION")
    if SECRET_SINK.search(content):
        issues.append("WORKFLOW_SECRET_SINK_FORBIDDEN")
    if re.search(r"(?im)(?:curl|wget).*(?:--upload-file|-T\s|--data-binary).*(?!github\.com|githubusercontent\.com|ghcr\.io)", content):
        issues.append("WORKFLOW_UNAPPROVED_EXTERNAL_UPLOAD")
    return sorted(set(issues))


def main(argv: list[str]) -> int:
    paths = [Path(value) for value in argv[1:]]
    if not paths:
        paths = sorted(Path(".github/workflows").glob("*.y*ml"))
    issues = [issue for path in paths for issue in validate_workflow(path)]
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print(f"workflow-security: PASS ({len(paths)} workflows)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
