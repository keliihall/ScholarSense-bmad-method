#!/usr/bin/env python3
"""Validate the Story 1.3 audit v1 contract, fixtures, semantics, and lock."""

from __future__ import annotations

import copy
import hashlib
import re
import sys
from pathlib import Path
from typing import Any
from datetime import datetime


sys.path.insert(0, str(Path(__file__).resolve().parent))

from release_json import load_json, schema_definition_issues, schema_issues  # noqa: E402


AUDIT = Path("contracts/audit")
LOCK = AUDIT / "audit-contract-lock-1.0.0.json"
SCHEMAS = {
    AUDIT / "action-catalog-1.0.0.json": AUDIT / "action-catalog.schema.json",
    AUDIT / "identity-access-vocabulary-1.0.0.json": AUDIT / "identity-access-vocabulary.schema.json",
    AUDIT / "trusted-clock-runtime-binding-1.0.0.json": AUDIT / "trusted-clock-runtime-binding.schema.json",
    AUDIT / "fixtures/valid/local-audit-fact.json": AUDIT / "local-audit-fact.schema.json",
    AUDIT / "fixtures/valid/local-audit-outbox.json": AUDIT / "local-audit-outbox.schema.json",
}
UUID_V7 = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
SEARCH_TOKEN = re.compile(r"^(?:ast|ost|ipt|agt|gst)_v1_k[0-9]+_[0-9a-f]{64}$")
UTC_TIME = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$")


def validate(project_root: Path) -> list[str]:
    root = project_root.resolve()
    issues: list[str] = []
    loaded_schemas: dict[Path, Any] = {}
    for schema_path in sorted(set(SCHEMAS.values())):
        try:
            schema = load_json(root / schema_path)
        except (OSError, ValueError):
            issues.append(f"AUDIT_SCHEMA_INVALID: {schema_path}")
            continue
        loaded_schemas[schema_path] = schema
        issues.extend(
            f"AUDIT_SCHEMA_INVALID: {schema_path}: {issue}"
            for issue in schema_definition_issues(schema)
        )

    documents: dict[Path, Any] = {}
    for instance_path, schema_path in SCHEMAS.items():
        try:
            instance = load_json(root / instance_path)
        except (OSError, ValueError):
            issues.append(f"AUDIT_CONTRACT_DOCUMENT_INVALID: {instance_path}")
            continue
        documents[instance_path] = instance
        schema = loaded_schemas.get(schema_path)
        if schema is not None:
            found = schema_issues(instance, schema)
            if found:
                issues.append(f"AUDIT_FIXTURE_SCHEMA_REJECTED: {instance_path}: {found[0]}")

    catalog = documents.get(AUDIT / "action-catalog-1.0.0.json")
    vocabulary = documents.get(AUDIT / "identity-access-vocabulary-1.0.0.json")
    fact = documents.get(AUDIT / "fixtures/valid/local-audit-fact.json")
    outbox = documents.get(AUDIT / "fixtures/valid/local-audit-outbox.json")
    clock_binding = documents.get(AUDIT / "trusted-clock-runtime-binding-1.0.0.json")
    issues.extend(_catalog_issues(catalog))
    issues.extend(_outbox_schema_issues(
        loaded_schemas.get(AUDIT / "local-audit-outbox.schema.json"),
        loaded_schemas.get(AUDIT / "local-audit-fact.schema.json")))
    issues.extend(_fact_issues(fact, catalog, vocabulary))
    issues.extend(_outbox_issues(outbox, fact))
    issues.extend(_clock_binding_issues(root, clock_binding))
    issues.extend(_field_profile_issues(root, loaded_schemas.get(AUDIT / "local-audit-fact.schema.json")))
    issues.extend(_storage_semantics_issues(root))
    issues.extend(_negative_fixture_issues(
        root, loaded_schemas.get(AUDIT / "local-audit-fact.schema.json"), catalog, vocabulary))
    issues.extend(_lock_issues(root))
    return sorted(set(issues))


