#!/usr/bin/env python3
"""Run the controlled responsibility fixture/consumer matrix and persist evidence."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
import sys
from pathlib import Path


SCENARIOS = [
    "responsibility-added",
    "responsibility-changed",
    "responsibility-invalidated",
    "primary-secondary-conflict",
    "recipient-missing",
    "college-exception-opened",
    "college-exception-resolved",
    "complete-snapshot",
    "partial-snapshot-rejected",
    "asia-shanghai-daily-boundary",
    "fifteen-minute-inclusive-boundary",
    "ninety-nine-point-nine-inclusive-boundary",
]

TESTS = ",".join(
    [
        "ResponsibilityRecipientValidityTest",
        "ResponsibilitySyncServiceTest",
        "ResponsibilitySyncWorkerTest",
        "ResponsibilityScopeQueryServiceTest",
        "ResponsibilityFullSnapshotNormalizerTest",
        "ResponsibilityReconciliationServiceTest",
        "ResponsibilityReconciliationWorkerTest",
        "ResponsibilityReconciliationSchedulerTest",
        "ResponsibilitySloServiceTest",
        "ResponsibilitySloCompensationServiceTest",
        "ResponsibilityAuthorityRuntimeProfileTest",
        "IdentitySyncConfigurationTest",
    ]
)


def run(command: list[str], root: Path) -> None:
    completed = subprocess.run(
        command,
        cwd=root,
        env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    print(completed.stdout, end="")
    if completed.returncode != 0:
        raise RuntimeError(
            f"controlled responsibility sandbox failed: {command[0]}"
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--evidence",
        type=Path,
        default=Path(
            "_bmad-output/implementation-artifacts/evidence/"
            "1-6b-responsibility-authority-sandbox-trace.json"
        ),
    )
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    toolchain = root / "_bmad/scripts/with_pab_toolchain.sh"
    try:
        run(
            [
                str(toolchain),
                "python3",
                "-B",
                str(root / "scripts/check_responsibility_authority_contracts.py"),
                str(root),
            ],
            root,
        )
        run(
            [
                str(toolchain),
                str(root / "backend/mvnw"),
                "-q",
                "-f",
                str(root / "backend/pom.xml"),
                f"-Dtest={TESTS}",
                "test",
            ],
            root,
        )
    except RuntimeError as failure:
        print(str(failure), file=sys.stderr)
        return 1

    evidence = {
        "version": "RESPONSIBILITY-AUTHORITY-SANDBOX-EVIDENCE-1.0.0",
        "storyId": "1.6b",
        "sourceId": "SRC-P0-RESPONSIBILITY-001",
        "environment": "controlled-local-sandbox",
        "productionEligible": False,
        "executedAt": dt.datetime.now(dt.UTC).isoformat(),
        "contractChecker": "scripts/check_responsibility_authority_contracts.py",
        "consumerMatrix": TESTS.split(","),
        "consumerResult": "PASS",
        "databaseEvidence": (
            "ResponsibilityAuthorityPostgreSqlIT via "
            "scripts/run_audit_postgresql_tests.sh"
        ),
        "transport": "loopback-and-fixture-test-only",
        "credentialsPersisted": False,
        "rawStudentReferencesPersisted": False,
        "scenarios": SCENARIOS,
        "boundaries": {
            "schedule": "Asia/Shanghai 06:00 inclusive",
            "authorizationEffective": "<=15m",
            "reconciliationMatchRate": ">=0.999000",
            "partialOrUnsealedSnapshot": "fail-closed",
        },
    }
    destination = args.evidence
    if not destination.is_absolute():
        destination = root / destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(
        json.dumps(
            evidence,
            ensure_ascii=False,
            sort_keys=True,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    try:
        label = destination.relative_to(root)
    except ValueError:
        label = destination
    print(
        "responsibility-authority-sandbox: PASS "
        f"({len(SCENARIOS)} scenarios, evidence={label})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
