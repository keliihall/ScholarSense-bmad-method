#!/usr/bin/env python3
"""Fail-closed checker for Story 2.3 self-contained quality snapshot events."""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


BASE = Path("contracts/events/ingestion-quality")
COMMON = BASE / "data-batch-quality-event.schema.json"
ASSESSED_SCHEMA = BASE / "data-batch-quality-assessed.schema.json"
PUBLISHED_SCHEMA = BASE / "data-batch-published.schema.json"
ASSESSED = BASE / "fixtures/valid/data-batch-quality-assessed-v1.json"
PUBLISHED = BASE / "fixtures/valid/data-batch-published-v1.json"
INVALID = BASE / "fixtures/invalid/data-batch-quality-event-missing-snapshot.json"
ORDERING = BASE / "fixtures/ordering/data-batch-quality-ordering-1.0.0.json"
HANDOFF = BASE / "candidate-clue-quality-snapshot-handoff-1.0.0.json"
LOCK = BASE / "data-batch-quality-event-contract-lock-1.0.0.json"
FILES = (COMMON, ASSESSED_SCHEMA, PUBLISHED_SCHEMA, ASSESSED, PUBLISHED, INVALID, ORDERING, HANDOFF)

UUID_V7 = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
TRACE = re.compile(r"^(?!0{32}$)[0-9a-f]{32}$")
ROOT_KEYS = {"data", "datacontenttype", "id", "source", "specversion", "subject", "time", "traceparent", "type"}
DATA_KEYS = {"aggregateId", "aggregateType", "aggregateVersion", "causationId", "contractVersion", "correlationId", "eventId", "occurredAt", "producer", "runtimeEvidenceClaim", "schemaVersion", "traceId", "batch", "qualitySnapshot"}
BATCH_KEYS = {"batchId", "sourceId", "sourceVersion", "status", "aggregateVersion", "manifestDigest", "observationWindow", "cutoffAt", "watermark", "sourceSchemaVersion", "sourceSchemaDigest", "qualityMetricDecisionProfileVersion", "qualityMetricDecisionProfileDigest", "qualityGateVersion", "qualityGateDigest", "lineageId", "supersedesBatchId", "effectiveAt", "evaluatedAt", "publishedAt"}
SNAPSHOT_KEYS = {"snapshotId", "batchId", "sourceId", "assessedBatchStatus", "overallResult", "observationWindow", "cutoffAt", "evaluatedAt", "watermark", "metricResults", "impactScopeCodes", "sourceOwnerRef", "approvalRef", "effectiveAt", "retentionScheduleVersion", "qualityMetricDecisionProfileVersion", "qualityMetricDecisionProfileDigest", "qualityGateVersion", "qualityGateDigest", "canonicalizationProfile", "manifestDigest", "sourceSchemaVersion", "sourceSchemaDigest", "immutableHash", "traceId", "lineageId", "supersedesSnapshotId", "aggregateVersion"}
METRIC_KEYS = {"metricId", "formulaId", "formulaVersion", "result", "applicable", "numerator", "denominator", "valueBasisPoints", "unit", "operator", "thresholdNumerator", "thresholdDenominator", "boundary", "reasonCode"}


def load(root: Path, relative: Path) -> Any:
    return json.loads((root / relative).read_text(encoding="utf-8"))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def traceparent(trace_id: Any) -> str:
    value = str(trace_id)
    span_id = value[:16]
    if span_id == "0" * 16:
        span_id = value[16:]
    return f"00-{value}-{span_id}-01"


