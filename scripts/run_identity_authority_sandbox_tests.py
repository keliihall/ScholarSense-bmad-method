#!/usr/bin/env python3
"""Run the real local provider/Java consumer matrix and persist redacted evidence."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path


EXPECTED_SCENARIOS = {
    "account-added",
    "account-changed",
    "account-deactivated",
    "role-added-and-multi-role",
    "role-removed",
    "organization-added",
    "organization-renamed",
    "organization-reparented",
    "organization-deactivated",
    "employment-window-ended",
    "unknown-role",
    "subject-binding-conflict",
    "authentication-failure",
    "invalid-signature",
}


def wait_for(path: Path, process: subprocess.Popen[bytes]) -> str:
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if path.exists():
            value = path.read_text(encoding="utf-8").strip()
            if value:
                return value
        if process.poll() is not None:
            raise RuntimeError("identity authority sandbox exited before readiness")
        time.sleep(0.05)
    raise RuntimeError("identity authority sandbox readiness timed out")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--evidence",
        type=Path,
        default=Path(
            "_bmad-output/implementation-artifacts/evidence/"
            "1-6a-identity-authority-sandbox-trace.json"
        ),
    )
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    token = "controlled-sandbox-workload-v1"
    signature_key = "controlled-sandbox-signature-key-v1"

    with tempfile.TemporaryDirectory(prefix="scholarsense-identity-sandbox-") as value:
        temporary = Path(value)
        ready = temporary / "ready"
        trace = temporary / "provider-trace.json"
        provider = subprocess.Popen(
            [
                sys.executable,
                "-B",
                str(root / "scripts/identity_authority_sandbox.py"),
                "--port",
                "0",
                "--ready-file",
                str(ready),
                "--trace-file",
                str(trace),
                "--token",
                token,
                "--signature-key",
                signature_key,
            ],
            cwd=root,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        try:
            endpoint = wait_for(ready, provider)
            command = [
                str(root / "_bmad/scripts/with_pab_toolchain.sh"),
                str(root / "backend/mvnw"),
                "-f",
                str(root / "backend/pom.xml"),
                "-Dtest=IdentityAuthoritySandboxIT",
                f"-Didentity.sandbox.endpoint={endpoint}",
                f"-Didentity.sandbox.token={token}",
                f"-Didentity.sandbox.signatureKey={signature_key}",
                "test",
            ]
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
                return completed.returncode
        finally:
            provider.terminate()
            try:
                output = provider.communicate(timeout=5)[0]
            except subprocess.TimeoutExpired:
                provider.kill()
                output = provider.communicate(timeout=5)[0]
            if output:
                print(output.decode("utf-8", errors="replace"), end="")

        provider_trace = json.loads(trace.read_text(encoding="utf-8"))
        events = provider_trace.get("events", [])
        scenarios = {event.get("scenario") for event in events}
        if scenarios != EXPECTED_SCENARIOS or len(events) != 14:
            print(
                "IDENTITY_AUTHORITY_SANDBOX_TRACE_INCOMPLETE: "
                f"events={len(events)} scenarios={sorted(scenarios)}",
                file=sys.stderr,
            )
            return 1
        e2e_ready = temporary / "e2e-ready"
        e2e_trace_path = temporary / "e2e-provider-trace.json"
        e2e_provider = subprocess.Popen(
            [
                sys.executable,
                "-B",
                str(root / "scripts/identity_authority_sandbox.py"),
                "--port",
                "0",
                "--ready-file",
                str(e2e_ready),
                "--trace-file",
                str(e2e_trace_path),
                "--token",
                token,
                "--signature-key",
                signature_key,
            ],
            cwd=root,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        try:
            e2e_endpoint = wait_for(e2e_ready, e2e_provider)
            postgres = subprocess.run(
                [str(root / "scripts/run_audit_postgresql_tests.sh")],
                cwd=root,
                env={
                    **os.environ,
                    "PYTHONDONTWRITEBYTECODE": "1",
                    "IDENTITY_SANDBOX_ENDPOINT": e2e_endpoint,
                    "IDENTITY_SANDBOX_TOKEN": token,
                    "IDENTITY_SANDBOX_SIGNATURE_KEY": signature_key,
                },
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )
            print(postgres.stdout, end="")
            if postgres.returncode != 0:
                return postgres.returncode
        finally:
            e2e_provider.terminate()
            try:
                e2e_output = e2e_provider.communicate(timeout=5)[0]
            except subprocess.TimeoutExpired:
                e2e_provider.kill()
                e2e_output = e2e_provider.communicate(timeout=5)[0]
            if e2e_output:
                print(e2e_output.decode("utf-8", errors="replace"), end="")
        e2e_requests = json.loads(
            e2e_trace_path.read_text(encoding="utf-8")
        ).get("events", [])
        if len(e2e_requests) != 1 or e2e_requests[0].get("traceId") != (
            "fedcba9876543210fedcba9876543210"
        ):
            print(
                "IDENTITY_AUTHORITY_SANDBOX_E2E_TRACE_INCOMPLETE",
                file=sys.stderr,
            )
            return 1
        evidence = {
            "version": "IDENTITY-AUTHORITY-SANDBOX-EVIDENCE-1.0.0",
            "storyId": "1.6a",
            "sourceId": "SRC-P0-RESPONSIBILITY-001",
            "environment": "controlled-local-sandbox",
            "productionEligible": False,
            "executedAt": dt.datetime.now(dt.UTC).isoformat(),
            "provider": "scripts/identity_authority_sandbox.py",
            "consumer": (
                "backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/"
                "adapters/outbound/IdentityAuthoritySandboxIT.java"
            ),
            "transport": "loopback-http-test-only",
            "consumerResult": "PASS",
            "providerConsumerLatencyAssertion": (
                "every accepted sandbox fixture observed-minus-sourceVisibleAt<=15m"
            ),
            "authorizationReadBackEvidence": (
                "IdentityAuthoritySandboxIT#"
                "sameTraceRunsThroughWorkerPostgreSqlAndCurrentAuthorizationReadBack"
            ),
            "endToEndTraceId": "fedcba9876543210fedcba9876543210",
            "endToEndPath": [
                "controlled-provider",
                "identity-sync-worker",
                "PostgreSQL-18.4",
                "current-authorization-read-back",
            ],
            "endToEndRequest": e2e_requests[0],
            "credentialsPersisted": False,
            "scenarios": sorted(EXPECTED_SCENARIOS),
            "requests": events,
        }
        destination = args.evidence
        if not destination.is_absolute():
            destination = root / destination
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(
            json.dumps(evidence, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
            encoding="utf-8",
        )
        try:
            evidence_label = destination.relative_to(root)
        except ValueError:
            evidence_label = destination
        print(
            "identity-authority-sandbox: PASS "
            f"(14 requests, evidence={evidence_label})"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
