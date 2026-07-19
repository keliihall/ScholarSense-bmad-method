#!/usr/bin/env python3
"""Canonical JSON and the fail-closed ScholarSense JSON Schema subset."""

from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


MAX_SAFE_INTEGER = 9_007_199_254_740_991
ALLOWED_SCHEMA_KEYWORDS = {
    "$defs", "$id", "$ref", "$schema", "additionalProperties", "const", "description",
    "enum", "format", "items", "maxItems", "maxLength", "maximum", "minItems",
    "minLength", "minimum", "pattern", "properties", "required", "title", "type",
    "uniqueItems",
}
ALLOWED_TYPES = {"array", "boolean", "integer", "null", "number", "object", "string"}
ALLOWED_FORMATS = {"date-time"}
PLACEHOLDER = re.compile(r"(?i)^(?:tbd|todo|unknown|latest|no_vcs|placeholder|somewhere|n/?a)$")
LOCAL_PATH = re.compile(r"(?:^|\s)(?:/Users/[^/\s]+/|/home/[^/\s]+/|[A-Za-z]:\\Users\\)")
HEX40 = re.compile(r"^[0-9a-f]{40}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
OCI_DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")


def _pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"JSON_DUPLICATE_KEY: {key}")
        result[key] = value
    return result


def _integer(raw: str) -> int:
    if raw == "-0":
        raise ValueError("JSON_NEGATIVE_ZERO_FORBIDDEN")
    value = int(raw)
    if abs(value) > MAX_SAFE_INTEGER:
        raise ValueError("JSON_INTEGER_OUT_OF_RANGE")
    return value


def _float(raw: str) -> float:
    raise ValueError(f"JSON_FLOAT_FORBIDDEN: {raw}")


def _constant(raw: str) -> None:
    raise ValueError(f"JSON_NONFINITE_NUMBER_FORBIDDEN: {raw}")


def parse_json_bytes(payload: bytes, *, legacy_numbers: bool = False) -> Any:
    if payload.startswith(b"\xef\xbb\xbf"):
        raise ValueError("JSON_BOM_FORBIDDEN")
    try:
        text = payload.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise ValueError("JSON_UTF8_REQUIRED") from error
    try:
        value = json.loads(
            text,
            object_pairs_hook=_pairs,
            parse_int=_integer,
            parse_float=float if legacy_numbers else _float,
            parse_constant=_constant,
        )
    except json.JSONDecodeError as error:
        raise ValueError(f"JSON_SYNTAX_INVALID: {error.msg}") from error
    _validate_canonical_value(value, "$", legacy_numbers=legacy_numbers)
    return value


def load_json(path: Path, *, legacy_numbers: bool = False) -> Any:
    return parse_json_bytes(path.read_bytes(), legacy_numbers=legacy_numbers)


def _validate_canonical_value(value: Any, path: str, *, legacy_numbers: bool = False) -> None:
    if value is None or isinstance(value, bool):
        return
    if isinstance(value, int):
        if abs(value) > MAX_SAFE_INTEGER:
            raise ValueError(f"JSON_INTEGER_OUT_OF_RANGE: {path}")
        return
    if isinstance(value, float):
        if legacy_numbers and value == value and value not in (float("inf"), float("-inf")):
            return
        raise ValueError(f"JSON_FLOAT_FORBIDDEN: {path}")
    if isinstance(value, str):
        if any(0xD800 <= ord(character) <= 0xDFFF for character in value):
            raise ValueError(f"JSON_LONE_SURROGATE_FORBIDDEN: {path}")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            _validate_canonical_value(item, f"{path}[{index}]", legacy_numbers=legacy_numbers)
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise ValueError(f"JSON_OBJECT_KEY_NOT_STRING: {path}")
            _validate_canonical_value(key, f"{path}.<key>", legacy_numbers=legacy_numbers)
            _validate_canonical_value(item, f"{path}.{key}", legacy_numbers=legacy_numbers)
        return
    raise ValueError(f"JSON_VALUE_TYPE_FORBIDDEN: {path}")


