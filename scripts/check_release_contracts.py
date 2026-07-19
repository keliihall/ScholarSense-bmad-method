#!/usr/bin/env python3
"""Validate release schemas, profiles, policies, fixtures, and canonical vectors."""

from __future__ import annotations

import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "release"))

from manifests import evidence_index_issues, release_manifest_issues  # noqa: E402
from release_json import (
    canonical_bytes,
    canonical_sha256,
    load_json,
    release_document_issues,
    schema_definition_issues,
    schema_issues,
)


def validate(project_root: Path) -> list[str]:
    root = project_root.resolve()
    contracts = root / "contracts/release"
    issues: list[str] = []
    schemas = sorted(contracts.glob("*.schema.json"))
    if len(schemas) < 14:
        issues.append("RELEASE_SCHEMA_SET_INCOMPLETE")
    for path in schemas:
        try:
            schema = load_json(path)
        except (OSError, ValueError) as error:
            issues.append(f"RELEASE_SCHEMA_INVALID_JSON: {path.name}: {error}")
            continue
        issues.extend(f"{path.name}: {issue}" for issue in schema_definition_issues(schema))

    try:
        index = load_json(contracts / "fixtures/index.json")
    except (OSError, ValueError) as error:
        return sorted(set(issues + [f"RELEASE_FIXTURE_INDEX_INVALID: {error}"]))
    entries = index.get("contracts", []) if isinstance(index, dict) else []
    if len(entries) < 14:
        issues.append("RELEASE_FIXTURE_SET_INCOMPLETE")
    for entry in entries:
        try:
            schema = load_json(contracts / entry["schema"])
            valid = load_json(contracts / entry["valid"])
            invalid = load_json(contracts / entry["invalid"])
        except (KeyError, OSError, ValueError) as error:
            issues.append(f"RELEASE_FIXTURE_INVALID: {entry}: {error}")
            continue
        valid_issues = schema_issues(valid, schema)
        if valid_issues:
            issues.append(f"RELEASE_VALID_FIXTURE_REJECTED: {entry['id']}: {valid_issues[0]}")
        if not schema_issues(invalid, schema):
            issues.append(f"RELEASE_INVALID_FIXTURE_ACCEPTED: {entry['id']}")

    for instance_name, schema_name in (
        ("backend-lock-1.0.0.json", "backend-lock.schema.json"),
        ("toolchain-lock-1.0.0.json", "toolchain-lock.schema.json"),
        ("vulnerability-policy-1.0.0.json", "vulnerability-policy.schema.json"),
        ("license-policy-1.0.0.json", "license-policy.schema.json"),
        ("release-source-inventory-1.0.0.json", "release-source-inventory.schema.json"),
        ("formal-web-runner-1.0.0.json", "formal-web-runner.schema.json"),
        ("ui-token-manifest-1.0.0.json", "ui-token-manifest.schema.json"),
        ("brand-asset-manifest-1.0.0.json", "brand-asset-manifest.schema.json"),
    ):
        try:
            instance = load_json(contracts / instance_name)
            schema = load_json(contracts / schema_name)
        except (OSError, ValueError) as error:
            issues.append(f"RELEASE_CONTROLLED_INSTANCE_INVALID: {instance_name}: {error}")
            continue
        issues.extend(f"{instance_name}: {issue}" for issue in schema_issues(instance, schema))
        issues.extend(f"{instance_name}: {issue}" for issue in release_document_issues(instance))

    try:
        vectors = load_json(contracts / "canonical-json-test-vectors-1.0.0.json")
        for vector in vectors["vectors"]:
            if canonical_bytes(vector["value"]).decode("utf-8") != vector["canonicalUtf8"]:
                issues.append(f"CANONICAL_VECTOR_BYTES_MISMATCH: {vector['id']}")
            if canonical_sha256(vector["value"]) != vector["sha256"]:
                issues.append(f"CANONICAL_VECTOR_DIGEST_MISMATCH: {vector['id']}")
    except (KeyError, OSError, TypeError, ValueError) as error:
        issues.append(f"CANONICAL_VECTOR_INVALID: {error}")
    try:
        build_fixture = load_json(contracts / "fixtures/valid/build-manifest.json")
        release_fixture = load_json(contracts / "fixtures/valid/release-manifest.json")
        index_fixture = load_json(contracts / "fixtures/valid/evidence-index.json")
        issues.extend(f"release-manifest fixture: {issue}" for issue in release_manifest_issues(release_fixture, build_fixture))
        issues.extend(f"evidence-index fixture: {issue}" for issue in evidence_index_issues(index_fixture, release_fixture))
    except (OSError, TypeError, ValueError) as error:
        issues.append(f"RELEASE_LIFECYCLE_FIXTURE_INVALID: {error}")
    return sorted(set(issues))


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) == 2 else Path(".")
    issues = validate(root)
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print("release-contracts: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