def _catalog_issues(catalog: Any) -> list[str]:
    if not isinstance(catalog, dict) or not isinstance(catalog.get("actions"), list):
        return ["AUDIT_ACTION_CATALOG_INVALID"]
    actions = [entry for entry in catalog["actions"] if isinstance(entry, dict)]
    codes = [entry.get("code") for entry in actions]
    issues: list[str] = []
    if len(actions) != len(catalog["actions"]) or len(codes) != len(set(codes)):
        issues.append("AUDIT_ACTION_CATALOG_INVALID")
    reasons = set(catalog.get("reasonCodes", []))
    outcomes = set(catalog.get("outcomes", []))
    for entry in actions:
        if not set(entry.get("allowedReasonCodes", [])).issubset(reasons):
            issues.append(f"AUDIT_ACTION_REASON_UNREGISTERED: {entry.get('code')}")
        if not set(entry.get("allowedOutcomes", [])).issubset(outcomes):
            issues.append(f"AUDIT_ACTION_OUTCOME_UNREGISTERED: {entry.get('code')}")
        results = entry.get("allowedResults", [])
        result_pairs = {
            (result.get("outcome"), result.get("reasonCode"))
            for result in results if isinstance(result, dict)
        }
        if len(result_pairs) != len(results) \
                or {pair[0] for pair in result_pairs} != set(entry.get("allowedOutcomes", [])) \
                or {pair[1] for pair in result_pairs} != set(entry.get("allowedReasonCodes", [])):
            issues.append(f"AUDIT_ACTION_RESULT_RULE_INVALID: {entry.get('code')}")
        if entry.get("status") == "active" and entry.get("ownerModule") != "identity-access":
            issues.append(f"AUDIT_ACTION_PREMATURELY_ACTIVE: {entry.get('code')}")
    return issues


def _fact_issues(fact: Any, catalog: Any, vocabulary: Any) -> list[str]:
    if not isinstance(fact, dict):
        return ["AUDIT_FACT_INVALID"]
    issues: list[str] = []
    if not UUID_V7.fullmatch(str(fact.get("auditId", ""))):
        issues.append("AUDIT_ID_NOT_UUID_V7")
    time_fields = [fact.get("occurredAt"), fact.get("recordedAt")]
    time_source = fact.get("timeSourceProfile")
    if isinstance(time_source, dict):
        time_fields.extend([time_source.get("observedAt"), time_source.get("freshUntil")])
    if any(not isinstance(value, str) or not UTC_TIME.fullmatch(value) for value in time_fields):
        issues.append("AUDIT_TIME_NOT_UTC")
    if isinstance(time_source, dict) and all(isinstance(value, str) for value in time_fields):
        occurred, recorded, observed, fresh = (_utc_instant(value) for value in time_fields)
        if None in (occurred, recorded, observed, fresh) \
                or not observed <= occurred <= recorded or not occurred < fresh:
            issues.append("AUDIT_TIME_WINDOW_INVALID")
    catalog_actions = {
        entry.get("code"): entry
        for entry in (catalog or {}).get("actions", [])
        if isinstance(entry, dict)
    }
    action = catalog_actions.get(fact.get("action"))
    if action is None or action.get("status") != "active":
        issues.append("AUDIT_ACTION_UNREGISTERED")
    else:
        if fact.get("outcome") not in action.get("allowedOutcomes", []):
            issues.append("AUDIT_OUTCOME_UNREGISTERED")
        if fact.get("reasonCode") not in action.get("allowedReasonCodes", []):
            issues.append("AUDIT_REASON_UNREGISTERED")
        allowed_results = {
            (entry.get("outcome"), entry.get("reasonCode"))
            for entry in action.get("allowedResults", []) if isinstance(entry, dict)
        }
        if (fact.get("outcome"), fact.get("reasonCode")) not in allowed_results:
            issues.append("AUDIT_RESULT_COMBINATION_UNREGISTERED")
    if fact.get("outcome") not in (catalog or {}).get("outcomes", []):
        issues.append("AUDIT_OUTCOME_UNREGISTERED")
    if fact.get("reasonCode") not in (catalog or {}).get("reasonCodes", []):
        issues.append("AUDIT_REASON_UNREGISTERED")

    for field in ("actorSearchToken", "objectSearchToken", "sourceIpSearchToken", "aggregateIdSearchToken"):
        value = fact.get(field)
        if value is not None and (not isinstance(value, str) or not SEARCH_TOKEN.fullmatch(value)):
            issues.append(f"AUDIT_SEARCH_TOKEN_INVALID: {field}")
    authorization = fact.get("authorizationContext")
    if isinstance(authorization, dict):
        for value in authorization.get("grantSearchTokens", []):
            if not isinstance(value, str) or not SEARCH_TOKEN.fullmatch(value):
                issues.append("AUDIT_SEARCH_TOKEN_INVALID: authorizationContext.grantSearchTokens")
        decision = authorization.get("decision")
        not_applicable = authorization.get("notApplicableReason")
        if (decision == "not-applicable") != (not_applicable is not None):
            issues.append("AUDIT_AUTHORIZATION_CONTEXT_INCOMPLETE")
        if decision in {"allow", "deny"} and authorization.get("policyVersion") is None:
            issues.append("AUDIT_AUTHORIZATION_CONTEXT_INCOMPLETE")
        if decision == "not-applicable" and (
                authorization.get("policyVersion") is not None
                or authorization.get("scopeCodes") != []
                or authorization.get("grantSearchTokens") != []):
            issues.append("AUDIT_AUTHORIZATION_CONTEXT_INCOMPLETE")
    if fact.get("actorType") == "anonymous":
        if fact.get("actorSearchToken") is not None or fact.get("roleIds") != [] \
                or fact.get("objectType") is not None or fact.get("objectSearchToken") is not None \
                or fact.get("aggregateType") is not None or fact.get("aggregateIdSearchToken") is not None \
                or isinstance(authorization, dict) and authorization.get("grantSearchTokens") != []:
            issues.append("AUDIT_ANONYMOUS_CONTEXT_FORGED")
        if not isinstance(authorization, dict) or authorization.get("decision") != "not-applicable":
            issues.append("AUDIT_ANONYMOUS_CONTEXT_FORGED")
        elif authorization.get("policyVersion") is not None \
                or authorization.get("scopeCodes") != [] \
                or authorization.get("grantSearchTokens") != []:
            issues.append("AUDIT_ANONYMOUS_CONTEXT_FORGED")
    if fact.get("actorType") == "user" and fact.get("actorSearchToken") is None:
        issues.append("AUDIT_ACTOR_CONTEXT_INCOMPLETE")
    issues.extend(_identity_vocabulary_issues(fact, vocabulary))
    if _contains_sensitive_field(fact):
        issues.append("AUDIT_SENSITIVE_FIELD_FORBIDDEN")
    return issues


