#!/usr/bin/env python3
"""Fail-closed checker for the Story 2.6a observability contract."""

from __future__ import annotations

import copy
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


BASE = Path("contracts/observability")
SCHEMA = BASE / "observability-contract.schema.json"
CONTRACT = BASE / "observability-contract-1.0.0.json"
COMPATIBILITY = BASE / "event-trace-context-compatibility-1.0.0.json"
VALID = BASE / "fixtures/valid/full-signal-chain-1.0.0.json"
INVALID = BASE / "fixtures/invalid"
PERFORMANCE_PROFILE = Path(
    "contracts/performance/performance-profile-pp-1.0.0.json")
RUNTIME_SCHEMA = Path("contracts/config/observability-runtime.schema.json")
RUNTIME_BUNDLE_SCHEMA = Path(
    "contracts/config/observability-runtime-bundle.schema.json")
RUNTIME_BUNDLE = Path(
    "contracts/config/observability-runtime-bundle-1.0.0.json")
RUNTIME_PROFILES = {
    environment: Path(
        f"contracts/config/observability-runtime-{environment}-1.0.0.json")
    for environment in ("dev", "test", "stage", "prod")
}
EVENT_TRACE_SCHEMA = Path(
    "contracts/events/ingestion-quality/data-batch-quality-trace-context.schema.json")
EVENT_TRACE_CONTRACT = Path(
    "contracts/events/ingestion-quality/data-batch-quality-trace-context-contract-2.0.0.json")
EVENT_TRACE_OUTCOMES = Path(
    "contracts/events/ingestion-quality/fixtures/ordering/"
    "data-batch-quality-trace-context-outcomes-2.0.0.json")
EVENT_TRACE_INVALID = Path(
    "contracts/events/ingestion-quality/fixtures/invalid/"
    "data-batch-quality-trace-context-trace-id-drift-2.0.0.json")

EXPECTED_INVALID = {
    "unknown-signal.json": "unknown signal",
    "unknown-field.json": "unknown field",
    "unknown-label.json": "unknown metric label",
    "unknown-value.json": "unknown value",
    "performance-profile-drift.json": "performance profile drift",
    "high-cardinality-label.json": "high cardinality metric label",
    "sensitive-value.json": "sensitive value",
    "untrusted-egress.json": "untrusted egress propagation",
}
FIELD_METADATA = {
    "name", "type", "semantics", "source", "owner", "required",
    "cardinality", "sensitivity", "allowedValues", "retention", "export",
}
FIXED_LOG_FIELDS = [
    "timestamp", "level", "service", "module", "traceId", "event", "code"]
METRIC_LABELS = [
    "service", "module", "role", "operation", "outcome", "code", "dependency", "error"]
FORBIDDEN_METRIC_LABELS = [
    "traceId", "spanId", "studentId", "subjectId", "batchId", "aggregateId",
    "url", "query",
]
TRACE_ID = re.compile(r"^[0-9a-f]{32}$")
SPAN_ID = re.compile(r"^[0-9a-f]{16}$")
TRACEPARENT = re.compile(
    r"^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")
