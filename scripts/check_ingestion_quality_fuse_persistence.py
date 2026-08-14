#!/usr/bin/env python3
"""Structural fail-closed checks for the Story 2.5a V16 persistence successor."""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path


MIGRATION = Path(
    "backend/src/main/resources/db/migration/ingestion-quality/"
    "V000016__ingestion-quality__quality_fuse_task_v1.sql"
)
V15 = Path(
    "backend/src/main/resources/db/migration/ingestion-quality/"
    "V000015__ingestion-quality__quality_eligibility_v1.sql"
)
ADAPTER = Path(
    "backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/"
    "adapters/outbound/JdbcQualityEligibilityEventTransactionAdapter.java"
)
QUERY_SERVICE = Path(
    "backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/application/"
    "QualityRecoveryTaskQueryService.java"
)
RELAY_WORK = Path(
    "backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/"
    "adapters/outbound/JdbcQualityTaskRelayWork.java"
)
RELAY_PROCESSOR = Path(
    "backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/"
    "application/QualityTaskRelayProcessor.java"
)
RETENTION_CLEANUP = Path(
    "backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/"
    "adapters/outbound/JdbcCatalogRetentionCleanup.java"
)
COPY_PATHS = (
    MIGRATION, V15, ADAPTER, QUERY_SERVICE, RELAY_WORK, RELAY_PROCESSOR,
    RETENTION_CLEANUP,
)
V15_SHA256 = "21a66478ce29bf71838f4375c7162f5bbd390d5db60661981c5acfa03c419edb"

REQUIRED = (
    "create role scholarsense_ingestion_quality_task_relay nologin",
    "create table ingestion_quality.iq_quality_fuse_episode_history",
    "create table ingestion_quality.iq_quality_fuse_episode_current",
    "create table ingestion_quality.iq_quality_recovery_task_history",
    "create table ingestion_quality.iq_quality_recovery_task_current",
    "create table ingestion_quality.iq_quality_recovery_task_affected_rule",
    "create table ingestion_quality.iq_quality_task_delivery",
    "create table ingestion_quality.iq_quality_task_outbox",
    "create table ingestion_quality.iq_quality_fuse_idempotency",
    "create table ingestion_quality.iq_quality_fuse_rejection_audit",
    "create function ingestion_quality.iq_find_quality_snapshot_evidence_v2",
    "create function ingestion_quality.iq_load_quality_eligibility_processing_state_v2",
    "create function ingestion_quality.iq_accept_quality_eligibility_event_v2",
    "create function ingestion_quality.iq_append_quality_fuse_rejection_audit",
    "create function ingestion_quality.iq_cleanup_quality_fuse_expired",
    "create function ingestion_quality.iq_quality_public_task_reason",
    "create function ingestion_quality.iq_quality_public_affected_rules",
    "create function ingestion_quality.iq_quality_trigger_reason",
    "create function ingestion_quality.iq_claim_next_quality_task_outbox",
    "create function ingestion_quality.iq_authorize_quality_task_send",
    "create function ingestion_quality.iq_mark_quality_task_delivery_retry",
    "create function ingestion_quality.iq_complete_quality_task_delivery",
    "create function ingestion_quality.iq_fail_quality_task_delivery",
    "insert into ingestion_quality.iq_quality_task_outbox",
    "security definer\nset search_path = pg_catalog",
    "owner to scholarsense_ingestion_quality_batch_owner",
)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def migration_inventory(root: Path) -> list[tuple[int, Path]]:
    result: list[tuple[int, Path]] = []
    for path in (root / "backend/src/main/resources/db/migration").rglob("V*.sql"):
        match = re.fullmatch(r"V(\d{6})__.+\.sql", path.name)
        if match:
            result.append((int(match.group(1)), path))
    return sorted(result)


def function_body(sql: str, function_name: str) -> str:
    match = re.search(
        rf"create function ingestion_quality\.{re.escape(function_name)}\b.*?\nas \$\$(.*?)\n\$\$;",
        sql,
        re.IGNORECASE | re.DOTALL,
    )
    return "" if match is None else match.group(1)


