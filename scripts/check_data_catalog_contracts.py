#!/usr/bin/env python3
"""Validate DCC/QG exact sets, schema closure, compatibility, evidence truth, and lock."""

from __future__ import annotations

import copy
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


sys.path.insert(0, str(Path(__file__).resolve().parent))

from release_json import canonical_bytes, load_json, schema_definition_issues, schema_issues  # noqa: E402


CONTRACT_ROOT = Path("contracts/data-catalog")
CATALOG = CONTRACT_ROOT / "dcc-1.0.0.json"
REGISTRY = CONTRACT_ROOT / "dependency-registry-1.0.0.json"
QUALITY_GATE = CONTRACT_ROOT / "qg-1.0.0.json"
LOCK = CONTRACT_ROOT / "data-catalog-contract-lock-1.0.0.json"
SOURCE_SCHEMA = CONTRACT_ROOT / "source-contract.schema.json"
CATALOG_SCHEMA = CONTRACT_ROOT / "data-source-catalog.schema.json"
REGISTRY_SCHEMA = CONTRACT_ROOT / "dependency-registry.schema.json"
QUALITY_SCHEMA = CONTRACT_ROOT / "quality-gate.schema.json"
LOCK_SCHEMA = CONTRACT_ROOT / "data-catalog-contract-lock.schema.json"
EVIDENCE_SCHEMA = CONTRACT_ROOT / "data-catalog-runtime-evidence.schema.json"
HANDOFF_SCHEMA = CONTRACT_ROOT / "data-catalog-target-handoff.schema.json"
TARGET_REPORT_SCHEMA = CONTRACT_ROOT / "data-catalog-target-report.schema.json"
OPEN_DECISIONS = Path("_bmad-output/planning-artifacts/open-decisions.md")
RULE_CATALOG = Path("_bmad-output/planning-artifacts/rule-catalog.md")

FORBIDDEN_FIELDS = {
    "grade", "grades", "ranking", "rank", "thesis", "fullacademicrecord",
    "diagnosis", "consultationbody", "freetext", "url", "domain", "content",
    "studentname", "studentnumber", "phone", "email",
}
IMMUTABLE_EVIDENCE_SCHEMES = ("sha256://", "oci://", "s3-version://", "evidence+sha256://")


def _derive_sources(root: Path) -> set[str]:
    text = (root / OPEN_DECISIONS).read_text(encoding="utf-8")
    return set(re.findall(r"^\| (SRC-P[01]-[A-Z-]+-[0-9]{3}) \|", text, re.MULTILINE))


def _derive_dependencies(root: Path) -> dict[str, str]:
    text = (root / RULE_CATALOG).read_text(encoding="utf-8")
    matches = re.findall(
        r"^\| `(?P<dependency>DEP-P[01]-[A-Z-]+-[0-9]{3})` \| `(?P<source>SRC-P[01]-[A-Z-]+-[0-9]{3})` \|",
        text,
        re.MULTILINE,
    )
    return {source: dependency for dependency, source in matches}


_DEFAULT_ROOT = Path(__file__).resolve().parents[1]
EXPECTED_SOURCES = frozenset(_derive_sources(_DEFAULT_ROOT))
EXPECTED_DEPENDENCIES = _derive_dependencies(_DEFAULT_ROOT)


def canonical_digest(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_bytes(value)).hexdigest()


def _duplicates(values: list[str]) -> bool:
    return len(values) != len(set(values))


def _forbidden_keys(value: Any) -> set[str]:
    found: set[str] = set()
    if isinstance(value, dict):
        for key, child in value.items():
            if key.lower().replace("_", "").replace("-", "") in FORBIDDEN_FIELDS:
                found.add(key)
            found.update(_forbidden_keys(child))
    elif isinstance(value, list):
        for child in value:
            found.update(_forbidden_keys(child))
    return found