DEFAULT_SENSITIVE_CORPUS = (
    "张三", "20260001", "证据正文-不可导出", "api-key-secret",
    "Bearer secret-token", "session=cookie-secret", "-----BEGIN CERTIFICATE-----",
)
EXPECTED_EVENT_SCENARIOS = [
    {"case": "producer", "currentVersion": 2, "incomingVersion": 3,
     "sameEventId": False, "payloadDigestMatches": True,
     "expectedAction": "commit-owner-state-and-outbox", "cursorAdvances": True},
    {"case": "consumer", "currentVersion": 2, "incomingVersion": 3,
     "sameEventId": False, "payloadDigestMatches": True,
     "expectedAction": "apply-in-owner-transaction", "cursorAdvances": True},
    {"case": "duplicate", "currentVersion": 3, "incomingVersion": 3,
     "sameEventId": True, "payloadDigestMatches": True,
     "expectedAction": "acknowledge-noop", "cursorAdvances": False},
    {"case": "gap", "currentVersion": 2, "incomingVersion": 4,
     "sameEventId": False, "payloadDigestMatches": True,
     "expectedAction": "pause-and-backfill-version-3", "cursorAdvances": False},
    {"case": "poison", "currentVersion": 2, "incomingVersion": 3,
     "sameEventId": False, "payloadDigestMatches": True,
     "expectedAction": "quarantine-without-business-mutation", "cursorAdvances": False},
    {"case": "replay", "currentVersion": 2, "incomingVersion": 3,
     "sameEventId": False, "payloadDigestMatches": True,
     "expectedAction": "apply-and-resume", "cursorAdvances": True},
    {"case": "aggregateVersion-conflict", "currentVersion": 3,
     "incomingVersion": 3, "sameEventId": True, "payloadDigestMatches": False,
     "expectedAction": "stable-idempotency-conflict", "cursorAdvances": False},
]


def load(root: Path, relative: Path) -> Any:
    return json.loads((root / relative).read_text(encoding="utf-8"))


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def self_digest(contract: dict[str, Any]) -> str:
    material = copy.deepcopy(contract)
    material.pop("selfDigest", None)
    return "sha256:" + hashlib.sha256(canonical_bytes(material)).hexdigest()


def content_digest(document: dict[str, Any], field: str) -> str:
    material = copy.deepcopy(document)
    material.pop(field, None)
    return "sha256:" + hashlib.sha256(canonical_bytes(material)).hexdigest()


