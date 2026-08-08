#!/usr/bin/env python3
"""Validate configuration, public envelope, and dual-role deployment seeds."""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from check_authorization_contracts import validate as validate_authorization_contracts


ENVIRONMENTS = ("dev", "test", "stage", "prod")
AUDIT_REFERENCE_RESOURCES = {
    "SCHOLARSENSE_AUDIT_INGESTION_POLICY_REF": "audit-ingestion-policy-1-0-0",
    "SCHOLARSENSE_AUDIT_HASH_PROFILE_REF": "audit-ledger-hash-1-0-0",
    "SCHOLARSENSE_AUDIT_COLLECTOR_REF": "audit-collector-1-0-0",
    "SCHOLARSENSE_AUDIT_VERIFIER_REF": "audit-verifier-1-0-0",
    "SCHOLARSENSE_AUDIT_ALERT_TRANSPORT_REF": "audit-alert-structured-log-1-0-0",
    "SCHOLARSENSE_AUDIT_METRIC_BINDING_REF": "audit-micrometer-1-0-0",
    "SCHOLARSENSE_AUDIT_RETENTION_CAPABILITY_REF": "audit-retention-capability-1-0-0",
}
IDENTITY_AUTHORITY_PROFILE_RESOURCE = "identity-authority-profile-1-0-0"
RESPONSIBILITY_AUTHORITY_PROFILE_RESOURCE = (
    "responsibility-authority-profile-2-0-0"
)
INGESTION_QUALITY_RUNTIME_PROFILE = "ingestion-quality-runtime-1.0.0.json"
SUBJECT_REGISTRY_RUNTIME_PROFILE = "subject-registry-runtime-1.0.0.json"
MAXIMUM_HANDOFF_REVISION = 9007199254740991
IDENTITY_SYNC_DATABASE_BINDINGS = {
    "SCHOLARSENSE_IDENTITY_SYNC_ACCESS_INVALIDATION_CONSUMER_JDBC_URL",
    "SCHOLARSENSE_IDENTITY_SYNC_ACCESS_INVALIDATION_CONSUMER_USERNAME",
    "SCHOLARSENSE_IDENTITY_SYNC_ACCESS_INVALIDATION_CONSUMER_PASSWORD",
    "SCHOLARSENSE_IDENTITY_SYNC_RESPONSIBILITY_V2_CUTOVER_JDBC_URL",
    "SCHOLARSENSE_IDENTITY_SYNC_RESPONSIBILITY_V2_CUTOVER_USERNAME",
    "SCHOLARSENSE_IDENTITY_SYNC_RESPONSIBILITY_V2_CUTOVER_PASSWORD",
}
IDENTITY_AUDIT_TOKEN_BINDINGS = {
    "SCHOLARSENSE_IDENTITY_AUDIT_TOKEN_KEY_PATH",
    "SCHOLARSENSE_IDENTITY_AUDIT_TOKEN_KEY_VERSION",
}
INGESTION_QUALITY_WEB_BINDINGS = {
    "SPRING_DATASOURCE_URL",
    "SPRING_DATASOURCE_USERNAME",
    "SPRING_DATASOURCE_PASSWORD",
    "SCHOLARSENSE_INGESTION_QUALITY_OWNER_BINDINGS_PATH",
    "SCHOLARSENSE_INGESTION_QUALITY_OWNER_BINDINGS_DIGEST",
    "SCHOLARSENSE_INGESTION_QUALITY_CONTRACT_ROOT",
    "SCHOLARSENSE_INGESTION_QUALITY_TARGET_REPORT_PATH",
    "SCHOLARSENSE_INGESTION_QUALITY_TARGET_REPORT_URI",
    "SCHOLARSENSE_INGESTION_QUALITY_TARGET_AUTHORITY",
    "SCHOLARSENSE_INGESTION_QUALITY_TARGET_ENVIRONMENT",
    "SCHOLARSENSE_INGESTION_QUALITY_TARGET_SIGNING_KEY_PATH",
    "SCHOLARSENSE_INGESTION_QUALITY_MINIMUM_HANDOFF_REVISION",
    "DATA_CATALOG_TARGET_MINIMUM_HANDOFF_REVISION",
    *IDENTITY_AUDIT_TOKEN_BINDINGS,
}
SUBJECT_REGISTRY_WEB_BINDINGS = {
    "SCHOLARSENSE_SUBJECT_REGISTRY_DATASOURCE_URL",
    "SCHOLARSENSE_SUBJECT_REGISTRY_DATASOURCE_USERNAME",
    "SCHOLARSENSE_SUBJECT_REGISTRY_DATASOURCE_PASSWORD",
    "SCHOLARSENSE_SUBJECT_REGISTRY_RELAY_DATASOURCE_URL",
    "SCHOLARSENSE_SUBJECT_REGISTRY_RELAY_DATASOURCE_USERNAME",
    "SCHOLARSENSE_SUBJECT_REGISTRY_RELAY_DATASOURCE_PASSWORD",
    "SCHOLARSENSE_SUBJECT_REGISTRY_ENVIRONMENT",
    "SCHOLARSENSE_SUBJECT_REGISTRY_KEY_REF",
    "SCHOLARSENSE_SUBJECT_REGISTRY_KEY_VERSION",
    "SCHOLARSENSE_SUBJECT_REGISTRY_ENCRYPTION_KEY_PATH",
    "SCHOLARSENSE_SUBJECT_REGISTRY_SEARCH_KEY_PATH",
}
RUNTIME_KEYS = {
    "SCHOLARSENSE_ENV",
    "SCHOLARSENSE_ROLE",
    "SCHOLARSENSE_ACCOUNT_REF",
    "SCHOLARSENSE_DATABASE_REF",
    "SCHOLARSENSE_SECRET_REF",
    "SCHOLARSENSE_STORAGE_NAMESPACE",
    "SCHOLARSENSE_EXTERNAL_BASE_URI",
    "SCHOLARSENSE_HTTP_PORT",
    "SCHOLARSENSE_IDENTITY_ENABLED",
    "SCHOLARSENSE_IDENTITY_SYNC_ENABLED",
    "SCHOLARSENSE_AUDIT_LEDGER_ENABLED",
    "SCHOLARSENSE_CLOCK_SOURCE_REF",
    "SCHOLARSENSE_IDENTITY_AUTHORITY_PROFILE_REF",
    "SCHOLARSENSE_RESPONSIBILITY_AUTHORITY_PROFILE_REF",
    *AUDIT_REFERENCE_RESOURCES,
}
SENSITIVE_CLIENT_NAME = re.compile(r"(?:SECRET|TOKEN|PASSWORD|PRIVATE|DATABASE|ACCOUNT|STORAGE)", re.IGNORECASE)
REFERENCE_PATTERNS = {
    "SCHOLARSENSE_ACCOUNT_REF": r"^account://(dev|test|stage|prod)/[a-z0-9-]+$",
    "SCHOLARSENSE_DATABASE_REF": r"^database://(dev|test|stage|prod)/[a-z0-9-]+$",
    "SCHOLARSENSE_SECRET_REF": r"^secret://(dev|test|stage|prod)/[a-z0-9-]+$",
    "SCHOLARSENSE_CLOCK_SOURCE_REF": r"^config://(dev|test|stage|prod)/[a-z0-9-]+$",
    "SCHOLARSENSE_IDENTITY_AUTHORITY_PROFILE_REF":
        rf"^config://(dev|test|stage|prod)/{IDENTITY_AUTHORITY_PROFILE_RESOURCE}$",
    "SCHOLARSENSE_RESPONSIBILITY_AUTHORITY_PROFILE_REF":
        rf"^config://(dev|test|stage|prod)/{RESPONSIBILITY_AUTHORITY_PROFILE_RESOURCE}$",
    **{
        key: rf"^config://(dev|test|stage|prod)/{resource}$"
        for key, resource in AUDIT_REFERENCE_RESOURCES.items()
    },
}
STORAGE_PATTERN = r"^[a-z0-9]+(?:-[a-z0-9]+)*-(dev|test|stage|prod)$"
EXTERNAL_URI_PATTERN = r"^https://(dev|test|stage|prod)(?:\.[a-z0-9-]+)*\.invalid(?:/[^#]*)?$"
EVENT_TYPE_PATTERN = (
    r"^(?:scholarsense\.)?[a-z][a-z0-9-]*\.[a-z][a-z0-9-]*"
    r"\.[a-z][a-z0-9-]*\.v[1-9][0-9]*$"
)


