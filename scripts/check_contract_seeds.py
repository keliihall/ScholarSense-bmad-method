#!/usr/bin/env python3
"""Validate configuration, public envelope, and dual-role deployment seeds."""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ENVIRONMENTS = ("dev", "test", "stage", "prod")
RUNTIME_KEYS = {
    "SCHOLARSENSE_ENV",
    "SCHOLARSENSE_ROLE",
    "SCHOLARSENSE_ACCOUNT_REF",
    "SCHOLARSENSE_DATABASE_REF",
    "SCHOLARSENSE_SECRET_REF",
    "SCHOLARSENSE_STORAGE_NAMESPACE",
    "SCHOLARSENSE_EXTERNAL_BASE_URI",
    "SCHOLARSENSE_HTTP_PORT",
}
SENSITIVE_CLIENT_NAME = re.compile(r"(?:SECRET|TOKEN|PASSWORD|PRIVATE|DATABASE|ACCOUNT|STORAGE)", re.IGNORECASE)
REFERENCE_PATTERNS = {
    "SCHOLARSENSE_ACCOUNT_REF": r"^account://(dev|test|stage|prod)/[a-z0-9-]+$",
    "SCHOLARSENSE_DATABASE_REF": r"^database://(dev|test|stage|prod)/[a-z0-9-]+$",
    "SCHOLARSENSE_SECRET_REF": r"^secret://(dev|test|stage|prod)/[a-z0-9-]+$",
}
STORAGE_PATTERN = r"^[a-z0-9]+(?:-[a-z0-9]+)*-(dev|test|stage|prod)$"
EXTERNAL_URI_PATTERN = r"^https://(dev|test|stage|prod)(?:\.[a-z0-9-]+)*\.invalid(?:/[^#]*)?$"
EVENT_TYPE_PATTERN = r"^[a-z][a-z0-9-]*\.[a-z][a-z0-9-]*\.[a-z][a-z0-9-]*\.v[1-9][0-9]*$"


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
    if set(schema.get("required", [])) != RUNTIME_KEYS - {"SCHOLARSENSE_HTTP_PORT"}:
        violations.append("RUNTIME_SCHEMA_REQUIRED_SET_INVALID")
    if set(schema.get("properties", {})) != RUNTIME_KEYS:
        violations.append("RUNTIME_SCHEMA_PROPERTY_SET_INVALID")
        return
    properties = schema["properties"]
    if properties["SCHOLARSENSE_ENV"] != {"type": "string", "enum": list(ENVIRONMENTS)}:
        violations.append("RUNTIME_SCHEMA_ENVIRONMENT_INVALID")
    if properties["SCHOLARSENSE_ROLE"] != {"type": "string", "enum": ["web-api", "worker"]}:
        violations.append("RUNTIME_SCHEMA_ROLE_INVALID")
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
        if not re.fullmatch(pattern, values[key]) or not values[key].startswith(
            key.removeprefix("SCHOLARSENSE_").removesuffix("_REF").lower() + f"://{environment}/"
        ):
            violations.append(f"CONFIG_EXAMPLE_REFERENCE_INVALID: {environment}:{key}")
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
    if openapi.get("paths") != {}:
        violations.append("OPENAPI_BUSINESS_PATHS_PREMATURE")
    schemas = openapi.get("components", {}).get("schemas", {})
    if set(schemas) != {"ErrorEnvelope", "FieldError"}:
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


def _check_event(event, violations: list[str]) -> None:
    if not isinstance(event, dict):
        return
    if event.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
        violations.append("EVENT_SCHEMA_DRAFT_INVALID")
    properties = event.get("properties", {})
    if properties.get("specversion", {}).get("const") != "1.0":
        violations.append("CLOUDEVENTS_VERSION_INVALID")
    if set(event.get("required", [])) != {"specversion", "id", "source", "type", "time", "data"}:
        violations.append("EVENT_ENVELOPE_REQUIRED_INVALID")
    if properties.get("data") != {} or "$defs" in event:
        violations.append("EVENT_BUSINESS_CONTRACT_PREMATURE")
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
            or properties["subject"] != {"type": "string"} \
            or properties["datacontenttype"] != {"const": "application/json"} \
            or properties["traceparent"] != {"type": "string"}:
        violations.append("EVENT_ENVELOPE_PROPERTY_INVALID")


def _check_roles(root: Path, roles, violations: list[str]) -> None:
    if not isinstance(roles, dict):
        return
    if roles.get("artifactContract") != "same-backend-jar":
        violations.append("ROLE_ARTIFACT_CONTRACT_INVALID")
    expected_environment = RUNTIME_KEYS - {"SCHOLARSENSE_ROLE", "SCHOLARSENSE_HTTP_PORT"}
    if set(roles.get("requiredEnvironment", [])) != expected_environment:
        violations.append("ROLE_REQUIRED_ENVIRONMENT_INVALID")
    definitions = roles.get("roles", {})
    if set(definitions) != {"web-api", "worker"}:
        violations.append("ROLE_SET_INVALID")
        return
    artifacts = {value.get("artifact") for value in definitions.values()}
    if len(artifacts) != 1:
        violations.append("ROLE_ARTIFACT_NOT_SHARED")
    expected_artifact = _maven_artifact(root / "backend/pom.xml")
    if expected_artifact is None or artifacts != {expected_artifact}:
        violations.append("ROLE_ARTIFACT_MISMATCH")
    if definitions["web-api"].get("businessHttp") is not True:
        violations.append("WEB_API_HTTP_CONTRACT_INVALID")
    if definitions["worker"].get("businessHttp") is not False:
        violations.append("WORKER_EXPOSES_BUSINESS_HTTP")
    if definitions["worker"].get("probe", {}).get("type") != "process-alive":
        violations.append("WORKER_PROBE_INVALID")
    if definitions["worker"].get("probe") != {"type": "process-alive"}:
        violations.append("WORKER_PROBE_INVALID")
    if definitions["web-api"].get("probe") != {
        "type": "health-http",
        "livenessPath": "/actuator/health/liveness",
        "readinessPath": "/actuator/health/readiness",
    }:
        violations.append("WEB_API_PROBE_INVALID")
    for role, definition in definitions.items():
        if definition.get("environment") != {"SCHOLARSENSE_ROLE": role}:
            violations.append(f"ROLE_ENVIRONMENT_MISMATCH: {role}")


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