def _walk_strings(value: Any):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for nested in value.values():
            yield from _walk_strings(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from _walk_strings(nested)


def scan_sensitive_values(
        value: Any, corpus: tuple[str, ...] = DEFAULT_SENSITIVE_CORPUS) -> list[str]:
    matches: list[str] = []
    for candidate in _walk_strings(value):
        for denied in corpus:
            if denied in candidate and denied not in matches:
                matches.append(denied)
    return matches


def _field_index(contract: dict[str, Any], signal: str) -> dict[str, dict[str, Any]]:
    return {
        field["name"]: field
        for field in contract.get("signals", {}).get(signal, {}).get("fields", [])
        if isinstance(field, dict) and isinstance(field.get("name"), str)
    }


def _complete_metadata(fields: Any) -> bool:
    if not isinstance(fields, list) or not fields:
        return False
    names = [field.get("name") for field in fields if isinstance(field, dict)]
    return len(names) == len(fields) == len(set(names)) \
        and all(set(field) == FIELD_METADATA and bool(field.get("source"))
                for field in fields)


def check_fixture(contract: dict[str, Any], fixture: dict[str, Any]) -> list[str]:
    issues: list[str] = []
    candidate = fixture.get("candidate", {})
    signal = candidate.get("signal")
    allowed_signals = {"log", "metric", "span", "event"}
    if signal is not None and signal not in allowed_signals:
        issues.append("unknown signal")
        return issues

    if candidate.get("performanceProfileVersion") not in (
            None, contract.get("performanceProfile", {}).get("version")):
        issues.append("performance profile drift")

    if signal in allowed_signals:
        index = _field_index(contract, signal)
        allowed_fields = set(index)
        if signal == "log":
            allowed_fields.update(
                contract["signals"]["log"].get("additionalFieldAllowlist", []))
        for field in candidate.get("fields", []):
            if field not in allowed_fields:
                issues.append("unknown field")

        labels = candidate.get("labels", {})
        if signal == "metric" and isinstance(labels, dict):
            allowed_labels = set(contract["signals"]["metric"].get("allowedLabels", []))
            forbidden = set(contract["signals"]["metric"].get("forbiddenLabels", []))
            for label in labels:
                if label in forbidden:
                    issues.append("high cardinality metric label")
                elif label not in allowed_labels:
                    issues.append("unknown metric label")

        for name, value in candidate.get("values", {}).items():
            field = index.get(name)
            if field and field.get("allowedValues") and value not in field["allowedValues"]:
                issues.append("unknown value")

    corpus = tuple(
        contract.get("signals", {}).get("privacy", {}).get(
            "deniedValueCorpus", DEFAULT_SENSITIVE_CORPUS))
    if scan_sensitive_values(candidate, corpus):
        issues.append("sensitive value")

    egress = contract.get("signals", {}).get("trustBoundary", {}).get("egress", {})
    if candidate.get("injectTraceparent") is True:
        if (not isinstance(egress, dict)
                or candidate.get("targetTrust") != egress.get("requiredEvidence")):
            issues.append("untrusted egress propagation")
    return issues


def _valid_trace_id(value: Any) -> bool:
    return isinstance(value, str) and TRACE_ID.fullmatch(value) is not None \
        and value != "0" * 32


def _valid_span_id(value: Any) -> bool:
    return isinstance(value, str) and SPAN_ID.fullmatch(value) is not None \
        and value != "0" * 16


def _event_traceparent_matches(trace_id: Any, traceparent: Any) -> bool:
    match = TRACEPARENT.fullmatch(traceparent or "")
    return _valid_trace_id(trace_id) and match is not None \
        and match.group(1) == trace_id and _valid_span_id(match.group(2)) \
        and match.group(3) in {"00", "01"}


def _check_event_trace_successor(root: Path) -> list[str]:
    issues: list[str] = []
    schema = load(root, EVENT_TRACE_SCHEMA)
    contract = load(root, EVENT_TRACE_CONTRACT)
    outcomes = load(root, EVENT_TRACE_OUTCOMES)
    invalid = load(root, EVENT_TRACE_INVALID)
    required_scenarios = [
        "producer", "consumer", "duplicate", "gap", "poison", "replay",
        "aggregateVersion-conflict",
    ]
    if (schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema"
            or schema.get("type") != "object"
            or schema.get("additionalProperties") is not False
            or set(schema.get("required", [])) != set(schema.get("properties", {}))):
        issues.append("closed additive event trace-context schema")
    if (set(contract) != set(schema.get("required", []))
            or contract.get("contractVersion") != "DATA-BATCH-QUALITY-TRACE-2.0.0"
            or contract.get("predecessor", {}).get("mutationPolicy") != "read-only"
            or contract.get("successor") != {
                "qualityAssessedType":
                    "scholarsense.ingestion-quality.data-batch.quality-assessed.v2",
                "qualityAssessedSchema": "DATA-BATCH-QUALITY-ASSESSED-2.0.0",
                "publishedType":
                    "scholarsense.ingestion-quality.data-batch.published.v2",
                "publishedSchema": "DATA-BATCH-PUBLISHED-2.0.0",
                "readerCutover": "dual-read-v1-v2-before-producer-cutover",
                "producerCutover": "single-v2-event-no-dual-publish",
            }
            or contract.get("traceContext") != {
                "wireField": "traceparent",
                "format": "00-{persisted-trace-id}-{non-zero-producer-span-id}-{00|01}",
                "producer": "real-w3c-child-context",
                "reader": "same-trace-id-any-valid-non-zero-parent-span",
                "invalid": "reject-before-owner-transaction",
            }
            or contract.get("processing", {}).get("latestStateLookup") != "forbidden"
            or contract.get("processing", {}).get("ownerTransaction")
                != "inbox-cursor-business-state-and-derived-outbox-atomic"
            or contract.get("requiredScenarios") != required_scenarios):
        issues.append("additive event trace-context successor semantics")
    scenarios = outcomes.get("scenarios", [])
    if (outcomes.get("contractVersion") != contract.get("contractVersion")
            or outcomes.get("ownerTransaction")
                != contract.get("processing", {}).get("ownerTransaction")
            or outcomes.get("latestStateLookup") is not False
            or [entry.get("case") for entry in scenarios] != required_scenarios
            or any(set(entry) != {
                "case", "currentVersion", "incomingVersion", "sameEventId",
                "payloadDigestMatches", "expectedAction", "cursorAdvances",
            } for entry in scenarios)
            or scenarios != EXPECTED_EVENT_SCENARIOS
            or not _event_traceparent_matches(
                outcomes.get("traceId"), outcomes.get("producerTraceparent"))
            or outcomes.get("consumerParentTraceparent")
                != outcomes.get("producerTraceparent")):
        issues.append("complete event ordering idempotency and trace fixture")
    if (_event_traceparent_matches(
            invalid.get("traceId"), invalid.get("producerTraceparent"))
            or invalid.get("expectedIssue")
                != "producer traceparent trace-id drift"):
        issues.append("event trace-id drift fixture rejected")
    return issues


def _check_valid_chain(contract: dict[str, Any], fixture: dict[str, Any]) -> list[str]:
    issues: list[str] = []
    signals = fixture.get("signals", [])
    if fixture.get("caseId") != "full-signal-chain" or len(signals) != 7:
        issues.append("complete representative signal chain")
        return issues
    trace_ids = {entry.get("traceId") for entry in signals}
    if len(trace_ids) != 1 or not _valid_trace_id(next(iter(trace_ids), None)):
        issues.append("single valid non-zero trace id")
    span_ids = [
        entry.get("spanId") for entry in signals if entry.get("signal") == "span"]
    if any(not _valid_span_id(span_id) for span_id in span_ids) \
            or len(span_ids) != len(set(span_ids)):
        issues.append("distinct valid non-zero span ids")
    required_names = {
        "http.server", "job.attempt", "batch.evaluate", "outbox.publish",
        "data-batch.quality-assessed", "event.consume", "http.client",
    }
    if {entry.get("name") for entry in signals} != required_names:
        issues.append("http job batch event consumer and external coverage")
    event = next((entry for entry in signals if entry.get("signal") == "event"), {})
    traceparent = event.get("values", {}).get("traceparent")
    match = TRACEPARENT.fullmatch(traceparent or "")
    if not match or match.group(1) != event.get("traceId") \
            or match.group(2) != event.get("spanId"):
        issues.append("event real W3C producer context")
    if scan_sensitive_values(
            fixture,
            tuple(contract["signals"]["privacy"]["deniedValueCorpus"])):
        issues.append("valid fixture sensitive value")
    return issues


def check(root: Path) -> list[str]:
    issues: list[str] = []
    required = [
        SCHEMA, CONTRACT, COMPATIBILITY, VALID, PERFORMANCE_PROFILE, RUNTIME_SCHEMA,
        *RUNTIME_PROFILES.values(), EVENT_TRACE_SCHEMA, EVENT_TRACE_CONTRACT,
        EVENT_TRACE_OUTCOMES, EVENT_TRACE_INVALID,
    ]
    required.extend(INVALID / filename for filename in EXPECTED_INVALID)
    missing = [str(path) for path in required if not (root / path).is_file()]
    if missing:
        return [f"missing {path}" for path in missing]

    schema = load(root, SCHEMA)
    contract = load(root, CONTRACT)
    if (schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema"
            or schema.get("type") != "object"
            or schema.get("additionalProperties") is not False
            or not set(schema.get("required", [])).issubset(
                set(schema.get("properties", {})))
            or set(schema.get("$defs", {}).get("field", {}).get("required", []))
                != FIELD_METADATA):
        issues.append("closed machine-checkable schema")

    expected_top = {
        "$schema", "schemaVersion", "contractVersion", "owner", "performanceProfile",
        "signals", "traceSemantics", "propagation", "sampling", "compatibility",
        "runtimeEvidence", "unresolvedSemantics", "selfDigest",
    }
    if set(contract) != expected_top or contract.get("contractVersion") != "OBS-1.0.0" \
            or contract.get("owner") != "platform-observability" \
            or contract.get("unresolvedSemantics") != []:
        issues.append("closed approved observability contract")
    if contract.get("selfDigest") != self_digest(contract):
        issues.append("observability self digest")

    profile = load(root, PERFORMANCE_PROFILE)
    profile_digest = content_digest(profile, "contentSha256")
    binding = contract.get("performanceProfile", {})
    if (profile.get("performanceProfileVersion") != "PP-1.0.0"
            or profile.get("contentSha256") != profile_digest.removeprefix("sha256:")
            or binding != {
                "path": str(PERFORMANCE_PROFILE),
                "version": "PP-1.0.0",
                "contentDigest": profile_digest,
            }):
        issues.append("performance profile drift")

    signals = contract.get("signals", {})
    if set(signals) != {"log", "metric", "span", "event", "privacy", "trustBoundary"}:
        issues.append("six-part signal dictionary")
    for signal in ("log", "metric", "span", "event"):
        fields = signals.get(signal, {}).get("fields", [])
        if not _complete_metadata(fields):
            issues.append(f"complete {signal} field metadata")
    log = signals.get("log", {})
    log_additional = log.get("additionalFields", [])
    if log.get("fixedFields") != FIXED_LOG_FIELDS:
        issues.append("exact structured log field order")
    if (not _complete_metadata(log_additional)
            or log.get("additionalFieldAllowlist")
                != [field.get("name") for field in log_additional]):
        issues.append("complete additional log field metadata")
    metric = signals.get("metric", {})
    metric_labels = metric.get("labels", [])
    if metric.get("allowedLabels") != METRIC_LABELS \
            or metric.get("forbiddenLabels") != FORBIDDEN_METRIC_LABELS \
            or metric.get("correlation") != "exemplar-or-trace-link-only":
        issues.append("metric label allowlist and exemplar correlation")
    if (not _complete_metadata(metric_labels)
            or metric.get("allowedLabels")
                != [field.get("name") for field in metric_labels]
            or any(field.get("cardinality") not in {"constant", "low"}
                   for field in metric_labels)
            or any(not field.get("allowedValues") for field in metric_labels)):
        issues.append("complete low-cardinality metric label metadata")
    span = signals.get("span", {})
    span_attributes = span.get("attributes", [])
    if (not _complete_metadata(span_attributes)
            or span.get("lowCardinalityAttributes") != [
                field.get("name") for field in span_attributes
                if field.get("cardinality") == "low"]
            or span.get("highCardinalityAttributes") != [
                field.get("name") for field in span_attributes
                if field.get("cardinality") == "high"]):
        issues.append("complete span attribute metadata")
    privacy = signals.get("privacy", {})
    if (privacy.get("failureMode") != "fail-closed"
            or set(privacy.get("scanScope", [])) != {
                "log", "span-export", "metric-export", "metric-scrape", "event-fixture"}
            or not {"requestBody", "query", "Authorization", "Cookie", "studentId",
                    "studentName", "evidenceText", "token", "secret", "certificate",
                    "baggage"}.issubset(set(privacy.get("deniedFieldNames", [])))):
        issues.append("privacy denylist and value scan scope")
    trust_boundary = signals.get("trustBoundary", {})
    if trust_boundary.get("egress") != {
            "profileVersion": "APPROVED-AUTHORITY-EGRESS-1.0.0",
            "source": "versioned-runtime-authority-profiles",
            "decision": "exact-configured-origin",
            "requiredEvidence": "approved-versioned-authority-profile",
            "productionProtocol": "https-only",
            "developmentSandbox": "explicit-loopback-origin-only",
            "placeholderProductionEndpoint": "reject",
    }:
        issues.append("profile-derived egress trust boundary")

    semantics = contract.get("traceSemantics", {})
    propagation = contract.get("propagation", {})
    if semantics != {
            "objectCreationTraceId": "immutable-creation-context",
            "traceId": "current-operation-context",
            "traceparent": "w3c-parent-context",
            "spanLink": "causal-link-not-parent-rewrite",
            "historyPolicy": "never-rewrite-hash-participating-snapshot",
    }:
        issues.append("creation operation parent and link semantics")
    if (propagation.get("invalidOrAllZero") != "clean-root"
            or propagation.get("untrustedIngress") != "clean-root"
            or propagation.get("trustedEgress") != "allowlist-only"
            or propagation.get("baggage") != "forbidden"
            or propagation.get("legacyHeader") != "dual-write-through-OBS-1.x"
            or contract.get("sampling") != {
                "production": "parent-based-ratio-from-versioned-config",
                "conformance": "always-on",
                "unsampled": "propagate-valid-context",
            }):
        issues.append("W3C trust sampling baggage and legacy propagation")

    compatibility = load(root, COMPATIBILITY)
    if (compatibility.get("evolution") != "additive-successor"
            or compatibility.get("predecessorFixtures") != "read-only"
            or compatibility.get("successorReader")
                != "same-trace-id-any-valid-parent-span"
            or compatibility.get("successorIdentity") != {
                "qualityAssessed":
                    "scholarsense.ingestion-quality.data-batch.quality-assessed.v2|DATA-BATCH-QUALITY-ASSESSED-2.0.0",
                "published":
                    "scholarsense.ingestion-quality.data-batch.published.v2|DATA-BATCH-PUBLISHED-2.0.0",
            }
            or compatibility.get("cutover")
                != "reader-first-then-single-v2-publish"
            or compatibility.get("immutableSnapshot")
                != "creation-trace-never-rewritten"):
        issues.append("additive event trace compatibility matrix")

    issues.extend(_check_valid_chain(contract, load(root, VALID)))
    actual_invalid = {
        path.name for path in (root / INVALID).glob("*.json") if path.is_file()}
    if actual_invalid != set(EXPECTED_INVALID):
        issues.append("complete class-specific invalid fixture matrix")
    for filename, expected in EXPECTED_INVALID.items():
        fixture = load(root, INVALID / filename)
        if fixture.get("expectedIssue") != expected \
                or expected not in check_fixture(contract, fixture):
            issues.append(f"invalid fixture not rejected: {filename}")

    if contract.get("runtimeEvidence") != {
            "story26aFoundation": "contract-and-local-conformance",
            "story26bRoleDashboardAndFinalPrivacy": "none",
            "story27DegradationAndRetry": "none",
            "story28NaturalMonthSliAndRecovery": "none",
            "productionDuration": "none",
    }:
        issues.append("observability runtime evidence boundary")

    runtime_schema = load(root, RUNTIME_SCHEMA)
    if (runtime_schema.get("$schema")
            != "https://json-schema.org/draft/2020-12/schema"
            or runtime_schema.get("type") != "object"
            or runtime_schema.get("additionalProperties") is not False
            or runtime_schema.get("properties", {}).get("profileVersion", {}).get("const")
                != "OBS-RUNTIME-1.0.0"):
        issues.append("closed versioned observability runtime schema")
    fixed_fields = [
        "timestamp", "level", "service", "module", "traceId", "event", "code"]
    for environment, path in RUNTIME_PROFILES.items():
        profile = load(root, path)
        otlp = profile.get("otlp", {})
        sampling = profile.get("sampling", {})
        resource = profile.get("resource", {})
        ingress = profile.get("trustedIngress", {})
        expected_export = environment in {"stage", "prod"}
        if (profile.get("profileVersion") != "OBS-RUNTIME-1.0.0"
                or profile.get("environment") != environment
                or otlp.get("enabled") is not expected_export
                or otlp.get("protocol") != "http/protobuf"
                or not 100 <= otlp.get("timeoutMs", 0) <= 10_000
                or not 128 <= otlp.get("maxQueueSize", 0) <= 8_192
                or sampling.get("sampler") != "parent-based-trace-id-ratio"
                or not 0 <= sampling.get("probability", -1) <= 1
                or resource.get("service") != "scholarsense"
                or resource.get("module") != profile.get("role")
                or ingress.get("profileVersion") != "TRUSTED-INGRESS-1.0.0"
                or ingress.get("proxyIdentity") != f"portal-proxy-{environment}-v1"
                or ingress.get("proxyClearsPropagationHeaders") is not True
                or not ingress.get("socketSources")
                or profile.get("structuredLogging", {}).get("fields") != fixed_fields
                or profile.get("baggage") != "forbidden"):
            issues.append(f"controlled observability runtime profile: {environment}")
        if expected_export:
            if (otlp.get("endpoint")
                    != f"https://otel.{environment}.scholarsense.suda.edu.cn/v1/traces"
                    or otlp.get("metricsEndpoint")
                    != f"https://otel.{environment}.scholarsense.suda.edu.cn/v1/metrics"
                    or profile.get("runtimeEvidenceClaim") != "none"):
                issues.append(f"production-like exporter boundary: {environment}")
        elif "endpoint" in otlp:
            issues.append(f"non-production exporter disabled: {environment}")
    test_profile = load(root, RUNTIME_PROFILES["test"])
    if (test_profile.get("sampling", {}).get("probability") != 1.0
            or test_profile.get("runtimeEvidenceClaim") != "conformance-only"):
        issues.append("test-only deterministic sampling boundary")
    runtime_bundle_schema = load(root, RUNTIME_BUNDLE_SCHEMA)
    runtime_bundle = load(root, RUNTIME_BUNDLE)
    if (runtime_bundle_schema.get("properties", {}).get("version", {}).get("const")
            != "OBSERVABILITY-RUNTIME-BUNDLE-1.0.0"
            or runtime_bundle.get("version")
            != "OBSERVABILITY-RUNTIME-BUNDLE-1.0.0"
            or runtime_bundle.get("contract", {}).get("version") != "OBS-1.0.0"
            or runtime_bundle.get("supportedRoles") != ["web-api", "worker"]
            or runtime_bundle.get("exportPolicy") != {
                "businessOutcomeAuthority": "forbidden",
                "failureMode": "drop-telemetry-keep-business-truth",
                "maxQueueSize": 2048,
                "networkInsideOwnerTransaction": False,
            }):
        issues.append("versioned observability runtime bundle")
    else:
        contract_bytes = (root / CONTRACT).read_bytes()
        if runtime_bundle["contract"].get("binarySha256") \
                != hashlib.sha256(contract_bytes).hexdigest():
            issues.append("runtime bundle observability contract digest")
        references = runtime_bundle.get("profiles", [])
        if [item.get("environment") for item in references] \
                != ["dev", "test", "stage", "prod"]:
            issues.append("runtime bundle profile order")
        for reference in references:
            environment = reference.get("environment")
            path = RUNTIME_PROFILES.get(environment)
            if path is None:
                issues.append("runtime bundle unknown profile")
                continue
            profile_bytes = (root / path).read_bytes()
            profile = load(root, path)
            if (reference.get("path") != path.as_posix()
                    or reference.get("version") != "OBS-RUNTIME-1.0.0"
                    or reference.get("role") != profile.get("role")
                    or reference.get("binarySha256")
                    != hashlib.sha256(profile_bytes).hexdigest()):
                issues.append(f"runtime bundle profile digest: {environment}")
    issues.extend(_check_event_trace_successor(root))
    return issues


def main() -> int:
    issues = check(Path("."))
    if issues:
        for issue in issues:
            print(f"OBSERVABILITY_CONTRACT: FAIL: {issue}")
        return 1
    print("OBSERVABILITY_CONTRACT: field dictionary, privacy, trust and W3C evolution valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