def event_issues(event: Any, expected: str) -> list[str]:
    issues: list[str] = []
    if not isinstance(event, dict) or set(event) != ROOT_KEYS:
        return [f"{expected}: envelope keys"]
    data = event.get("data")
    if not isinstance(data, dict) or set(data) != DATA_KEYS:
        return [f"{expected}: data keys"]
    batch, snapshot = data.get("batch"), data.get("qualitySnapshot")
    if not isinstance(batch, dict) or set(batch) != BATCH_KEYS:
        issues.append(f"{expected}: frozen batch keys")
    if not isinstance(snapshot, dict) or set(snapshot) != SNAPSHOT_KEYS:
        issues.append(f"{expected}: snapshot keys")
    if issues:
        return issues
    if not UUID_V7.fullmatch(str(event.get("id"))) or event["id"] != data["eventId"]:
        issues.append(f"{expected}: event UUIDv7 binding")
    for name in ("aggregateId", "causationId", "correlationId", "eventId"):
        if not UUID_V7.fullmatch(str(data.get(name))):
            issues.append(f"{expected}: {name} UUIDv7")
    for name in ("batchId", "lineageId"):
        if not UUID_V7.fullmatch(str(batch.get(name))):
            issues.append(f"{expected}: batch {name} UUIDv7")
    for name in ("snapshotId", "batchId", "lineageId"):
        if not UUID_V7.fullmatch(str(snapshot.get(name))):
            issues.append(f"{expected}: snapshot {name} UUIDv7")
    if data.get("aggregateId") != batch.get("batchId") or batch.get("batchId") != snapshot.get("batchId"):
        issues.append(f"{expected}: aggregate/batch/snapshot binding")
    if event.get("subject") != f"data-batch/{data.get('aggregateId')}":
        issues.append(f"{expected}: subject binding")
    if event.get("time") != data.get("occurredAt"):
        issues.append(f"{expected}: event time binding")
    if data.get("runtimeEvidenceClaim") != "none":
        issues.append(f"{expected}: runtimeEvidenceClaim must remain none")
    if not TRACE.fullmatch(str(data.get("traceId"))):
        issues.append(f"{expected}: traceId")
    if event.get("traceparent") != traceparent(data.get("traceId")):
        issues.append(f"{expected}: traceparent binding")
    for name in ("manifestDigest", "sourceSchemaDigest", "qualityMetricDecisionProfileDigest", "qualityGateDigest"):
        if not DIGEST.fullmatch(str(batch.get(name))):
            issues.append(f"{expected}: batch {name}")
        if snapshot.get(name) != batch.get(name):
            issues.append(f"{expected}: snapshot {name} binding")
    if not DIGEST.fullmatch(str(snapshot.get("immutableHash"))):
        issues.append(f"{expected}: immutableHash")
    for name in ("sourceId", "watermark", "observationWindow", "cutoffAt", "sourceSchemaVersion", "qualityMetricDecisionProfileVersion", "qualityGateVersion", "lineageId"):
        if snapshot.get(name) != batch.get(name):
            issues.append(f"{expected}: snapshot {name} binding")
    metrics = snapshot.get("metricResults")
    if not isinstance(metrics, list) or not 1 <= len(metrics) <= 14:
        issues.append(f"{expected}: metric bounds")
    else:
        for metric in metrics:
            if not isinstance(metric, dict) or set(metric) != METRIC_KEYS or "rawCount" in metric:
                issues.append(f"{expected}: metric evidence shape")
                break
    scopes = snapshot.get("impactScopeCodes")
    if not isinstance(scopes, list) or len(scopes) > 64 or len(scopes) != len(set(scopes)):
        issues.append(f"{expected}: impact scope bounds")
    if len(canonical_bytes(event)) > 65_536:
        issues.append(f"{expected}: exceeds 64 KiB")
    if expected == "assessed":
        if event.get("type") != "scholarsense.ingestion-quality.data-batch.quality-assessed.v1" or data.get("schemaVersion") != "DATA-BATCH-QUALITY-ASSESSED-1.0.0" or data.get("aggregateVersion") != 3 or batch.get("aggregateVersion") != 3 or batch.get("status") not in {"quality-passed", "quality-failed"} or batch.get("publishedAt") is not None or snapshot.get("aggregateVersion") != 3 or snapshot.get("overallResult") != batch.get("status") or snapshot.get("assessedBatchStatus") != batch.get("status"):
            issues.append("assessed: event/status/version binding")
    elif expected == "published":
        if event.get("type") != "scholarsense.ingestion-quality.data-batch.published.v1" or data.get("schemaVersion") != "DATA-BATCH-PUBLISHED-1.0.0" or data.get("aggregateVersion") != 4 or batch.get("aggregateVersion") != 4 or batch.get("status") != "published" or batch.get("publishedAt") != data.get("occurredAt") or snapshot.get("aggregateVersion") != 3 or snapshot.get("overallResult") != "quality-passed" or snapshot.get("assessedBatchStatus") != "quality-passed":
            issues.append("published: event/status/version binding")
    return issues