def canonical_bytes(value: Any, *, legacy_numbers: bool = False) -> bytes:
    _validate_canonical_value(value, "$", legacy_numbers=legacy_numbers)
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def canonical_sha256(value: Any, *, legacy_numbers: bool = False) -> str:
    return hashlib.sha256(canonical_bytes(value, legacy_numbers=legacy_numbers)).hexdigest()


def schema_definition_issues(schema: Any) -> list[str]:
    issues: list[str] = []

    def visit(node: Any, path: str) -> None:
        if isinstance(node, bool):
            return
        if not isinstance(node, dict):
            issues.append(f"SCHEMA_NODE_NOT_OBJECT: {path}")
            return
        unknown = sorted(set(node) - ALLOWED_SCHEMA_KEYWORDS)
        if unknown:
            issues.append(f"SCHEMA_KEYWORD_UNSUPPORTED: {path}: {','.join(unknown)}")
        declared = node.get("type")
        declared_types = declared if isinstance(declared, list) else [declared]
        if declared is not None and (
            not all(isinstance(item, str) and item in ALLOWED_TYPES for item in declared_types)
            or len(set(declared_types)) != len(declared_types)
        ):
            issues.append(f"SCHEMA_TYPE_UNSUPPORTED: {path}")
        schema_format = node.get("format")
        if schema_format is not None and schema_format not in ALLOWED_FORMATS:
            issues.append(f"SCHEMA_FORMAT_UNSUPPORTED: {path}: {schema_format}")
        reference = node.get("$ref")
        if reference is not None and (
            not isinstance(reference, str) or not re.fullmatch(r"#/(?:\$defs|properties)(?:/[A-Za-z0-9_.-]+)+", reference)
        ):
            issues.append(f"SCHEMA_REMOTE_REF_FORBIDDEN: {path}")
        pattern = node.get("pattern")
        if pattern is not None:
            try:
                re.compile(pattern)
            except (re.error, TypeError):
                issues.append(f"SCHEMA_PATTERN_INVALID: {path}")
        properties = node.get("properties", {})
        if properties is not None and not isinstance(properties, dict):
            issues.append(f"SCHEMA_PROPERTIES_INVALID: {path}")
        elif isinstance(properties, dict):
            for name, child in properties.items():
                if name == "sha":
                    issues.append(f"SCHEMA_GENERIC_SHA_FORBIDDEN: {path}.properties.sha")
                visit(child, f"{path}.properties.{name}")
        definitions = node.get("$defs", {})
        if definitions is not None and not isinstance(definitions, dict):
            issues.append(f"SCHEMA_DEFS_INVALID: {path}")
        elif isinstance(definitions, dict):
            for name, child in definitions.items():
                visit(child, f"{path}.$defs.{name}")
        if "items" in node:
            visit(node["items"], f"{path}.items")
        additional = node.get("additionalProperties")
        if isinstance(additional, dict):
            visit(additional, f"{path}.additionalProperties")
        elif additional is not None and not isinstance(additional, bool):
            issues.append(f"SCHEMA_ADDITIONAL_PROPERTIES_INVALID: {path}")
        required = node.get("required")
        if required is not None and (
            not isinstance(required, list)
            or any(not isinstance(item, str) for item in required)
            or len(set(required)) != len(required)
        ):
            issues.append(f"SCHEMA_REQUIRED_INVALID: {path}")

    visit(schema, "$")
    return sorted(set(issues))