def validate(project_root: Path) -> list[str]:
    root = project_root.resolve()
    violations: list[str] = []
    schema = _json(root / "contracts/config/runtime-config.schema.json", "RUNTIME_SCHEMA", violations)
    openapi = _json(root / "contracts/openapi/envelope.openapi.json", "OPENAPI_SEED", violations)
    event = _json(root / "contracts/events/envelope.schema.json", "EVENT_SEED", violations)
    roles = _json(root / "deploy/base/roles.json", "ROLE_SEED", violations)
    allowlist = _json(root / "frontend/src/app/config/client-env-allowlist.json", "CLIENT_ALLOWLIST", violations)

    _check_runtime_schema(schema, violations)
    for environment in ENVIRONMENTS:
        _check_example(root / f"contracts/config/examples/{environment}.env.example", environment, violations)
    _check_allowlist(allowlist, violations)
    _check_openapi(openapi, violations)
    _check_event(event, violations)
    _check_roles(root, roles, violations)
    violations.extend(
        validate_authorization_contracts(root, include_successors=False)
    )
    return sorted(set(violations))


def _json(path: Path, label: str, violations: list[str]):
    if not path.is_file():
        violations.append(f"{label}_MISSING: {path}")
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as error:
        violations.append(f"{label}_INVALID_JSON: {error.__class__.__name__}")
        return None