def _outbox_issues(outbox: Any, fact: Any) -> list[str]:
    if not isinstance(outbox, dict) or not isinstance(fact, dict):
        return ["AUDIT_OUTBOX_INVALID"]
    issues: list[str] = []
    envelope = outbox.get("envelope")
    if not UUID_V7.fullmatch(str(outbox.get("eventId", ""))):
        issues.append("AUDIT_EVENT_ID_NOT_UUID_V7")
    if outbox.get("auditId") != fact.get("auditId"):
        issues.append("AUDIT_OUTBOX_FACT_MISMATCH")
    producer = fact.get("producerModule")
    expected_type = f"{producer}.local-audit-fact.recorded.v1"
    if outbox.get("producer") != producer or outbox.get("eventType") != expected_type:
        issues.append("AUDIT_OUTBOX_PRODUCER_MISMATCH")
    if isinstance(envelope, dict):
        if envelope.get("id") != outbox.get("eventId") or envelope.get("time") != outbox.get("createdAt"):
            issues.append("AUDIT_OUTBOX_ENVELOPE_MISMATCH")
        if envelope.get("subject") != f"audit/{outbox.get('auditId')}":
            issues.append("AUDIT_OUTBOX_ENVELOPE_MISMATCH")
        if envelope.get("source") != f"urn:scholarsense:{producer}" or envelope.get("type") != expected_type:
            issues.append("AUDIT_OUTBOX_PRODUCER_MISMATCH")
        data = envelope.get("data")
        if not isinstance(data, dict) or data != fact:
            issues.append("AUDIT_OUTBOX_FACT_MISMATCH")
    if any(not UTC_TIME.fullmatch(str(value)) for value in (outbox.get("createdAt"), (envelope or {}).get("time"))):
        issues.append("AUDIT_TIME_NOT_UTC")
    return issues


def _outbox_schema_issues(outbox_schema: Any, fact_schema: Any) -> list[str]:
    if not isinstance(outbox_schema, dict) or not isinstance(fact_schema, dict):
        return ["AUDIT_OUTBOX_FACT_SCHEMA_MISMATCH"]
    embedded = outbox_schema.get("$defs", {}).get("localAuditFact")
    return [] if embedded == fact_schema else ["AUDIT_OUTBOX_FACT_SCHEMA_MISMATCH"]


