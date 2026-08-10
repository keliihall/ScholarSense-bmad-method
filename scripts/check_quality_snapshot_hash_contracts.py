#!/usr/bin/env python3
"""Validate the additive QSHM-1.0.0 deterministic snapshot hash contract."""

from __future__ import annotations

import copy
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


sys.path.insert(0, str(Path(__file__).resolve().parent))

from release_json import (  # noqa: E402
    MAX_SAFE_INTEGER,
    canonical_bytes,
    load_json,
    parse_json_bytes,
    schema_definition_issues,
    schema_issues,
)


BATCH_QUALITY = Path("contracts/ingestion-quality/batch-quality")
PROFILE_SCHEMA = BATCH_QUALITY / "quality-snapshot-hash-profile.schema.json"
PROFILE = BATCH_QUALITY / "quality-snapshot-hash-profile-1.0.0.json"
MATERIAL_SCHEMA = BATCH_QUALITY / "quality-snapshot-hash-material.schema.json"
VECTOR_SCHEMA = BATCH_QUALITY / "quality-snapshot-hash-vectors.schema.json"
VECTORS = BATCH_QUALITY / "fixtures/valid/quality-snapshot-hash-vectors-1.0.0.json"
NEGATIVE_SCHEMA = BATCH_QUALITY / "quality-snapshot-hash-negative-fixtures.schema.json"
NEGATIVE = BATCH_QUALITY / "fixtures/invalid/quality-snapshot-hash-negative-fixtures-1.0.0.json"
LOCK_SCHEMA = BATCH_QUALITY / "quality-snapshot-hash-contract-lock.schema.json"
LOCK = BATCH_QUALITY / "quality-snapshot-hash-contract-lock-1.0.0.json"
POLICY = BATCH_QUALITY / "executable-quality-policy-1.0.0.json"

QSHM_DIGESTED_FILES = frozenset({
    str(PROFILE_SCHEMA),
    str(PROFILE),
    str(MATERIAL_SCHEMA),
    str(VECTOR_SCHEMA),
    str(VECTORS),
    str(NEGATIVE_SCHEMA),
    str(NEGATIVE),
    str(LOCK_SCHEMA),
})
LOCKED_QSHM_FILES = frozenset({*QSHM_DIGESTED_FILES, str(LOCK)})

UPSTREAM_BINDINGS: dict[str, dict[str, str]] = {
    "contracts/ingestion-quality/batch-quality/executable-quality-policy-1.0.0.json": {
        "rawSha256": "1e7703748a7bda56034ab189700a82a503674d364ecac67c1eb35fe6ee8c0d84",
        "canonicalDigest": "sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8",
    },
    "contracts/ingestion-quality/batch-quality/executable-quality-contract-lock-1.0.0.json": {
        "rawSha256": "b93d5547e6b28aa7281f736bd2440800eea590987d68ae8ab28ffd6a225cdb6f",
        "canonicalDigest": "sha256:386f02acbdb9310fbe93e155e021023154005b2e9e01673f93c2ecdc881f8fce",
    },
    "contracts/field-projection/field-projection-policy-binding-1.1.0.json": {
        "rawSha256": "9a7e8edb67ad6c7c010af74e57ce62f64a38662aaa16c049a81dc1e7c58842b4",
        "canonicalDigest": "sha256:0ca0a7814184292ad4d0dffd3e75a8dbec750826dcc712c3b18c0566316f7c34",
    },
    "contracts/release/canonical-json-profile-1.0.0.json": {
        "rawSha256": "28cfa27dfc947d1a352f2e68e9f5df7274d582dcc666a3a46f13f65898a15245",
        "canonicalDigest": "sha256:0a81cb007877194fd933f569a5d36cedbf089b0184b3906d634ccf0b4ceef499",
    },
    "_bmad-output/planning-artifacts/sprint-change-proposal-2026-08-09.md": {
        "rawSha256": "6b090edabee4d72f0164d961a59dc15995c5c16f5138876eedc9a4764fceb2ed",
    },
}

APPROVED_PROFILE_RAW_SHA256 = (
    "2389902346fa1cef377bba7d8d575b03ef41e7f0614262b540df2e494f7fb882"
)
APPROVED_PROFILE_CANONICAL_DIGEST = (
    "sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2"
)
APPROVED_LOCK_RAW_SHA256 = (
    "95eeb36ad905079eabf3f83addb40c335e0d9c862c4c582a5981e21b2379dc82"
)

STRING_ESCAPING = {
    "strategy": "minimal-json-escapes",
    "quotationMark": '\\"',
    "reverseSolidus": "\\\\",
    "backspace": "\\b",
    "formFeed": "\\f",
    "lineFeed": "\\n",
    "carriageReturn": "\\r",
    "tab": "\\t",
    "otherControls": "lowercase-\\u00xx",
    "solidus": "unescaped",
    "nonAscii": "direct-utf8",
    "unicodeScalarsOnly": True,
}
RATIO_NON_SUBSET_OPERANDS = frozenset({
    ("calendar-current-days", "calendar-expected-days"),
})

INCLUDED_TOP_LEVEL_FIELDS = (
    "domainTag", "hashProfileVersion", "hashProfileDigest", "batchId", "sourceId",
    "assessedBatchStatus", "overallResult", "observationWindow", "cutoffAt", "watermark",
    "metricResults", "impactScopeCodes", "sourceOwnerRef", "approvalRef", "effectiveAt",
    "retentionScheduleVersion", "qualityMetricDecisionProfileVersion",
    "qualityMetricDecisionProfileDigest", "qualityGateVersion", "qualityGateDigest",
    "canonicalizationProfile", "manifestDigest", "sourceSchemaVersion", "sourceSchemaDigest",
    "lineageId", "supersedesSnapshotId",
)
EXCLUDED_SNAPSHOT_FIELDS = (
    "snapshotId", "evaluatedAt", "traceId", "aggregateVersion", "immutableHash",
)
METRIC_RESULT_FIELDS = (
    "metricId", "formulaId", "formulaVersion", "result", "applicable", "numerator",
    "denominator", "valueBasisPoints", "unit", "operator", "thresholdNumerator",
    "thresholdDenominator", "boundary", "reasonCode",
)
SNAPSHOT_REQUIRED_FIELDS = frozenset({
    *(set(INCLUDED_TOP_LEVEL_FIELDS) - {"domainTag"}),
    *EXCLUDED_SNAPSHOT_FIELDS,
})
UUID_V7 = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
SOURCE_ID = re.compile(r"^SRC-P[01]-[A-Z-]+-[0-9]{3}$")
TRACE_ID = re.compile(r"^[0-9a-f]{32}$")
UTC_MICROS = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}Z$"
)
OFFSET_TIME = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
    r"(?:\.([0-9]+))?(?:Z|[+-][0-9]{2}:[0-9]{2})$"
)