def _check_runtime_schema(schema, violations: list[str]) -> None:
    if not isinstance(schema, dict):
        return
    if schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
        violations.append("RUNTIME_SCHEMA_DRAFT_INVALID")
    if schema.get("type") != "object" or schema.get("additionalProperties") is not False:
        violations.append("RUNTIME_SCHEMA_OBJECT_BOUNDARY_INVALID")
    optional = {
        "SCHOLARSENSE_HTTP_PORT", "SCHOLARSENSE_CLOCK_SOURCE_REF",
        "SCHOLARSENSE_IDENTITY_AUTHORITY_PROFILE_REF",
        "SCHOLARSENSE_RESPONSIBILITY_AUTHORITY_PROFILE_REF",
        *AUDIT_REFERENCE_RESOURCES,
    }
    if set(schema.get("required", [])) != RUNTIME_KEYS - optional:
        violations.append("RUNTIME_SCHEMA_REQUIRED_SET_INVALID")
    if set(schema.get("properties", {})) != RUNTIME_KEYS:
        violations.append("RUNTIME_SCHEMA_PROPERTY_SET_INVALID")
        return
    properties = schema["properties"]
    if properties["SCHOLARSENSE_ENV"] != {"type": "string", "enum": list(ENVIRONMENTS)}:
        violations.append("RUNTIME_SCHEMA_ENVIRONMENT_INVALID")
    if properties["SCHOLARSENSE_ROLE"] != {"type": "string", "enum": ["web-api", "worker"]}:
        violations.append("RUNTIME_SCHEMA_ROLE_INVALID")
    for key in (
        "SCHOLARSENSE_IDENTITY_ENABLED",
        "SCHOLARSENSE_IDENTITY_SYNC_ENABLED",
        "SCHOLARSENSE_AUDIT_LEDGER_ENABLED",
    ):
        if properties[key] != {"type": "string", "enum": ["true", "false"]}:
            violations.append(f"RUNTIME_SCHEMA_CAPABILITY_INVALID: {key}")
    for key, expected in REFERENCE_PATTERNS.items():
        if properties[key].get("type") != "string" or properties[key].get("pattern") != expected:
            violations.append(f"RUNTIME_SCHEMA_REFERENCE_PATTERN_INVALID: {key}")
    if properties["SCHOLARSENSE_STORAGE_NAMESPACE"] != {
        "type": "string", "pattern": STORAGE_PATTERN
    }:
        violations.append("RUNTIME_SCHEMA_STORAGE_PATTERN_INVALID")
    external = properties["SCHOLARSENSE_EXTERNAL_BASE_URI"]
    if external.get("type") != "string" or external.get("format") != "uri" \
            or external.get("pattern") != EXTERNAL_URI_PATTERN:
        violations.append("RUNTIME_SCHEMA_EXTERNAL_URI_INVALID")
    port = properties["SCHOLARSENSE_HTTP_PORT"]
    port_pattern = port.get("pattern", "")
    try:
        accepts_bounds = all(re.fullmatch(port_pattern, value) for value in ("0", "8080", "65535"))
        rejects_out_of_range = not re.fullmatch(port_pattern, "65536")
    except re.error:
        accepts_bounds = False
        rejects_out_of_range = False
    if not accepts_bounds or not rejects_out_of_range:
        violations.append("RUNTIME_SCHEMA_PORT_RANGE_INVALID")
    if port.get("type") != "string" or port.get("default") != "8080":
        violations.append("RUNTIME_SCHEMA_PORT_CONTRACT_INVALID")
    audit_required = set()
    for rule in schema.get("allOf", []):
        condition = rule.get("if", {}).get("properties", {}).get(
            "SCHOLARSENSE_AUDIT_LEDGER_ENABLED", {}
        )
        if condition.get("const") == "true":
            audit_required.update(rule.get("then", {}).get("required", []))
    if audit_required != set(AUDIT_REFERENCE_RESOURCES):
        violations.append("RUNTIME_SCHEMA_AUDIT_BINDINGS_INVALID")
    sync_required = set()
    for rule in schema.get("allOf", []):
        condition = rule.get("if", {}).get("properties", {}).get(
            "SCHOLARSENSE_IDENTITY_SYNC_ENABLED", {}
        )
        if condition.get("const") == "true":
            sync_required.update(rule.get("then", {}).get("required", []))
    if sync_required != {
        "SCHOLARSENSE_IDENTITY_AUTHORITY_PROFILE_REF",
        "SCHOLARSENSE_RESPONSIBILITY_AUTHORITY_PROFILE_REF",
    }:
        violations.append("RUNTIME_SCHEMA_IDENTITY_SYNC_BINDINGS_INVALID")


def _check_example(path: Path, environment: str, violations: list[str]) -> None:
    if not path.is_file():
        violations.append(f"CONFIG_EXAMPLE_MISSING: {environment}")
        return
    values: dict[str, str] = {}
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            violations.append(f"CONFIG_EXAMPLE_LINE_INVALID: {environment}:{line_number}")
            continue
        key, value = line.split("=", 1)
        if key in values:
            violations.append(f"CONFIG_EXAMPLE_DUPLICATE_KEY: {environment}:{key}")
        values[key] = value
    if set(values) != RUNTIME_KEYS:
        violations.append(f"CONFIG_EXAMPLE_KEY_SET_INVALID: {environment}")
        return
    if values["SCHOLARSENSE_ENV"] != environment or values["SCHOLARSENSE_ROLE"] not in {"web-api", "worker"}:
        violations.append(f"CONFIG_EXAMPLE_ENV_ROLE_INVALID: {environment}")
    for key, pattern in REFERENCE_PATTERNS.items():
        expected_scheme = "config" if key in {
            "SCHOLARSENSE_CLOCK_SOURCE_REF",
            "SCHOLARSENSE_IDENTITY_AUTHORITY_PROFILE_REF",
            "SCHOLARSENSE_RESPONSIBILITY_AUTHORITY_PROFILE_REF",
            *AUDIT_REFERENCE_RESOURCES,
        } else (
            key.removeprefix("SCHOLARSENSE_").removesuffix("_REF").lower()
        )
        if not re.fullmatch(pattern, values[key]) or not values[key].startswith(
            expected_scheme + f"://{environment}/"
        ):
            violations.append(f"CONFIG_EXAMPLE_REFERENCE_INVALID: {environment}:{key}")
    for key, resource in AUDIT_REFERENCE_RESOURCES.items():
        if values.get(key) != f"config://{environment}/{resource}":
            violations.append(f"CONFIG_EXAMPLE_AUDIT_REFERENCE_STALE: {environment}:{key}")
    if values.get("SCHOLARSENSE_IDENTITY_AUTHORITY_PROFILE_REF") != \
            f"config://{environment}/{IDENTITY_AUTHORITY_PROFILE_RESOURCE}":
        violations.append(
            f"CONFIG_EXAMPLE_IDENTITY_AUTHORITY_REFERENCE_STALE: {environment}"
        )
    if values.get("SCHOLARSENSE_RESPONSIBILITY_AUTHORITY_PROFILE_REF") != \
            f"config://{environment}/{RESPONSIBILITY_AUTHORITY_PROFILE_RESOURCE}":
        violations.append(
            f"CONFIG_EXAMPLE_RESPONSIBILITY_AUTHORITY_REFERENCE_STALE: {environment}"
        )
    if not re.fullmatch(
        rf"[a-z0-9]+(?:-[a-z0-9]+)*-{re.escape(environment)}",
        values["SCHOLARSENSE_STORAGE_NAMESPACE"],
    ):
        violations.append(f"CONFIG_EXAMPLE_NAMESPACE_INVALID: {environment}")
    if values["SCHOLARSENSE_EXTERNAL_BASE_URI"] != f"https://{environment}.example.invalid":
        violations.append(f"CONFIG_EXAMPLE_ENDPOINT_NOT_RESERVED: {environment}")
    if not re.fullmatch(r"(?:0|[1-9][0-9]{0,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5])", values["SCHOLARSENSE_HTTP_PORT"]):
        violations.append(f"CONFIG_EXAMPLE_PORT_INVALID: {environment}")


