#!/usr/bin/env python3
"""Validate the exact macOS/arm64/font/ephemeral formal Web runner baseline."""

from __future__ import annotations

import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "release"))
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from formal_web import formal_runner_issues  # noqa: E402
from release_json import load_json, schema_issues  # noqa: E402


def main(argv: list[str]) -> int:
    if len(argv) > 2 or (len(argv) == 2 and argv[1] != "--require-actions"):
        print("usage: check_formal_web_runner.py [--require-actions]", file=sys.stderr)
        return 2
    try:
        document = load_json(PROJECT_ROOT / "contracts/release/formal-web-runner-1.0.0.json")
        schema = load_json(PROJECT_ROOT / "contracts/release/formal-web-runner.schema.json")
        issues = schema_issues(document, schema)
        issues.extend(formal_runner_issues(document, require_actions=len(argv) == 2))
    except (OSError, TypeError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    if issues:
        print("\n".join(sorted(set(issues))), file=sys.stderr)
        return 1
    print(f"formal-web-runner: PASS ({document['runnerImageSha256']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
