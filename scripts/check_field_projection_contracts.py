#!/usr/bin/env python3
"""Validate the Story 1.8 field-projection successor contract and oracle."""

from __future__ import annotations

import hashlib
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


sys.path.insert(0, str(Path(__file__).resolve().parent))

from release_json import load_json, schema_definition_issues, schema_issues  # noqa: E402


FIELD_PROJECTION = Path("contracts/field-projection")
POLICY = FIELD_PROJECTION / "field-projection-policy-binding-1.0.0.json"
FIXTURE = FIELD_PROJECTION / "fixtures/valid/field-projection-oracle-1.0.0.json"
NEGATIVE_FIXTURE = FIELD_PROJECTION / "fixtures/invalid/negative-fixtures-1.0.0.json"
LOCK = FIELD_PROJECTION / "field-projection-contract-lock-1.0.0.json"
SCHEMAS = {
    POLICY: FIELD_PROJECTION / "field-projection.schema.json",
    FIXTURE: FIELD_PROJECTION / "field-projection-fixture.schema.json",
}
LOCKED_FILES = frozenset(
    {
        "contracts/field-projection/field-projection.schema.json",
        "contracts/field-projection/field-projection-fixture.schema.json",
        "contracts/field-projection/field-projection-policy-binding-1.0.0.json",
        "contracts/field-projection/fixtures/valid/field-projection-oracle-1.0.0.json",
        "contracts/field-projection/fixtures/invalid/negative-fixtures-1.0.0.json",
    }
)
PREDECESSOR_LOCK_DIGESTS = {
    "contracts/authorization/authorization-contract-lock-1.0.0.json":
        "0efd0d0c2a6949fff786c421bf12471cfd293979c64743aea0641a6248286c84",
    "contracts/audit/audit-contract-lock-1.0.0.json":
        "24a4861ddbfa9ac11253a26f6e3c906f1735d73c4d774d713faf9cee9f8cb62f",
    "contracts/audit/audit-contract-lock-1.1.0.json":
        "a07833c0c75ed28f6d0a534d697af0b09d3d5d8e0476f3c251c2bb8acd0439b9",
    "contracts/audit/audit-contract-lock-1.2.0.json":
        "cabb6259c4c3c9405823dba93a29de16c81ef730fbb04592bb20a9ad61f48c19",
    "contracts/audit/audit-contract-lock-1.3.0.json":
        "cd47617b3447c3476fa8b1036a2e488c2d527404eaf0de0f5a64444a74a29b7c",
    "contracts/audit/audit-contract-lock-1.4.0.json":
        "5244325f843b534ff0535c83446d44399b1cf2af8c521ec80920b2863046ae5a",
}
ROLE_IDS = {f"R{index}" for index in range(1, 8)}
FIELD_CLASSES = set("BICSENGT")
VISIBILITY_ORDER = {"C": 0, "M": 1, "H": 2}


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _parse_instant(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def _load_role_matrix(binding: dict[str, Any], root: Path | None = None) -> dict[str, dict[str, str]]:
    reference = binding.get("roleFieldPolicy", {})
    relative = reference.get("path") if isinstance(reference, dict) else None
    source_root = root or Path(__file__).resolve().parents[1]
    if not isinstance(relative, str):
        return {}
    try:
        policy = json.loads((source_root / relative).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return {
        item.get("roleId"): item.get("fieldVisibility")
        for item in policy.get("roles", [])
        if isinstance(item, dict)
        and isinstance(item.get("roleId"), str)
        and isinstance(item.get("fieldVisibility"), dict)
    }


def validate(
    project_root: Path,
    *,
    include_release: bool = True,
    include_predecessors: bool = True,
) -> list[str]:
    root = project_root.resolve()
    issues: list[str] = []
    documents: dict[Path, Any] = {}
    schemas: dict[Path, Any] = {}
    for schema_path in sorted(set(SCHEMAS.values())):
        try:
            schema = load_json(root / schema_path)
        except (OSError, ValueError):
            issues.append(f"FIELD_PROJECTION_SCHEMA_INVALID: {schema_path}")
            continue
        schemas[schema_path] = schema
        issues.extend(
            f"FIELD_PROJECTION_SCHEMA_INVALID: {schema_path}: {item}"
            for item in schema_definition_issues(schema)
        )
    for document_path, schema_path in SCHEMAS.items():
        try:
            document = load_json(root / document_path)
        except (OSError, ValueError):
            issues.append(f"FIELD_PROJECTION_DOCUMENT_INVALID: {document_path}")
            continue
        documents[document_path] = document
        schema = schemas.get(schema_path)
        if schema is not None:
            found = schema_issues(document, schema)
            if found:
                issues.append(f"FIELD_PROJECTION_SCHEMA_REJECTED: {document_path}: {found[0]}")

    binding = documents.get(POLICY)
    fixture = documents.get(FIXTURE)
    issues.extend(_binding_issues(root, binding))
    issues.extend(fixture_issues(binding, fixture, root=root))
    issues.extend(_negative_fixture_issues(root, binding))
    issues.extend(_lock_issues(root))
    if include_predecessors:
        issues.extend(_predecessor_issues(root))
    if include_release:
        issues.extend(_release_binding_issues(root))
    return sorted(set(issues))


def _binding_issues(root: Path, binding: Any) -> list[str]:
    if not isinstance(binding, dict):
        return ["FIELD_PROJECTION_BINDING_INVALID"]
    issues: list[str] = []
    if (
        binding.get("schemaVersion") != "FIELD-PROJECTION-1.0.0"
        or binding.get("policyBindingVersion") != "FIELD-PROJECTION-POLICY-BINDING-1.0.0"
        or binding.get("status") != "approved"
        or binding.get("unknownSemantics") != "fail-closed"
        or "roles" in binding
    ):
        issues.append("FIELD_PROJECTION_BINDING_VERSION_INVALID")

    reference = binding.get("roleFieldPolicy", {})
    relative = reference.get("path") if isinstance(reference, dict) else None
    if (
        reference.get("version") != "RFP-1.0.0"
        or relative != "contracts/authorization/role-field-policy-rfp-1.0.0.json"
        or not isinstance(relative, str)
        or not (root / relative).is_file()
        or reference.get("sha256") != _sha256(root / relative)
    ):
        issues.append("FIELD_PROJECTION_RFP_BINDING_INVALID")

    fields = binding.get("fields", [])
    field_by_name = {
        item.get("name"): item
        for item in fields
        if isinstance(item, dict) and isinstance(item.get("name"), str)
    }
    if len(field_by_name) != len(fields):
        issues.append("FIELD_PROJECTION_FIELD_CATALOG_INVALID")
    masks = binding.get("maskProfiles", {})
    for name, field in field_by_name.items():
        if field.get("fieldClass") not in FIELD_CLASSES:
            issues.append(f"FIELD_PROJECTION_FIELD_CLASS_UNKNOWN: {name}")
        profile = field.get("maskProfile")
        if profile is not None and profile not in masks:
            issues.append(f"FIELD_PROJECTION_MASK_PROFILE_UNKNOWN: {name}")
    object_schemas = binding.get("objectSchemas", [])
    object_by_class = {
        item.get("objectClass"): item
        for item in object_schemas
        if isinstance(item, dict) and isinstance(item.get("objectClass"), str)
    }
    if len(object_by_class) != len(object_schemas):
        issues.append("FIELD_PROJECTION_OBJECT_CATALOG_INVALID")
    for object_class, item in object_by_class.items():
        unknown = set(item.get("fields", [])) - set(field_by_name)
        if unknown:
            issues.append(f"FIELD_PROJECTION_OBJECT_FIELD_UNKNOWN: {object_class}")
    global_hidden = set(binding.get("globalHiddenFields", []))
    if not global_hidden or any(
        name not in field_by_name or field_by_name[name].get("globalHidden") is not True
        for name in global_hidden
    ):
        issues.append("FIELD_PROJECTION_GLOBAL_HIDDEN_INVALID")
    r5 = binding.get("conditionalRules", {}).get("R5", {})
    if (
        r5.get("windowSemantics") != "[startAt,endAt)"
        or len(set(r5.get("closedFieldUniverse", []))) != 7
    ):
        issues.append("FIELD_PROJECTION_R5_RULE_INVALID")
    return issues


def fixture_issues(
    binding: Any,
    document: Any,
    *,
    root: Path | None = None,
) -> list[str]:
    if not isinstance(binding, dict) or not isinstance(document, dict):
        return ["FIELD_PROJECTION_FIXTURE_INVALID"]
    issues: list[str] = []
    if document.get("schemaVersion") != "FIELD-PROJECTION-FIXTURE-1.0.0":
        issues.append("FIELD_PROJECTION_FIXTURE_SCHEMA_VERSION_INVALID")
    if document.get("policyBindingVersion") != binding.get("policyBindingVersion"):
        issues.append("FIELD_PROJECTION_FIXTURE_POLICY_VERSION_INVALID")
    matrix = document.get("roleMatrixOracle", {})
    if not isinstance(matrix, dict):
        issues.append("FIELD_PROJECTION_FIXTURE_ROLE_MATRIX_INVALID")
        matrix = {}
    for role_id, visibility in matrix.items():
        if role_id not in ROLE_IDS:
            issues.append("FIELD_PROJECTION_FIXTURE_ROLE_UNKNOWN")
        if not isinstance(visibility, dict) or set(visibility) != FIELD_CLASSES or any(
            value not in VISIBILITY_ORDER for value in visibility.values()
        ):
            issues.append("FIELD_PROJECTION_FIXTURE_ROLE_MATRIX_INVALID")
    expected_matrix = _load_role_matrix(binding, root)
    if matrix and expected_matrix and matrix != expected_matrix:
        issues.append("FIELD_PROJECTION_FIXTURE_ROLE_MATRIX_DRIFT")

    field_names = {
        item.get("name")
        for item in binding.get("fields", [])
        if isinstance(item, dict) and isinstance(item.get("name"), str)
    }
    for scenario in document.get("scenarios", []):
        if not isinstance(scenario, dict):
            issues.append("FIELD_PROJECTION_FIXTURE_SCENARIO_INVALID")
            continue
        if any(role not in ROLE_IDS for role in scenario.get("roles", [])):
            issues.append("FIELD_PROJECTION_FIXTURE_ROLE_UNKNOWN")
        start = _parse_instant(scenario.get("startAt"))
        end = _parse_instant(scenario.get("endAt"))
        if (start is None) != (end is None) or (start is not None and end is not None and start >= end):
            issues.append("FIELD_PROJECTION_FIXTURE_WINDOW_INVALID")
        values = scenario.get("values", {})
        if not isinstance(values, dict) or any(name not in field_names for name in values):
            issues.append("FIELD_PROJECTION_FIXTURE_FIELD_UNKNOWN")
    return sorted(set(issues))


def _denied(code: str = "FIELD_PROJECTION_DENIED") -> dict[str, Any]:
    error = {
        "status": 403 if code == "FIELD_PROJECTION_DENIED" else 503,
        "code": code,
        "message": "请求的数据不可用" if code == "FIELD_PROJECTION_DENIED" else "敏感数据服务暂不可用",
    }
    return {"json": {}, "exportSink": {}, "clearConditionalFields": [], "error": error}


def project_fixture(binding: dict[str, Any], scenario: dict[str, Any]) -> dict[str, Any]:
    """Run the transport-neutral conformance oracle for a fixture scenario."""
    object_schemas = {
        item.get("objectClass"): item
        for item in binding.get("objectSchemas", [])
        if isinstance(item, dict)
    }
    object_schema = object_schemas.get(scenario.get("objectClass"))
    if (
        not isinstance(object_schema, dict)
        or scenario.get("authorizationOutcome") != "ALLOW"
        or scenario.get("purpose") not in object_schema.get("approvedPurposes", [])
    ):
        return _denied()
    roles = scenario.get("roles", [])
    role_matrix = _load_role_matrix(binding)
    if not roles or any(role not in role_matrix for role in roles):
        return _denied()
    if "R2" in roles and scenario.get("currentWorkItem") is not True:
        return _denied()
    if "R5" in roles:
        start = _parse_instant(scenario.get("startAt"))
        end = _parse_instant(scenario.get("endAt"))
        now = _parse_instant(scenario.get("serverNow"))
        if (
            scenario.get("objectClass") != "TransferOrder"
            or scenario.get("assigned") is not True
            or start is None
            or end is None
            or now is None
            or not start <= now < end
        ):
            return _denied()
    if "R6" in roles and scenario.get("objectClass") == "SubjectMappingException" and (
        scenario.get("purpose") != binding.get("conditionalRules", {}).get("R6", {}).get("purpose")
        or scenario.get("ownedSource") is not True
    ):
        return _denied()

    field_by_name = {
        item.get("name"): item
        for item in binding.get("fields", [])
        if isinstance(item, dict) and isinstance(item.get("name"), str)
    }
    approved = set(object_schema.get("fields", []))
    values = scenario.get("values", {})
    r5_universe = set(binding.get("conditionalRules", {}).get("R5", {}).get("closedFieldUniverse", []))
    allowlist = set(scenario.get("fieldAllowlist", []))
    delegation = scenario.get("delegationFields")
    delegation_fields = set(delegation) if isinstance(delegation, list) else None
    projected: dict[str, Any] = {}
    clear_conditional: list[str] = []
    for field_name in object_schema.get("fields", []):
        if field_name not in values or field_name not in approved:
            continue
        descriptor = field_by_name.get(field_name)
        if not isinstance(descriptor, dict) or descriptor.get("globalHidden") is True:
            continue
        field_class = descriptor.get("fieldClass")
        decisions = [role_matrix[role].get(field_class, "H") for role in roles]
        visibility = max(decisions, key=lambda value: VISIBILITY_ORDER.get(value, 2))
        if "R6" in roles and field_class == "I" and scenario.get("ownedSource") is True:
            visibility = "C"
        if "R5" in roles and field_class in {"C", "S", "N"}:
            if (
                field_name not in r5_universe
                or field_name not in allowlist
                or (delegation_fields is not None and field_name not in delegation_fields)
            ):
                visibility = "H"
            elif visibility == "C":
                clear_conditional.append(field_name)
        if visibility == "C":
            projected[field_name] = values[field_name]
        elif visibility == "M":
            profile = binding.get("maskProfiles", {}).get(descriptor.get("maskProfile"))
            if isinstance(profile, dict) and "fixedValue" in profile:
                projected[field_name] = profile["fixedValue"]
    return {
        "json": projected,
        "exportSink": dict(projected),
        "clearConditionalFields": clear_conditional,
        "summary": {
            "clear": len(projected),
            "masked": sum(
                1
                for name, value in projected.items()
                if field_by_name.get(name, {}).get("maskProfile")
                and value == binding.get("maskProfiles", {}).get(
                    field_by_name[name].get("maskProfile"), {}
                ).get("fixedValue")
            ),
        },
        "error": None,
    }


def _negative_fixture_issues(root: Path, binding: Any) -> list[str]:
    try:
        negative = load_json(root / NEGATIVE_FIXTURE)
    except (OSError, ValueError):
        return ["FIELD_PROJECTION_NEGATIVE_FIXTURES_INVALID"]
    if not isinstance(binding, dict) or not isinstance(negative, dict):
        return ["FIELD_PROJECTION_NEGATIVE_FIXTURES_INVALID"]
    issues: list[str] = []
    for case in negative.get("cases", []):
        if not isinstance(case, dict):
            issues.append("FIELD_PROJECTION_NEGATIVE_FIXTURES_INVALID")
            continue
        expected = case.get("expectedCode")
        found = fixture_issues(binding, case.get("document"), root=root)
        if not isinstance(expected, str) or not any(item.startswith(expected) for item in found):
            issues.append(f"FIELD_PROJECTION_NEGATIVE_FIXTURE_NOT_REJECTED: {case.get('caseId')}")
    return issues


def _lock_issues(root: Path) -> list[str]:
    try:
        lock = load_json(root / LOCK)
    except (OSError, ValueError):
        return ["FIELD_PROJECTION_LOCK_INVALID"]
    entries = lock.get("files", []) if isinstance(lock, dict) else []
    by_path = {item.get("path"): item for item in entries if isinstance(item, dict)}
    issues: list[str] = []
    if (
        not isinstance(lock, dict)
        or lock.get("version") != "FIELD-PROJECTION-CONTRACT-LOCK-1.0.0"
        or set(by_path) != set(LOCKED_FILES)
        or len(by_path) != len(entries)
    ):
        issues.append("FIELD_PROJECTION_LOCK_FILE_SET_INVALID")
    for relative, item in by_path.items():
        path = root / str(relative)
        if not path.is_file() or item.get("sha256") != _sha256(path):
            issues.append(f"FIELD_PROJECTION_LOCK_DIGEST_MISMATCH: {relative}")
    return issues


def _predecessor_issues(root: Path) -> list[str]:
    return [
        f"FIELD_PROJECTION_PREDECESSOR_LOCK_CHANGED: {relative}"
        for relative, expected in PREDECESSOR_LOCK_DIGESTS.items()
        if not (root / relative).is_file() or _sha256(root / relative) != expected
    ]


def _release_binding_issues(root: Path) -> list[str]:
    try:
        assembly = (root / "release/assembly.py").read_text(encoding="utf-8")
        manifests = (root / "release/manifests.py").read_text(encoding="utf-8")
        schema = load_json(root / "contracts/release/release-manifest.schema.json")
        manifest = load_json(root / "contracts/release/fixtures/valid/release-manifest.json")
    except (OSError, ValueError):
        return ["FIELD_PROJECTION_RELEASE_BINDING_INVALID"]
    controlled_schema = schema.get("properties", {}).get("controlledInputs", {})
    runtime_schema = schema.get("properties", {}).get("runtimeEvidence", {})
    controlled_ids = {item.get("id") for item in manifest.get("controlledInputs", []) if isinstance(item, dict)}
    runtime = {item.get("id"): item for item in manifest.get("runtimeEvidence", []) if isinstance(item, dict)}
    required_runtime = {"export-job-download", "transfer-task", "mobile-projection"}
    checks = (
        '"FieldProjection": ("FIELD-PROJECTION-CONTRACT-LOCK-1.0.0", "contracts/field-projection/field-projection-contract-lock-1.0.0.json")' in assembly,
        '"FieldProjection"' in manifests,
        controlled_schema.get("minItems") == 19,
        controlled_schema.get("maxItems") == 19,
        runtime_schema.get("minItems") == 7,
        runtime_schema.get("maxItems") == 7,
        "FieldProjection" in controlled_ids,
        required_runtime <= set(runtime),
        all(runtime[item].get("runtimeEvidenceClaim") == "none" for item in required_runtime if item in runtime),
    )
    return [] if all(checks) else ["FIELD_PROJECTION_RELEASE_BINDING_INVALID"]


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) == 2 else Path(".")
    issues = validate(root)
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print("field-projection-contracts: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