def _check_allowlist(allowlist, violations: list[str]) -> None:
    if not isinstance(allowlist, list) or not all(isinstance(value, str) for value in allowlist):
        violations.append("CLIENT_ALLOWLIST_SHAPE_INVALID")
        return
    for value in allowlist:
        if not value.startswith("VITE_SCHOLARSENSE_PUBLIC_") or SENSITIVE_CLIENT_NAME.search(value):
            violations.append(f"CLIENT_ALLOWLIST_SENSITIVE_OR_UNSCOPED: {value}")


def _check_openapi(openapi, violations: list[str]) -> None:
    if not isinstance(openapi, dict):
        return
    if openapi.get("openapi") != "3.1.2":
        violations.append("OPENAPI_VERSION_INVALID")
    paths = openapi.get("paths")
    expected_paths = {
        "/api/v1/audit-records/search",
        "/api/v1/audit-retention-executions/{executionId}",
    }
    if not isinstance(paths, dict) or set(paths) != expected_paths:
        violations.append("OPENAPI_AUDIT_PATH_SET_INVALID")
    else:
        search = paths["/api/v1/audit-records/search"].get("post", {})
        evidence = paths["/api/v1/audit-retention-executions/{executionId}"].get("get", {})
        if search.get("requestBody", {}).get("content", {}).get(
                "application/json", {}).get("schema", {}).get("$ref") \
                != "../audit-retention/search-request.schema.json":
            violations.append("OPENAPI_AUDIT_SEARCH_REQUEST_REF_INVALID")
        if search.get("responses", {}).get("200", {}).get("content", {}).get(
                "application/json", {}).get("schema", {}).get("$ref") \
                != "../audit-retention/search-response.schema.json":
            violations.append("OPENAPI_AUDIT_SEARCH_RESPONSE_REF_INVALID")
        if set(search.get("responses", {})) != {"200", "400", "403", "409", "503"}:
            violations.append("OPENAPI_AUDIT_SEARCH_RESPONSE_SET_INVALID")
        if set(evidence.get("responses", {})) != {"200", "403", "503"}:
            violations.append("OPENAPI_AUDIT_EVIDENCE_RESPONSE_SET_INVALID")
        for operation in (search, evidence):
            success = operation.get("responses", {}).get("200", {})
            headers = success.get("headers", {})
            if headers.get("Cache-Control", {}).get("schema", {}).get("const") != "no-store" \
                    or headers.get("Referrer-Policy", {}).get("schema", {}).get("const") != "no-referrer":
                violations.append("OPENAPI_AUDIT_CACHE_BOUNDARY_INVALID")
    schemas = openapi.get("components", {}).get("schemas", {})
    if set(schemas) != {"ErrorEnvelope", "FieldError", "RetentionEvidenceResponse"}:
        violations.append("OPENAPI_ENVELOPE_SCOPE_INVALID")
        return
    required = set(schemas["ErrorEnvelope"].get("required", []))
    if required != {"code", "message", "traceId", "fieldErrors"}:
        violations.append("OPENAPI_ERROR_ENVELOPE_REQUIRED_INVALID")
    envelope = schemas["ErrorEnvelope"]
    properties = envelope.get("properties", {})
    if envelope.get("type") != "object" or envelope.get("additionalProperties") is not False:
        violations.append("OPENAPI_ERROR_ENVELOPE_BOUNDARY_INVALID")
    if set(properties) != {
        "code", "message", "traceId", "fieldErrors",
        "currentVersion", "latestOperator", "latestChangedAt",
    }:
        violations.append("OPENAPI_ERROR_ENVELOPE_PROPERTIES_INVALID")
    else:
        if properties["code"].get("type") != "string" \
                or properties["code"].get("pattern") != "^[A-Z][A-Z0-9_]*$":
            violations.append("OPENAPI_ERROR_CODE_INVALID")
        if properties["message"].get("type") != "string" \
                or properties["traceId"].get("minLength") != 1:
            violations.append("OPENAPI_ERROR_TEXT_FIELDS_INVALID")
        if properties["fieldErrors"].get("items", {}).get("$ref") != "#/components/schemas/FieldError":
            violations.append("OPENAPI_FIELD_ERRORS_REFERENCE_INVALID")
        if properties["currentVersion"] != {"type": "integer", "minimum": 0} \
                or properties["latestOperator"] != {"type": "string", "minLength": 1} \
                or properties["latestChangedAt"] != {"type": "string", "format": "date-time"}:
            violations.append("OPENAPI_VERSION_CONFLICT_CONTEXT_INVALID")
    rules = envelope.get("allOf", [])
    expected_context = {"currentVersion", "latestOperator", "latestChangedAt"}
    if len(rules) != 1 \
            or rules[0].get("if", {}).get("properties", {}).get("code", {}).get("pattern") != "_VERSION_CONFLICT$" \
            or set(rules[0].get("if", {}).get("required", [])) != {"code"} \
            or set(rules[0].get("then", {}).get("required", [])) != expected_context:
        violations.append("OPENAPI_VERSION_CONFLICT_CONTEXT_INVALID")
    field_error = schemas["FieldError"]
    if field_error.get("type") != "object" or field_error.get("additionalProperties") is not False \
            or set(field_error.get("required", [])) != {"field", "code", "message"} \
            or set(field_error.get("properties", {})) != {"field", "code", "message"} \
            or any(
                field_error.get("properties", {}).get(name, {}).get("type") != "string"
                for name in ("field", "code", "message")
            ):
        violations.append("OPENAPI_FIELD_ERROR_INVALID")
    evidence = schemas["RetentionEvidenceResponse"]
    fields = evidence.get("properties", {}).get("fields", {})
    if evidence.get("additionalProperties") is not False \
            or fields.get("additionalProperties") is not False \
            or fields.get("properties", {}).get("nonProductionEvidence") != {"const": True} \
            or "archiveObjectUrl" in fields.get("properties", {}):
        violations.append("OPENAPI_AUDIT_EVIDENCE_BOUNDARY_INVALID")