class QshmViolation(ValueError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def _raw_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _canonical_digest(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_bytes(value)).hexdigest()


def canonical_material_bytes(material: dict[str, Any]) -> bytes:
    return canonical_bytes(material)


def canonical_hash(material: dict[str, Any]) -> str:
    return "sha256:" + hashlib.sha256(canonical_material_bytes(material)).hexdigest()


def _require_int(value: Any, code: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= MAX_SAFE_INTEGER:
        raise QshmViolation(code)
    return value


def _canonical_instant(value: Any) -> str:
    if not isinstance(value, str):
        raise QshmViolation("QSHM_TIME_INVALID")
    matched = OFFSET_TIME.fullmatch(value)
    if matched is None:
        raise QshmViolation("QSHM_TIME_INVALID")
    fraction = matched.group(1)
    if fraction is not None and len(fraction) > 6:
        raise QshmViolation("QSHM_TIME_PRECISION_INVALID")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise QshmViolation("QSHM_TIME_INVALID") from error
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise QshmViolation("QSHM_TIME_INVALID")
    return parsed.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def _expected_definitions(policy: dict[str, Any], source_id: str) -> list[dict[str, Any]]:
    sources = policy.get("sources")
    if not isinstance(sources, list):
        raise QshmViolation("QSHM_POLICY_INVALID")
    source = next(
        (item for item in sources if isinstance(item, dict) and item.get("sourceId") == source_id),
        None,
    )
    if not isinstance(source, dict):
        raise QshmViolation("QSHM_SOURCE_UNKNOWN")
    selected = source.get("applicableCommonMetricIds")
    common = policy.get("commonMetrics")
    gates = source.get("sourceGates")
    if (
        not isinstance(selected, list)
        or not all(isinstance(item, str) for item in selected)
        or len(selected) != len(set(selected))
        or not isinstance(common, list)
        or not isinstance(gates, list)
    ):
        raise QshmViolation("QSHM_POLICY_INVALID")
    selected_set = set(selected)
    definitions = [
        item for item in common
        if isinstance(item, dict) and item.get("metricId") in selected_set
    ] + [item for item in gates if isinstance(item, dict)]
    if len(definitions) != len(selected) + len(gates):
        raise QshmViolation("QSHM_POLICY_INVALID")
    formulas = [item.get("formulaId") for item in definitions]
    if any(not isinstance(item, str) for item in formulas) or len(formulas) != len(set(formulas)):
        raise QshmViolation("QSHM_POLICY_INVALID")
    return definitions


def _expected_source(policy: dict[str, Any], source_id: str) -> dict[str, Any]:
    sources = policy.get("sources")
    if not isinstance(sources, list):
        raise QshmViolation("QSHM_POLICY_INVALID")
    matches = [
        item for item in sources
        if isinstance(item, dict) and item.get("sourceId") == source_id
    ]
    if len(matches) != 1:
        raise QshmViolation("QSHM_SOURCE_UNKNOWN")
    return matches[0]


def _policy_applicability(
    definition: dict[str, Any],
    source: dict[str, Any],
) -> bool | None:
    applicability = definition.get("applicability")
    if not isinstance(applicability, dict):
        raise QshmViolation("QSHM_POLICY_INVALID")
    predicate = applicability.get("predicateId")
    if predicate == "always":
        return True
    if predicate == "source-in-approved-set":
        source_ids = applicability.get("sourceIds")
        if not isinstance(source_ids, list) or not all(
            isinstance(item, str) for item in source_ids
        ):
            raise QshmViolation("QSHM_POLICY_INVALID")
        return source.get("sourceId") in source_ids
    if predicate == "source-field-group-present":
        field_set = definition.get("fieldSet")
        if isinstance(field_set, list) and field_set:
            return True
        gates = source.get("sourceGates")
        if not isinstance(gates, list):
            raise QshmViolation("QSHM_POLICY_INVALID")
        return any(
            isinstance(gate, dict)
            and gate.get("metricId") == definition.get("metricId")
            and isinstance(gate.get("fieldSet"), list)
            and bool(gate["fieldSet"])
            for gate in gates
        )
    if predicate == "overlap-records-present":
        return None
    raise QshmViolation("QSHM_POLICY_INVALID")


def _normalized_metric_results(
    results: Any,
    definitions: list[dict[str, Any]],
    source: dict[str, Any],
) -> list[dict[str, Any]]:
    if not isinstance(results, list) or not results:
        raise QshmViolation("QSHM_METRIC_SET_INVALID")
    expected_ids = [item["formulaId"] for item in definitions]
    expected_id_set = set(expected_ids)
    by_formula: dict[str, dict[str, Any]] = {}
    for result in results:
        if not isinstance(result, dict):
            raise QshmViolation("QSHM_METRIC_FIELD_SET_INVALID")
        if "rawCount" in result:
            raise QshmViolation("QSHM_RAW_COUNT_FORBIDDEN")
        if set(result) != set(METRIC_RESULT_FIELDS):
            raise QshmViolation("QSHM_METRIC_FIELD_SET_INVALID")
        formula_id = result.get("formulaId")
        if not isinstance(formula_id, str) or formula_id not in expected_id_set:
            raise QshmViolation("QSHM_FORMULA_UNKNOWN")
        if formula_id in by_formula:
            raise QshmViolation("QSHM_FORMULA_DUPLICATE")
        by_formula[formula_id] = result
    if set(by_formula) != set(expected_ids):
        raise QshmViolation("QSHM_METRIC_SET_INVALID")

    normalized: list[dict[str, Any]] = []
    for definition in definitions:
        result = by_formula[definition["formulaId"]]
        _require_int(result.get("thresholdNumerator"), "QSHM_METRIC_VALUE_INVALID")
        _require_int(result.get("thresholdDenominator"), "QSHM_METRIC_VALUE_INVALID")
        expected_binding = {
            "metricId": definition.get("metricId"),
            "formulaId": definition.get("formulaId"),
            "formulaVersion": definition.get("formulaVersion"),
            "unit": definition.get("unit"),
            "operator": definition.get("operator"),
            "thresholdNumerator": definition.get("thresholdNumerator"),
            "thresholdDenominator": definition.get("thresholdDenominator"),
            "boundary": definition.get("boundary"),
        }
        if any(result.get(key) != value for key, value in expected_binding.items()):
            raise QshmViolation("QSHM_METRIC_POLICY_BINDING_INVALID")
        if not isinstance(result.get("applicable"), bool):
            raise QshmViolation("QSHM_METRIC_VALUE_INVALID")
        numerator = _require_int(result.get("numerator"), "QSHM_METRIC_VALUE_INVALID")
        denominator = _require_int(result.get("denominator"), "QSHM_METRIC_VALUE_INVALID")
        value_bp = result.get("valueBasisPoints")
        if value_bp is not None:
            _require_int(value_bp, "QSHM_METRIC_VALUE_INVALID")
        reason = result.get("reasonCode")
        if reason is not None and (not isinstance(reason, str) or not reason):
            raise QshmViolation("QSHM_METRIC_VALUE_INVALID")
        if not result["applicable"]:
            if (
                numerator != 0
                or denominator != 0
                or value_bp is not None
                or result.get("result") != "not-applicable"
                or reason is not None
            ):
                raise QshmViolation("QSHM_NOT_APPLICABLE_INVALID")
        else:
            if denominator == 0:
                raise QshmViolation("QSHM_ZERO_DENOMINATOR")
            if result.get("result") not in {"passed", "failed"}:
                raise QshmViolation("QSHM_METRIC_VALUE_INVALID")
            if reason is not None:
                raise QshmViolation("QSHM_METRIC_VALUE_INVALID")
            calculation = definition.get("calculation", {})
            kind = calculation.get("kind")
            if kind in {"count", "duration"}:
                if denominator != 1 or value_bp is not None:
                    raise QshmViolation("QSHM_METRIC_VALUE_INVALID")
            elif kind == "ratio":
                numerator_operand = calculation.get("numerator", {}).get("operandId")
                denominator_operand = calculation.get("denominator", {}).get("operandId")
                if (
                    numerator > denominator
                    and (numerator_operand, denominator_operand) not in RATIO_NON_SUBSET_OPERANDS
                ):
                    raise QshmViolation("QSHM_OPERAND_RELATION_INVALID")
                expected_value = _half_up_basis_points(numerator, denominator)
                if value_bp != expected_value:
                    raise QshmViolation("QSHM_METRIC_VALUE_INVALID")
            elif kind == "composite-and":
                if numerator > denominator:
                    raise QshmViolation("QSHM_OPERAND_RELATION_INVALID")
                expected_value = _half_up_basis_points(numerator, denominator)
                if value_bp != expected_value:
                    raise QshmViolation("QSHM_METRIC_VALUE_INVALID")
            else:
                raise QshmViolation("QSHM_POLICY_INVALID")
            left = numerator * definition["thresholdDenominator"]
            right = definition["thresholdNumerator"] * denominator
            passed = {
                ">=": left >= right,
                "<=": left <= right,
                "=": left == right,
            }.get(definition.get("operator"))
            if passed is None:
                raise QshmViolation("QSHM_POLICY_INVALID")
            expected_result = "passed" if passed else "failed"
            if result.get("result") != expected_result:
                raise QshmViolation("QSHM_METRIC_RESULT_INVALID")
        expected_applicable = _policy_applicability(definition, source)
        if expected_applicable is not None and result["applicable"] != expected_applicable:
            raise QshmViolation("QSHM_APPLICABILITY_INVALID")
        normalized.append({key: copy.deepcopy(result[key]) for key in METRIC_RESULT_FIELDS})
    return normalized


def _half_up_basis_points(numerator: int, denominator: int) -> int:
    quotient, remainder = divmod(numerator * 10_000, denominator)
    return quotient + int(remainder * 2 >= denominator)


def build_material(
    snapshot: dict[str, Any],
    profile: dict[str, Any],
    policy: dict[str, Any],
) -> dict[str, Any]:
    if not isinstance(snapshot, dict):
        raise QshmViolation("QSHM_FIELD_SET_INVALID")
    if "rawCount" in snapshot:
        raise QshmViolation("QSHM_RAW_COUNT_FORBIDDEN")
    if set(snapshot) != SNAPSHOT_REQUIRED_FIELDS:
        raise QshmViolation("QSHM_FIELD_SET_INVALID")
    if profile.get("includedTopLevelFields") != list(INCLUDED_TOP_LEVEL_FIELDS):
        raise QshmViolation("QSHM_PROFILE_FIELD_SET_INVALID")
    if profile.get("excludedSnapshotFields") != list(EXCLUDED_SNAPSHOT_FIELDS):
        raise QshmViolation("QSHM_PROFILE_EXCLUSION_INVALID")
    if profile.get("metricResultFields") != list(METRIC_RESULT_FIELDS):
        raise QshmViolation("QSHM_PROFILE_METRIC_FIELDS_INVALID")
    if _canonical_digest(profile) != APPROVED_PROFILE_CANONICAL_DIGEST:
        raise QshmViolation("QSHM_PROFILE_SEMANTICS_INVALID")
    if snapshot.get("hashProfileVersion") != "QSHM-1.0.0":
        raise QshmViolation("QSHM_PROFILE_BINDING_INVALID")
    profile_digest = _canonical_digest(profile)
    if snapshot.get("hashProfileDigest") != profile_digest:
        raise QshmViolation("QSHM_PROFILE_BINDING_INVALID")
    for field in ("snapshotId", "batchId", "lineageId"):
        if not isinstance(snapshot.get(field), str) or UUID_V7.fullmatch(snapshot[field]) is None:
            raise QshmViolation("QSHM_UUID_INVALID")
    predecessor = snapshot.get("supersedesSnapshotId")
    if predecessor is not None and (
        not isinstance(predecessor, str) or UUID_V7.fullmatch(predecessor) is None
    ):
        raise QshmViolation("QSHM_UUID_INVALID")
    if predecessor == snapshot["snapshotId"]:
        raise QshmViolation("QSHM_LINEAGE_INVALID")
    if not isinstance(snapshot.get("sourceId"), str) or SOURCE_ID.fullmatch(snapshot["sourceId"]) is None:
        raise QshmViolation("QSHM_SOURCE_UNKNOWN")
    if not isinstance(snapshot.get("traceId"), str) or TRACE_ID.fullmatch(snapshot["traceId"]) is None:
        raise QshmViolation("QSHM_TRACE_ID_INVALID")
    aggregate_version = _require_int(snapshot.get("aggregateVersion"), "QSHM_AGGREGATE_VERSION_INVALID")
    if aggregate_version < 1:
        raise QshmViolation("QSHM_AGGREGATE_VERSION_INVALID")
    for field in (
        "hashProfileDigest", "qualityMetricDecisionProfileDigest", "qualityGateDigest",
        "manifestDigest", "sourceSchemaDigest", "immutableHash",
    ):
        if not isinstance(snapshot.get(field), str) or DIGEST.fullmatch(snapshot[field]) is None:
            raise QshmViolation("QSHM_DIGEST_INVALID")
    observation = snapshot.get("observationWindow")
    if not isinstance(observation, dict) or set(observation) != {"startAt", "endAt"}:
        raise QshmViolation("QSHM_FIELD_SET_INVALID")
    start_at = _canonical_instant(observation["startAt"])
    end_at = _canonical_instant(observation["endAt"])
    if start_at >= end_at:
        raise QshmViolation("QSHM_WINDOW_INVALID")
    _canonical_instant(snapshot["evaluatedAt"])

    source = _expected_source(policy, snapshot["sourceId"])
    schema_binding = source.get("schemaBinding")
    quality_gate = policy.get("controlledInputs", {}).get("qualityGate")
    canonicalization = policy.get("canonicalization")
    if not isinstance(schema_binding, dict) or not isinstance(quality_gate, dict):
        raise QshmViolation("QSHM_POLICY_INVALID")
    expected_bindings = {
        "sourceOwnerRef": source.get("owner"),
        "approvalRef": policy.get("approvalRef"),
        "retentionScheduleVersion": "RS-1.0.0",
        "qualityMetricDecisionProfileVersion": policy.get("profileVersion"),
        "qualityMetricDecisionProfileDigest": UPSTREAM_BINDINGS[str(POLICY)]["canonicalDigest"],
        "qualityGateVersion": quality_gate.get("version"),
        "qualityGateDigest": quality_gate.get("canonicalDigest"),
        "canonicalizationProfile": (
            canonicalization.get("profile") if isinstance(canonicalization, dict) else None
        ),
        "sourceSchemaVersion": schema_binding.get("version"),
        "sourceSchemaDigest": schema_binding.get("canonicalDigest"),
    }
    if any(snapshot.get(field) != value for field, value in expected_bindings.items()):
        raise QshmViolation("QSHM_CONTROL_BINDING_INVALID")
    if _canonical_instant(snapshot["effectiveAt"]) != _canonical_instant(policy.get("effectiveAt")):
        raise QshmViolation("QSHM_CONTROL_BINDING_INVALID")
    for field, maximum in (("watermark", 512), ("sourceOwnerRef", 256)):
        value = snapshot.get(field)
        if not isinstance(value, str) or not 1 <= len(value) <= maximum:
            raise QshmViolation("QSHM_FIELD_VALUE_INVALID")

    definitions = _expected_definitions(policy, snapshot["sourceId"])
    metrics = _normalized_metric_results(snapshot.get("metricResults"), definitions, source)
    applicable = [item for item in metrics if item["applicable"]]
    if not applicable:
        raise QshmViolation("QSHM_OVERALL_STATUS_INVALID")
    expected_result = (
        "quality-failed" if any(item["result"] == "failed" for item in applicable)
        else "quality-passed"
    )
    if (
        snapshot.get("overallResult") != expected_result
        or snapshot.get("assessedBatchStatus") != expected_result
    ):
        raise QshmViolation("QSHM_OVERALL_STATUS_INVALID")
    impacts = snapshot.get("impactScopeCodes")
    if (
        not isinstance(impacts, list)
        or any(
            not isinstance(item, str)
            or not 1 <= len(item) <= 64
            or any(0xD800 <= ord(character) <= 0xDFFF for character in item)
            for item in impacts
        )
        or len(impacts) != len(set(impacts))
    ):
        raise QshmViolation("QSHM_IMPACT_SCOPE_INVALID")

    material = {
        "domainTag": profile.get("domainTag"),
        "hashProfileVersion": snapshot["hashProfileVersion"],
        "hashProfileDigest": snapshot["hashProfileDigest"],
        "batchId": snapshot["batchId"],
        "sourceId": snapshot["sourceId"],
        "assessedBatchStatus": snapshot["assessedBatchStatus"],
        "overallResult": snapshot["overallResult"],
        "observationWindow": {"startAt": start_at, "endAt": end_at},
        "cutoffAt": _canonical_instant(snapshot["cutoffAt"]),
        "watermark": snapshot["watermark"],
        "metricResults": metrics,
        "impactScopeCodes": sorted(impacts),
        "sourceOwnerRef": snapshot["sourceOwnerRef"],
        "approvalRef": snapshot["approvalRef"],
        "effectiveAt": _canonical_instant(snapshot["effectiveAt"]),
        "retentionScheduleVersion": snapshot["retentionScheduleVersion"],
        "qualityMetricDecisionProfileVersion": snapshot["qualityMetricDecisionProfileVersion"],
        "qualityMetricDecisionProfileDigest": snapshot["qualityMetricDecisionProfileDigest"],
        "qualityGateVersion": snapshot["qualityGateVersion"],
        "qualityGateDigest": snapshot["qualityGateDigest"],
        "canonicalizationProfile": snapshot["canonicalizationProfile"],
        "manifestDigest": snapshot["manifestDigest"],
        "sourceSchemaVersion": snapshot["sourceSchemaVersion"],
        "sourceSchemaDigest": snapshot["sourceSchemaDigest"],
        "lineageId": snapshot["lineageId"],
        "supersedesSnapshotId": predecessor,
    }
    if set(material) != set(INCLUDED_TOP_LEVEL_FIELDS):
        raise QshmViolation("QSHM_FIELD_SET_INVALID")
    return material


def material_issues(
    snapshot: Any,
    profile: dict[str, Any],
    policy: dict[str, Any],
) -> list[str]:
    try:
        build_material(snapshot, profile, policy)
    except QshmViolation as error:
        return [error.code]
    except (KeyError, TypeError, ValueError):
        return ["QSHM_MATERIAL_INVALID"]
    return []


def profile_issues(root: Path, profile: Any) -> list[str]:
    if not isinstance(profile, dict):
        return ["QSHM_PROFILE_INVALID"]
    issues: list[str] = []
    if profile.get("includedTopLevelFields") != list(INCLUDED_TOP_LEVEL_FIELDS):
        issues.append("QSHM_PROFILE_FIELD_SET_INVALID")
    if profile.get("excludedSnapshotFields") != list(EXCLUDED_SNAPSHOT_FIELDS):
        issues.append("QSHM_PROFILE_EXCLUSION_INVALID")
    if profile.get("metricResultFields") != list(METRIC_RESULT_FIELDS):
        issues.append("QSHM_PROFILE_METRIC_FIELDS_INVALID")
    if profile.get("stringEscaping") != STRING_ESCAPING:
        issues.append("QSHM_PROFILE_STRING_ESCAPING_INVALID")
    if "hashProfileDigest" in profile:
        issues.append("QSHM_PROFILE_SELF_DIGEST_FORBIDDEN")
    if _canonical_digest(profile) != APPROVED_PROFILE_CANONICAL_DIGEST:
        issues.append("QSHM_PROFILE_SEMANTICS_INVALID")
    profile_path = root / PROFILE
    if not profile_path.is_file() or _raw_sha256(profile_path) != APPROVED_PROFILE_RAW_SHA256:
        issues.append("QSHM_PROFILE_RAW_DIGEST_MISMATCH")
    if any(
        "rawCount" in item
        for item in [
            *profile.get("includedTopLevelFields", []),
            *profile.get("metricResultFields", []),
        ]
        if isinstance(item, str)
    ):
        issues.append("QSHM_RAW_COUNT_FORBIDDEN")
    controlled = profile.get("controlledInputs")
    expected = [
        {"path": path, **binding} for path, binding in UPSTREAM_BINDINGS.items()
    ]
    if controlled != expected:
        issues.append("QSHM_PROFILE_UPSTREAM_BINDING_INVALID")
    return sorted(set(issues))


def vector_issues(root: Path, profile: Any, policy: Any, vectors: Any) -> list[str]:
    if not isinstance(vectors, dict) or not isinstance(vectors.get("cases"), list):
        return ["QSHM_VECTOR_INVALID"]
    issues: list[str] = []
    try:
        material_schema = load_json(root / MATERIAL_SCHEMA)
    except (OSError, ValueError):
        return ["QSHM_MATERIAL_SCHEMA_INVALID"]
    seen: set[str] = set()
    cases_by_id: dict[str, dict[str, Any]] = {}
    snapshots_by_id: dict[str, dict[str, Any]] = {}
    required_cases = {
        "root-policy-order-golden",
        "offcampus-predecessor-golden",
        "successor-not-applicable-golden",
    }
    for case in vectors["cases"]:
        if not isinstance(case, dict) or not isinstance(case.get("caseId"), str):
            issues.append("QSHM_VECTOR_CASE_INVALID")
            continue
        case_id = case["caseId"]
        if case_id in seen:
            issues.append("QSHM_VECTOR_CASE_DUPLICATE")
            continue
        seen.add(case_id)
        snapshot = case.get("snapshot")
        cases_by_id[case_id] = case
        if isinstance(snapshot, dict) and isinstance(snapshot.get("snapshotId"), str):
            snapshot_id = snapshot["snapshotId"]
            if snapshot_id in snapshots_by_id:
                issues.append(f"QSHM_VECTOR_LINEAGE_INVALID: {case_id}")
            snapshots_by_id[snapshot_id] = snapshot
        try:
            material = build_material(case.get("snapshot"), profile, policy)
        except QshmViolation as error:
            issues.append(f"{error.code}: {case_id}")
            continue
        if schema_issues(material, material_schema):
            issues.append(f"QSHM_MATERIAL_SCHEMA_REJECTED: {case_id}")
        encoded = canonical_material_bytes(material)
        if case.get("expectedCanonicalUtf8Hex") != encoded.hex():
            issues.append(f"QSHM_GOLDEN_BYTES_MISMATCH: {case_id}")
        if case.get("expectedImmutableHash") != canonical_hash(material):
            issues.append(f"QSHM_GOLDEN_HASH_MISMATCH: {case_id}")
    if seen != required_cases:
        issues.append("QSHM_VECTOR_CASE_SET_INVALID")
    roots = ("root-policy-order-golden", "offcampus-predecessor-golden")
    for case_id in roots:
        case = cases_by_id.get(case_id)
        snapshot = case.get("snapshot") if isinstance(case, dict) else None
        if not isinstance(snapshot, dict) or snapshot.get("supersedesSnapshotId") is not None:
            issues.append(f"QSHM_VECTOR_LINEAGE_INVALID: {case_id}")
    successor_case = cases_by_id.get("successor-not-applicable-golden")
    predecessor_case = cases_by_id.get("offcampus-predecessor-golden")
    if not isinstance(successor_case, dict) or not isinstance(predecessor_case, dict):
        issues.append("QSHM_VECTOR_LINEAGE_INVALID: successor-not-applicable-golden")
    else:
        successor = successor_case.get("snapshot")
        predecessor = predecessor_case.get("snapshot")
        if (
            not isinstance(successor, dict)
            or not isinstance(predecessor, dict)
            or successor.get("supersedesSnapshotId") != predecessor.get("snapshotId")
            or successor.get("snapshotId") == predecessor.get("snapshotId")
            or successor.get("sourceId") != predecessor.get("sourceId")
            or successor.get("lineageId") != predecessor.get("lineageId")
            or predecessor.get("supersedesSnapshotId") is not None
        ):
            issues.append("QSHM_VECTOR_LINEAGE_INVALID: successor-not-applicable-golden")
    reference_counts: dict[str, int] = {}
    for case in cases_by_id.values():
        snapshot = case.get("snapshot")
        predecessor_id = snapshot.get("supersedesSnapshotId") if isinstance(snapshot, dict) else None
        if predecessor_id is not None:
            reference_counts[predecessor_id] = reference_counts.get(predecessor_id, 0) + 1
            if predecessor_id not in snapshots_by_id:
                issues.append(f"QSHM_VECTOR_LINEAGE_INVALID: {case.get('caseId')}")
    if any(count != 1 for count in reference_counts.values()):
        issues.append("QSHM_VECTOR_LINEAGE_FORK_INVALID")
    return sorted(set(issues))


def negative_fixture_issues(root: Path) -> list[str]:
    try:
        profile = load_json(root / PROFILE)
        policy = load_json(root / POLICY)
        vectors = load_json(root / VECTORS)
        negative = load_json(root / NEGATIVE)
    except (OSError, ValueError):
        return ["QSHM_NEGATIVE_DOCUMENT_INVALID"]
    cases = negative.get("cases") if isinstance(negative, dict) else None
    base_id = negative.get("baseCaseId") if isinstance(negative, dict) else None
    base = next(
        (item.get("snapshot") for item in vectors.get("cases", []) if item.get("caseId") == base_id),
        None,
    )
    if not isinstance(cases, list) or not isinstance(base, dict):
        return ["QSHM_NEGATIVE_INVALID"]
    issues: list[str] = []
    seen: set[str] = set()
    for case in cases:
        if not isinstance(case, dict):
            issues.append("QSHM_NEGATIVE_CASE_INVALID")
            continue
        case_id = case.get("caseId")
        mutation = case.get("mutation")
        expected = case.get("expectedCode")
        if not isinstance(case_id, str) or case_id in seen:
            issues.append("QSHM_NEGATIVE_CASE_DUPLICATE")
            continue
        seen.add(case_id)
        observed = _run_negative_mutation(base, mutation, profile, policy)
        if observed != expected:
            issues.append(f"QSHM_NEGATIVE_FALSE_GREEN: {case_id}: {observed}")
    return sorted(set(issues))


def _run_negative_mutation(
    base: dict[str, Any],
    mutation: Any,
    profile: dict[str, Any],
    policy: dict[str, Any],
) -> str:
    raw_cases = {
        "duplicate-json-key": (b'{"a":1,"a":2}', "QSHM_DUPLICATE_KEY"),
        "nested-duplicate-json-key": (
            b'{"outer":{"a":1,"a":2}}', "QSHM_DUPLICATE_KEY"
        ),
        "metric-duplicate-json-key": (
            b'{"metricResults":[{"metricId":"A","metricId":"B"}]}',
            "QSHM_DUPLICATE_KEY",
        ),
        "float": (b'{"value":1.5}', "QSHM_FLOAT_FORBIDDEN"),
        "negative-zero": (b'{"value":-0}', "QSHM_NEGATIVE_ZERO_FORBIDDEN"),
        "unsafe-integer": (b'{"value":9007199254740992}', "QSHM_UNSAFE_INTEGER"),
        "utf8-bom": (b'\xef\xbb\xbf{}', "QSHM_BOM_FORBIDDEN"),
        "lone-surrogate": (b'{"value":"\\ud800"}', "QSHM_LONE_SURROGATE_FORBIDDEN"),
    }
    if mutation in raw_cases:
        payload, code = raw_cases[mutation]
        expected_error = {
            "QSHM_DUPLICATE_KEY": "JSON_DUPLICATE_KEY",
            "QSHM_FLOAT_FORBIDDEN": "JSON_FLOAT_FORBIDDEN",
            "QSHM_NEGATIVE_ZERO_FORBIDDEN": "JSON_NEGATIVE_ZERO_FORBIDDEN",
            "QSHM_UNSAFE_INTEGER": "JSON_INTEGER_OUT_OF_RANGE",
            "QSHM_BOM_FORBIDDEN": "JSON_BOM_FORBIDDEN",
            "QSHM_LONE_SURROGATE_FORBIDDEN": "JSON_LONE_SURROGATE_FORBIDDEN",
        }[code]
        try:
            parse_json_bytes(payload)
        except ValueError as error:
            return code if str(error).startswith(expected_error) else "QSHM_RAW_GUARD_ERROR_INVALID"
        return "QSHM_NEGATIVE_NOT_REJECTED"

    candidate = copy.deepcopy(base)
    try:
        if mutation == "remove-supersedes-null":
            del candidate["supersedesSnapshotId"]
        elif mutation == "raw-count":
            candidate["metricResults"][0]["rawCount"] = 1
        elif mutation == "top-raw-count":
            candidate["rawCount"] = 1
        elif mutation == "duplicate-formula":
            candidate["metricResults"][1] = copy.deepcopy(candidate["metricResults"][0])
        elif mutation == "unknown-formula":
            candidate["metricResults"][0]["formulaId"] = "QMDP-1.0.0/UNKNOWN"
        elif mutation == "missing-formula":
            candidate["metricResults"].pop()
        elif mutation == "extra-formula":
            extra = copy.deepcopy(candidate["metricResults"][0])
            extra["formulaId"] = "QMDP-1.0.0/EXTRA"
            candidate["metricResults"].append(extra)
        elif mutation == "duplicate-impact":
            candidate["impactScopeCodes"] = ["COVERAGE", "COVERAGE"]
        elif mutation == "uuid":
            candidate["batchId"] = "not-a-uuid"
        elif mutation == "digest":
            candidate["manifestDigest"] = "sha256:ABC"
        elif mutation == "higher-time-precision":
            candidate["cutoffAt"] = "2026-08-09T00:00:00.0000001Z"
        elif mutation == "missing-time-offset":
            candidate["cutoffAt"] = "2026-08-09T00:00:00"
        elif mutation == "zero-denominator":
            next(item for item in candidate["metricResults"] if item["applicable"])["denominator"] = 0
        elif mutation == "invalid-not-applicable":
            item = candidate["metricResults"][0]
            item.update({"applicable": False, "numerator": 1, "denominator": 0,
                         "valueBasisPoints": None, "result": "not-applicable", "reasonCode": None})
        elif mutation == "not-applicable-reason":
            item = candidate["metricResults"][0]
            item.update({"applicable": False, "numerator": 0, "denominator": 0,
                         "valueBasisPoints": None, "result": "not-applicable",
                         "reasonCode": "NOT_APPLICABLE"})
        elif mutation == "always-gate-not-applicable":
            item = next(
                result for result in candidate["metricResults"]
                if result["formulaId"] == "QMDP-1.0.0/PRIMARY_KEY_COMPLETENESS_BP"
            )
            item.update({"applicable": False, "numerator": 0, "denominator": 0,
                         "valueBasisPoints": None, "result": "not-applicable",
                         "reasonCode": None})
            candidate["overallResult"] = "quality-passed"
            candidate["assessedBatchStatus"] = "quality-passed"
        elif mutation == "composite-member-overflow":
            item = next(
                result for result in candidate["metricResults"]
                if result["formulaId"] == "QMDP-1.0.0/SOURCE_CONTINUITY_GATE"
            )
            item.update({"numerator": 2, "denominator": 1,
                         "valueBasisPoints": 20_000, "result": "failed"})
            candidate["overallResult"] = "quality-failed"
            candidate["assessedBatchStatus"] = "quality-failed"
        elif mutation == "ratio-subset-overflow":
            item = next(
                result for result in candidate["metricResults"]
                if result["formulaId"] == "QMDP-1.0.0/PRIMARY_KEY_COMPLETENESS_BP"
            )
            item.update({
                "numerator": 10_001,
                "denominator": 10_000,
                "valueBasisPoints": 10_001,
                "result": "passed",
            })
            candidate["overallResult"] = "quality-passed"
            candidate["assessedBatchStatus"] = "quality-passed"
        elif mutation == "missing-metric-null":
            del candidate["metricResults"][0]["reasonCode"]
        elif mutation == "metric-definition-drift":
            candidate["metricResults"][0]["operator"] = "<="
        elif mutation == "metric-result-drift":
            item = next(result for result in candidate["metricResults"] if result["applicable"])
            item["result"] = "passed" if item["result"] == "failed" else "failed"
        elif mutation == "count-denominator":
            item = next(result for result in candidate["metricResults"] if result["unit"] == "count")
            item["denominator"] = 2
        elif mutation == "overall-result":
            candidate["overallResult"] = "quality-passed"
            candidate["assessedBatchStatus"] = "quality-passed"
        elif mutation == "all-not-applicable":
            for item in candidate["metricResults"]:
                item.update({"applicable": False, "numerator": 0, "denominator": 0,
                             "valueBasisPoints": None, "result": "not-applicable",
                             "reasonCode": None})
        elif mutation == "profile-digest":
            candidate["hashProfileDigest"] = "sha256:" + "0" * 64
        elif mutation == "policy-digest":
            candidate["qualityMetricDecisionProfileDigest"] = "sha256:" + "1" * 64
        elif mutation == "quality-gate-digest":
            candidate["qualityGateDigest"] = "sha256:" + "2" * 64
        elif mutation == "source-schema-version":
            candidate["sourceSchemaVersion"] += ".drift"
        elif mutation == "source-schema-digest":
            candidate["sourceSchemaDigest"] = "sha256:" + "3" * 64
        elif mutation == "source-id":
            candidate["sourceId"] = "SRC-P1-OFFCAMPUS-001"
        elif mutation == "source-owner":
            candidate["sourceOwnerRef"] += " drift"
        elif mutation == "approval-ref":
            candidate["approvalRef"] = "AUTH-2026-08-09-001"
        elif mutation == "effective-at-binding":
            candidate["effectiveAt"] = "2026-08-09T10:02:23+08:00"
        elif mutation == "canonicalization-profile":
            candidate["canonicalizationProfile"] = "SCHOLARSENSE-CANONICAL-JSON-1.0.1"
        elif mutation == "retention-version":
            candidate["retentionScheduleVersion"] = "RS-1.0.1"
        elif mutation == "observation-start-higher-precision":
            candidate["observationWindow"]["startAt"] = "2026-08-08T00:00:00.0000001Z"
        elif mutation == "observation-end-higher-precision":
            candidate["observationWindow"]["endAt"] = "2026-08-09T00:00:00.0000001Z"
        elif mutation == "effective-higher-precision":
            candidate["effectiveAt"] = "2026-08-09T02:02:22.0000001Z"
        elif mutation == "trace-id":
            candidate["traceId"] = "not-a-trace"
        elif mutation == "aggregate-version":
            candidate["aggregateVersion"] = 0
        elif mutation == "threshold-numerator-boolean":
            item = next(
                result for result in candidate["metricResults"]
                if result["thresholdNumerator"] == 1
            )
            item["thresholdNumerator"] = True
        elif mutation == "threshold-denominator-boolean":
            item = next(
                result for result in candidate["metricResults"]
                if result["thresholdDenominator"] == 1
            )
            item["thresholdDenominator"] = True
        elif mutation == "self-predecessor":
            candidate["supersedesSnapshotId"] = candidate["snapshotId"]
        elif mutation == "uncontrolled-reason-code":
            candidate["metricResults"][0]["reasonCode"] = "UNCONTROLLED_REASON"
        elif mutation == "unknown-top-field":
            candidate["immutableEvidence"] = "extra"
        else:
            return "QSHM_NEGATIVE_MUTATION_UNKNOWN"
        found = material_issues(candidate, profile, policy)
        return found[0] if found else "QSHM_NEGATIVE_NOT_REJECTED"
    except (KeyError, StopIteration, TypeError):
        return "QSHM_NEGATIVE_MUTATION_INVALID"


def upstream_issues(root: Path, profile: Any | None = None) -> list[str]:
    issues: list[str] = []
    for relative, binding in UPSTREAM_BINDINGS.items():
        path = root / relative
        if not path.is_file():
            issues.append(f"QSHM_UPSTREAM_MISSING: {relative}")
            continue
        if _raw_sha256(path) != binding["rawSha256"]:
            issues.append(f"QSHM_UPSTREAM_RAW_DIGEST_MISMATCH: {relative}")
        expected_canonical = binding.get("canonicalDigest")
        if expected_canonical is not None:
            try:
                actual = _canonical_digest(load_json(path))
            except (OSError, ValueError):
                actual = None
            if actual != expected_canonical:
                issues.append(f"QSHM_UPSTREAM_CANONICAL_DIGEST_MISMATCH: {relative}")
    if isinstance(profile, dict):
        expected = [{"path": path, **binding} for path, binding in UPSTREAM_BINDINGS.items()]
        if profile.get("controlledInputs") != expected:
            issues.append("QSHM_PROFILE_UPSTREAM_BINDING_INVALID")
    return sorted(set(issues))


def lock_issues(root: Path, profile: Any | None = None) -> list[str]:
    try:
        lock = load_json(root / LOCK)
    except (OSError, ValueError):
        return ["QSHM_LOCK_INVALID"]
    if not isinstance(lock, dict):
        return ["QSHM_LOCK_INVALID"]
    issues: list[str] = []
    if _raw_sha256(root / LOCK) != APPROVED_LOCK_RAW_SHA256:
        issues.append("QSHM_LOCK_RAW_DIGEST_MISMATCH")
    digests = lock.get("digests")
    if not isinstance(digests, list) or any(not isinstance(item, dict) for item in digests):
        issues.append("QSHM_LOCK_FILE_SET_INVALID")
    else:
        digest_map = {
            item.get("path"): item.get("rawDigest")
            for item in digests
            if isinstance(item.get("path"), str)
        }
        if len(digest_map) != len(digests) or set(digest_map) != set(QSHM_DIGESTED_FILES):
            issues.append("QSHM_LOCK_FILE_SET_INVALID")
        for relative in QSHM_DIGESTED_FILES:
            path = root / relative
            if not path.is_file():
                issues.append(f"QSHM_LOCK_FILE_MISSING: {relative}")
            elif digest_map.get(relative) != "sha256:" + _raw_sha256(path):
                issues.append(f"QSHM_LOCK_RAW_DIGEST_MISMATCH: {relative}")
    expected_upstreams = [{"path": path, **binding} for path, binding in UPSTREAM_BINDINGS.items()]
    if lock.get("upstreamBindings") != expected_upstreams:
        issues.append("QSHM_LOCK_UPSTREAM_BINDING_INVALID")
    if (
        not isinstance(profile, dict)
        or lock.get("profileCanonicalDigest") != _canonical_digest(profile)
        or lock.get("profileCanonicalDigest") != APPROVED_PROFILE_CANONICAL_DIGEST
    ):
        issues.append("QSHM_LOCK_PROFILE_CANONICAL_DIGEST_MISMATCH")
    return sorted(set(issues))


def _schema_document_issues(root: Path) -> tuple[list[str], dict[Path, Any]]:
    issues: list[str] = []
    loaded: dict[Path, Any] = {}
    for path in (PROFILE_SCHEMA, MATERIAL_SCHEMA, VECTOR_SCHEMA, NEGATIVE_SCHEMA, LOCK_SCHEMA):
        try:
            document = load_json(root / path)
        except (OSError, ValueError):
            issues.append(f"QSHM_SCHEMA_INVALID: {path}")
            continue
        loaded[path] = document
        for item in schema_definition_issues(document):
            issues.append(f"QSHM_SCHEMA_INVALID: {path}: {item}")
        for item in _schema_closure_issues(document):
            issues.append(f"QSHM_SCHEMA_INVALID: {path}: {item}")
    return issues, loaded


def _schema_closure_issues(schema: Any) -> list[str]:
    """Every QSHM object shape is a closed exact record, never an open map."""
    issues: list[str] = []

    def visit(node: Any, path: str) -> None:
        if isinstance(node, bool) or not isinstance(node, dict):
            return
        if node.get("type") == "object":
            properties = node.get("properties")
            required = node.get("required")
            if node.get("additionalProperties") is not False:
                issues.append(f"QSHM_SCHEMA_OBJECT_OPEN: {path}")
            if not isinstance(properties, dict) or not isinstance(required, list) or set(required) != set(properties):
                issues.append(f"QSHM_SCHEMA_OBJECT_NOT_EXACT: {path}")
        for keyword in ("properties", "$defs"):
            children = node.get(keyword)
            if isinstance(children, dict):
                for name, child in children.items():
                    visit(child, f"{path}.{keyword}.{name}")
        items = node.get("items")
        if isinstance(items, dict):
            visit(items, f"{path}.items")
        alternatives = node.get("oneOf")
        if isinstance(alternatives, list):
            for index, child in enumerate(alternatives):
                visit(child, f"{path}.oneOf[{index}]")

    visit(schema, "$")
    return sorted(set(issues))


def validate(project_root: Path) -> list[str]:
    root = project_root.resolve()
    issues, schemas = _schema_document_issues(root)
    documents: dict[Path, Any] = {}
    for path, schema_path, invalid_code in (
        (PROFILE, PROFILE_SCHEMA, "QSHM_PROFILE_DOCUMENT_INVALID"),
        (VECTORS, VECTOR_SCHEMA, "QSHM_VECTOR_DOCUMENT_INVALID"),
        (NEGATIVE, NEGATIVE_SCHEMA, "QSHM_NEGATIVE_DOCUMENT_INVALID"),
        (LOCK, LOCK_SCHEMA, "QSHM_LOCK_INVALID"),
    ):
        try:
            document = load_json(root / path)
        except (OSError, ValueError):
            issues.append(invalid_code)
            continue
        documents[path] = document
        schema = schemas.get(schema_path)
        if schema is not None and schema_issues(document, schema):
            issues.append(invalid_code.replace("DOCUMENT_INVALID", "SCHEMA_REJECTED"))

    profile = documents.get(PROFILE)
    vectors = documents.get(VECTORS)
    try:
        policy = load_json(root / POLICY)
    except (OSError, ValueError):
        policy = None
        issues.append("QSHM_POLICY_INVALID")
    issues.extend(profile_issues(root, profile))
    issues.extend(upstream_issues(root, profile))
    if isinstance(profile, dict) and isinstance(policy, dict):
        issues.extend(vector_issues(root, profile, policy, vectors))
        issues.extend(negative_fixture_issues(root))
    issues.extend(lock_issues(root, profile))
    return sorted(set(issues))


def main(argv: list[str]) -> int:
    if len(argv) > 2:
        print("usage: check_quality_snapshot_hash_contracts.py [project-root]", file=sys.stderr)
        return 2
    root = Path(argv[1] if len(argv) == 2 else ".")
    issues = validate(root)
    if issues:
        for issue in issues:
            print(issue, file=sys.stderr)
        return 1
    print("quality snapshot hash contracts: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
