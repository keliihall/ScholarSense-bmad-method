#!/usr/bin/env python3
"""Compose the Story 2.3 Task 0 contract gates without duplicating them."""

from __future__ import annotations

import hashlib
import importlib
import json
import sys
from pathlib import Path
from typing import Any, Callable


CHILD_CHECKERS = (
    ("data-catalog", "check_data_catalog_contracts"),
    ("field-projection", "check_field_projection_contracts"),
    ("audit-retention", "check_audit_retention_contracts"),
    ("ingestion-batch", "check_ingestion_batch_contracts"),
)

INGESTION_LOCK = Path(
    "contracts/ingestion-quality/batch-quality/"
    "executable-quality-contract-lock-1.0.0.json"
)
METRIC_VECTORS = Path(
    "contracts/ingestion-quality/batch-quality/fixtures/valid/"
    "quality-metric-vectors-1.0.0.json"
)
FIELD_LOCK = Path(
    "contracts/field-projection/field-projection-contract-lock-1.1.0.json"
)
AUDIT_LOCK = Path(
    "contracts/audit-retention/audit-retention-contract-lock-1.0.0.json"
)

EXPECTED_LOCK_ENTRY_COUNT = 48
EXPECTED_POLICY_CANONICAL_DIGEST = (
    "sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8"
)
EXPECTED_RETENTION_CANONICAL_DIGEST = (
    "sha256:1770fc6fa8e58b6853dbdca7dde3dec9aac0da8a89f7840c86ce252f00ebcae0"
)
EXPECTED_FIELD_LOCK_RAW_DIGEST = (
    "sha256:91270f357115d3e7f9273723b677143e6d3adbc5a9a0e3f249279c64ebbd5a11"
)
EXPECTED_AUDIT_LOCK_RAW_DIGEST = (
    "sha256:321755d18d6de7ee8eef436bd070a5d5f6ad94773bbbe686ba89b0cf21926c8b"
)
EXPECTED_SOURCE_IDS = frozenset(
    {
        "SRC-P0-ACCOMMODATION-001",
        "SRC-P0-CALENDAR-001",
        "SRC-P0-CAMPUS-ACCESS-001",
        "SRC-P0-CARD-001",
        "SRC-P0-DEVICE-001",
        "SRC-P0-DORM-ACCESS-001",
        "SRC-P0-LEAVE-001",
        "SRC-P0-RESPONSIBILITY-001",
        "SRC-P0-STUDENT-001",
        "SRC-P0-TIMETABLE-001",
        "SRC-P1-ACADEMIC-001",
        "SRC-P1-AID-001",
        "SRC-P1-CARE-LIST-001",
        "SRC-P1-NETWORK-001",
        "SRC-P1-OFFCAMPUS-001",
        "SRC-P1-PSYCH-DEID-001",
        "SRC-P1-WORK-VISIT-001",
    }
)

# These are the exact release predecessors protected by the approved handoff.
# Successor release inputs remain the release owner's later responsibility.
RELEASE_PREDECESSOR_RAW_GUARDS = {
    "contracts/release/release-manifest.schema.json":
        "9ee461f8772441366396cb181dd358d3df848aa834837ba19ff76d484a5b1f6e",
    "contracts/release/evidence-index.schema.json":
        "c9ad274343c42f3b73ed6f6ee62a8adfdb79c490f60fd6b4c95456b094b6629a",
    "contracts/release/fixtures/valid/release-manifest.json":
        "717acd02b76737c709002e68804b0b8d98e5ca844fc0f05ae7c04ae9d4676f10",
    "contracts/release/fixtures/valid/evidence-index.json":
        "ac45ac4b53f3e5bf663254e2320a83bf60edc9896b0a2f84ea6aa116d1724e8d",
    "contracts/release/release-manifest-2.schema.json":
        "a012807699695cab091808fc5fbb1f4ebe7db56aa8ce8789cec79f4f68c50aac",
    "contracts/release/evidence-index-2.schema.json":
        "959134c8e3d481131d7c76ea403b5ae6304bf8f8eb58a77f8a79a83910f7dfde",
    "contracts/release/fixtures/valid/release-manifest-2.json":
        "fef2472545834b7417a1a0e1d97130318deed3672d8e68c18bf1263365a6d2f7",
    "contracts/release/fixtures/valid/evidence-index-2.json":
        "be396b5e339ced0651126f906fc1dcd0100114a59dab6cb33e59a3992ab3fea7",
    "contracts/release/release-manifest-3.schema.json":
        "f5506bb2b535c404ab1f029fea1329d614a23a8ea9bd98a4d876707132428625",
    "contracts/release/evidence-index-3.schema.json":
        "401117e68de1267d0bc677cce2114a2820a7476d39430fda8df93ea2e7b6ae9c",
    "contracts/release/release-manifest-4.schema.json":
        "6483e1421c50363beca5aad5a469ca2734daedf2c84636c75fc143bd834cb094",
    "contracts/release/evidence-index-4.schema.json":
        "6bddb59a22d4408139d562793b566d2e5098eaef2f3bbce7b41c3acfd3927d7e",
}

Validator = Callable[[Path], list[str]]