def schema_issues(instance: Any, schema: Any) -> list[str]:
    definition_issues = schema_definition_issues(schema)
    if definition_issues:
        return definition_issues
    issues: list[str] = []

    def resolve(reference: str) -> Any:
        node = schema
        for raw in reference[2:].split("/"):
            key = raw.replace("~1", "/").replace("~0", "~")
            if not isinstance(node, dict) or key not in node:
                raise KeyError(reference)
            node = node[key]
        return node

    def visit(value: Any, node: Any, path: str) -> None:
        if node is False:
            issues.append(f"SCHEMA_FALSE_REJECTED: {path}")
            return
        if node is True:
            return
        if "$ref" in node:
            try:
                visit(value, resolve(node["$ref"]), path)
            except KeyError:
                issues.append(f"SCHEMA_REF_UNRESOLVED: {path}")
            return
        declared = node.get("type")
        if declared is not None and not _type_matches(value, declared):
            issues.append(f"SCHEMA_TYPE_MISMATCH: {path}")
            return
        if "const" in node and not _json_equal(value, node["const"]):
            issues.append(f"SCHEMA_CONST_MISMATCH: {path}")
        if "enum" in node and not any(_json_equal(value, candidate) for candidate in node["enum"]):
            issues.append(f"SCHEMA_ENUM_MISMATCH: {path}")
        if isinstance(value, dict):
            for key in node.get("required", []):
                if key not in value:
                    issues.append(f"SCHEMA_REQUIRED_MISSING: {path}.{key}")
            properties = node.get("properties", {})
            for key, child in value.items():
                if key in properties:
                    visit(child, properties[key], f"{path}.{key}")
                else:
                    additional = node.get("additionalProperties", True)
                    if additional is False:
                        issues.append(f"SCHEMA_ADDITIONAL_PROPERTY: {path}.{key}")
                    elif isinstance(additional, dict):
                        visit(child, additional, f"{path}.{key}")
        if isinstance(value, list):
            if len(value) < node.get("minItems", 0):
                issues.append(f"SCHEMA_MIN_ITEMS: {path}")
            if "maxItems" in node and len(value) > node["maxItems"]:
                issues.append(f"SCHEMA_MAX_ITEMS: {path}")
            if node.get("uniqueItems") is True:
                seen: set[bytes] = set()
                for item in value:
                    rendered = canonical_bytes(item)
                    if rendered in seen:
                        issues.append(f"SCHEMA_UNIQUE_ITEMS: {path}")
                        break
                    seen.add(rendered)
            if "items" in node:
                for index, child in enumerate(value):
                    visit(child, node["items"], f"{path}[{index}]")
        if isinstance(value, str):
            if len(value) < node.get("minLength", 0):
                issues.append(f"SCHEMA_MIN_LENGTH: {path}")
            if "maxLength" in node and len(value) > node["maxLength"]:
                issues.append(f"SCHEMA_MAX_LENGTH: {path}")
            if "pattern" in node and re.search(node["pattern"], value) is None:
                issues.append(f"SCHEMA_PATTERN_MISMATCH: {path}")
            if node.get("format") == "date-time" and not _date_time(value):
                issues.append(f"SCHEMA_DATE_TIME_INVALID: {path}")
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            if "minimum" in node and value < node["minimum"]:
                issues.append(f"SCHEMA_MINIMUM: {path}")
            if "maximum" in node and value > node["maximum"]:
                issues.append(f"SCHEMA_MAXIMUM: {path}")

    visit(instance, schema, "$")
    return sorted(set(issues))


def _type_matches(value: Any, declared: Any) -> bool:
    if isinstance(declared, list):
        return any(_type_matches(value, item) for item in declared)
    checks = {
        "array": lambda item: isinstance(item, list),
        "boolean": lambda item: isinstance(item, bool),
        "integer": lambda item: isinstance(item, int) and not isinstance(item, bool),
        "null": lambda item: item is None,
        "number": lambda item: isinstance(item, (int, float)) and not isinstance(item, bool),
        "object": lambda item: isinstance(item, dict),
        "string": lambda item: isinstance(item, str),
    }
    return bool(checks.get(declared, lambda _item: False)(value))


def _json_equal(left: Any, right: Any) -> bool:
    return type(left) is type(right) and left == right


def _date_time(value: str) -> bool:
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})", value):
        return False
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return False
    return parsed.tzinfo is not None


