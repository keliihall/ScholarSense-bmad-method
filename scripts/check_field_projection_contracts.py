#!/usr/bin/env python3
"""Validate the Story 1.8 field-projection successor contract and oracle."""

from __future__ import annotations

import hashlib
import json
import re
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
SUCCESSOR_POLICY = FIELD_PROJECTION / "field-projection-policy-binding-1.1.0.json"
SUCCESSOR_FIXTURE = FIELD_PROJECTION / "fixtures/valid/field-projection-oracle-1.1.0.json"
SUCCESSOR_NEGATIVE_FIXTURE = (
    FIELD_PROJECTION / "fixtures/invalid/negative-fixtures-1.1.0.json"
)
SUCCESSOR_LOCK = FIELD_PROJECTION / "field-projection-contract-lock-1.1.0.json"
SCHEMAS = {
    POLICY: FIELD_PROJECTION / "field-projection.schema.json",
    FIXTURE: FIELD_PROJECTION / "field-projection-fixture.schema.json",
    SUCCESSOR_POLICY: FIELD_PROJECTION / "field-projection-1.1.schema.json",
    SUCCESSOR_FIXTURE: FIELD_PROJECTION / "field-projection-fixture-1.1.schema.json",
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
SUCCESSOR_LOCKED_FILES = frozenset(
    {
        "contracts/field-projection/field-projection-1.1.schema.json",
        "contracts/field-projection/field-projection-fixture-1.1.schema.json",
        "contracts/field-projection/field-projection-policy-binding-1.1.0.json",
        "contracts/field-projection/fixtures/valid/field-projection-oracle-1.1.0.json",
        "contracts/field-projection/fixtures/invalid/negative-fixtures-1.1.0.json",
    }
)
FIELD_PROJECTION_PREDECESSOR_DIGESTS = {
    "contracts/field-projection/field-projection.schema.json":
        "2a21e1c7285c9956ff15123ba0b4df011fe4e99440a1c34414a75dd7d6118c28",
    "contracts/field-projection/field-projection-fixture.schema.json":
        "da39d2bf426a4e5c84a329301e154d00333026eca19c1e5027607cc8a54f5edb",
    "contracts/field-projection/field-projection-policy-binding-1.0.0.json":
        "5a0592296a43049bf745f9e826df65dc3f4b0479cea17fd822e98ac04d92f880",
    "contracts/field-projection/fixtures/valid/field-projection-oracle-1.0.0.json":
        "123350691bf4ae29cb5cfefdb4a3b305f466da568572b94df3bac770c3f50785",
    "contracts/field-projection/fixtures/invalid/negative-fixtures-1.0.0.json":
        "75f94f3e2e2d4ffad24782168b720c9151eebae8a555b02d98f03678373d200b",
    "contracts/field-projection/field-projection-contract-lock-1.0.0.json":
        "9b81341bab67d0c9c04858c90946c1184d5e02de67ff5c7752b3439b913ffee6",
}
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
QUALITY_SNAPSHOT_FIELDS = {
    "snapshotId": ("B", "string", False),
    "batchId": ("B", "string", False),
    "sourceId": ("B", "string", False),
    "assessedBatchStatus": ("B", "string", False),
    "overallResult": ("B", "string", False),
    "observationWindow.startAt": ("B", "timestamp", False),
    "observationWindow.endAt": ("B", "timestamp", False),
    "cutoffAt": ("B", "timestamp", False),
    "evaluatedAt": ("B", "timestamp", False),
    "watermark": ("B", "string", False),
    "metricResults[].metricId": ("B", "string", False),
    "metricResults[].result": ("B", "string", False),
    "metricResults[].applicable": ("B", "boolean", False),
    "metricResults[].numerator": ("B", "integer", False),
    "metricResults[].denominator": ("B", "integer", False),
    "metricResults[].valueBasisPoints": ("B", "integer", True),
    "metricResults[].unit": ("B", "string", False),
    "metricResults[].operator": ("B", "string", False),
    "metricResults[].thresholdNumerator": ("B", "integer", False),
    "metricResults[].thresholdDenominator": ("B", "integer", False),
    "metricResults[].boundary": ("B", "string", False),
    "metricResults[].reasonCode": ("E", "string", True),
    "impactScopeCodes[]": ("E", "string", False),
    "sourceOwnerRef": ("G", "string", False),
    "approvalRef": ("G", "string", False),
    "effectiveAt": ("G", "timestamp", False),
    "retentionScheduleVersion": ("G", "string", False),
    "qualityMetricDecisionProfileVersion": ("T", "string", False),
    "qualityMetricDecisionProfileDigest": ("T", "string", False),
    "qualityGateVersion": ("T", "string", False),
    "qualityGateDigest": ("T", "string", False),
    "metricResults[].formulaId": ("T", "string", False),
    "metricResults[].formulaVersion": ("T", "string", False),
    "canonicalizationProfile": ("T", "string", False),
    "manifestDigest": ("T", "string", False),
    "sourceSchemaVersion": ("T", "string", False),
    "sourceSchemaDigest": ("T", "string", False),
    "immutableHash": ("T", "string", False),
    "traceId": ("T", "string", False),
    "lineageId": ("T", "string", False),
    "supersedesSnapshotId": ("T", "string", True),
    "aggregateVersion": ("T", "integer", False),
}
QUALITY_SNAPSHOT_LEAF_POLICY = {
    "grammar": "closed-explicit-top-level-and-approved-array-leaf-paths",
    "unknownField": "H/omit",
    "unknownPath": "H/omit",
    "pathSuperset": "H/omit",
    "jsonPointer": "forbidden",
    "recursiveObject": "forbidden",
    "numericArrayIndex": "forbidden",
    "serializationParity": ["pre-serialization", "post-serialization"],
}
JAVA_FIELD_CLASSES = {
    "BASIC": "B",
    "IDENTITY": "I",
    "CONTACT": "C",
    "SENSITIVE_CARE": "S",
    "EVIDENCE": "E",
    "NARRATIVE": "N",
    "GOVERNANCE": "G",
    "TECHNICAL": "T",
}


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
    successor_binding = documents.get(SUCCESSOR_POLICY)
    successor_fixture = documents.get(SUCCESSOR_FIXTURE)
    issues.extend(_binding_issues(root, binding))
    issues.extend(fixture_issues(binding, fixture, root=root))
    issues.extend(_negative_fixture_issues(root, binding))
    issues.extend(_successor_binding_issues(root, binding, successor_binding))
    issues.extend(quality_snapshot_fixture_issues(successor_binding, successor_fixture))
    issues.extend(_successor_negative_fixture_issues(root, successor_binding))
    issues.extend(_lock_issues(root))
    issues.extend(_successor_lock_issues(root))
    issues.extend(runtime_parity_issues(root, binding=successor_binding))
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


def _successor_binding_issues(
    root: Path,
    predecessor: Any,
    binding: Any,
) -> list[str]:
    if not isinstance(predecessor, dict) or not isinstance(binding, dict):
        return ["FIELD_PROJECTION_SUCCESSOR_BINDING_INVALID"]
    issues: list[str] = []
    if (
        binding.get("schemaVersion") != "FIELD-PROJECTION-1.1.0"
        or binding.get("policyBindingVersion")
            != "FIELD-PROJECTION-POLICY-BINDING-1.1.0"
        or binding.get("status") != "approved"
        or binding.get("unknownSemantics") != "fail-closed"
        or "roles" in binding
    ):
        issues.append("FIELD_PROJECTION_SUCCESSOR_BINDING_VERSION_INVALID")

    references = (
        (
            "predecessor",
            "FIELD-PROJECTION-POLICY-BINDING-1.0.0",
            "contracts/field-projection/field-projection-policy-binding-1.0.0.json",
            "FIELD_PROJECTION_SUCCESSOR_PREDECESSOR_INVALID",
        ),
        (
            "roleFieldPolicy",
            "RFP-1.0.0",
            "contracts/authorization/role-field-policy-rfp-1.0.0.json",
            "FIELD_PROJECTION_SUCCESSOR_RFP_BINDING_INVALID",
        ),
        (
            "roleFieldPolicyFixture",
            "RFP-FIXTURE-1.0.0",
            "contracts/authorization/rfp-fixture-1.0.0.json",
            "FIELD_PROJECTION_SUCCESSOR_RFP_FIXTURE_BINDING_INVALID",
        ),
    )
    for key, version, relative, code in references:
        reference = binding.get(key, {})
        path = root / relative
        if (
            not isinstance(reference, dict)
            or reference.get("version") != version
            or reference.get("path") != relative
            or not path.is_file()
            or reference.get("sha256") != _sha256(path)
        ):
            issues.append(code)

    if binding.get("leafPathPolicy") != QUALITY_SNAPSHOT_LEAF_POLICY:
        issues.append("FIELD_PROJECTION_SUCCESSOR_LEAF_POLICY_INVALID")

    preserved_top_level = (
        "maskProfiles", "fields", "globalHiddenFields", "projectionOrder", "errors",
    )
    if any(binding.get(key) != predecessor.get(key) for key in preserved_top_level):
        issues.append("FIELD_PROJECTION_SUCCESSOR_PREDECESSOR_SEMANTICS_DRIFT")
    successor_rules = binding.get("conditionalRules", {})
    if not isinstance(successor_rules, dict):
        successor_rules = {}
    preserved_rules = {
        key: value
        for key, value in successor_rules.items()
        if key != "R6QualitySnapshot"
    }
    if preserved_rules != predecessor.get("conditionalRules"):
        issues.append("FIELD_PROJECTION_SUCCESSOR_PREDECESSOR_RULE_DRIFT")

    objects = binding.get("objectSchemas", [])
    if not isinstance(objects, list):
        objects = []
    legacy_objects = [
        item for item in objects
        if isinstance(item, dict) and item.get("objectClass") != "QualitySnapshot"
    ]
    if legacy_objects != predecessor.get("objectSchemas"):
        issues.append("FIELD_PROJECTION_SUCCESSOR_PREDECESSOR_OBJECT_DRIFT")
    quality_objects = [
        item for item in objects
        if isinstance(item, dict) and item.get("objectClass") == "QualitySnapshot"
    ]
    if len(quality_objects) != 1 or len(objects) != 4:
        issues.append("FIELD_PROJECTION_SUCCESSOR_OBJECT_CATALOG_INVALID")
        quality = {}
    else:
        quality = quality_objects[0]
    if (
        quality.get("approvedPurposes") != ["data-quality.read"]
        or quality.get("requiredScopeAnchor") != "OWNED_SOURCE"
    ):
        issues.append("FIELD_PROJECTION_SUCCESSOR_QUALITY_SCOPE_INVALID")

    descriptors = quality.get("fields", []) if isinstance(quality, dict) else []
    descriptor_by_path = {
        item.get("path"): item
        for item in descriptors
        if isinstance(item, dict) and isinstance(item.get("path"), str)
    }
    if len(descriptor_by_path) != len(descriptors) or len(descriptor_by_path) != 42:
        issues.append("FIELD_PROJECTION_SUCCESSOR_QUALITY_FIELD_CATALOG_INVALID")
    actual = {
        path: (
            item.get("fieldClass"),
            item.get("valueType"),
            item.get("nullable"),
        )
        for path, item in descriptor_by_path.items()
    }
    if actual != QUALITY_SNAPSHOT_FIELDS or any(
        item.get("globalHidden") is not False
        or "name" in item
        or "maskProfile" in item
        for item in descriptor_by_path.values()
    ):
        issues.append("FIELD_PROJECTION_SUCCESSOR_QUALITY_FIELDS_INVALID")

    rule = successor_rules.get("R6QualitySnapshot", {})
    if rule != {
        "objectClass": "QualitySnapshot",
        "purpose": "data-quality.read",
        "requiresOwnedSource": True,
        "requiresAssessedSnapshot": True,
        "ownerResolution": "snapshot-to-current-persisted-source-owner",
        "clearFieldClasses": ["B", "E", "G", "T"],
        "hiddenFieldClasses": ["I", "C", "S", "N"],
    }:
        issues.append("FIELD_PROJECTION_SUCCESSOR_R6_RULE_INVALID")

    if "DataBatch" in json.dumps(binding, ensure_ascii=False):
        issues.append("FIELD_PROJECTION_SUCCESSOR_DATABATCH_FORBIDDEN")
    issues.extend(_rfp_quality_snapshot_oracle_issues(root, binding))
    return sorted(set(issues))


def _rfp_quality_snapshot_oracle_issues(
    root: Path,
    binding: dict[str, Any],
) -> list[str]:
    reference = binding.get("roleFieldPolicyFixture", {})
    relative = reference.get("path") if isinstance(reference, dict) else None
    if not isinstance(relative, str):
        return ["FIELD_PROJECTION_SUCCESSOR_RFP_ORACLE_INVALID"]
    try:
        fixture = load_json(root / relative)
    except (OSError, ValueError):
        return ["FIELD_PROJECTION_SUCCESSOR_RFP_ORACLE_INVALID"]
    objects = {
        item.get("objectToken"): item
        for item in fixture.get("objects", [])
        if isinstance(item, dict)
    }
    oracle = {
        item.get("scenarioId"): item
        for item in fixture.get("oracle", [])
        if isinstance(item, dict)
    }
    checks = (
        objects.get("DQ-A", {}).get("objectClass") == "QualitySnapshot",
        objects.get("DQ-A", {}).get("anchors") == ["owned-source"],
        objects.get("DQ-B", {}).get("objectClass") == "QualitySnapshot",
        objects.get("DQ-B", {}).get("anchors") == [],
        oracle.get("R6-DQ-A", {}).get("expectedResult") == "ALLOW",
        oracle.get("R6-DQ-B", {}).get("expectedResult") == "DENY",
        oracle.get("R6-DQ-B", {}).get("expectedReason") == "SCOPE_NOT_PROVEN",
        objects.get("JOB-1", {}).get("objectClass") == "Job",
        objects.get("JOB-1", {}).get("anchors") == ["technical-object"],
        oracle.get("R7-JOB-1", {}).get("actionId") == "platform.read",
        oracle.get("R7-JOB-1", {}).get("expectedResult") == "ALLOW",
    )
    return [] if all(checks) else ["FIELD_PROJECTION_SUCCESSOR_RFP_ORACLE_INVALID"]


def quality_snapshot_fixture_issues(
    binding: Any,
    document: Any,
) -> list[str]:
    if not isinstance(binding, dict) or not isinstance(document, dict):
        return ["FIELD_PROJECTION_SUCCESSOR_FIXTURE_INVALID"]
    issues: list[str] = []
    if document.get("schemaVersion") != "FIELD-PROJECTION-FIXTURE-1.1.0":
        issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_SCHEMA_VERSION_INVALID")
    if document.get("policyBindingVersion") != binding.get("policyBindingVersion"):
        issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_POLICY_VERSION_INVALID")
    if document.get("authorizationOracle") != binding.get("roleFieldPolicyFixture"):
        issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_AUTHORIZATION_ORACLE_INVALID")

    quality = next((
        item for item in binding.get("objectSchemas", [])
        if isinstance(item, dict) and item.get("objectClass") == "QualitySnapshot"
    ), {})
    descriptors = {
        item.get("path"): item
        for item in quality.get("fields", [])
        if isinstance(item, dict) and isinstance(item.get("path"), str)
    }
    scenarios = document.get("scenarios", [])
    if not isinstance(scenarios, list):
        return sorted(set(issues + ["FIELD_PROJECTION_SUCCESSOR_FIXTURE_SCENARIO_INVALID"]))
    scenario_by_id: dict[str, dict[str, Any]] = {}
    for scenario in scenarios:
        if not isinstance(scenario, dict):
            issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_SCENARIO_INVALID")
            continue
        scenario_id = scenario.get("scenarioId")
        if not isinstance(scenario_id, str) or not scenario_id or scenario_id in scenario_by_id:
            issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_SCENARIO_INVALID")
        else:
            scenario_by_id[scenario_id] = scenario
        if scenario.get("roles") != ["R6"]:
            issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_ROLE_INVALID")
        if scenario.get("objectClass") != "QualitySnapshot":
            issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_OBJECT_INVALID")
        if _parse_instant(scenario.get("serverNow")) is None:
            issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_TIME_INVALID")
        if not isinstance(scenario.get("ownedSource"), bool) or not isinstance(
            scenario.get("assessedSnapshot"), bool
        ):
            issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_SCOPE_INVALID")
        if scenario.get("authorizationOutcome") not in {"ALLOW", "DENY"}:
            issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_AUTHORIZATION_OUTCOME_INVALID")
        oracle_id = scenario.get("authorizationOracleScenarioId")
        expected_oracle = {
            "R6-DQ-A": ("DQ-A", True, "ALLOW"),
            "R6-DQ-B": ("DQ-B", False, "DENY"),
        }.get(oracle_id)
        if expected_oracle is None or (
            scenario.get("objectToken"),
            scenario.get("ownedSource"),
            scenario.get("authorizationOutcome"),
        ) != expected_oracle:
            issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_AUTHORIZATION_OUTCOME_INVALID")

        values = scenario.get("values")
        if not isinstance(values, dict):
            issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_VALUES_INVALID")
            continue
        metric_lengths: set[int] = set()
        for path, value in values.items():
            descriptor = descriptors.get(path)
            if descriptor is None:
                continue
            if not _quality_value_matches(path, descriptor, value):
                issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_VALUE_TYPE_INVALID")
            if path.startswith("metricResults[].") and isinstance(value, list):
                metric_lengths.add(len(value))
        if len(metric_lengths) > 1:
            issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_ARRAY_ALIGNMENT_INVALID")

    required = {
        "R6-OWNED-QUALITY-SNAPSHOT",
        "R6-UNOWNED-QUALITY-SNAPSHOT",
        "R6-OWNED-UNKNOWN-PATH",
    }
    if not required <= set(scenario_by_id):
        issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_SCENARIO_SET_INVALID")
    owned = scenario_by_id.get("R6-OWNED-QUALITY-SNAPSHOT")
    if isinstance(owned, dict) and set(owned.get("values", {})) != set(descriptors):
        issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_FULL_ORACLE_INVALID")
    unknown = scenario_by_id.get("R6-OWNED-UNKNOWN-PATH")
    if isinstance(unknown, dict) and not {
        "studentOfficialRef",
        "metricResults[].evidenceBody",
        "metricResults[0].metricId",
        "/metricResults/0/metricId",
        "observationWindow.startAt.extra",
    } <= set(unknown.get("values", {})):
        issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_UNKNOWN_ORACLE_INVALID")

    for scenario_id in (
        "R6-OWNED-QUALITY-SNAPSHOT",
        "R6-UNOWNED-QUALITY-SNAPSHOT",
        "R6-OWNED-UNKNOWN-PATH",
    ):
        scenario = scenario_by_id.get(scenario_id)
        if not isinstance(scenario, dict):
            continue
        projected = project_quality_snapshot_fixture(binding, scenario)
        if projected.get("json") != projected.get("exportSink"):
            issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_SERIALIZATION_PARITY_INVALID")
    if isinstance(owned, dict):
        projected = project_quality_snapshot_fixture(binding, owned)
        if projected.get("error") is not None or set(projected.get("json", {})) != set(descriptors):
            issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_FULL_ORACLE_INVALID")
    unowned = scenario_by_id.get("R6-UNOWNED-QUALITY-SNAPSHOT")
    if isinstance(unowned, dict) and project_quality_snapshot_fixture(
        binding, unowned
    ).get("error", {}).get("code") != "FIELD_PROJECTION_DENIED":
        issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_SCOPE_ORACLE_INVALID")
    for scenario_id in ("R6-OWNED-WRONG-PURPOSE", "R6-OWNED-UNASSESSED"):
        scenario = scenario_by_id.get(scenario_id)
        if isinstance(scenario, dict) and project_quality_snapshot_fixture(
            binding, scenario
        ).get("error", {}).get("code") != "FIELD_PROJECTION_DENIED":
            issues.append("FIELD_PROJECTION_SUCCESSOR_FIXTURE_SCOPE_ORACLE_INVALID")
    return sorted(set(issues))


def _quality_value_matches(
    path: str,
    descriptor: dict[str, Any],
    value: Any,
) -> bool:
    repeated = "[]" in path
    values = value if repeated and isinstance(value, list) else [value]
    if repeated and not isinstance(value, list):
        return False
    expected = descriptor.get("valueType")
    nullable = descriptor.get("nullable") is True
    for item in values:
        if item is None:
            if not nullable:
                return False
            continue
        if expected == "boolean":
            valid = isinstance(item, bool)
        elif expected == "integer":
            valid = isinstance(item, int) and not isinstance(item, bool)
        elif expected == "timestamp":
            valid = _parse_instant(item) is not None
        else:
            valid = isinstance(item, str)
        if not valid:
            return False
    return True


def project_quality_snapshot_fixture(
    binding: dict[str, Any],
    scenario: dict[str, Any],
) -> dict[str, Any]:
    quality = next((
        item for item in binding.get("objectSchemas", [])
        if isinstance(item, dict) and item.get("objectClass") == "QualitySnapshot"
    ), None)
    rule = binding.get("conditionalRules", {}).get("R6QualitySnapshot", {})
    if (
        not isinstance(quality, dict)
        or scenario.get("roles") != ["R6"]
        or scenario.get("objectClass") != "QualitySnapshot"
        or scenario.get("purpose") not in quality.get("approvedPurposes", [])
        or scenario.get("authorizationOutcome") != "ALLOW"
        or scenario.get("ownedSource") is not True
        or scenario.get("assessedSnapshot") is not True
        or rule.get("requiresOwnedSource") is not True
        or rule.get("requiresAssessedSnapshot") is not True
    ):
        return _denied()
    values = scenario.get("values", {})
    if not isinstance(values, dict):
        return _denied()
    clear_classes = set(rule.get("clearFieldClasses", []))
    projected: dict[str, Any] = {}
    for descriptor in quality.get("fields", []):
        if not isinstance(descriptor, dict):
            continue
        path = descriptor.get("path")
        if (
            not isinstance(path, str)
            or path not in values
            or descriptor.get("globalHidden") is True
            or descriptor.get("fieldClass") not in clear_classes
            or not _quality_value_matches(path, descriptor, values[path])
        ):
            continue
        projected[path] = values[path]
    post_serialization = json.loads(json.dumps(
        projected,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
    ))
    return {
        "json": post_serialization,
        "exportSink": dict(post_serialization),
        "clearConditionalFields": list(post_serialization),
        "summary": {"clear": len(post_serialization), "masked": 0},
        "error": None,
    }


def runtime_parity_issues(
    project_root: Path,
    *,
    binding: Any | None = None,
) -> list[str]:
    root = project_root.resolve()
    if binding is None:
        try:
            binding = load_json(root / SUCCESSOR_POLICY)
        except (OSError, ValueError):
            binding = None
    if not isinstance(binding, dict):
        return ["FIELD_PROJECTION_RUNTIME_BINDING_UNAVAILABLE"]
    paths = {
        "catalog": root / (
            "backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/"
            "domain/FieldProjectionCatalog.java"
        ),
        "object_class": root / (
            "backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/"
            "domain/ProjectionObjectClass.java"
        ),
        "role_catalog": root / (
            "backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/"
            "domain/RoleFieldPolicyCatalog.java"
        ),
        "job_query": root / (
            "backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/"
            "application/SubjectRecomputeJobQueryService.java"
        ),
        "owner_provider": root / (
            "backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/"
            "adapters/outbound/CatalogOwnerEvidenceProvider.java"
        ),
        "shell": root / (
            "backend/src/main/java/cn/edu/suda/scholarsense/subjectregistry/"
            "adapters/SubjectRegistryConfiguration.java"
        ),
        "mapping_view": root / (
            "frontend/src/domains/subject-registry/internal/"
            "SubjectMappingExceptionsView.vue"
        ),
    }
    sources: dict[str, str] = {}
    issues: list[str] = []
    for key, path in paths.items():
        try:
            sources[key] = path.read_text(encoding="utf-8")
        except OSError:
            issues.append(f"FIELD_PROJECTION_RUNTIME_SOURCE_MISSING: {path.relative_to(root)}")
    if issues:
        return sorted(set(issues))

    catalog = sources["catalog"]
    if 'VERSION = "FIELD-PROJECTION-POLICY-BINDING-1.1.0"' not in catalog:
        issues.append("FIELD_PROJECTION_RUNTIME_VERSION_DRIFT")
    try:
        quality_block = catalog.split(
            "List<ApprovedFieldDescriptor> qualitySnapshotFields = List.of(", 1
        )[1].split("List<ProjectionObjectSchema> objectSchemas", 1)[0]
    except IndexError:
        quality_block = ""
    java_descriptors: list[tuple[str, str | None, str]] = []
    for path, java_class, value_type in re.findall(
        r'f\("([^"]+)",\s*FieldClass\.([A-Z_]+),\s*"([^"]+)",'
        r'\s*null,\s*false,\s*masks\)',
        quality_block,
    ):
        java_descriptors.append((path, JAVA_FIELD_CLASSES.get(java_class), value_type))
    quality = next((
        item for item in binding.get("objectSchemas", [])
        if isinstance(item, dict) and item.get("objectClass") == "QualitySnapshot"
    ), {})
    contract_descriptors = [
        (item.get("path"), item.get("fieldClass"), item.get("valueType"))
        for item in quality.get("fields", [])
        if isinstance(item, dict)
    ]
    if (
        len(java_descriptors) != 42
        or len(set(java_descriptors)) != 42
        or set(java_descriptors) != set(contract_descriptors)
        or "Map.of(ProjectionObjectClass.QUALITY_SNAPSHOT, qualitySnapshotFields)"
            not in catalog
    ):
        issues.append("FIELD_PROJECTION_RUNTIME_QUALITY_DESCRIPTOR_DRIFT")
    if "QUALITY_SNAPSHOT" not in sources["object_class"]:
        issues.append("FIELD_PROJECTION_RUNTIME_QUALITY_OBJECT_DRIFT")

    role_catalog = sources["role_catalog"]
    try:
        r6_block = role_catalog.split("result.put(RolePackage.R6", 1)[1].split(
            "result.put(RolePackage.R7", 1
        )[0]
        r7_block = role_catalog.split("result.put(RolePackage.R7", 1)[1].split(
            "return Map.copyOf(result)", 1
        )[0]
    except IndexError:
        r6_block = ""
        r7_block = ""
    if (
        "ObjectClass.JOB" in r6_block
        or 'pair(ObjectClass.QUALITY_SNAPSHOT, "data-quality.read")' not in r6_block
        or "ScopeAnchor.OWNED_SOURCE" not in r6_block
    ):
        issues.append("FIELD_PROJECTION_RUNTIME_R6_JOB_DRIFT")
    if (
        'pair(ObjectClass.JOB, "platform.read"' not in r7_block
        or "ObjectClass.JOB" not in r7_block
        or "ScopeAnchor.TECHNICAL_OBJECT" not in r7_block
        or '"data-quality.read"' in r7_block
    ):
        issues.append("FIELD_PROJECTION_RUNTIME_R7_JOB_DRIFT")

    job_query = sources["job_query"]
    if (
        '"data-quality.read"' in job_query
        or 'java.util.List.of("platform.read")' not in job_query
    ):
        issues.append("FIELD_PROJECTION_RUNTIME_JOB_QUERY_DRIFT")

    owner_provider = sources["owner_provider"]
    technical_only_job = re.search(
        r'if\s*\("JOB"\.equals\(query\.objectClass\(\)\)\)\s*\{'
        r'.*?AuthorizationScopeAnchor\.TECHNICAL_OBJECT.*?\}\s*else\s*\{'
        r'.*?AuthorizationScopeAnchor\.OWNED_SOURCE',
        owner_provider,
        re.DOTALL,
    )
    if technical_only_job is None:
        issues.append("FIELD_PROJECTION_RUNTIME_JOB_SCOPE_DRIFT")

    shell = sources["shell"]
    job_capability = re.search(
        r'"subject-recompute-jobs".*?Set\.of\((.*?)\)\)',
        shell,
        re.DOTALL,
    )
    if (
        job_capability is None
        or '"R7-PLATFORM-OPS"' not in job_capability.group(1)
        or "R6-DATA-OWNER" in job_capability.group(1)
    ):
        issues.append("FIELD_PROJECTION_RUNTIME_JOB_SHELL_DRIFT")

    mapping_view = sources["mapping_view"]
    if re.search(
        r'<RouterLink[^>]+subject-recompute-jobs|name:\s*[\'\"]subject-recompute-jobs[\'\"]',
        mapping_view,
    ):
        issues.append("FIELD_PROJECTION_RUNTIME_R6_JOB_LINK_DRIFT")
    return sorted(set(issues))


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


def _successor_negative_fixture_issues(root: Path, binding: Any) -> list[str]:
    try:
        negative = load_json(root / SUCCESSOR_NEGATIVE_FIXTURE)
    except (OSError, ValueError):
        return ["FIELD_PROJECTION_SUCCESSOR_NEGATIVE_FIXTURES_INVALID"]
    if not isinstance(binding, dict) or not isinstance(negative, dict):
        return ["FIELD_PROJECTION_SUCCESSOR_NEGATIVE_FIXTURES_INVALID"]
    if negative.get("version") != "FIELD-PROJECTION-NEGATIVE-FIXTURES-1.1.0":
        return ["FIELD_PROJECTION_SUCCESSOR_NEGATIVE_FIXTURES_INVALID"]
    issues: list[str] = []
    cases = negative.get("cases", [])
    if not isinstance(cases, list) or not cases:
        return ["FIELD_PROJECTION_SUCCESSOR_NEGATIVE_FIXTURES_INVALID"]
    seen: set[str] = set()
    for case in cases:
        if not isinstance(case, dict):
            issues.append("FIELD_PROJECTION_SUCCESSOR_NEGATIVE_FIXTURES_INVALID")
            continue
        case_id = case.get("caseId")
        expected = case.get("expectedCode")
        if (
            not isinstance(case_id, str)
            or not case_id
            or case_id in seen
            or not isinstance(expected, str)
        ):
            issues.append("FIELD_PROJECTION_SUCCESSOR_NEGATIVE_FIXTURES_INVALID")
            continue
        seen.add(case_id)
        found = quality_snapshot_fixture_issues(binding, case.get("document"))
        if expected not in found:
            issues.append(
                f"FIELD_PROJECTION_SUCCESSOR_NEGATIVE_FIXTURE_NOT_REJECTED: {case_id}"
            )
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


def _successor_lock_issues(root: Path) -> list[str]:
    try:
        lock = load_json(root / SUCCESSOR_LOCK)
    except (OSError, ValueError):
        return ["FIELD_PROJECTION_SUCCESSOR_LOCK_INVALID"]
    entries = lock.get("files", []) if isinstance(lock, dict) else []
    by_path = {item.get("path"): item for item in entries if isinstance(item, dict)}
    issues: list[str] = []
    if (
        not isinstance(lock, dict)
        or lock.get("version") != "FIELD-PROJECTION-CONTRACT-LOCK-1.1.0"
        or set(by_path) != set(SUCCESSOR_LOCKED_FILES)
        or len(by_path) != len(entries)
    ):
        issues.append("FIELD_PROJECTION_SUCCESSOR_LOCK_FILE_SET_INVALID")
    for relative, item in by_path.items():
        path = root / str(relative)
        if not path.is_file() or item.get("sha256") != _sha256(path):
            issues.append(
                f"FIELD_PROJECTION_SUCCESSOR_LOCK_DIGEST_MISMATCH: {relative}"
            )
    return issues


def _predecessor_issues(root: Path) -> list[str]:
    issues = [
        f"FIELD_PROJECTION_PREDECESSOR_LOCK_CHANGED: {relative}"
        for relative, expected in PREDECESSOR_LOCK_DIGESTS.items()
        if not (root / relative).is_file() or _sha256(root / relative) != expected
    ]
    issues.extend(
        f"FIELD_PROJECTION_PREDECESSOR_RAW_BYTES_CHANGED: {relative}"
        for relative, expected in FIELD_PROJECTION_PREDECESSOR_DIGESTS.items()
        if not (root / relative).is_file() or _sha256(root / relative) != expected
    )
    return issues


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
