#!/usr/bin/env python3
"""Execute privacy-bounded minimal-slice provider/consumer conformance without network."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_data_catalog_contracts import EXPECTED_SOURCES  # noqa: E402
from release_json import canonical_bytes, load_json, schema_definition_issues, schema_issues  # noqa: E402


CONTRACT = Path("contracts/data-catalog")
SLICE_SOURCES = {
    "SRC-P0-CALENDAR-001": "src-p0-calendar-001.schema.json",
    "SRC-P0-TIMETABLE-001": "src-p0-timetable-001.schema.json",
    "SRC-P0-ACCOMMODATION-001": "src-p0-accommodation-001.schema.json",
    "SRC-P0-LEAVE-001": "src-p0-leave-001.schema.json",
    "SRC-P1-OFFCAMPUS-001": "src-p1-offcampus-001.schema.json",
    "SRC-P0-CAMPUS-ACCESS-001": "src-p0-campus-access-001.schema.json",
    "SRC-P0-DORM-ACCESS-001": "src-p0-dorm-access-001.schema.json",
    "SRC-P0-DEVICE-001": "src-p0-device-001.schema.json",
}
FORBIDDEN_TIMETABLE = {"grade", "ranking", "thesis", "fullAcademicRecord"}
EVENT_SOURCES = {"SRC-P0-CAMPUS-ACCESS-001", "SRC-P0-DORM-ACCESS-001"}
INTERVAL_SOURCES = {
    "SRC-P0-ACCOMMODATION-001",
    "SRC-P0-LEAVE-001",
    "SRC-P1-OFFCAMPUS-001",
    "SRC-P0-DEVICE-001",
}


def _parse(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("DCC_TIME_OFFSET_REQUIRED")
    return parsed.astimezone(timezone.utc)


def calendar_records(anchor: date, day_types: list[str]) -> list[dict[str, Any]]:
    timezone_shanghai = ZoneInfo("Asia/Shanghai")
    result: list[dict[str, Any]] = []
    for index in range(-90, 181):
        local_date = anchor + timedelta(days=index)
        start = datetime.combine(local_date, time.min, timezone_shanghai).astimezone(timezone.utc)
        end = datetime.combine(local_date + timedelta(days=1), time.min, timezone_shanghai).astimezone(timezone.utc)
        result.append({
            "businessCalendarVersion": "BC-1.0.0",
            "businessTimezone": "Asia/Shanghai",
            "localDate": local_date.isoformat(),
            "dayType": day_types[(index + 90) % len(day_types)],
            "dayStartsAt": start.isoformat().replace("+00:00", "Z"),
            "dayEndsAt": end.isoformat().replace("+00:00", "Z"),
            "effectiveAt": "2026-07-17T00:00:00Z",
            "sourceVersion": 1,
            "supersedesVersion": None,
        })
    return result


def validate_calendar(records: list[dict[str, Any]], anchor: date) -> list[str]:
    errors: list[str] = []
    expected = {anchor + timedelta(days=index) for index in range(-90, 181)}
    actual = [date.fromisoformat(str(item.get("localDate"))) for item in records]
    if set(actual) != expected or len(actual) != len(expected):
        errors.append("DCC_CALENDAR_COVERAGE_INVALID")
    allowed = {"workday", "weekend", "statutory-holiday", "makeup-workday", "school-holiday", "emergency-closure"}
    if any(item.get("dayType") not in allowed for item in records):
        errors.append("DCC_CALENDAR_DAY_TYPE_INVALID")
    timezone_shanghai = ZoneInfo("Asia/Shanghai")
    for item in records:
        local_date = date.fromisoformat(str(item["localDate"]))
        expected_start = datetime.combine(
            local_date, time.min, timezone_shanghai
        ).astimezone(timezone.utc)
        expected_end = datetime.combine(
            local_date + timedelta(days=1), time.min, timezone_shanghai
        ).astimezone(timezone.utc)
        if (
            item.get("businessTimezone") != "Asia/Shanghai"
            or _parse(item["dayStartsAt"]) != expected_start
            or _parse(item["dayEndsAt"]) != expected_end
        ):
            errors.append("DCC_CALENDAR_INTERVAL_INVALID")
            break
    return sorted(set(errors))


def project_calendar(history: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[str]]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for item in history:
        grouped.setdefault(item["localDate"], []).append(item)
    projected: list[dict[str, Any]] = []
    errors: list[str] = []
    for values in grouped.values():
        ordered = sorted(values, key=lambda item: item["sourceVersion"])
        if ordered[0]["sourceVersion"] != 1:
            errors.append("DCC_SOURCE_VERSION_REGRESSION")
        for prior, current in zip(ordered, ordered[1:]):
            if current["sourceVersion"] <= prior["sourceVersion"] or current.get("supersedesVersion") != prior["sourceVersion"]:
                errors.append("DCC_CORRECTION_CHAIN_INVALID")
        projected.append(ordered[-1])
    return projected, sorted(set(errors))


def validate_timetable(record: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if _parse(record["activityStartsAt"]) >= _parse(record["activityEndsAt"]):
        errors.append("DCC_TIMETABLE_INTERVAL_INVALID")
    if FORBIDDEN_TIMETABLE.intersection(record):
        errors.append("DCC_FORBIDDEN_FIELD")
    if record.get("sourceVersion", 0) < 1:
        errors.append("DCC_SOURCE_VERSION_REGRESSION")
    supersedes = record.get("supersedesVersion")
    if supersedes is not None and supersedes >= record.get("sourceVersion", 0):
        errors.append("DCC_CORRECTION_CHAIN_INVALID")
    return errors


def _semantic_record_issues(source_id: str, record: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if source_id in {"SRC-P0-ACCOMMODATION-001", "SRC-P0-LEAVE-001", "SRC-P1-OFFCAMPUS-001", "SRC-P0-DEVICE-001"}:
        start = _parse(record["effectiveFrom"])
        end_value = record.get("effectiveTo")
        if end_value is not None and start >= _parse(end_value):
            errors.append("DCC_INTERVAL_INVALID")
    if source_id in {"SRC-P0-CAMPUS-ACCESS-001", "SRC-P0-DORM-ACCESS-001"}:
        if _parse(record["occurredAt"]) > _parse(record["receivedAt"]):
            errors.append("DCC_EVENT_TIME_ORDER_INVALID")
        state = record.get("eventState", "effective")
        if (state in {"corrected", "revoked"}) != bool(record.get("correctsEventId")):
            errors.append("DCC_CORRECTION_CHAIN_INVALID")
    return errors


def validate_reconcile_sequence(
    source_id: str, sequence: list[dict[str, Any]]
) -> list[str]:
    """Execute correction/revocation/order/watermark invariants over record history."""
    if not sequence:
        return ["DCC_RECONCILE_SEQUENCE_EMPTY"]
    errors: list[str] = []
    versions = [item.get("sourceVersion") for item in sequence]
    if (
        any(not isinstance(value, int) or value < 1 for value in versions)
        or versions[0] != 1
        or any(current <= prior for prior, current in zip(versions, versions[1:]))
    ):
        errors.append("DCC_SOURCE_VERSION_REGRESSION")
    if source_id in EVENT_SOURCES:
        event_ids = [item.get("eventId") for item in sequence]
        if len(event_ids) != len(set(event_ids)):
            errors.append("DCC_DUPLICATE_BUSINESS_KEY")
        watermarks = [item.get("watermark") for item in sequence]
        if (
            any(not isinstance(value, str) or not value for value in watermarks)
            or any(current <= prior for prior, current in zip(watermarks, watermarks[1:]))
        ):
            errors.append("DCC_WATERMARK_REGRESSION")
        received = [_parse(str(item["receivedAt"])) for item in sequence]
        if any(current < prior for prior, current in zip(received, received[1:])):
            errors.append("DCC_RECEIPT_ORDER_INVALID")
        for prior, current in zip(sequence, sequence[1:]):
            if (
                current.get("eventState") not in {"corrected", "revoked"}
                or current.get("correctsEventId") != prior.get("eventId")
            ):
                errors.append("DCC_CORRECTION_CHAIN_INVALID")
    elif source_id in INTERVAL_SOURCES:
        for prior, current in zip(sequence, sequence[1:]):
            if current.get("supersedesVersion") != prior.get("sourceVersion"):
                errors.append("DCC_CORRECTION_CHAIN_INVALID")
    else:
        errors.append("DCC_RECONCILE_SOURCE_UNSUPPORTED")
    for record in sequence:
        errors.extend(_semantic_record_issues(source_id, record))
    return sorted(set(errors))


def _utc_value(value: datetime) -> str:
    return value.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace(
        "+00:00", "Z"
    )


def _exercise_reconcile_sequence(
    source_id: str, record: dict[str, Any]
) -> list[str]:
    issues: list[str] = []
    if source_id in EVENT_SOURCES:
        corrected = dict(
            record,
            eventId=str(record["eventId"]) + ".correction",
            eventState="corrected",
            correctsEventId=record["eventId"],
            occurredAt=_utc_value(_parse(record["occurredAt"]) - timedelta(seconds=1)),
            receivedAt=_utc_value(_parse(record["receivedAt"]) + timedelta(seconds=1)),
            sourceVersion=2,
            watermark=str(record["watermark"]) + ".002",
        )
        revoked = dict(
            corrected,
            eventId=str(record["eventId"]) + ".revocation",
            eventState="revoked",
            correctsEventId=corrected["eventId"],
            receivedAt=_utc_value(_parse(corrected["receivedAt"]) + timedelta(seconds=1)),
            sourceVersion=3,
            watermark=str(record["watermark"]) + ".003",
        )
        if validate_reconcile_sequence(source_id, [record, corrected, revoked]):
            issues.append("DCC_RECONCILE_SEQUENCE_INVALID")
        if "DCC_CORRECTION_CHAIN_INVALID" not in validate_reconcile_sequence(
            source_id, [record, dict(corrected, correctsEventId=None)]
        ):
            issues.append("DCC_CORRECTION_NEGATIVE_NOT_REJECTED")
        if "DCC_WATERMARK_REGRESSION" not in validate_reconcile_sequence(
            source_id, [record, dict(corrected, watermark="wm.000")]
        ):
            issues.append("DCC_WATERMARK_NEGATIVE_NOT_REJECTED")
        if "DCC_SOURCE_VERSION_REGRESSION" not in validate_reconcile_sequence(
            source_id, [record, dict(corrected, sourceVersion=1)]
        ):
            issues.append("DCC_VERSION_NEGATIVE_NOT_REJECTED")
    else:
        corrected = dict(record, sourceVersion=2, supersedesVersion=1)
        if source_id in {"SRC-P0-LEAVE-001", "SRC-P1-OFFCAMPUS-001"}:
            corrected["approvalState"] = "revoked"
        elif source_id == "SRC-P0-DEVICE-001":
            corrected["state"] = "maintenance"
        if validate_reconcile_sequence(source_id, [record, corrected]):
            issues.append("DCC_RECONCILE_SEQUENCE_INVALID")
        if "DCC_CORRECTION_CHAIN_INVALID" not in validate_reconcile_sequence(
            source_id, [record, dict(corrected, supersedesVersion=None)]
        ):
            issues.append("DCC_CORRECTION_NEGATIVE_NOT_REJECTED")
        if "DCC_SOURCE_VERSION_REGRESSION" not in validate_reconcile_sequence(
            source_id, [record, dict(corrected, sourceVersion=1)]
        ):
            issues.append("DCC_VERSION_NEGATIVE_NOT_REJECTED")
    return sorted(set(issues))


def execute(project_root: Path) -> tuple[list[str], dict[str, Any]]:
    root = project_root.resolve()
    contract = root / CONTRACT
    boundary = load_json(contract / "fixtures/valid/minimal-slice-boundaries-1.0.0.json")
    samples = load_json(contract / "fixtures/valid/minimal-slice-records-1.0.0.json")["records"]
    catalog = load_json(contract / "dcc-1.0.0.json")
    descriptors = {item["sourceId"]: item for item in catalog["sources"]}
    issues: list[str] = []
    scenarios: dict[str, list[dict[str, str]]] = {source: [] for source in sorted(EXPECTED_SOURCES)}

    for source_id, descriptor in descriptors.items():
        schema = load_json(contract / descriptor["schemaRef"])
        definition_issues = schema_definition_issues(schema)
        result = "pass" if not definition_issues else "fail"
        scenarios[source_id].append({"id": "schema-definition", "result": result})
        if definition_issues:
            issues.append("DCC_SCHEMA_DEFINITION_INVALID")

    anchor = date.fromisoformat(boundary["anchorDate"])
    calendar = calendar_records(anchor, boundary["calendar"]["requiredDayTypes"])
    calendar_schema = load_json(contract / "sources/src-p0-calendar-001.schema.json")
    calendar_schema_errors = [item for record in calendar for item in schema_issues(record, calendar_schema)]
    calendar_errors = calendar_schema_errors + validate_calendar(calendar, anchor)
    scenarios["SRC-P0-CALENDAR-001"].append({"id": "today-90-through-today+180", "result": "pass" if not calendar_errors else "fail"})
    missing = calendar[:10] + calendar[11:]
    if "DCC_CALENDAR_COVERAGE_INVALID" not in validate_calendar(missing, anchor):
        issues.append("DCC_CALENDAR_NEGATIVE_NOT_REJECTED")
    scenarios["SRC-P0-CALENDAR-001"].append({"id": "missing-day-rejected", "result": "pass"})
    correction = dict(calendar[90], sourceVersion=2, supersedesVersion=1, dayType="emergency-closure")
    projected, correction_errors = project_calendar(calendar + [correction])
    correction_errors += validate_calendar(projected, anchor)
    if next(item for item in projected if item["localDate"] == anchor.isoformat())["dayType"] != "emergency-closure":
        correction_errors.append("DCC_EMERGENCY_CORRECTION_NOT_EFFECTIVE")
    scenarios["SRC-P0-CALENDAR-001"].append({"id": "emergency-correction-chain", "result": "pass" if not correction_errors else "fail"})
    _, broken_chain = project_calendar(calendar + [dict(correction, supersedesVersion=None)])
    if "DCC_CORRECTION_CHAIN_INVALID" not in broken_chain:
        issues.append("DCC_CALENDAR_CORRECTION_NEGATIVE_NOT_REJECTED")

    timetable = load_json(contract / "fixtures/valid/timetable-activity.json")
    timetable_schema = load_json(contract / "sources/src-p0-timetable-001.schema.json")
    timetable_errors = schema_issues(timetable, timetable_schema) + validate_timetable(timetable)
    scenarios["SRC-P0-TIMETABLE-001"].append({"id": "half-open-state-and-campus", "result": "pass" if not timetable_errors else "fail"})
    timetable_variants = [
        dict(timetable, activityState="rescheduled", campusCode="CAMPUS-02", sourceVersion=2, supersedesVersion=1),
        dict(timetable, activityState="cancelled", sourceVersion=3, supersedesVersion=2),
        dict(timetable, enrollmentState="withdrawn", sourceVersion=4, supersedesVersion=3),
    ]
    state_errors = [issue for value in timetable_variants for issue in schema_issues(value, timetable_schema) + validate_timetable(value)]
    scenarios["SRC-P0-TIMETABLE-001"].append({"id": "reschedule-cancel-withdraw-cross-campus", "result": "pass" if not state_errors else "fail"})
    if "DCC_SOURCE_VERSION_REGRESSION" not in validate_timetable(dict(timetable, sourceVersion=0)):
        issues.append("DCC_TIMETABLE_VERSION_NEGATIVE_NOT_REJECTED")
    for field in ("grade", "thesis"):
        mutated = dict(timetable, **{field: "synthetic-forbidden"})
        if not schema_issues(mutated, timetable_schema) or "DCC_FORBIDDEN_FIELD" not in validate_timetable(mutated):
            issues.append("DCC_TIMETABLE_PRIVACY_NEGATIVE_NOT_REJECTED")
    scenarios["SRC-P0-TIMETABLE-001"].append({"id": "forbidden-academic-fields-rejected", "result": "pass"})

    for source_id, record in samples.items():
        schema = load_json(contract / "sources" / SLICE_SOURCES[source_id])
        record_errors = schema_issues(record, schema) + _semantic_record_issues(source_id, record)
        scenarios[source_id].append({"id": "minimal-record-and-reconcile", "result": "pass" if not record_errors else "fail"})
        sequence_errors = _exercise_reconcile_sequence(source_id, record)
        scenarios[source_id].append({
            "id": "correction-revocation-out-of-order-watermark",
            "result": "pass" if not sequence_errors else "fail",
        })
        issues.extend("DCC_MINIMAL_SLICE_INVALID" for _ in record_errors)
        issues.extend(sequence_errors)

    for source_id in sorted(EXPECTED_SOURCES):
        if any(item["result"] != "pass" for item in scenarios[source_id]):
            issues.append("DCC_SOURCE_SCENARIO_FAILED")

    report = {
        "reportVersion": "DCC-SANDBOX-REPORT-1.0.0",
        "evidenceClass": "fixture-conformance",
        "runtimeEvidenceClaim": "none",
        "sourceCount": len(scenarios),
        "scenarioSummary": {source: values for source, values in scenarios.items()},
        "inputDigest": "sha256:" + hashlib.sha256(canonical_bytes({"boundary": boundary, "samples": samples})).hexdigest(),
        "privacy": {"studentPlaintextStored": False, "rawBodyStored": False, "samplesAreSynthetic": True},
        "result": "pass" if not issues else "fail",
    }
    return sorted(set(issues)), report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("project_root", nargs="?", default=".")
    parser.add_argument("--report")
    args = parser.parse_args(argv)
    issues, report = execute(Path(args.project_root))
    if args.report:
        Path(args.report).write_text(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print(f"data-catalog-sandbox: PASS (sources={report['sourceCount']}; runtimeEvidenceClaim=none)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