def _raw_sha256(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def _load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _default_validators() -> dict[str, Any]:
    validators: dict[str, Any] = {}
    for checker_id, module_name in CHILD_CHECKERS:
        try:
            module = importlib.import_module(module_name)
        except Exception as error:
            validators[checker_id] = error
            continue
        validators[checker_id] = getattr(module, "validate", None)
    return validators


def _handoff_issues(root: Path) -> list[str]:
    issues: list[str] = []
    try:
        lock = _load_json(root / INGESTION_LOCK)
    except (OSError, ValueError):
        return ["TASK0_READINESS_INGESTION_LOCK_INVALID"]
    if not isinstance(lock, dict) or not isinstance(lock.get("digests"), dict):
        return ["TASK0_READINESS_INGESTION_LOCK_INVALID"]
    digests = lock["digests"]
    if len(digests) != EXPECTED_LOCK_ENTRY_COUNT:
        issues.append("TASK0_READINESS_LOCK_ENTRY_COUNT_INVALID")
    if INGESTION_LOCK.as_posix() in digests:
        issues.append("TASK0_READINESS_LOCK_SELF_REFERENCE")
    if lock.get("policyCanonicalDigest") != EXPECTED_POLICY_CANONICAL_DIGEST:
        issues.append("TASK0_READINESS_POLICY_CANONICAL_DIGEST_INVALID")
    if lock.get("retentionCanonicalDigest") != EXPECTED_RETENTION_CANONICAL_DIGEST:
        issues.append("TASK0_READINESS_RETENTION_CANONICAL_DIGEST_INVALID")

    lock_handoffs = (
        (
            FIELD_LOCK,
            EXPECTED_FIELD_LOCK_RAW_DIGEST,
            "TASK0_READINESS_FIELD_LOCK_RAW_MISMATCH",
        ),
        (
            AUDIT_LOCK,
            EXPECTED_AUDIT_LOCK_RAW_DIGEST,
            "TASK0_READINESS_AUDIT_LOCK_RAW_MISMATCH",
        ),
    )
    for relative, expected, code in lock_handoffs:
        try:
            actual = _raw_sha256(root / relative)
        except OSError:
            actual = None
        if actual != expected or digests.get(relative.as_posix()) != expected:
            issues.append(code)

    try:
        vectors = _load_json(root / METRIC_VECTORS)
        cases = vectors.get("cases") if isinstance(vectors, dict) else None
        source_ids = frozenset(
            case.get("sourceId")
            for case in cases
            if isinstance(case, dict) and isinstance(case.get("sourceId"), str)
        )
    except (OSError, TypeError, ValueError):
        source_ids = frozenset()
    if source_ids != EXPECTED_SOURCE_IDS:
        issues.append("TASK0_READINESS_VECTOR_SOURCE_SET_INVALID")

    for relative, expected in RELEASE_PREDECESSOR_RAW_GUARDS.items():
        try:
            actual = hashlib.sha256((root / relative).read_bytes()).hexdigest()
        except OSError:
            actual = None
        if actual != expected:
            issues.append(
                f"TASK0_READINESS_RELEASE_BASELINE_MISMATCH: {relative}"
            )
    return sorted(set(issues))


def validate(
    project_root: Path,
    *,
    child_validators: dict[str, Any] | None = None,
) -> list[str]:
    """Run every child gate and the mechanical Task 0 handoff checks."""
    root = project_root.resolve()
    validators = (
        _default_validators()
        if child_validators is None
        else child_validators
    )
    issues: list[str] = []
    for checker_id, _module_name in CHILD_CHECKERS:
        validator = validators.get(checker_id) if isinstance(validators, dict) else None
        if isinstance(validator, Exception):
            issues.append(
                f"TASK0_READINESS_CHILD_EXCEPTION[{checker_id}]: "
                f"{type(validator).__name__}"
            )
            continue
        if not callable(validator):
            issues.append(f"TASK0_READINESS_CHILD_VALIDATE_MISSING[{checker_id}]")
            continue
        try:
            child_issues = validator(root)
        except Exception as error:
            issues.append(
                f"TASK0_READINESS_CHILD_EXCEPTION[{checker_id}]: "
                f"{type(error).__name__}"
            )
            continue
        if (
            not isinstance(child_issues, list)
            or any(
                not isinstance(issue, str) or not issue
                for issue in child_issues
            )
        ):
            issues.append(f"TASK0_READINESS_CHILD_RESULT_INVALID[{checker_id}]")
            continue
        issues.extend(
            f"TASK0_READINESS_CHILD_ISSUE[{checker_id}]: {issue}"
            for issue in child_issues
        )
    issues.extend(_handoff_issues(root))
    return sorted(set(issues))


def main(argv: list[str]) -> int:
    if len(argv) > 2:
        print("usage: check_story_2_3_task0_readiness.py [project-root]", file=sys.stderr)
        return 2
    root = Path(argv[1]).resolve() if len(argv) == 2 else Path(__file__).resolve().parents[1]
    issues = validate(root)
    if issues:
        print("STORY_2_3_TASK_0_READINESS_FAIL")
        for issue in issues:
            print(f"- {issue}")
        return 1
    print("STORY_2_3_TASK_0_READINESS_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