def _catalog_issues(
    catalog: dict[str, Any],
    registry: dict[str, Any],
    quality: dict[str, Any],
    root: Path,
    *,
    schema_overrides: dict[str, dict[str, Any]] | None = None,
) -> list[str]:
    issues: list[str] = []
    expected_sources = _derive_sources(root)
    expected_dependencies = _derive_dependencies(root)
    sources = catalog.get("sources") if isinstance(catalog, dict) else None
    bindings = registry.get("bindings") if isinstance(registry, dict) else None
    if not isinstance(sources, list):
        return ["DCC_CATALOG_INVALID"]
    if not isinstance(bindings, list):
        return ["DCC_DEPENDENCY_REGISTRY_INVALID"]
    source_ids = [item.get("sourceId") for item in sources if isinstance(item, dict)]
    dependency_ids = [item.get("dependencyId") for item in bindings if isinstance(item, dict)]
    if _duplicates(source_ids):
        issues.append("DCC_SOURCE_ID_DUPLICATE")
    if set(source_ids) != expected_sources:
        issues.append("DCC_SOURCE_SET_INVALID")
    if _duplicates(dependency_ids):
        issues.append("DCC_DEPENDENCY_ID_DUPLICATE")
    actual_dependencies = {
        item.get("sourceId"): item.get("dependencyId")
        for item in bindings
        if isinstance(item, dict)
    }
    if actual_dependencies != expected_dependencies:
        issues.append("DCC_DEPENDENCY_SET_INVALID")
    mapped = set(expected_dependencies)
    descriptor_schema = load_json(root / SOURCE_SCHEMA)
    for descriptor in sources:
        if not isinstance(descriptor, dict) or schema_issues(descriptor, descriptor_schema):
            issues.append("DCC_SOURCE_DESCRIPTOR_INVALID")
            continue
        source_id = descriptor["sourceId"]
        if source_id in mapped and descriptor.get("consumerMode") != "rule-dependency":
            issues.append("DCC_DEPENDENCY_PURPOSE_INVALID")
        if source_id not in mapped and descriptor.get("consumerMode") != "purpose-isolated":
            issues.append("DCC_PURPOSE_ISOLATION_INVALID")
        evidence_uri = descriptor.get("evidenceUri", "")
        claim = descriptor.get("runtimeEvidenceClaim")
        if claim == "none":
            if not evidence_uri.startswith("evidence://pending/"):
                issues.append("DCC_EVIDENCE_URI_INVALID")
        elif claim == "target-verified":
            if not evidence_uri.startswith(IMMUTABLE_EVIDENCE_SCHEMES):
                issues.append("DCC_EVIDENCE_URI_INVALID")
        schema_ref = descriptor.get("schemaRef", "")
        schema_path = root / CONTRACT_ROOT / schema_ref
        if schema_overrides and schema_ref in schema_overrides:
            source_schema = schema_overrides[schema_ref]
        elif schema_path.is_file():
            source_schema = load_json(schema_path)
        else:
            issues.append("DCC_SCHEMA_REFERENCE_MISSING")
            continue
        if schema_definition_issues(source_schema):
            issues.append("DCC_SCHEMA_DEFINITION_INVALID")
        if _forbidden_keys(source_schema):
            issues.append("DCC_FORBIDDEN_FIELD")
    if set(quality.get("sourceOverrides", {})) != expected_sources:
        issues.append("DCC_QUALITY_SOURCE_SET_INVALID")
    required_gates = {
        "schemaAllowlistCompatibilityPercent": 100,
        "forbiddenFieldCount": 0,
        "requiredFieldValidityPercent": 100,
        "validRecordBasisPointsMinimum": 9950,
        "p0SubjectMappingBasisPointsMinimum": 9950,
        "coreFieldBasisPointsMinimum": 9800,
        "withinSloArrivalBasisPointsMinimum": 9900,
        "unresolvedIntervalConflictCount": 0,
        "duplicateBusinessKeyCount": 0,
        "versionRegressionCount": 0,
    }
    if quality.get("commonHardGates") != required_gates:
        issues.append("DCC_QUALITY_GATE_INVALID")
    return sorted(set(issues))


def _reference_issues(root: Path, entry_paths: list[Path]) -> list[str]:
    issues: list[str] = []
    active: set[Path] = set()
    visited: set[Path] = set()

    def walk(path: Path) -> None:
        resolved = path.resolve()
        if resolved in active:
            issues.append("DCC_SCHEMA_REFERENCE_CYCLE")
            return
        if resolved in visited:
            return
        if not resolved.is_file() or not resolved.is_relative_to(root.resolve()):
            issues.append("DCC_SCHEMA_REFERENCE_MISSING")
            return
        visited.add(resolved)
        active.add(resolved)
        document = load_json(resolved)

        def inspect(value: Any) -> None:
            if isinstance(value, dict):
                reference = value.get("$ref")
                if isinstance(reference, str) and not reference.startswith(("http://", "https://", "#")):
                    target = reference.split("#", 1)[0]
                    if target:
                        walk((resolved.parent / target).resolve())
                for child in value.values():
                    inspect(child)
            elif isinstance(value, list):
                for child in value:
                    inspect(child)

        inspect(document)
        active.remove(resolved)

    for entry in entry_paths:
        walk(entry)
    return sorted(set(issues))