def _clock_binding_issues(root: Path, binding: Any) -> list[str]:
    if not isinstance(binding, dict):
        return ["AUDIT_CLOCK_BINDING_INVALID"]
    issues: list[str] = []
    if "maximumSkewMs" in binding or _has_key(binding, "maximumSkewMs"):
        issues.append("AUDIT_CLOCK_SKEW_DUPLICATED")
    try:
        performance = load_json(root / "contracts/performance/performance-profile-pp-1.0.0.json", legacy_numbers=True)
        clock = performance["clock"]
    except (OSError, ValueError, KeyError, TypeError):
        return issues + ["AUDIT_CLOCK_SINGLE_SOURCE_INVALID"]
    if clock != {"source": "server-synchronized-ntp", "maximumSkewMs": 100}:
        issues.append("AUDIT_CLOCK_SINGLE_SOURCE_INVALID")
    if binding.get("performanceProfileVersion") != performance.get("performanceProfileVersion"):
        issues.append("AUDIT_CLOCK_PROFILE_VERSION_MISMATCH")
    return issues


def _field_profile_issues(root: Path, fact_schema: Any) -> list[str]:
    try:
        profile = load_json(root / AUDIT / "local-audit-field-profile-1.0.0.json")
        rules = profile["rules"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["AUDIT_FIELD_PROFILE_INVALID"]
    if not isinstance(fact_schema, dict) or not isinstance(rules, list):
        return ["AUDIT_FIELD_PROFILE_INVALID"]
    properties = fact_schema.get("properties", {})
    required = set(fact_schema.get("required", []))
    by_field = {entry.get("field"): entry for entry in rules if isinstance(entry, dict)}
    issues: list[str] = []
    if set(by_field) != set(properties) or len(by_field) != len(rules):
        issues.append("AUDIT_FIELD_PROFILE_SCOPE_MISMATCH")
    for field, schema in properties.items():
        rule = by_field.get(field, {})
        nullable = isinstance(schema.get("type"), list) and "null" in schema["type"]
        if rule.get("required") != (field in required) or rule.get("nullable") != nullable:
            issues.append(f"AUDIT_FIELD_PROFILE_SEMANTIC_MISMATCH: {field}")
        if not rule.get("representation") or not rule.get("notApplicable"):
            issues.append(f"AUDIT_FIELD_PROFILE_INCOMPLETE: {field}")
    return issues


def _storage_semantics_issues(root: Path) -> list[str]:
    try:
        value = load_json(root / AUDIT / "audit-storage-semantics-1.0.0.json")
    except (OSError, ValueError):
        return ["AUDIT_STORAGE_SEMANTICS_INVALID"]
    expected = {
        "localFactMutation": "append-only-no-update-delete-truncate-for-online-writer",
        "deliveryStateLocation": "separate-local-audit-outbox",
        "producerSequence": "not-defined-v1",
        "localSurrogate": "gap-allowed-storage-only",
        "centralCollectionStory": "1-4",
        "ledgerSequenceOwner": "audit-operations",
        "centralLedgerImplemented": False,
    }
    return [] if all(value.get(key) == expected_value for key, expected_value in expected.items()) \
        and value.get("factDeliveryFields") == [] else ["AUDIT_STORAGE_SEMANTICS_INVALID"]


def _negative_fixture_issues(root: Path, fact_schema: Any, catalog: Any, vocabulary: Any) -> list[str]:
    try:
        suite = load_json(root / AUDIT / "fixtures/negative-fixtures-1.0.0.json")
        base = load_json(root / suite["baseFixture"])
        cases = suite["cases"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["AUDIT_NEGATIVE_FIXTURE_CATALOG_INVALID"]
    issues: list[str] = []
    identities: list[str] = []
    for case in cases:
        if not isinstance(case, dict):
            issues.append("AUDIT_NEGATIVE_FIXTURE_CATALOG_INVALID")
            continue
        identities.append(str(case.get("id")))
        candidate = copy.deepcopy(base)
        if not _apply_mutation(candidate, case):
            issues.append(f"AUDIT_NEGATIVE_FIXTURE_INVALID: {case.get('id')}")
            continue
        actual: list[str] = []
        schema_found = schema_issues(candidate, fact_schema) if isinstance(fact_schema, dict) else []
        if schema_found:
            actual.append("AUDIT_FIXTURE_SCHEMA_REJECTED")
        actual.extend(_fact_issues(candidate, catalog, vocabulary))
        if not any(code.startswith(str(case.get("expectedCode"))) for code in actual):
            issues.append(f"AUDIT_NEGATIVE_FIXTURE_NOT_REJECTED: {case.get('id')}")
    if len(identities) != len(set(identities)) or len(identities) < 6:
        issues.append("AUDIT_NEGATIVE_FIXTURE_CATALOG_INVALID")
    return issues


def _apply_mutation(document: dict[str, Any], case: dict[str, Any]) -> bool:
    parts = str(case.get("path", "")).split(".")
    if not parts or any(not part for part in parts):
        return False
    target: Any = document
    for part in parts[:-1]:
        if not isinstance(target, dict) or part not in target:
            return False
        target = target[part]
    if not isinstance(target, dict):
        return False
    operation = case.get("operation")
    if operation == "replace" and parts[-1] not in target:
        return False
    if operation not in {"replace", "add"}:
        return False
    target[parts[-1]] = case.get("value")
    return True


def _lock_issues(root: Path) -> list[str]:
    try:
        lock = load_json(root / LOCK)
        entries = lock["files"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["AUDIT_CONTRACT_LOCK_INVALID"]
    issues: list[str] = []
    expected_paths = sorted(
        str(path.relative_to(root))
        for path in (root / AUDIT).rglob("*")
        if path.is_file() and path != root / LOCK
    )
    locked_paths = [entry.get("path") for entry in entries if isinstance(entry, dict)]
    if lock.get("version") != "AUDIT-CONTRACT-LOCK-1.0.0" or locked_paths != expected_paths:
        issues.append("AUDIT_CONTRACT_LOCK_SCOPE_MISMATCH")
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("path"), str) \
                or not isinstance(entry.get("sha256"), str):
            issues.append("AUDIT_CONTRACT_LOCK_INVALID")
            continue
        try:
            digest = hashlib.sha256((root / entry["path"]).read_bytes()).hexdigest()
        except OSError:
            issues.append(f"AUDIT_CONTRACT_LOCK_PATH_MISSING: {entry.get('path')}")
            continue
        if digest != entry["sha256"]:
            issues.append(f"AUDIT_CONTRACT_LOCK_DIGEST_MISMATCH: {entry['path']}")
    return issues


def _contains_sensitive_field(value: Any) -> bool:
    if isinstance(value, dict):
        return any(_sensitive_key(str(key)) or _contains_sensitive_field(child) for key, child in value.items())
    if isinstance(value, list):
        return any(_contains_sensitive_field(child) for child in value)
    return False


def _sensitive_key(key: str) -> bool:
    normalized = re.sub(r"[^a-z0-9]", "", key.lower())
    forbidden = ("student", "account", "cookie", "password", "secret", "evidencebody", "requestbody", "keymaterial")
    return normalized.startswith("raw") or any(word in normalized for word in forbidden) \
        or ("token" in normalized and "searchtoken" not in normalized and normalized != "tokenizationprofileversion")


def _has_key(value: Any, target: str) -> bool:
    if isinstance(value, dict):
        return target in value or any(_has_key(child, target) for child in value.values())
    if isinstance(value, list):
        return any(_has_key(child, target) for child in value)
    return False


def _utc_instant(value: str) -> datetime | None:
    if not UTC_TIME.fullmatch(value):
        return None
    try:
        return datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError:
        return None


def _identity_vocabulary_issues(fact: dict[str, Any], vocabulary: Any) -> list[str]:
    if fact.get("producerModule") != "identity-access":
        return []
    if not isinstance(vocabulary, dict):
        return ["AUDIT_IDENTITY_VOCABULARY_INVALID"]
    authorization = fact.get("authorizationContext")
    checks = (
        set(fact.get("roleIds", [])) <= set(vocabulary.get("roleIds", [])),
        fact.get("purpose") is None or fact.get("purpose") in vocabulary.get("purposes", []),
        fact.get("projectionScope") is None
            or fact.get("projectionScope") in vocabulary.get("projectionScopes", []),
        fact.get("objectType") is None or fact.get("objectType") in vocabulary.get("objectTypes", []),
        fact.get("aggregateType") is None
            or fact.get("aggregateType") in vocabulary.get("aggregateTypes", []),
        isinstance(authorization, dict)
            and set(authorization.get("scopeCodes", [])) <= set(vocabulary.get("scopeCodes", [])),
        isinstance(authorization, dict)
            and (authorization.get("notApplicableReason") is None
                 or authorization.get("notApplicableReason") in vocabulary.get("notApplicableReasons", [])),
        fact.get("policyVersions") == {
            key: value for key, value in vocabulary.get("policyVersions", {}).items()
            if key in fact.get("policyVersions", {})
        },
    )
    return [] if all(checks) else ["AUDIT_IDENTITY_VOCABULARY_INVALID"]


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) == 2 else Path(".")
    issues = validate(root)
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print(f"audit-contracts: PASS ({len(SCHEMAS)} controlled documents)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