def validate(root: Path) -> list[str]:
    issues: list[str] = []
    migration = root / MIGRATION
    if not migration.is_file():
        return [f"missing {MIGRATION}"]
    inventory = migration_inventory(root)
    versions = [version for version, _ in inventory]
    v16 = root / MIGRATION
    if (versions != list(range(1, len(versions) + 1))
            or len(inventory) < 16
            or inventory[15] != (16, v16)):
        issues.append("V16 must remain the continuous global successor of V15")
    if not (root / V15).is_file() or digest(root / V15) != V15_SHA256:
        issues.append("V15 predecessor byte digest changed")

    sql = migration.read_text(encoding="utf-8")
    lower = sql.lower()
    for token in REQUIRED:
        if token.lower() not in lower:
            label = "task outbox" if "task_outbox" in token else token
            issues.append(f"missing persistence token: {label}")
    source_lock = lower.find("quality-eligibility:")
    rule_lock = lower.find("quality-eligibility-rule:")
    episode_lock = lower.find("quality-fuse-episode:")
    task_lock = lower.find("quality-recovery-task:")
    state_read = lower.find("'currenteligibilities'")
    if not (0 <= source_lock < rule_lock < episode_lock < task_lock < state_read):
        issues.append("stable lock order must be source, RuleVersion, episode, task, then state read")
    if not re.search(
        r"create unique index\s+iq_quality_fuse_episode_one_active_idx.*?where active",
        lower,
        re.DOTALL,
    ):
        issues.append("unique active episode constraint missing")
    if (lower.count("work_item_key_version varchar(32) not null") < 4
            or "'workitemkeyversion'" not in lower
            or "work_item_key_version<>iq_plan->>'workitemkeyversion'" not in lower):
        issues.append("work-item HMAC key version must be pinned to episode and task facts")
    if "before update or delete on ingestion_quality.iq_quality_fuse_episode_history" not in lower:
        issues.append("fuse episode history must be append-only")
    if "before update or delete on ingestion_quality.iq_quality_recovery_task_history" not in lower:
        issues.append("RecoveryTask history must be append-only")
    if "before update or delete on ingestion_quality.iq_quality_fuse_rejection_audit" not in lower:
        issues.append("quality-fuse rejection audit must be append-only")
    if re.search(r"references\s+(?!ingestion_quality\.)[a-z_]+\.", lower):
        issues.append("cross-schema foreign key/reference is forbidden")
    if re.search(
        r"grant\s+(?:insert|update|delete|truncate)\b[^;]*"
        r"scholarsense_ingestion_quality_(?:eligibility_consumer|task_relay|online)",
        lower,
        re.DOTALL,
    ):
        issues.append("workload role received raw table mutation privilege")

    delivery_bodies = "\n".join(function_body(sql, name) for name in (
        "iq_mark_quality_task_delivery_retry",
        "iq_complete_quality_task_delivery",
        "iq_fail_quality_task_delivery",
    )).lower()
    if ("update ingestion_quality.iq_quality_recovery_task_current" in delivery_bodies
            or "update ingestion_quality.iq_quality_eligibility_current" in delivery_bodies):
        issues.append("delivery orthogonality forbids business/quality state updates")
    if "-- delivery_state_orthogonality" not in delivery_bodies:
        issues.append("delivery orthogonality marker missing")
    claim_body = function_body(sql, "iq_claim_next_quality_task_outbox").lower()
    send_body = function_body(sql, "iq_authorize_quality_task_send").lower()
    if ("task.aggregate_version=delivery.route_sequence" not in claim_body
            or "quality_task_route_superseded" not in claim_body
            or "task.aggregate_version=queued.route_sequence" not in send_body
            or delivery_bodies.count(
                "task.aggregate_version=delivery.route_sequence") < 3):
        issues.append("task relay claim, send and finalizers must fence current routeSequence")
    relay_work = (root / RELAY_WORK).read_text(encoding="utf-8")
    if ("pg_catalog.pg_advisory_lock" not in relay_work
            or "pg_catalog.pg_advisory_unlock" not in relay_work
            or "quality-task-route:" not in relay_work
            or "try (QualityTaskRelayWorkPort.SendPermit permit" not in (
                root / RELAY_PROCESSOR).read_text(encoding="utf-8")
            or "quality-task-route:'||iq_task_id::text" not in lower):
        issues.append(
            "task route updates and external send must share a non-transactional route permit"
        )

    accept_body = function_body(sql, "iq_accept_quality_eligibility_event_v2")
    if "ingestion_quality.iq_quality_public_task_reason(iq_trigger_reason)" not in accept_body:
        issues.append("public task reason mapping must be explicit at the outbox boundary")
    if ("ingestion_quality.iq_quality_public_affected_rules(iq_plan->'affectedRules')"
            not in accept_body):
        issues.append("public affectedRules mapping must be explicit at the outbox boundary")
    trigger_body = function_body(sql, "iq_quality_trigger_reason")
    if ("transition->>'reasonCode'=transition->>'evaluatedReasonCode'"
            not in trigger_body
            or "transition->>'reasonCode'='RECOVERY_RELAPSED'" not in trigger_body
            or "coalesce((select transition->>'evaluatedReasonCode'" in accept_body
            or "iq_quality_trigger_reason(" not in accept_body):
        issues.append("episode reason must come only from a true triggering transition")

    adapter = (root / ADAPTER).read_text(encoding="utf-8")
    if ("iq_load_quality_eligibility_processing_state_v2" not in adapter
            or "iq_accept_quality_eligibility_event_v2" not in adapter
            or "fuseTaskPlan" not in adapter):
        issues.append("JDBC adapter must use V16 state/mutation functions")
    if adapter.count("transactions.execute(") != 1:
        issues.append("JDBC adapter must preserve one outer transaction")
    if ("authorization.capture(" not in adapter
            or "authorization.revalidate(" not in adapter
            or "trustedTime.now()" not in adapter):
        issues.append("quality-fuse mutation must use authoritative authorization and trusted time")
    if ('"authorizationGeneration", 1' in adapter
            or '"serviceRef", "workload:' in adapter):
        issues.append("quality-fuse authorization evidence must not contain self-reported constants")
    if "iq_append_quality_fuse_rejection_audit" not in adapter:
        issues.append("authorization rejection must append a minimal audit fact")
    if ("eligibilityEvidence" not in adapter or "formulaEvidence" not in adapter
            or "sourceContractVersion" not in adapter
            or "comparisonResult" not in adapter):
        issues.append("fuse/task handoff must carry eligibility, member and formula evidence")
    if ("'eligibilities',iq_plan->'eligibilityEvidence'" not in sql
            or "'formulaBoundaries',iq_plan->'formulaEvidence'" not in sql):
        issues.append("episode/task facts must persist the complete handoff evidence")
    if (lower.count("interval '2 years'") < 5
            or lower.count("interval '90 days'") < 4
            or lower.count("legal_hold boolean not null default false") < 8
            or "not idempotency.legal_hold" not in lower
            or "not read_audit.legal_hold" not in lower):
        issues.append("quality-fuse P2Y/P90D retention must be legal-hold-aware")
    retention_cleanup = (root / RETENTION_CLEANUP).read_text(encoding="utf-8")
    if ("iq_cleanup_expired(?)" not in retention_cleanup
            or "iq_cleanup_quality_fuse_expired(?)" not in retention_cleanup):
        issues.append("production retention scheduler must execute the V16 fuse cleanup")
    query_service = (root / QUERY_SERVICE).read_text(encoding="utf-8")
    if ("authorizeOwnerScope(" not in query_service
            or query_service.find("authorizeOwnerScope(")
                > query_service.find("List<QualityRecoveryTask> candidates = safeList(raw)")
            or "criteria.afterTaskId(), 101" in query_service
            or "if requested_source_id is null" not in lower
            or "where current_fact.source_id=requested_source_id" not in lower):
        issues.append("RecoveryTask page slice must be formed inside an authorized source owner")
    return issues


def main() -> int:
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
    issues = validate(root)
    if issues:
        for issue in issues:
            print(f"QUALITY_FUSE_PERSISTENCE: {issue}", file=sys.stderr)
        return 1
    print("QUALITY_FUSE_PERSISTENCE: V16 atomic fuse/task/delivery boundary and least privilege valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