def execute_compatibility_fixture(document: dict[str, Any]) -> str:
    if document.get("fixtureVersion") != "DCC-COMPAT-1.0.0":
        return "DCC_COMPATIBILITY_FIXTURE_INVALID"
    change = document.get("change", {})
    from_version = str(document.get("fromVersion", ""))
    to_version = str(document.get("toVersion", ""))
    try:
        from_major = int(from_version.rsplit("-", 1)[1].split(".")[0])
        to_major = int(to_version.rsplit("-", 1)[1].split(".")[0])
    except (IndexError, ValueError):
        return "DCC_COMPATIBILITY_FIXTURE_INVALID"
    if change.get("kind") == "add-optional" and change.get("field") and to_major == from_major:
        return "DCC_COMPATIBLE"
    if change.get("kind") in {"add-required", "remove", "narrow", "change-meaning"}:
        return "DCC_BREAKING_CHANGE_REQUIRES_MAJOR" if to_major == from_major else "DCC_COMPATIBLE"
    return "DCC_COMPATIBILITY_FIXTURE_INVALID"


def execute_negative_fixture(case: dict[str, Any], project_root: Path) -> str:
    allowed = {
        "remove-source", "add-source", "duplicate-source", "remove-dependency",
        "duplicate-dependency", "claim-fixture-as-runtime", "add-forbidden-schema-field",
    }
    mutation = case.get("mutation")
    if mutation not in allowed or not isinstance(case.get("input"), dict):
        return "DCC_FIXTURE_INVALID"
    catalog = copy.deepcopy(load_json(project_root / CATALOG))
    registry = copy.deepcopy(load_json(project_root / REGISTRY))
    quality = copy.deepcopy(load_json(project_root / QUALITY_GATE))
    data = case["input"]
    overrides: dict[str, dict[str, Any]] = {}
    if mutation == "remove-source":
        catalog["sources"] = [item for item in catalog["sources"] if item["sourceId"] != data.get("sourceId")]
    elif mutation == "add-source":
        invented = copy.deepcopy(catalog["sources"][0])
        invented["sourceId"] = data.get("sourceId")
        catalog["sources"].append(invented)
    elif mutation == "duplicate-source":
        source = next(item for item in catalog["sources"] if item["sourceId"] == data.get("sourceId"))
        catalog["sources"].append(copy.deepcopy(source))
    elif mutation == "remove-dependency":
        registry["bindings"] = [item for item in registry["bindings"] if item["dependencyId"] != data.get("dependencyId")]
    elif mutation == "duplicate-dependency":
        binding = next(item for item in registry["bindings"] if item["dependencyId"] == data.get("dependencyId"))
        registry["bindings"].append(copy.deepcopy(binding))
    elif mutation == "claim-fixture-as-runtime":
        source = next(item for item in catalog["sources"] if item["sourceId"] == data.get("sourceId"))
        source["runtimeEvidenceClaim"] = "target-verified"
        source["evidenceUri"] = data.get("evidenceUri")
    elif mutation == "add-forbidden-schema-field":
        schema_ref = data.get("schemaRef")
        schema = copy.deepcopy(load_json(project_root / CONTRACT_ROOT / schema_ref))
        schema.setdefault("properties", {})[data.get("field")] = {"type": "string"}
        overrides[schema_ref] = schema
    issues = _catalog_issues(catalog, registry, quality, project_root, schema_overrides=overrides)
    priorities = [
        "DCC_SOURCE_ID_DUPLICATE", "DCC_SOURCE_SET_INVALID", "DCC_DEPENDENCY_ID_DUPLICATE",
        "DCC_DEPENDENCY_SET_INVALID", "DCC_EVIDENCE_URI_INVALID", "DCC_FORBIDDEN_FIELD",
    ]
    return next((code for code in priorities if code in issues), issues[0] if issues else "DCC_FIXTURE_DID_NOT_FAIL")


def _controlled_files(root: Path) -> list[Path]:
    contract = root / CONTRACT_ROOT
    return sorted(
        (path for path in contract.rglob("*.json") if path.name != LOCK.name),
        key=lambda path: path.relative_to(root).as_posix(),
    )