def _check_event(event, violations: list[str]) -> None:
    if not isinstance(event, dict):
        return
    if event.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
        violations.append("EVENT_SCHEMA_DRAFT_INVALID")
    properties = event.get("properties", {})
    if properties.get("specversion", {}).get("const") != "1.0":
        violations.append("CLOUDEVENTS_VERSION_INVALID")
    if set(event.get("required", [])) != {
        "specversion",
        "id",
        "source",
        "type",
        "time",
        "data",
    }:
        violations.append("EVENT_ENVELOPE_REQUIRED_INVALID")
    if properties.get("data") != {} or "$defs" in event:
        violations.append("EVENT_ENVELOPE_DATA_EXTENSION_INVALID")
    expected_properties = {
        "specversion", "id", "source", "type", "time", "subject",
        "datacontenttype", "traceparent", "data",
    }
    if event.get("type") != "object" or event.get("additionalProperties") is not True \
            or set(properties) != expected_properties:
        violations.append("EVENT_ENVELOPE_PROPERTY_INVALID")
        return
    if properties["id"] != {"type": "string", "minLength": 1} \
            or properties["source"] != {"type": "string", "format": "uri-reference"} \
            or properties["type"] != {"type": "string", "pattern": EVENT_TYPE_PATTERN} \
            or properties["time"] != {"type": "string", "format": "date-time"} \
            or properties["subject"] != {
                "type": "string", "minLength": 1, "maxLength": 256
            } \
            or properties["datacontenttype"] != {"const": "application/json"} \
            or properties["traceparent"] != {"type": "string"}:
        violations.append("EVENT_ENVELOPE_PROPERTY_INVALID")