def check(root: Path) -> list[str]:
    issues: list[str] = []
    for relative in (*FILES, LOCK):
        if not (root / relative).is_file():
            issues.append(f"missing {relative}")
    if issues:
        return issues
    common = load(root, COMMON)
    if common.get("$schema") != "https://json-schema.org/draft/2020-12/schema" or set(common.get("properties", {})) != ROOT_KEYS:
        issues.append("common schema is not the frozen 2020-12 envelope")
    expected_wrappers = ((ASSESSED_SCHEMA, "scholarsense.ingestion-quality.data-batch.quality-assessed.v1", "DATA-BATCH-QUALITY-ASSESSED-1.0.0", 3), (PUBLISHED_SCHEMA, "scholarsense.ingestion-quality.data-batch.published.v1", "DATA-BATCH-PUBLISHED-1.0.0", 4))
    for path, event_type, version, aggregate in expected_wrappers:
        wrapper = load(root, path)
        if wrapper.get("$ref") != "data-batch-quality-event.schema.json" or wrapper.get("x-eventType") != event_type or wrapper.get("x-schemaVersion") != version or wrapper.get("x-aggregateVersion") != aggregate or wrapper.get("x-runtimeEvidenceClaim") != "none":
            issues.append(f"{path}: wrapper metadata")
    issues.extend(event_issues(load(root, ASSESSED), "assessed"))
    issues.extend(event_issues(load(root, PUBLISHED), "published"))
    if not event_issues(load(root, INVALID), "assessed"):
        issues.append("negative missing-snapshot fixture was accepted")
    ordering = load(root, ORDERING)
    scenarios = ordering.get("scenarios", []) if isinstance(ordering, dict) else []
    if ordering.get("runtimeEvidenceClaim") != "none" or {x.get("case") for x in scenarios if isinstance(x, dict)} != {"duplicate", "old", "gap", "poison", "backfill"}:
        issues.append("ordering fixtures incomplete")
    by_case = {x["case"]: x for x in scenarios if isinstance(x, dict) and "case" in x}
    if by_case.get("gap", {}).get("expectedAction") != "pause-and-backfill" or by_case.get("poison", {}).get("cursorAdvances") is not False or by_case.get("backfill", {}).get("cursorAdvances") is not True:
        issues.append("gap/poison/backfill fail-closed semantics")
    handoff = load(root, HANDOFF)
    immutable_copy = handoff.get("immutableEvidenceCopy", {}) if isinstance(handoff, dict) else {}
    ordering_handoff = handoff.get("identityAndOrdering", {}) if isinstance(handoff, dict) else {}
    required_copy = {
        "qualitySnapshot.snapshotId", "qualitySnapshot.immutableHash",
        "qualitySnapshot.manifestDigest", "qualitySnapshot.watermark",
        "qualitySnapshot.qualityMetricDecisionProfileVersion",
        "qualitySnapshot.qualityMetricDecisionProfileDigest",
        "qualitySnapshot.qualityGateVersion", "qualitySnapshot.qualityGateDigest",
        "qualitySnapshot.metricResults",
    }
    if (handoff.get("version") != "CANDIDATE-CLUE-QUALITY-SNAPSHOT-HANDOFF-1.0.0"
            or handoff.get("runtimeEvidenceClaim") != "none"
            or handoff.get("consumers") != {"Candidate": "not-installed", "Clue": "not-installed"}
            or set(immutable_copy.get("requiredFields", [])) != required_copy
            or immutable_copy.get("lookupSemantics") !=
                "event-time immutable copy; never mutable latest lookup"
            or "three years after case closure" not in immutable_copy.get("retention", "")
            or ordering_handoff.get("idempotencyKey") != ["source", "id"]
            or ordering_handoff.get("gap") != "pause-and-backfill"
            or ordering_handoff.get("poison") != "quarantine-without-cursor-advance"):
        issues.append("Candidate/Clue handoff is not closed and non-runtime")
    lock = load(root, LOCK)
    expected_paths = {str(path): f"sha256:{sha256(root / path)}" for path in FILES}
    if lock.get("version") != "DATA-BATCH-QUALITY-EVENT-CONTRACT-LOCK-1.0.0" or lock.get("runtimeEvidenceClaim") != "none" or lock.get("files") != expected_paths:
        issues.append("event contract lock mismatch")
    return issues


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    issues = check(root)
    if issues:
        for issue in issues:
            print(f"EVENT_CONTRACT: {issue}")
        return 1
    print("EVENT_CONTRACT: self-contained assessed/published schemas, fixtures, ordering and lock valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
