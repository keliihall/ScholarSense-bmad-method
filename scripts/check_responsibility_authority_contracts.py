#!/usr/bin/env python3
"""Validate Story 1.6b responsibility authority contracts and immutable lock."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from release_json import load_json, schema_definition_issues, schema_issues  # noqa: E402


BASE = Path("contracts/responsibility-authority")
LOCK = BASE / "responsibility-authority-contract-lock-1.0.0.json"
POLICY = BASE / "responsibility-policy-1.0.0.json"
UPSTREAM_LOCK = Path(
    "contracts/identity-authority/identity-authority-contract-lock-1.0.0.json"
)
SCHEMAS = {
    BASE / "fixtures/valid/incremental-batch.json": BASE
    / "incremental-batch.schema.json",
    BASE / "fixtures/valid/full-snapshot.json": BASE / "full-snapshot.schema.json",
    BASE / "fixtures/valid/empty-complete-snapshot.json": BASE
    / "full-snapshot.schema.json",
    BASE / "fixtures/valid/reconciliation-result.json": BASE
    / "reconciliation-result.schema.json",
    BASE / "fixtures/valid/exception-projection.json": BASE
    / "exception-projection.schema.json",
    BASE / "sandbox-runtime-profile-1.0.0.json": BASE
    / "runtime-profile.schema.json",
    BASE / "student-token-vectors-1.0.0.json": BASE
    / "student-token-vectors.schema.json",
}
REQUIRED_NEGATIVE_CASES = {
    "partial-snapshot",
    "unsealed-snapshot",
    "missing-partition",
    "count-mismatch",
    "digest-mismatch",
    "duplicate-relation",
    "overlapping-primary",
    "inactive-recipient",
    "non-r1-recipient",
    "wrong-college",
    "exact-effective-end",
    "stale-source-version",
    "watermark-gap",
    "same-key-different-payload",
    "wrong-token-purpose",
    "unknown-token-key-version",
    "below-threshold",
}


def validate(project_root: Path) -> list[str]:
    root = project_root.resolve()
    issues: list[str] = []
    schemas: dict[Path, Any] = {}
    for relative in sorted(set(SCHEMAS.values()) | {BASE / "responsibility-relation.schema.json"}):
        try:
            schema = load_json(root / relative)
        except (OSError, ValueError):
            issues.append(f"RESPONSIBILITY_AUTHORITY_SCHEMA_INVALID: {relative}")
            continue
        schemas[relative] = schema
        issues.extend(
            f"RESPONSIBILITY_AUTHORITY_SCHEMA_INVALID: {relative}: {issue}"
            for issue in schema_definition_issues(schema)
        )
    for instance_path, schema_path in SCHEMAS.items():
        try:
            instance = load_json(root / instance_path)
        except (OSError, ValueError):
            issues.append(
                f"RESPONSIBILITY_AUTHORITY_EXAMPLE_INVALID: {instance_path}"
            )
            continue
        validation = schema_issues(instance, schemas.get(schema_path, {}))
        if validation:
            issues.append(
                "RESPONSIBILITY_AUTHORITY_EXAMPLE_REJECTED: "
                f"{instance_path}: {validation[0]}"
            )
    issues.extend(_incremental_relation_issues(root, schemas))
    issues.extend(_policy_issues(root))
    issues.extend(_negative_catalog_issues(root))
    issues.extend(_lock_issues(root))
    return sorted(set(issues))


def _incremental_relation_issues(
    root: Path, schemas: dict[Path, Any]
) -> list[str]:
    try:
        batch = load_json(root / BASE / "fixtures/valid/incremental-batch.json")
        records = batch["records"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["RESPONSIBILITY_AUTHORITY_BATCH_INVALID"]
    relation_schema = schemas.get(BASE / "responsibility-relation.schema.json", {})
    if (
        batch.get("sourceId") != "SRC-P0-RESPONSIBILITY-001"
        or batch.get("consumerProjection") != "responsibility"
        or batch.get("supportingIdentityOrgWatermarks")
        != {"identity-authority|sandbox-0": 42}
        or not records
        or any(schema_issues(record.get("payload"), relation_schema) for record in records)
    ):
        return ["RESPONSIBILITY_AUTHORITY_BATCH_INVALID"]
    return []


def _policy_issues(root: Path) -> list[str]:
    try:
        policy = load_json(root / POLICY)
        profile = load_json(root / BASE / "sandbox-runtime-profile-1.0.0.json")
    except (OSError, ValueError):
        return ["RESPONSIBILITY_AUTHORITY_POLICY_INVALID"]
    canonical_policy = dict(policy)
    supplied_digest = canonical_policy.pop("digest", None)
    expected_digest = _canonical_digest(canonical_policy)
    canonical_bindings = dict(profile)
    supplied_profile_digest = canonical_bindings.pop("digest", None)
    expected_profile_digest = _canonical_digest(canonical_bindings)
    valid = (
        policy.get("version") == "RESPONSIBILITY-POLICY-1.0.0"
        and policy.get("sourceId") == "SRC-P0-RESPONSIBILITY-001"
        and policy.get("providedBy") == "SRC-P0-RESPONSIBILITY-001 source owner"
        and policy.get("approvedBy") == "Hei"
        and policy.get("studentToken", {}).get("purposeCode")
        == "RESPONSIBILITY-STUDENT-REF"
        and policy.get("recipient", {}).get("primaryCardinality") == "exactly-one"
        and policy.get("recipientReferenceBinding", {}).get("approvedBy")
        == "Hei"
        and policy.get("recipientReferenceBinding", {}).get("platformPurpose")
        == "identity-external-ref"
        and policy.get("recipientReferenceBinding", {}).get("algorithm")
        == "existing-platform-keyed-pseudonymization"
        and policy.get("recipientReferenceBinding", {}).get("crosswalk")
        == "not-required"
        and policy.get("schedule", {}).get("timeZone") == "Asia/Shanghai"
        and policy.get("schedule", {}).get("localTime") == "06:00:00"
        and policy.get("schedule", {}).get("firstBusinessDate") == "2026-07-30"
        and policy.get("reconciliation", {}).get("minimumMatchRate") == "0.999"
        and policy.get("reconciliation", {}).get("formula")
        == "matched/(matched+missing+unexpected+versionDrift)"
        and policy.get("reconciliation", {}).get("maximumActiveUnmappedCount") == 0
        and supplied_digest == expected_digest
        and profile.get("policyDigest") == supplied_digest
        and supplied_profile_digest == expected_profile_digest
    )
    return [] if valid else ["RESPONSIBILITY_AUTHORITY_POLICY_INVALID"]


def _negative_catalog_issues(root: Path) -> list[str]:
    try:
        catalog = load_json(
            root / BASE / "fixtures/negative-fixtures-1.0.0.json"
        )
        cases = catalog["cases"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["RESPONSIBILITY_AUTHORITY_NEGATIVE_CATALOG_INVALID"]
    identifiers = [
        case.get("id") for case in cases if isinstance(case, dict)
    ]
    valid = (
        catalog.get("version")
        == "RESPONSIBILITY-AUTHORITY-NEGATIVE-FIXTURES-1.0.0"
        and set(identifiers) == REQUIRED_NEGATIVE_CASES
        and len(identifiers) == len(set(identifiers))
        and all(
            isinstance(case.get("expectedReasonCode"), str)
            and case["expectedReasonCode"].startswith("RESPONSIBILITY_")
            for case in cases
        )
    )
    return [] if valid else ["RESPONSIBILITY_AUTHORITY_NEGATIVE_CATALOG_INVALID"]


def _lock_issues(root: Path) -> list[str]:
    try:
        lock = load_json(root / LOCK)
        entries = lock["files"]
        upstream = lock["upstreamLocks"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["RESPONSIBILITY_AUTHORITY_CONTRACT_LOCK_INVALID"]
    expected_paths = sorted(
        str(path.relative_to(root))
        for path in (root / BASE).rglob("*")
        if path.is_file() and path.relative_to(root) != LOCK
    )
    actual_paths = [entry.get("path") for entry in entries if isinstance(entry, dict)]
    if actual_paths != expected_paths:
        return ["RESPONSIBILITY_AUTHORITY_CONTRACT_LOCK_INVALID"]
    for entry in entries:
        path = root / entry["path"]
        if (
            not path.is_file()
            or entry.get("sha256") != hashlib.sha256(path.read_bytes()).hexdigest()
        ):
            return [
                "RESPONSIBILITY_AUTHORITY_CONTRACT_LOCK_DIGEST_MISMATCH: "
                f"{entry.get('path')}"
            ]
    expected_upstream = hashlib.sha256((root / UPSTREAM_LOCK).read_bytes()).hexdigest()
    if upstream != [{"path": str(UPSTREAM_LOCK), "sha256": expected_upstream}]:
        return ["RESPONSIBILITY_AUTHORITY_UPSTREAM_LOCK_DIGEST_MISMATCH"]
    return []


def _canonical_digest(value: Any) -> str:
    payload = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(payload).hexdigest()


def main() -> int:
    issues = validate(Path.cwd())
    if issues:
        for issue in issues:
            print(issue)
        return 1
    print("responsibility authority contracts: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