def _check_roles(root: Path, roles, violations: list[str]) -> None:
    if not isinstance(roles, dict):
        return
    if roles.get("artifactContract") != "same-backend-jar":
        violations.append("ROLE_ARTIFACT_CONTRACT_INVALID")
    expected_environment = RUNTIME_KEYS - {
        "SCHOLARSENSE_ROLE", "SCHOLARSENSE_HTTP_PORT",
        "SCHOLARSENSE_IDENTITY_ENABLED", "SCHOLARSENSE_IDENTITY_SYNC_ENABLED",
        "SCHOLARSENSE_AUDIT_LEDGER_ENABLED",
        "SCHOLARSENSE_IDENTITY_AUTHORITY_PROFILE_REF",
        "SCHOLARSENSE_RESPONSIBILITY_AUTHORITY_PROFILE_REF",
        *AUDIT_REFERENCE_RESOURCES,
    }
    if set(roles.get("requiredEnvironment", [])) != expected_environment:
        violations.append("ROLE_REQUIRED_ENVIRONMENT_INVALID")
    definitions = roles.get("roles", {})
    if set(definitions) != {"web-api", "worker", "identity-sync-worker"}:
        violations.append("ROLE_SET_INVALID")
        return
    if definitions["web-api"].get("capabilityProfiles") != [
        INGESTION_QUALITY_RUNTIME_PROFILE, SUBJECT_REGISTRY_RUNTIME_PROFILE
    ]:
        violations.append("WEB_API_CAPABILITY_PROFILE_INVALID")
    if definitions["worker"].get("capabilityProfiles") != [
        INGESTION_QUALITY_RUNTIME_PROFILE
    ]:
        violations.append("INGESTION_QUALITY_CAPABILITY_PROFILE_INVALID: worker")
    if "capabilityProfiles" in definitions["identity-sync-worker"]:
        violations.append(
            "INGESTION_QUALITY_CAPABILITY_PROFILE_INVALID: identity-sync-worker"
        )
    ingestion_quality = _json(
        root / f"deploy/base/{INGESTION_QUALITY_RUNTIME_PROFILE}",
        "INGESTION_QUALITY_RUNTIME_PROFILE",
        violations,
    )
    _check_ingestion_quality_runtime_profile(ingestion_quality, violations)
    subject_registry = _json(
        root / f"deploy/base/{SUBJECT_REGISTRY_RUNTIME_PROFILE}",
        "SUBJECT_REGISTRY_RUNTIME_PROFILE",
        violations,
    )
    _check_subject_registry_runtime_profile(subject_registry, violations)
    artifacts = {value.get("artifact") for value in definitions.values()}
    if len(artifacts) != 1:
        violations.append("ROLE_ARTIFACT_NOT_SHARED")
    expected_artifact = _maven_artifact(root / "backend/pom.xml")
    if expected_artifact is None or artifacts != {expected_artifact}:
        violations.append("ROLE_ARTIFACT_MISMATCH")
    if definitions["web-api"].get("businessHttp") is not True:
        violations.append("WEB_API_HTTP_CONTRACT_INVALID")
    for worker_role in ("worker", "identity-sync-worker"):
        if definitions[worker_role].get("businessHttp") is not False:
            violations.append("WORKER_EXPOSES_BUSINESS_HTTP")
        if definitions[worker_role].get("probe") != {"type": "process-alive"}:
            violations.append("WORKER_PROBE_INVALID")
    if definitions["web-api"].get("probe") != {
        "type": "health-http",
        "livenessPath": "/actuator/health/liveness",
        "readinessPath": "/actuator/health/readiness",
    }:
        violations.append("WEB_API_PROBE_INVALID")
    expected_role_environment = {
        "web-api": {
            "SCHOLARSENSE_ROLE": "web-api",
            "SCHOLARSENSE_IDENTITY_ENABLED": "true",
            "SCHOLARSENSE_IDENTITY_SYNC_ENABLED": "false",
            "SCHOLARSENSE_AUDIT_LEDGER_ENABLED": "false",
            "SCHOLARSENSE_SUBJECT_REGISTRY_ENABLED": "true",
            "SCHOLARSENSE_SUBJECT_REGISTRY_RELAY_ENABLED": "true",
            "SCHOLARSENSE_INGESTION_QUALITY_SUBJECT_RECOMPUTE_WORKER_ENABLED": "true",
        },
        "worker": {
            "SCHOLARSENSE_ROLE": "worker",
            "SCHOLARSENSE_IDENTITY_ENABLED": "false",
            "SCHOLARSENSE_IDENTITY_SYNC_ENABLED": "false",
            "SCHOLARSENSE_AUDIT_LEDGER_ENABLED": "true",
        },
        "identity-sync-worker": {
            "SCHOLARSENSE_ROLE": "worker",
            "SCHOLARSENSE_IDENTITY_ENABLED": "false",
            "SCHOLARSENSE_IDENTITY_SYNC_ENABLED": "true",
            "SCHOLARSENSE_AUDIT_LEDGER_ENABLED": "false",
        },
    }
    expected_role_required = {
        "web-api": {
            *IDENTITY_AUDIT_TOKEN_BINDINGS,
            "SCHOLARSENSE_INGESTION_QUALITY_MINIMUM_HANDOFF_REVISION",
            "DATA_CATALOG_TARGET_MINIMUM_HANDOFF_REVISION",
            *SUBJECT_REGISTRY_WEB_BINDINGS,
        },
        "worker": {
            *AUDIT_REFERENCE_RESOURCES,
        },
        "identity-sync-worker": {
            "SCHOLARSENSE_IDENTITY_AUTHORITY_PROFILE_REF",
            "SCHOLARSENSE_RESPONSIBILITY_AUTHORITY_PROFILE_REF",
            "SCHOLARSENSE_IDENTITY_SYNC_SECURITY_DIRECTORY",
            *IDENTITY_SYNC_DATABASE_BINDINGS,
        },
    }
    for role, definition in definitions.items():
        if definition.get("environment") != expected_role_environment[role]:
            violations.append(f"ROLE_ENVIRONMENT_MISMATCH: {role}")
        required = definition.get("requiredEnvironment")
        if not isinstance(required, list) or len(required) != len(set(required)) \
                or set(required) != expected_role_required[role]:
            violations.append(f"ROLE_REQUIRED_ENVIRONMENT_INVALID: {role}")
    if definitions["identity-sync-worker"].get("allowedEnvironments") != [
        "dev", "test", "stage"
    ]:
        violations.append("IDENTITY_SYNC_PRODUCTION_DISABLED_INVALID")
    if definitions["web-api"].get("allowedEnvironments") != [
        "test", "stage", "prod"
    ]:
        violations.append("WEB_API_DEV_DISABLED_INVALID")
    if "allowedEnvironments" in definitions["worker"]:
        violations.append("ROLE_ALLOWED_ENVIRONMENT_INVALID: worker")