def validate(project_root: Path) -> list[str]:
    root = project_root.resolve()
    issues: list[str] = []
    required = [
        CATALOG, REGISTRY, QUALITY_GATE, LOCK, SOURCE_SCHEMA, CATALOG_SCHEMA,
        REGISTRY_SCHEMA, QUALITY_SCHEMA, LOCK_SCHEMA, EVIDENCE_SCHEMA, HANDOFF_SCHEMA,
        TARGET_REPORT_SCHEMA,
    ]
    if any(not (root / path).is_file() for path in required):
        return ["DCC_REQUIRED_FILE_MISSING"]
    documents = {path: load_json(root / path) for path in required}
    for schema_path in (
        SOURCE_SCHEMA, CATALOG_SCHEMA, REGISTRY_SCHEMA, QUALITY_SCHEMA,
        LOCK_SCHEMA, EVIDENCE_SCHEMA, HANDOFF_SCHEMA, TARGET_REPORT_SCHEMA,
    ):
        if schema_definition_issues(documents[schema_path]):
            issues.append("DCC_SCHEMA_DEFINITION_INVALID")
    if schema_issues(documents[CATALOG], documents[CATALOG_SCHEMA]):
        issues.append("DCC_CATALOG_SCHEMA_REJECTED")
    if schema_issues(documents[REGISTRY], documents[REGISTRY_SCHEMA]):
        issues.append("DCC_DEPENDENCY_SCHEMA_REJECTED")
    if schema_issues(documents[QUALITY_GATE], documents[QUALITY_SCHEMA]):
        issues.append("DCC_QUALITY_SCHEMA_REJECTED")
    if schema_issues(documents[LOCK], documents[LOCK_SCHEMA]):
        issues.append("DCC_LOCK_SCHEMA_REJECTED")
    issues.extend(_catalog_issues(documents[CATALOG], documents[REGISTRY], documents[QUALITY_GATE], root))
    source_paths = [root / CONTRACT_ROOT / item["schemaRef"] for item in documents[CATALOG]["sources"]]
    issues.extend(_reference_issues(root, source_paths))
    lock = documents[LOCK]
    locked = {item.get("path"): item.get("canonicalDigest") for item in lock.get("files", []) if isinstance(item, dict)}
    controlled = _controlled_files(root)
    expected_paths = {path.relative_to(root).as_posix() for path in controlled}
    if set(locked) != expected_paths:
        issues.append("DCC_LOCK_FILE_SET_INVALID")
    for path in controlled:
        relative = path.relative_to(root).as_posix()
        if locked.get(relative) != canonical_digest(load_json(path)):
            issues.append("DCC_LOCK_DIGEST_MISMATCH")
    negative = load_json(root / CONTRACT_ROOT / "fixtures/invalid/negative-fixtures-1.0.0.json")
    for case in negative.get("cases", []):
        if execute_negative_fixture(case, root) != case.get("expectedCode"):
            issues.append("DCC_NEGATIVE_FIXTURE_MISMATCH")
    for name in ("optional-addition-1.1.0.json", "breaking-without-major.json"):
        fixture = load_json(root / CONTRACT_ROOT / "fixtures/compatibility" / name)
        if execute_compatibility_fixture(fixture) != fixture.get("expectedCode"):
            issues.append("DCC_COMPATIBILITY_FIXTURE_MISMATCH")
    responsibility = load_json(root / CONTRACT_ROOT / "sources/src-p0-responsibility-001.schema.json")
    controlled_version = responsibility.get("properties", {}).get(
        "responsibilityAuthorityContractVersion", {}
    ).get("const")
    if controlled_version != "RESPONSIBILITY-AUTHORITY-V2-2.0.0":
        issues.append("DCC_RESPONSIBILITY_AUTHORITY_DUPLICATED")
    return sorted(set(issues))


def main(argv: list[str]) -> int:
    if len(argv) > 2:
        print("usage: check_data_catalog_contracts.py [project-root]", file=sys.stderr)
        return 2
    root = Path(argv[1]) if len(argv) == 2 else Path.cwd()
    try:
        issues = validate(root)
    except (OSError, ValueError, TypeError, KeyError) as error:
        print(f"data-catalog-contracts: invalid package: {error}", file=sys.stderr)
        return 1
    if issues:
        for issue in issues:
            print(issue, file=sys.stderr)
        return 1
    print(f"data-catalog-contracts: PASS (sources={len(_derive_sources(root.resolve()))}; dependencies={len(_derive_dependencies(root.resolve()))}; DCC-1.0.0/QG-1.0.0)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