def _uri(value: str) -> bool:
    parsed = urlparse(value)
    return bool(parsed.scheme and (parsed.netloc or parsed.scheme == "urn"))


def evidence_graph_issues(document: dict[str, Any]) -> list[str]:
    issues: list[str] = []
    subject = document.get("subjectDigest")
    evidence = document.get("evidence", [])
    if not HEX64.fullmatch(str(subject or "")):
        issues.append("EVIDENCE_SUBJECT_DIGEST_INVALID")
    if not isinstance(evidence, list):
        return sorted(set(issues + ["EVIDENCE_LIST_INVALID"]))
    positions: dict[str, int] = {}
    dependencies: dict[str, list[str]] = {}
    for index, item in enumerate(evidence):
        if not isinstance(item, dict) or not isinstance(item.get("id"), str):
            issues.append("EVIDENCE_ID_INVALID")
            continue
        identity = item["id"]
        if identity in positions:
            issues.append(f"EVIDENCE_ID_DUPLICATE: {identity}")
        positions[identity] = index
        dependencies[identity] = item.get("dependsOn", [])
        if item.get("sha256") == subject:
            issues.append(f"EVIDENCE_SELF_REFERENCE: {identity}")
    for identity, values in dependencies.items():
        if not isinstance(values, list):
            issues.append(f"EVIDENCE_DEPENDENCY_INVALID: {identity}")
            continue
        for dependency in values:
            if dependency not in positions:
                issues.append(f"EVIDENCE_DEPENDENCY_MISSING: {identity}->{dependency}")
            elif positions[dependency] >= positions[identity]:
                issues.append(f"EVIDENCE_ORDER_INVALID: {identity}->{dependency}")

    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(identity: str) -> None:
        if identity in visiting:
            issues.append(f"EVIDENCE_GRAPH_CYCLE: {identity}")
            return
        if identity in visited:
            return
        visiting.add(identity)
        for dependency in dependencies.get(identity, []):
            if dependency in dependencies:
                visit(dependency)
        visiting.remove(identity)
        visited.add(identity)

    for identity in dependencies:
        visit(identity)
    bindings: dict[str, str] = {}
    for binding in document.get("versionBindings", []):
        if not isinstance(binding, dict):
            issues.append("EVIDENCE_VERSION_BINDING_INVALID")
            continue
        version = binding.get("version")
        digest = binding.get("manifestSha256")
        if version in bindings and bindings[version] != digest:
            issues.append(f"EVIDENCE_VERSION_DIGEST_REBIND: {version}")
        if isinstance(version, str) and isinstance(digest, str):
            bindings[version] = digest
    return sorted(set(issues))


def release_document_issues(document: Any) -> list[str]:
    issues: list[str] = []

    def visit(value: Any, path: str, key: str | None = None) -> None:
        if key == "sha":
            issues.append(f"RELEASE_GENERIC_SHA_FORBIDDEN: {path}")
        if key == "actionCommitOid" and not HEX40.fullmatch(str(value)):
            issues.append(f"RELEASE_ACTION_COMMIT_OID_INVALID: {path}")
        if key == "binarySha256" and not HEX64.fullmatch(str(value)):
            issues.append(f"RELEASE_BINARY_SHA256_INVALID: {path}")
        if key == "ociDigest" and not OCI_DIGEST.fullmatch(str(value)):
            issues.append(f"RELEASE_OCI_DIGEST_INVALID: {path}")
        if isinstance(value, str):
            if PLACEHOLDER.fullmatch(value.strip()):
                issues.append(f"RELEASE_PLACEHOLDER_FORBIDDEN: {path}")
            if LOCAL_PATH.search(value):
                issues.append(f"RELEASE_LOCAL_PATH_FORBIDDEN: {path}")
        elif isinstance(value, dict):
            for child_key, child in value.items():
                visit(child, f"{path}.{child_key}", child_key)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                visit(child, f"{path}[{index}]")

    visit(document, "$")
    return sorted(set(issues))