def _check_ingestion_quality_runtime_profile(profile, violations: list[str]) -> None:
    if not isinstance(profile, dict):
        return
    roles = profile.get("sameArtifactRoles")
    if not isinstance(roles, dict) or set(roles) != {"web-api", "worker"}:
        violations.append("INGESTION_QUALITY_ROLE_BINDINGS_INVALID")
    else:
        web = roles.get("web-api", {})
        worker = roles.get("worker", {})
        if web.get("capabilities") != [
            "catalog-api", "source-dependency-owner-evidence"
        ] or web.get("allowedEnvironments") != ["test", "stage", "prod"] \
                or set(web.get("requiredEnvironment", [])) != \
                INGESTION_QUALITY_WEB_BINDINGS:
            violations.append("INGESTION_QUALITY_WEB_BINDINGS_INVALID")
        if worker.get("capabilities") != ["ingestion-quality-audit-relay"] \
                or set(worker.get("requiredEnvironment", [])) != {
                    "SPRING_DATASOURCE_URL",
                    "SPRING_DATASOURCE_USERNAME",
                    "SPRING_DATASOURCE_PASSWORD",
                }:
            violations.append("INGESTION_QUALITY_WORKER_BINDINGS_INVALID")
    if profile.get("identityAuditTokenization") != {
        "keyPathEnvironment": "SCHOLARSENSE_IDENTITY_AUDIT_TOKEN_KEY_PATH",
        "keyVersionEnvironment": "SCHOLARSENSE_IDENTITY_AUDIT_TOKEN_KEY_VERSION",
        "location": "absolute-protected-regular-non-symlink-mounted-file",
        "minimumBytes": 32,
        "maximumBytes": 64,
        "algorithm": "hmac-sha256",
        "keyVersionPattern": "^k[0-9]+$",
        "failureMode": "startup-fail-closed",
        "committedMaterialAllowed": False,
    }:
        violations.append("IDENTITY_AUDIT_TOKEN_BINDING_INVALID")
    owners = profile.get("ownerBindings", {})
    if owners.get("objectClasses") != [
        "SOURCE", "DEPENDENCY", "SUBJECT_MAPPING_EXCEPTION", "JOB"
    ] or owners.get("derivedObjectBindings") != {
        "SUBJECT_MAPPING_EXCEPTION": {
            "bindingLookupClass": "SOURCE",
            "bindingKey": "exception-source-id",
            "authorizationTokenDigest": "sha256(sourceId)",
            "scopeAnchors": ["owned-source"],
        },
        "JOB": {
            "bindingLookupClass": "SOURCE",
            "bindingKey": "persisted-owner-source-id",
            "authorizationTokenDigest": "sha256(ownerSourceId)",
            "scopeAnchors": ["owned-source", "technical-object"],
        },
    }:
        violations.append("INGESTION_QUALITY_DERIVED_OWNER_BINDINGS_INVALID")
    if profile.get("targetHandoffAntiRollback") != {
        "minimumRevisionEnvironment":
            "SCHOLARSENSE_INGESTION_QUALITY_MINIMUM_HANDOFF_REVISION",
        "releaseMinimumRevisionEnvironment":
            "DATA_CATALOG_TARGET_MINIMUM_HANDOFF_REVISION",
        "ownership": "deployment",
        "type": "integer",
        "minimum": 1,
        "maximum": MAXIMUM_HANDOFF_REVISION,
        "inputConstraints": {
            "SCHOLARSENSE_INGESTION_QUALITY_MINIMUM_HANDOFF_REVISION": {
                "minimum": 1,
                "maximum": MAXIMUM_HANDOFF_REVISION,
            },
            "DATA_CATALOG_TARGET_MINIMUM_HANDOFF_REVISION": {
                "minimum": 1,
                "maximum": MAXIMUM_HANDOFF_REVISION,
            },
        },
        "updateInvariant": "monotonic-non-decreasing",
        "crossSurfaceInvariant": "release-and-runtime-use-same-floor",
        "belowFloor": "startup-fail-closed",
    }:
        violations.append("INGESTION_QUALITY_HANDOFF_ANTI_ROLLBACK_INVALID")
    gate = profile.get("databaseStartupGate")
    if not isinstance(gate, dict):
        violations.append("INGESTION_QUALITY_DATABASE_GATE_INVALID")
        return
    if gate.get("productionJdbcParameters") != {
        "sslmode": "verify-full",
        "channelBinding": "require",
    } or gate.get("failureMode") != "startup-fail-closed":
        violations.append("INGESTION_QUALITY_DATABASE_TRANSPORT_GATE_INVALID")
    if gate.get("serverVersionQuery") != \
            "select current_setting('server_version_num')" \
            or gate.get("requiredServerVersionNum") != "180004":
        violations.append("INGESTION_QUALITY_DATABASE_SERVER_VERSION_GATE_INVALID")
    if gate.get("identityQuery") != "select current_user, session_user" \
            or gate.get("sessionIdentityInvariant") \
            != "session_user=current_user=SPRING_DATASOURCE_USERNAME" \
            or gate.get("expectedIdentitySource") != "SPRING_DATASOURCE_USERNAME":
        violations.append("INGESTION_QUALITY_DATABASE_SESSION_IDENTITY_GATE_INVALID")
    if gate.get("loginRoleAttributes") != {
        "LOGIN": True,
        "INHERIT": True,
        "SUPERUSER": False,
        "CREATEROLE": False,
        "CREATEDB": False,
        "REPLICATION": False,
        "BYPASSRLS": False,
    } or gate.get("groupRoleAttributes") != {
        "LOGIN": False,
        "SUPERUSER": False,
        "CREATEROLE": False,
        "CREATEDB": False,
        "REPLICATION": False,
        "BYPASSRLS": False,
    }:
        violations.append("INGESTION_QUALITY_DATABASE_PRINCIPAL_RESTRICTION_INVALID")
    if gate.get("roleActivation") != "inherited-membership" \
            or gate.get("setRoleAllowed") is not False \
            or gate.get("membershipGrantOptions") != {
                "inherit": True,
                "set": False,
            }:
        violations.append("INGESTION_QUALITY_DATABASE_SET_ROLE_BOUNDARY_INVALID")
    if gate.get("distinctWorkloadLogins") != ["web-api", "worker"] \
            or gate.get("workloadRoleBindings") != {
                "web-api": {
                    "requiredGroupRole": "scholarsense_ingestion_quality_online",
                    "forbiddenGroupRoles": [
                        "scholarsense_ingestion_quality_relay"
                    ],
                },
                "worker": {
                    "requiredGroupRole": "scholarsense_ingestion_quality_relay",
                    "forbiddenGroupRoles": [
                        "scholarsense_ingestion_quality_online"
                    ],
                },
            }:
        violations.append("INGESTION_QUALITY_DATABASE_ROLE_BINDING_INVALID")
    if gate.get("effectivePrivilegeVerification") \
            != "exact-table-and-column-matrix":
        violations.append("INGESTION_QUALITY_DATABASE_PRIVILEGE_GATE_INVALID")


