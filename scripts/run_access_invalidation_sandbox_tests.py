#!/usr/bin/env python3
"""Run and attest the controlled 1.6c PostgreSQL convergence matrix."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path
from typing import Any


TESTS = ",".join(
    [
        "AccessInvalidationDomainTest",
        "AccessInvalidationPortsTest",
        "AccessInvalidationPublisherServiceTest",
        "AccessInvalidationOutboxRelayProcessorTest",
        "AccessInvalidationWorkersTest",
        "ResponsibilityScopeQueryServiceTest",
        "ResponsibilitySyncServiceTest",
        "IdentitySyncServiceTest",
        "IdentitySyncConfigurationTest",
        "MicrometerIdentitySyncObservabilityAdapterTest",
    ]
)
COMMAND_TIMEOUT_SECONDS = 900


def run(
    command: list[str],
    root: Path,
    *,
    extra_env: dict[str, str] | None = None,
) -> dict[str, Any]:
    environment = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1"}
    if extra_env:
        environment.update(extra_env)
    try:
        completed = subprocess.run(
            command,
            cwd=root,
            env=environment,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
            timeout=COMMAND_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as failure:
        if failure.stdout:
            partial_output = failure.stdout
            if isinstance(partial_output, bytes):
                partial_output = partial_output.decode(
                    errors="replace"
                )
            print(partial_output, end="")
        raise RuntimeError(
            "controlled access invalidation sandbox timed out: "
            f"{command[0]} (timeout={COMMAND_TIMEOUT_SECONDS}s)"
        ) from failure
    print(completed.stdout, end="")
    result = {
        "command": str(command[0]),
        "exitCode": completed.returncode,
        "succeeded": completed.returncode == 0,
    }
    if completed.returncode != 0:
        raise RuntimeError(
            "controlled access invalidation sandbox failed: "
            f"{command[0]} (exit={completed.returncode})"
        )
    return result


def validate_raw_evidence(raw: dict[str, Any]) -> None:
    required = {
        "traceId",
        "eventId",
        "aggregateVersion",
        "sourceVisibleAt",
        "causeEventId",
        "causeTraceId",
        "lineageId",
        "impactJobStatus",
        "impactJobCursorLineageId",
        "readBackValidity",
        "readBackReasonCode",
        "readBackEvaluatedAt",
        "withinFifteenMinutes",
        "outboxStatus",
        "outboxTraceId",
        "deliveryOutcome",
        "watermarkBeforeRelay",
        "consumerId",
        "consumerWatermark",
        "consumerLastEventId",
        "consumerTraceId",
        "localFenceState",
        "localFenceTraceId",
        "reconciliationOutcome",
        "reconciliationTraceId",
        "propagationStatus",
        "requiredConsumerCount",
        "appliedRequiredConsumerCount",
        "propagationTraceId",
        "plannedConsumersAdvanced",
        "databaseVersion",
    }
    missing = sorted(required.difference(raw))
    if missing:
        raise ValueError(f"PostgreSQL evidence missing fields: {missing}")

    trace_id = raw["traceId"]
    if not isinstance(trace_id, str) or not re.fullmatch(
        r"[0-9a-f]{32}", trace_id
    ):
        raise ValueError("PostgreSQL evidence traceId is invalid")
    trace_fields = (
        "causeTraceId",
        "outboxTraceId",
        "consumerTraceId",
        "localFenceTraceId",
        "reconciliationTraceId",
        "propagationTraceId",
    )
    if any(raw[field] != trace_id for field in trace_fields):
        raise ValueError("PostgreSQL evidence is not one same-trace sequence")

    try:
        event_id = uuid.UUID(str(raw["eventId"]))
    except (ValueError, AttributeError) as failure:
        raise ValueError("PostgreSQL evidence eventId is invalid") from failure
    if event_id.version != 7 or event_id.variant != uuid.RFC_4122:
        raise ValueError("PostgreSQL evidence eventId is not UUIDv7")
    try:
        cause_event_id = uuid.UUID(str(raw["causeEventId"]))
    except (ValueError, AttributeError) as failure:
        raise ValueError(
            "PostgreSQL evidence causeEventId is invalid"
        ) from failure
    if cause_event_id.version != 7 or cause_event_id.variant != uuid.RFC_4122:
        raise ValueError("PostgreSQL evidence causeEventId is not UUIDv7")

    def instant(field: str) -> dt.datetime:
        value = raw[field]
        if not isinstance(value, str):
            raise ValueError(f"PostgreSQL evidence {field} is invalid")
        try:
            parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as failure:
            raise ValueError(
                f"PostgreSQL evidence {field} is invalid"
            ) from failure
        if parsed.tzinfo is None:
            raise ValueError(f"PostgreSQL evidence {field} lacks timezone")
        return parsed

    source_visible_at = instant("sourceVisibleAt")
    evaluated_at = instant("readBackEvaluatedAt")
    observed_within_window = (
        source_visible_at
        <= evaluated_at
        <= source_visible_at + dt.timedelta(minutes=15)
    )

    version = raw["aggregateVersion"]
    checks = {
        "impactJobStatus": raw["impactJobStatus"] == "completed",
        "impactJobCursor": raw["impactJobCursorLineageId"]
        == raw["lineageId"],
        "readBackValidity": raw["readBackValidity"] == "INVALID",
        "withinFifteenMinutes": raw["withinFifteenMinutes"] is True
        and observed_within_window,
        "outboxStatus": raw["outboxStatus"] == "published",
        "deliveryOutcome": raw["deliveryOutcome"] == "published",
        "watermarkBeforeRelay": raw["watermarkBeforeRelay"] == 0,
        "consumerId": raw["consumerId"] == "authorization-current-scope",
        "consumerWatermark": isinstance(version, int)
        and version >= 1
        and raw["consumerWatermark"] == version,
        "consumerLastEventId": raw["consumerLastEventId"]
        == raw["eventId"],
        "localFenceState": raw["localFenceState"] == "invalidated",
        "reconciliationOutcome": raw["reconciliationOutcome"] == "healthy",
        "propagationStatus": raw["propagationStatus"] == "complete",
        "requiredConsumers": raw["requiredConsumerCount"] >= 1
        and raw["requiredConsumerCount"]
        == raw["appliedRequiredConsumerCount"],
        "plannedConsumersAdvanced": raw["plannedConsumersAdvanced"] == 0,
        "databaseVersion": str(raw["databaseVersion"]).startswith(
            "PostgreSQL 18.4"
        ),
    }
    failed = sorted(name for name, valid in checks.items() if not valid)
    if failed:
        raise ValueError(
            "PostgreSQL evidence failed convergence checks: "
            + ", ".join(failed)
        )


def build_evidence(
    raw: dict[str, Any],
    contract_result: dict[str, Any],
    consumer_result: dict[str, Any],
    database_result: dict[str, Any],
) -> dict[str, Any]:
    validate_raw_evidence(raw)
    return {
        "version": "ACCESS-INVALIDATION-SANDBOX-EVIDENCE-1.0.0",
        "storyId": "1.6c",
        "environment": "controlled-local-sandbox",
        "productionEligible": False,
        "executedAt": dt.datetime.now(dt.UTC).isoformat(),
        "traceId": raw["traceId"],
        "contractChecker": contract_result,
        "consumerMatrix": {
            **consumer_result,
            "tests": TESTS.split(","),
        },
        "databaseEvidence": {
            **database_result,
            "databaseVersion": raw["databaseVersion"],
            "test": (
                "AccessInvalidationPostgreSqlIT#"
                "sameTracePostgreSqlEvidenceExecutesReadBackRelayConsumerAndReconciliation"
            ),
        },
        "transport": "local-transport-adapter-test-only",
        "runtimeEvidenceBoundary": {
            "authorization-current-scope": "current-runtime",
            "public-task": "none",
            "reporting-export": "none",
            "responsibility-transfer": "none",
            "mobile-surface-verification": "none",
        },
        "sameTraceSequence": [
            {
                "step": "identity-change-published",
                "eventId": raw["causeEventId"],
                "traceId": raw["causeTraceId"],
            },
            {
                "step": "impact-fan-out-completed",
                "eventId": raw["eventId"],
                "aggregateVersion": raw["aggregateVersion"],
                "lineageId": raw["lineageId"],
                "jobStatus": raw["impactJobStatus"],
                "traceId": raw["traceId"],
            },
            {
                "step": "outbox-relayed",
                "status": raw["outboxStatus"],
                "deliveryOutcome": raw["deliveryOutcome"],
                "consumerWatermarkBeforeRelay": raw[
                    "watermarkBeforeRelay"
                ],
                "traceId": raw["outboxTraceId"],
            },
            {
                "step": "consumer-applied",
                "consumerId": raw["consumerId"],
                "continuousWatermark": raw["consumerWatermark"],
                "lastEventId": raw["consumerLastEventId"],
                "localFenceState": raw["localFenceState"],
                "traceId": raw["consumerTraceId"],
            },
            {
                "step": "current-scope-read-back",
                "authorizationResult": (
                    "deny"
                    if raw["readBackValidity"] == "INVALID"
                    else "not-deny"
                ),
                "reasonCode": raw["readBackReasonCode"],
                "sourceVisibleAt": raw["sourceVisibleAt"],
                "evaluatedAt": raw["readBackEvaluatedAt"],
                "withinFifteenMinutes": raw["withinFifteenMinutes"],
                "traceId": raw["localFenceTraceId"],
            },
            {
                "step": "reconciled",
                "outcome": raw["reconciliationOutcome"],
                "propagationStatus": raw["propagationStatus"],
                "requiredConsumerCount": raw["requiredConsumerCount"],
                "appliedRequiredConsumerCount": raw[
                    "appliedRequiredConsumerCount"
                ],
                "plannedConsumersAdvanced": raw[
                    "plannedConsumersAdvanced"
                ],
                "traceId": raw["reconciliationTraceId"],
            },
        ],
        "rawPostgreSqlEvidence": raw,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--evidence",
        type=Path,
        default=Path(
            "_bmad-output/implementation-artifacts/evidence/"
            "1-6c-access-invalidation-sandbox-trace.json"
        ),
    )
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    toolchain = root / "_bmad/scripts/with_pab_toolchain.sh"
    try:
        contract_result = run(
            [
                str(toolchain),
                "python3",
                "-B",
                str(root / "scripts/check_access_invalidation_contracts.py"),
                str(root),
            ],
            root,
        )
        consumer_result = run(
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
        with tempfile.TemporaryDirectory(
            prefix="scholarsense-access-invalidation-evidence-"
        ) as temporary:
            raw_path = Path(temporary) / "postgresql-same-trace.json"
            database_result = run(
                [str(root / "scripts/run_audit_postgresql_tests.sh")],
                root,
                extra_env={
                    "ACCESS_INVALIDATION_EVIDENCE_OUTPUT": str(raw_path)
                },
            )
            if not raw_path.is_file():
                raise RuntimeError(
                    "PostgreSQL test did not emit same-trace evidence"
                )
            raw = json.loads(raw_path.read_text(encoding="utf-8"))
            evidence = build_evidence(
                raw,
                contract_result,
                consumer_result,
                database_result,
            )
    except (RuntimeError, ValueError, json.JSONDecodeError) as failure:
        print(str(failure), file=sys.stderr)
        return 1

    destination = args.evidence
    if not destination.is_absolute():
        destination = root / destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(
        json.dumps(evidence, ensure_ascii=False, sort_keys=True, indent=2)
        + "\n",
        encoding="utf-8",
    )
    try:
        label = destination.relative_to(root)
    except ValueError:
        label = destination
    print(
        "access-invalidation-sandbox: VERIFIED "
        f"(executed same-trace PostgreSQL convergence, evidence={label})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
