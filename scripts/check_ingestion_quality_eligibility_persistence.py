#!/usr/bin/env python3
"""Fail-closed structural checks for the Story 2.4 owner persistence successor."""

from __future__ import annotations

import pathlib
import re
import sys


FEATURE = "V000015__ingestion-quality__quality_eligibility_v1.sql"
REQUIRED_TOKENS = (
    "create role scholarsense_ingestion_quality_eligibility_consumer nologin",
    "create table ingestion_quality.iq_rule_dependency_registry",
    "create table ingestion_quality.iq_rule_dependency_member",
    "create table ingestion_quality.iq_quality_event_inbox",
    "create table ingestion_quality.iq_quality_dependency_cursor",
    "create table ingestion_quality.iq_quality_event_quarantine",
    "create table ingestion_quality.iq_quality_backfill_request",
    "create table ingestion_quality.iq_dependency_quality_current",
    "create table ingestion_quality.iq_quality_eligibility_current",
    "create table ingestion_quality.iq_quality_eligibility_history",
    "create table ingestion_quality.iq_quality_eligibility_member_history",
    "create table ingestion_quality.iq_quality_eligibility_audit",
    "create table ingestion_quality.iq_quality_eligibility_outbox",
    "create table ingestion_quality.iq_quality_eligibility_idempotency",
    "create function ingestion_quality.iq_find_quality_snapshot_evidence",
    "create function ingestion_quality.iq_load_quality_eligibility_processing_state",
    "create function ingestion_quality.iq_accept_quality_eligibility_event",
    "create function ingestion_quality.iq_find_quality_eligibility_ids",
    "create function ingestion_quality.iq_find_quality_eligibility_page",
    "create function ingestion_quality.iq_find_quality_eligibility_page_members",
    "create function ingestion_quality.iq_append_quality_eligibility_read_audit",
    "create function ingestion_quality.iq_claim_next_quality_eligibility_outbox",
    "create function ingestion_quality.iq_cleanup_quality_eligibility_expired",
    "security definer\nset search_path = pg_catalog",
    "owner to scholarsense_ingestion_quality_batch_owner",
    "to scholarsense_ingestion_quality_eligibility_consumer",
)


def migration_inventory(root: pathlib.Path) -> list[tuple[int, pathlib.Path]]:
    migration_root = root / "backend/src/main/resources/db/migration"
    result: list[tuple[int, pathlib.Path]] = []
    for path in migration_root.rglob("V*.sql"):
        match = re.fullmatch(r"V(\d{6})__.+\.sql", path.name)
        if match:
            result.append((int(match.group(1)), path))
    return sorted(result)


def validate(root: pathlib.Path) -> list[str]:
    issues: list[str] = []
    inventory = migration_inventory(root)
    if [version for version, _ in inventory] != list(range(1, len(inventory) + 1)):
        issues.append("production migration inventory is not a continuous global sequence")
    feature = [path for _, path in inventory if path.name == FEATURE]
    if len(feature) != 1:
        return issues + [f"expected exactly one {FEATURE}"]
    sql = feature[0].read_text(encoding="utf-8")
    for token in REQUIRED_TOKENS:
        if token not in sql:
            issues.append(f"missing persistence token: {token}")
    if re.search(
        r"grant\s+(?:insert|delete|truncate)\b[^;]*scholarsense_ingestion_quality_",
        sql,
        re.IGNORECASE | re.DOTALL,
    ):
        issues.append("workload role received forbidden raw mutation privilege")
    functions = re.findall(
        r"create(?: or replace)? function\s+ingestion_quality\.([a-z0-9_]+).*?\n\$\$;",
        sql,
        re.IGNORECASE | re.DOTALL,
    )
    for function in sorted(set(functions)):
        if function.startswith("iq_") and (
            f"revoke all on function ingestion_quality.{function}" not in sql
            and function not in {"iq_guard_quality_eligibility_history", "iq_guard_quality_eligibility_current"}
        ):
            issues.append(f"function lacks explicit PUBLIC revoke: {function}")
    return issues


def main() -> int:
    root = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parents[1]
    issues = validate(root)
    if issues:
        for issue in issues:
            print(f"QUALITY_ELIGIBILITY_PERSISTENCE: {issue}", file=sys.stderr)
        return 1
    print("QUALITY_ELIGIBILITY_PERSISTENCE: migration, atomic boundary and least privilege valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