def _check_subject_registry_runtime_profile(profile, violations: list[str]) -> None:
    if not isinstance(profile, dict):
        return
    if profile.get("version") != "SUBJECT-REGISTRY-RUNTIME-1.0.0" \
            or profile.get("status") != "deployment-input-required":
        violations.append("SUBJECT_REGISTRY_RUNTIME_VERSION_INVALID")
    roles = profile.get("sameArtifactRoles")
    web = roles.get("web-api", {}) if isinstance(roles, dict) else {}
    if not isinstance(roles, dict) or set(roles) != {"web-api"} \
            or web.get("capabilities") != [
                "subject-mapping-api",
                "subject-mapping-event-relay",
                "subject-window-recompute-worker",
            ] \
            or web.get("allowedEnvironments") != ["test", "stage", "prod"] \
            or web.get("activationEnvironment") != {
                "SCHOLARSENSE_SUBJECT_REGISTRY_ENABLED": "true",
                "SCHOLARSENSE_SUBJECT_REGISTRY_RELAY_ENABLED": "true",
                "SCHOLARSENSE_INGESTION_QUALITY_SUBJECT_RECOMPUTE_WORKER_ENABLED": "true",
            } \
            or set(web.get("requiredEnvironment", [])) != SUBJECT_REGISTRY_WEB_BINDINGS:
        violations.append("SUBJECT_REGISTRY_WEB_BINDINGS_INVALID")
    gates = profile.get("databaseStartupGates", {})
    if gates.get("online") != {
        "urlEnvironment": "SCHOLARSENSE_SUBJECT_REGISTRY_DATASOURCE_URL",
        "usernameEnvironment": "SCHOLARSENSE_SUBJECT_REGISTRY_DATASOURCE_USERNAME",
        "requiredGroupRole": "scholarsense_subject_registry_online",
        "forbiddenGroupRole": "scholarsense_subject_registry_relay",
    } or gates.get("relay") != {
        "urlEnvironment": "SCHOLARSENSE_SUBJECT_REGISTRY_RELAY_DATASOURCE_URL",
        "usernameEnvironment": "SCHOLARSENSE_SUBJECT_REGISTRY_RELAY_DATASOURCE_USERNAME",
        "requiredGroupRole": "scholarsense_subject_registry_relay",
        "forbiddenGroupRole": "scholarsense_subject_registry_online",
    } or gates.get("productionJdbcParameters") != {
        "sslmode": "verify-full", "channelBinding": "require"
    } or gates.get("requiredServerVersionNum") != "180004" \
            or gates.get("effectivePrivilegeVerification") != \
            "exact-table-column-function-matrix" \
            or gates.get("failureMode") != "startup-fail-closed":
        violations.append("SUBJECT_REGISTRY_DATABASE_GATES_INVALID")
    protection = profile.get("identifierProtection", {})
    if protection.get("materialDelivery") \
            != "approved-school-kms-to-protected-mount" \
            or protection.get("location") != \
            "absolute-protected-regular-non-symlink-mounted-file" \
            or protection.get("algorithms") != ["aes-256-gcm", "hmac-sha256"] \
            or protection.get("minimumBytes") != 32 \
            or protection.get("committedMaterialAllowed") is not False \
            or protection.get("runtimeEvidenceRequiredForProductionCompletion") is not True \
            or protection.get("failureMode") != "startup-fail-closed":
        violations.append("SUBJECT_REGISTRY_PROTECTION_BINDING_INVALID")
    if profile.get("consumerBoundary") != {
        "producerTransaction": "subject-registry-local-outbox",
        "relayTransaction": "consumer-commit-before-producer-confirm",
        "consumerTransaction": "ingestion-quality-inbox-plan-atomic",
        "distributedTransactionAllowed": False,
        "poisonPolicy": "integrity-failed",
        "workerLeaseSeconds": 60,
        "workerBatchSize": 100,
    }:
        violations.append("SUBJECT_REGISTRY_CONSUMER_BOUNDARY_INVALID")


def _maven_artifact(pom_path: Path) -> str | None:
    try:
        root = ET.parse(pom_path).getroot()
    except (ET.ParseError, OSError):
        return None
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    artifact_id = root.findtext("m:artifactId", namespaces=namespace)
    version = root.findtext("m:version", namespaces=namespace)
    if not artifact_id or not version:
        return None
    final_name = root.findtext("m:build/m:finalName", namespaces=namespace)
    if final_name:
        replacements = {
            "${project.artifactId}": artifact_id,
            "${project.version}": version,
            "${artifactId}": artifact_id,
            "${version}": version,
        }
        for placeholder, replacement in replacements.items():
            final_name = final_name.replace(placeholder, replacement)
        if "${" in final_name or not final_name.strip():
            return None
        artifact_name = final_name.strip()
    else:
        artifact_name = f"{artifact_id}-{version}"
    return f"backend/target/{artifact_name}.jar"


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) == 2 else Path(".")
    violations = validate(root)
    if violations:
        print("\n".join(violations), file=sys.stderr)
        return 1
    print("contract-seeds: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
