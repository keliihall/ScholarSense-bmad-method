#!/usr/bin/env python3
"""Validate the additive production QualitySnapshot deletion-result 1.1 contract."""

from __future__ import annotations

import copy
import hashlib
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_ingestion_batch_contracts as task0  # noqa: E402
from release_json import (  # noqa: E402
    canonical_bytes,
    load_json,
    parse_json_bytes,
    schema_definition_issues,
    schema_issues,
)


EVENT_ROOT = Path("contracts/events/ingestion-quality")
SCHEMA = EVENT_ROOT / "quality-snapshot-deletion-result-1.1.0.schema.json"
LOCK_SCHEMA = (
    EVENT_ROOT / "quality-snapshot-deletion-result-contract-lock.schema.json"
)
LOCK = EVENT_ROOT / "quality-snapshot-deletion-result-contract-lock-1.1.0.json"
NEGATIVE_FIXTURES = (
    EVENT_ROOT
    / "fixtures/invalid/quality-snapshot-deletion-result-production-negative-fixtures-1.1.0.json"
)
VALID_FIXTURES = {
    "blocked": EVENT_ROOT
    / "fixtures/valid/quality-snapshot-deletion-production-blocked-v1.json",
    "completed": EVENT_ROOT
    / "fixtures/valid/quality-snapshot-deletion-production-completed-v1.json",
    "failed": EVENT_ROOT
    / "fixtures/valid/quality-snapshot-deletion-production-failed-v1.json",
    "missing-authority-blocked": EVENT_ROOT
    / "fixtures/valid/quality-snapshot-deletion-production-missing-authority-blocked-v1.json",
    "pre-due-blocked": EVENT_ROOT
    / "fixtures/valid/quality-snapshot-deletion-production-pre-due-blocked-v1.json",
    "partial": EVENT_ROOT
    / "fixtures/valid/quality-snapshot-deletion-production-partial-v1.json",
}

MAX_EVENT_BYTES = 64 * 1024
MAX_SAFE_INTEGER = 9_007_199_254_740_991
RESULT_CONTRACT_VERSION = "QUALITY-SNAPSHOT-DELETION-RESULT-1.1.0"
EVENT_TYPE = "scholarsense.ingestion-quality.quality-snapshot.deletion-result.v1"
REGISTRY_VERSION = "QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0"
CANONICALIZATION_PROFILE = "SCHOLARSENSE-CANONICAL-JSON-1.0.0"

PREDECESSOR_RAW_DIGESTS = {
    "contracts/events/ingestion-quality/fixtures/ordering/quality-snapshot-deletion-ordering-1.0.0.json":
        "sha256:0439a547c9d085e89d56589d73e285bce1ae1db54b017fd2ff065efba0014444",
    "contracts/events/ingestion-quality/fixtures/valid/quality-snapshot-deletion-blocked-v1.json":
        "sha256:b0d11e728bc7532698011f6fd943aa12cdaeed02d7a9a65ad209dfd499066b04",
    "contracts/events/ingestion-quality/fixtures/valid/quality-snapshot-deletion-completed-v1.json":
        "sha256:a74a57d80584764636321129e552f216b77acdb8921e6d33f654dac7a1ae1c5f",
    "contracts/events/ingestion-quality/fixtures/valid/quality-snapshot-deletion-failed-v1.json":
        "sha256:94d76ea91d889c9e938121d3141c7da5b1906dba32590bc10f6dfa668d32e8cc",
    "contracts/events/ingestion-quality/fixtures/valid/quality-snapshot-deletion-partial-v1.json":
        "sha256:56b23187885c2d21885dc4dd39dda179b235e2731b7237d807662aa4b24a9013",
    "contracts/events/ingestion-quality/quality-snapshot-deletion-result.schema.json":
        "sha256:7d1924966c69cf2465152ca945a4f8b12b7969f7e78adbcd5416fe53bfc3b754",
    "contracts/ingestion-quality/batch-quality/executable-quality-contract-lock-1.0.0.json":
        "sha256:b93d5547e6b28aa7281f736bd2440800eea590987d68ae8ab28ffd6a225cdb6f",
}

SUCCESSOR_LOCKED_FILES = frozenset({
    str(SCHEMA),
    str(LOCK_SCHEMA),
    str(NEGATIVE_FIXTURES),
    *(str(path) for path in VALID_FIXTURES.values()),
})

MANDATORY_NEGATIVE_CASES = frozenset({
    "predecessor-result-version-rejected",
    "conformance-anchor-field-rejected",
    "conformance-provider-rejected",
    "authority-runtime-none-rejected",
    "authority-id-not-uuidv7",
    "event-id-trailing-lf-rejected",
    "traceparent-trailing-lf-rejected",
    "snapshot-digest-trailing-lf-rejected",
    "source-id-trailing-lf-rejected",
    "consumer-id-trailing-lf-rejected",
    "malformed-database-transaction-id-rejected",
    "pre-due-blocker-missing",
    "pre-due-blocker-wrong",
    "pre-due-trusted-time-reaches-due",
    "pre-due-guard-after-decision",
    "completed-cannot-use-pre-due-chronology",
    "pre-due-successor-time-regression",
    "authority-evidence-ref-id-drift",
    "authority-evidence-id-aliases-prior-result",
    "authority-scope-digest-drift",
    "authority-registry-version-drift",
    "authority-registry-digest-drift",
    "authority-members-digest-drift",
    "authority-checked-at-drift",
    "authority-verification-status-drift",
    "missing-authority-root-causation-aliases-future-result",
    "outer-runtime-production-claim-rejected",
    "unknown-authority-field-rejected",
    "registry-digest-drift",
    "registry-members-unsorted",
    "registry-member-duplicate-consumer-id",
    "watermark-member-set-drift",
    "watermark-duplicate-consumer-id",
    "watermark-behind-completed-rejected",
    "transport-ack-missing-completed-rejected",
    "inbox-ack-missing-completed-rejected",
    "evidence-copy-ack-missing-completed-rejected",
    "attestation-consumer-drift",
    "attestation-snapshot-drift",
    "attestation-hash-drift",
    "attestation-version-drift",
    "attestation-registry-digest-drift",
    "attestation-scope-digest-drift",
    "attestation-after-commit",
    "authority-check-after-commit",
    "available-authority-null-rejected",
    "read-models-transaction-id-missing",
    "read-models-transaction-digest-drift",
    "read-models-count-drift",
    "unavailable-members-nonempty-rejected",
    "completed-with-legal-hold",
    "blocked-after-delete",
    "partial-without-failure",
    "failed-after-commit",
    "wire-bom-rejected",
    "wire-duplicate-key-rejected",
    "wire-unsafe-number-rejected",
    "wire-over-64-kib-rejected",
})

DATABASE_TARGET_KEYS = ("onlineSnapshot", "onlineMetrics", "readModels")

SOURCE_ID_RE = re.compile(r"SRC-P[01]-[A-Z-]+-[0-9]{3}", re.ASCII)
CONSUMER_ID_RE = re.compile(r"[a-z][a-z0-9-]{2,79}", re.ASCII)
TRACEPARENT_RE = re.compile(
    r"00-(?!0{32}-)[0-9a-f]{32}-(?!0{16}-)[0-9a-f]{16}-0[01]",
    re.ASCII,
)
TRACE_ID_RE = re.compile(r"(?!0{32})[0-9a-f]{32}", re.ASCII)


def _raw_digest(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def _canonical_digest(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_bytes(value)).hexdigest()


def _instant(value: Any) -> datetime:
    if not isinstance(value, str):
        raise ValueError("instant must be a string")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("instant must carry an offset")
    return parsed


def _is_source_id(value: Any) -> bool:
    return isinstance(value, str) and SOURCE_ID_RE.fullmatch(value) is not None


def _is_consumer_id(value: Any) -> bool:
    return isinstance(value, str) and CONSUMER_ID_RE.fullmatch(value) is not None


def _is_traceparent(value: Any) -> bool:
    return isinstance(value, str) and TRACEPARENT_RE.fullmatch(value) is not None


def _is_trace_id(value: Any) -> bool:
    return isinstance(value, str) and TRACE_ID_RE.fullmatch(value) is not None


def _schema_candidate_issues(root: Path, event: Any) -> list[str]:
    try:
        schema = load_json(root / SCHEMA)
    except (OSError, TypeError, ValueError):
        return ["DELETION_RESULT_1_1_SCHEMA_REJECTED"]
    if schema_definition_issues(schema) or schema_issues(event, schema):
        return ["DELETION_RESULT_1_1_SCHEMA_REJECTED"]
    return []


def _registry_members(value: Any) -> list[dict[str, Any]] | None:
    if not isinstance(value, list):
        return None
    members: list[dict[str, Any]] = []
    for item in value:
        if not isinstance(item, dict):
            return None
        member = {
            "consumerId": item.get("consumerId"),
            "registryMembership": item.get("registryMembership"),
            "lifecycleStatus": item.get("lifecycleStatus"),
        }
        if (
            not _is_consumer_id(member["consumerId"])
            or not all(
                isinstance(member[key], str) and member[key]
                for key in ("registryMembership", "lifecycleStatus")
            )
        ):
            return None
        members.append(member)
    return members


def _valid_membership_lifecycle(member: dict[str, Any]) -> bool:
    allowed = {
        "required-for-snapshot": {"active", "inactive"},
        "unreleased-reference-holder": {"active", "inactive", "retired"},
        "planned-never-held-reference": {"planned"},
    }
    return member.get("lifecycleStatus") in allowed.get(
        member.get("registryMembership"), set()
    )


def _production_guard_binding_issues(data: dict[str, Any]) -> list[str]:
    issues: list[str] = []
    scope = data.get("scope")
    guards = data.get("guards")
    if not isinstance(scope, dict) or not isinstance(guards, dict):
        return ["DELETION_RESULT_1_1_GUARD_BINDING_INVALID"]

    legal_hold = guards.get("legalHold")
    if (
        not isinstance(legal_hold, dict)
        or legal_hold.get("checkedScopeDigest") != scope.get("scopeDigest")
    ):
        issues.append("DELETION_RESULT_1_1_LEGAL_HOLD_SCOPE_MISMATCH")

    registry = guards.get("consumerRegistry")
    watermarks = guards.get("consumerWatermarks")
    if not isinstance(registry, dict) or not isinstance(watermarks, list):
        return sorted(set(issues + [
            "DELETION_RESULT_1_1_REGISTRY_BINDING_INVALID",
            "DELETION_RESULT_1_1_WATERMARK_BINDING_INVALID",
        ]))

    registry_version = registry.get("registryVersion")
    registry_status = registry.get("status")
    members = _registry_members(registry.get("members"))
    watermark_members = _registry_members(watermarks)
    empty_registry_digest = _canonical_digest({
        "registryVersion": REGISTRY_VERSION,
        "members": [],
    })

    if registry_status == "unavailable":
        if (
            registry_version != REGISTRY_VERSION
            or registry.get("registryDigest") != empty_registry_digest
            or registry.get("members") != []
            or registry.get("checkedAt") is not None
            or registry.get("authorityEvidence") is not None
        ):
            issues.append("DELETION_RESULT_1_1_REGISTRY_BINDING_INVALID")
        if watermarks != [] or guards.get("consumerWatermarksCheckedAt") is not None:
            issues.append("DELETION_RESULT_1_1_WATERMARK_BINDING_INVALID")
        return sorted(set(issues))

    if registry_status != "available" or members is None:
        return sorted(set(issues + [
            "DELETION_RESULT_1_1_REGISTRY_BINDING_INVALID"
        ]))

    consumer_ids = [member["consumerId"] for member in members]
    members_sorted_unique = (
        consumer_ids == sorted(consumer_ids)
        and len(consumer_ids) == len(set(consumer_ids))
        and all(_valid_membership_lifecycle(member) for member in members)
    )
    members_digest = _canonical_digest(members)
    registry_digest = _canonical_digest({
        "registryVersion": registry_version,
        "members": members,
    })
    if (
        registry_version != REGISTRY_VERSION
        or not members_sorted_unique
        or registry.get("registryDigest") != registry_digest
    ):
        issues.append("DELETION_RESULT_1_1_REGISTRY_BINDING_INVALID")

    authority = registry.get("authorityEvidence")
    if not isinstance(authority, dict):
        issues.append("DELETION_RESULT_1_1_AUTHORITY_EVIDENCE_INVALID")
    else:
        authority_id = authority.get("authorityEvidenceId")
        expected_ref = (
            "consumer-registry-authority://production/quality-snapshot/"
            f"{authority_id}"
        )
        owner_identity_values = {
            data.get("eventId"),
            data.get("correlationId"),
            data.get("aggregateId"),
            data.get("executionId"),
        }
        if (
            not task0._is_uuid_v7(authority_id)
            or authority_id in owner_identity_values
            or authority.get("provider") != "consumer-registry-authority"
            or authority.get("evidenceRef") != expected_ref
            or authority.get("scopeDigest") != scope.get("scopeDigest")
            or authority.get("registryVersion") != registry_version
            or authority.get("registryDigest") != registry_digest
            or authority.get("membersDigest") != members_digest
            or authority.get("checkedAt") != registry.get("checkedAt")
            or authority.get("verificationStatus") != "verified"
            or authority.get("runtimeEvidenceClaim") != "production-verified"
        ):
            issues.append("DELETION_RESULT_1_1_AUTHORITY_EVIDENCE_INVALID")

    watermark_ids = (
        [member["consumerId"] for member in watermark_members]
        if watermark_members is not None else []
    )
    if (
        watermark_members is None
        or watermark_members != members
        or watermark_ids != sorted(watermark_ids)
        or len(watermark_ids) != len(set(watermark_ids))
        or guards.get("consumerWatermarksCheckedAt") != registry.get("checkedAt")
    ):
        issues.append("DELETION_RESULT_1_1_WATERMARK_BINDING_INVALID")

    evaluated_at: datetime | None
    decision_at: datetime | None
    try:
        evaluated_at = _instant(scope.get("evaluatedAt"))
        decision_at = _instant(
            data.get("deletionCommittedAt") or data.get("occurredAt")
        )
    except (TypeError, ValueError):
        evaluated_at = None
        decision_at = None

    seen_attestations: dict[bytes, str] = {}
    for watermark in watermarks:
        if not isinstance(watermark, dict):
            issues.append("DELETION_RESULT_1_1_WATERMARK_BINDING_INVALID")
            continue
        if watermark.get("requiredAggregateVersion") != scope.get(
            "snapshotAggregateVersion"
        ):
            issues.append("DELETION_RESULT_1_1_WATERMARK_BINDING_INVALID")
        attestation = watermark.get("attestation")
        if attestation is None:
            continue
        if not isinstance(attestation, dict):
            issues.append("DELETION_RESULT_1_1_ATTESTATION_BINDING_INVALID")
            continue
        expected_attestation = {
            "consumerId": watermark.get("consumerId"),
            "registryVersion": registry_version,
            "registryDigest": registry_digest,
            "scopeDigest": scope.get("scopeDigest"),
            "snapshotId": scope.get("snapshotId"),
            "snapshotImmutableHash": scope.get("snapshotImmutableHash"),
            "requiredAggregateVersion": scope.get("snapshotAggregateVersion"),
        }
        if any(attestation.get(key) != value for key, value in expected_attestation.items()):
            issues.append("DELETION_RESULT_1_1_ATTESTATION_BINDING_INVALID")
        evidence_ack = watermark.get("evidenceCopyAck")
        attestation_kind = attestation.get("kind")
        if (
            (evidence_ack == "copied" and attestation_kind != "evidence-copy")
            or (
                evidence_ack == "not-required"
                and attestation_kind not in {
                    "zero-dependency-no-reference",
                    "decommission-no-reference",
                }
            )
            or (
                watermark.get("lifecycleStatus") == "active"
                and attestation_kind == "decommission-no-reference"
            )
        ):
            issues.append("DELETION_RESULT_1_1_ATTESTATION_BINDING_INVALID")
        try:
            attested_at = _instant(attestation.get("attestedAt"))
            if (
                evaluated_at is None
                or decision_at is None
                or not evaluated_at <= attested_at <= decision_at
            ):
                issues.append("DELETION_RESULT_1_1_CHRONOLOGY_INVALID")
        except (TypeError, ValueError):
            issues.append("DELETION_RESULT_1_1_CHRONOLOGY_INVALID")
        try:
            attestation_bytes = canonical_bytes(attestation)
        except (TypeError, ValueError):
            continue
        previous_consumer = seen_attestations.get(attestation_bytes)
        if previous_consumer is not None and previous_consumer != watermark.get(
            "consumerId"
        ):
            issues.append("DELETION_RESULT_1_1_ATTESTATION_BINDING_INVALID")
        seen_attestations[attestation_bytes] = watermark.get("consumerId")
    return sorted(set(issues))


def _guard_blockers(data: dict[str, Any]) -> list[str]:
    blockers: list[str] = []
    scope = data.get("scope")
    guards = data.get("guards")
    if not isinstance(scope, dict) or not isinstance(guards, dict):
        return ["WATERMARK_DEPENDENCY_UNAVAILABLE"]

    trusted = guards.get("trustedTime")
    if not isinstance(trusted, dict) or trusted.get("status") != "available":
        blockers.append("TRUSTED_TIME_UNAVAILABLE")
    else:
        try:
            if _instant(trusted.get("observedAt")) < _instant(
                scope.get("retentionDueAt")
            ):
                blockers.append("RETENTION_NOT_DUE")
        except (TypeError, ValueError):
            blockers.append("TRUSTED_TIME_UNAVAILABLE")

    legal_hold = guards.get("legalHold")
    if not isinstance(legal_hold, dict):
        blockers.append("LEGAL_HOLD_DEPENDENCY_UNAVAILABLE")
    elif legal_hold.get("status") == "matched":
        matched = legal_hold.get("matchedScopeDigests")
        if (
            isinstance(matched, list)
            and legal_hold.get("matchedCount") == len(matched)
            and scope.get("scopeDigest") in matched
        ):
            blockers.append("LEGAL_HOLD_MATCHED")
        else:
            blockers.append("LEGAL_HOLD_DEPENDENCY_UNAVAILABLE")
    elif legal_hold.get("status") == "clear":
        if legal_hold.get("matchedCount") != 0 or legal_hold.get(
            "matchedScopeDigests"
        ) != []:
            blockers.append("LEGAL_HOLD_DEPENDENCY_UNAVAILABLE")
    else:
        blockers.append("LEGAL_HOLD_DEPENDENCY_UNAVAILABLE")

    registry = guards.get("consumerRegistry")
    watermarks = guards.get("consumerWatermarks")
    if not isinstance(registry, dict) or registry.get("status") != "available":
        blockers.append("CONSUMER_REGISTRY_UNAVAILABLE")
        return sorted(set(blockers))
    if not isinstance(watermarks, list):
        return sorted(set(blockers + ["WATERMARK_DEPENDENCY_UNAVAILABLE"]))

    for watermark in watermarks:
        if not isinstance(watermark, dict):
            blockers.append("WATERMARK_DEPENDENCY_UNAVAILABLE")
            continue
        membership = watermark.get("registryMembership")
        lifecycle = watermark.get("lifecycleStatus")
        attestation = watermark.get("attestation")
        attestation_kind = (
            attestation.get("kind") if isinstance(attestation, dict) else None
        )
        released = (
            membership == "unreleased-reference-holder"
            and attestation_kind == "decommission-no-reference"
        )
        included = membership == "required-for-snapshot" or (
            membership == "unreleased-reference-holder" and not released
        )
        if membership == "planned-never-held-reference":
            if lifecycle != "planned":
                blockers.append("CONSUMER_REGISTRY_UNAVAILABLE")
            continue
        if not included:
            continue

        status = watermark.get("watermarkStatus")
        required = watermark.get("requiredAggregateVersion")
        confirmed = watermark.get("confirmedAggregateVersion")
        if status == "missing":
            blockers.append("CONSUMER_WATERMARK_MISSING")
        elif status == "unknown":
            blockers.append("CONSUMER_WATERMARK_UNKNOWN")
        elif (
            status != "confirmed"
            or not isinstance(required, int)
            or isinstance(required, bool)
            or not isinstance(confirmed, int)
            or isinstance(confirmed, bool)
            or required != scope.get("snapshotAggregateVersion")
        ):
            blockers.append("WATERMARK_DEPENDENCY_UNAVAILABLE")
        elif confirmed < required:
            blockers.append("CONSUMER_WATERMARK_BEHIND")
        if watermark.get("transportAck") != "acked" or watermark.get(
            "inboxAck"
        ) != "acked":
            blockers.append("WATERMARK_DEPENDENCY_UNAVAILABLE")
        evidence_ack = watermark.get("evidenceCopyAck")
        evidence_satisfied = (
            evidence_ack == "copied" and attestation_kind == "evidence-copy"
        ) or (
            evidence_ack == "not-required"
            and attestation_kind in {
                "zero-dependency-no-reference",
                "decommission-no-reference",
            }
        )
        if not evidence_satisfied:
            blockers.append("EVIDENCE_COPY_ACK_MISSING")
    return sorted(set(blockers))


def _chronology_issues(data: dict[str, Any]) -> list[str]:
    scope = data.get("scope")
    guards = data.get("guards")
    if not isinstance(scope, dict) or not isinstance(guards, dict):
        return ["DELETION_RESULT_1_1_CHRONOLOGY_INVALID"]
    trusted = guards.get("trustedTime")
    legal = guards.get("legalHold")
    registry = guards.get("consumerRegistry")
    if not all(isinstance(item, dict) for item in (trusted, legal, registry)):
        return ["DELETION_RESULT_1_1_CHRONOLOGY_INVALID"]
    try:
        evaluated_at = _instant(scope.get("evaluatedAt"))
        due_at = _instant(scope.get("retentionDueAt"))
        occurred_at = _instant(data.get("occurredAt"))
        committed_raw = data.get("deletionCommittedAt")
        decision_at = _instant(committed_raw) if committed_raw else occurred_at
        trusted_available = trusted.get("status") == "available"
        trusted_at = (
            _instant(trusted.get("observedAt")) if trusted_available else None
        )
        pre_due_decision = (
            committed_raw is None
            and trusted_at is not None
            and trusted_at < due_at
        )
        time_specs = (
            (trusted.get("observedAt"), trusted_available),
            (legal.get("checkedAt"), legal.get("status") in {"clear", "matched"}),
            (registry.get("checkedAt"), registry.get("status") == "available"),
            (
                guards.get("consumerWatermarksCheckedAt"),
                registry.get("status") == "available",
            ),
        )
        checked_times: list[datetime] = []
        for value, required in time_specs:
            if required and value is None:
                raise ValueError("required check time missing")
            if not required and value is not None:
                raise ValueError("unavailable dependency carried a check time")
            if value is not None:
                checked_times.append(_instant(value))
        if pre_due_decision:
            if not evaluated_at <= decision_at <= occurred_at:
                raise ValueError("pre-due decision ordering invalid")
            if any(
                not evaluated_at <= checked <= decision_at
                for checked in checked_times
            ):
                raise ValueError("pre-due guard ordering invalid")
        else:
            if not due_at <= decision_at <= occurred_at:
                raise ValueError("decision ordering invalid")
            if any(
                not due_at <= checked <= decision_at
                for checked in checked_times
            ):
                raise ValueError("guard ordering invalid")
    except (TypeError, ValueError):
        return ["DELETION_RESULT_1_1_CHRONOLOGY_INVALID"]
    return []


def _map_task0_issues(values: list[str]) -> list[str]:
    return [
        value.replace("DELETION_RESULT_", "DELETION_RESULT_1_1_", 1)
        if value.startswith("DELETION_RESULT_") else value
        for value in values
    ]


def database_transaction_evidence_material_1_1(
    data: dict[str, Any], local_results: dict[str, Any]
) -> dict[str, Any]:
    """Return the additive three-table transaction binding material."""
    target_fields = (
        "status",
        "selectedCount",
        "deletedCount",
        "remainingCount",
        "evidenceDigest",
        "errorCode",
    )
    targets = {
        key: local_results.get(key)
        if isinstance(local_results.get(key), dict) else {}
        for key in DATABASE_TARGET_KEYS
    }
    scope = data.get("scope")
    return {
        "executionId": data.get("executionId"),
        "scopeDigest": (
            scope.get("scopeDigest") if isinstance(scope, dict) else None
        ),
        "result": data.get("result"),
        "deletionCommittedAt": data.get("deletionCommittedAt"),
        "transactionId": targets["onlineSnapshot"].get("transactionId"),
        **{
            key: {
                field: targets[key].get(field) for field in target_fields
            }
            for key in DATABASE_TARGET_KEYS
        },
    }


def _owner_database_issues_1_1(
    data: dict[str, Any], local_results: dict[str, Any]
) -> list[str]:
    issues: list[str] = []
    database_results = [local_results.get(key) for key in DATABASE_TARGET_KEYS]
    if not all(isinstance(item, dict) for item in database_results):
        return [
            "DELETION_RESULT_1_1_MANDATORY_DB_TARGET_INVALID",
            "DELETION_RESULT_1_1_OWNER_DB_ATOMICITY_EVIDENCE_INVALID",
        ]
    result = data.get("result")
    if any(item.get("status") == "not-applicable" for item in database_results):
        issues.append("DELETION_RESULT_1_1_MANDATORY_DB_TARGET_INVALID")
    if result in {"completed", "partial"} and any(
        item.get("status") == "not-attempted" for item in database_results
    ):
        issues.append("DELETION_RESULT_1_1_MANDATORY_DB_TARGET_INVALID")
    snapshot = database_results[0]
    if snapshot.get("status") in {"deleted", "failed"} and snapshot.get(
        "selectedCount"
    ) != 1:
        issues.append("DELETION_RESULT_1_1_SNAPSHOT_COUNT_INVALID")

    success_statuses = {"deleted", "already-absent"}
    success_flags = [
        item.get("status") in success_statuses for item in database_results
    ]
    if any(success_flags) and not all(success_flags):
        issues.append("DELETION_RESULT_1_1_OWNER_DB_ATOMICITY_INVALID")

    transaction_ids = [item.get("transactionId") for item in database_results]
    transaction_digests = [
        item.get("transactionEvidenceDigest") for item in database_results
    ]
    if result == "blocked":
        evidence_valid = (
            transaction_ids == [None, None, None]
            and transaction_digests == [None, None, None]
        )
    elif result in {"completed", "partial", "failed"}:
        expected = _canonical_digest(
            database_transaction_evidence_material_1_1(data, local_results)
        )
        evidence_valid = (
            len(set(transaction_ids)) == 1
            and task0._is_uuid_v7(transaction_ids[0])
            and len(set(transaction_digests)) == 1
            and transaction_digests[0] == expected
        )
    else:
        evidence_valid = False
    if not evidence_valid:
        issues.append("DELETION_RESULT_1_1_OWNER_DB_ATOMICITY_EVIDENCE_INVALID")

    for key in task0.OWNER_LOCAL_RESULT_KEYS - set(DATABASE_TARGET_KEYS):
        item = local_results.get(key)
        if isinstance(item, dict) and (
            item.get("transactionId") is not None
            or item.get("transactionEvidenceDigest") is not None
        ):
            issues.append("DELETION_RESULT_1_1_OWNER_DB_ATOMICITY_EVIDENCE_INVALID")
    return sorted(set(issues))


def production_event_issues(project_root: Path, event: Any) -> list[str]:
    """Validate a parsed production 1.1 deletion-result event."""
    root = project_root.resolve()
    if not isinstance(event, dict):
        return ["DELETION_RESULT_1_1_SCHEMA_REJECTED"]
    issues: list[str] = []
    try:
        if len(canonical_bytes(event)) > MAX_EVENT_BYTES:
            issues.append("DELETION_RESULT_1_1_PAYLOAD_TOO_LARGE")
    except (TypeError, ValueError):
        return ["DELETION_RESULT_1_1_WIRE_JSON_INVALID"]
    private_keys = {
        "studentid",
        "studentnumber",
        "rawstudentidentifier",
        "sourcepayload",
        "evidencebody",
        "plaintext",
        "secret",
    }
    if task0._normalized_keys(event) & private_keys:
        issues.append("DELETION_RESULT_1_1_PRIVACY_BOUNDARY")
    schema_candidate_issues = _schema_candidate_issues(root, event)
    if schema_candidate_issues:
        return sorted(set(issues + schema_candidate_issues))

    data = event.get("data")
    if not isinstance(data, dict):
        return sorted(set(issues + ["DELETION_RESULT_1_1_SCHEMA_REJECTED"]))
    if data.get("resultContractVersion") != RESULT_CONTRACT_VERSION:
        issues.append("DELETION_RESULT_1_1_VERSION_INVALID")
    if event.get("type") != EVENT_TYPE:
        issues.append("DELETION_RESULT_1_1_EVENT_TYPE_INVALID")
    if data.get("runtimeEvidenceClaim") != "none":
        issues.append("DELETION_RESULT_1_1_OUTER_RUNTIME_CLAIM_INVALID")
    issues.extend(_map_task0_issues(task0._pic_binding_issues(data)))
    if (
        not task0._is_uuid_v7(event.get("id"))
        or not task0._is_uuid_v7(data.get("eventId"))
    ):
        issues.append("DELETION_RESULT_1_1_EVENT_ID_INVALID")
    if event.get("id") != data.get("eventId"):
        issues.append("DELETION_RESULT_1_1_ID_MISMATCH")
    scope = data.get("scope")
    if not isinstance(scope, dict) or event.get("subject") != (
        f"quality-snapshot/{scope.get('snapshotId')}"
    ):
        issues.append("DELETION_RESULT_1_1_SUBJECT_MISMATCH")
    if event.get("time") != data.get("occurredAt"):
        issues.append("DELETION_RESULT_1_1_TIME_MISMATCH")
    if (
        not _is_traceparent(event.get("traceparent"))
        or not _is_trace_id(data.get("traceId"))
    ):
        issues.append("DELETION_RESULT_1_1_TRACE_INVALID")
    issues.extend(_map_task0_issues(task0._trace_issues(event, data)))
    if data.get("aggregateId") != data.get("executionId"):
        issues.append("DELETION_RESULT_1_1_EXECUTION_IDENTITY_MISMATCH")
    version = data.get("aggregateVersion")
    if (
        not isinstance(version, int)
        or isinstance(version, bool)
        or not 1 <= version <= MAX_SAFE_INTEGER
    ):
        issues.append("DELETION_RESULT_1_1_VERSION_INVALID")
    if not isinstance(scope, dict) or scope.get("scopeDigest") != (
        task0._deletion_scope_digest(data)
    ):
        issues.append("DELETION_RESULT_1_1_SCOPE_DIGEST_MISMATCH")
    if isinstance(scope, dict):
        if (
            not _is_source_id(scope.get("sourceId"))
            or not task0._is_sha256_digest(scope.get("snapshotImmutableHash"))
            or not task0._is_sha256_digest(scope.get("scopeDigest"))
        ):
            issues.append("DELETION_RESULT_1_1_RETENTION_SCOPE_INVALID")
        try:
            if _instant(scope.get("retentionDueAt")) != task0._plus_utc_years(
                scope.get("evaluatedAt"), 2
            ):
                issues.append("DELETION_RESULT_1_1_RETENTION_SCOPE_INVALID")
        except (TypeError, ValueError):
            issues.append("DELETION_RESULT_1_1_RETENTION_SCOPE_INVALID")

    handoff = data.get("auditHandoff")
    if (
        "deletionreceiptid" in task0._normalized_keys(event)
        or not isinstance(handoff, dict)
        or handoff.get("targetOwner") != "audit-operations"
        or handoff.get("inputKind") != "owner-local-deletion-result"
        or handoff.get("finalReceiptOwner") != "audit-operations"
        or handoff.get("ownerResultIsFinalReceipt") is not False
        or handoff.get("conformanceReceiptSatisfiesProduction") is not False
    ):
        issues.append("DELETION_RESULT_1_1_FINAL_RECEIPT_BOUNDARY")

    result = data.get("result")
    local_results = data.get("ownerLocalResults")
    if not isinstance(local_results, dict) or set(local_results) != task0.OWNER_LOCAL_RESULT_KEYS:
        issues.append("DELETION_RESULT_1_1_TARGET_SET_INVALID")
        local_results = {}
    if any(
        not task0._owner_result_counts_valid(item)
        for item in local_results.values()
    ):
        issues.append("DELETION_RESULT_1_1_TARGET_COUNT_INVALID")
    issues.extend(_owner_database_issues_1_1(data, local_results))
    issues.extend(_production_guard_binding_issues(data))
    issues.extend(_chronology_issues(data))

    blockers = _guard_blockers(data)
    blocker_codes = data.get("blockerCodes")
    failure_codes = data.get("failureCodes")
    if (
        not isinstance(blocker_codes, list)
        or not isinstance(failure_codes, list)
        or not all(isinstance(code, str) for code in blocker_codes)
        or not all(isinstance(code, str) for code in failure_codes)
        or len(blocker_codes) != len(set(blocker_codes))
        or len(failure_codes) != len(set(failure_codes))
    ):
        issues.append("DELETION_RESULT_1_1_REASON_SET_INVALID")
        blocker_codes = []
        failure_codes = []
    if set(blocker_codes) != set(blockers):
        issues.append("DELETION_RESULT_1_1_BLOCKER_SET_MISMATCH")
    target_failure_codes = {
        item.get("errorCode")
        for item in local_results.values()
        if isinstance(item, dict)
        and item.get("status") == "failed"
        and isinstance(item.get("errorCode"), str)
    }
    if set(failure_codes) != target_failure_codes:
        issues.append("DELETION_RESULT_1_1_FAILURE_SET_MISMATCH")
    if any(
        code not in task0.CONTROLLED_DELETION_FAILURE_CODES
        for code in set(failure_codes) | target_failure_codes
    ):
        issues.append("DELETION_RESULT_1_1_FAILURE_CODE_UNCONTROLLED")

    if result == "completed":
        targets_valid = all(
            isinstance(item, dict)
            and item.get("status") in {
                "deleted", "already-absent", "not-applicable"
            }
            and item.get("remainingCount") == 0
            and item.get("errorCode") is None
            for item in local_results.values()
        )
        if (
            blockers
            or blocker_codes
            or failure_codes
            or not targets_valid
            or data.get("deletionCommittedAt") is None
        ):
            issues.append("DELETION_RESULT_1_1_COMPLETED_INVARIANT")
    elif result == "blocked":
        targets_valid = all(
            isinstance(item, dict)
            and item.get("status") == "not-attempted"
            and item.get("deletedCount") == 0
            for item in local_results.values()
        )
        if (
            not blockers
            or not blocker_codes
            or failure_codes
            or not targets_valid
            or data.get("deletionCommittedAt") is not None
        ):
            issues.append("DELETION_RESULT_1_1_BLOCKED_INVARIANT")
    elif result == "partial":
        has_delete = any(
            isinstance(item, dict)
            and item.get("status") == "deleted"
            and item.get("deletedCount", 0) > 0
            for item in local_results.values()
        )
        has_failure = any(
            isinstance(item, dict)
            and (
                item.get("status") == "failed"
                or item.get("remainingCount", 0) > 0
            )
            for item in local_results.values()
        )
        if (
            blockers
            or blocker_codes
            or not failure_codes
            or not has_delete
            or not has_failure
            or data.get("deletionCommittedAt") is None
        ):
            issues.append("DELETION_RESULT_1_1_PARTIAL_INVARIANT")
    elif result == "failed":
        targets_valid = any(
            isinstance(item, dict) and item.get("status") == "failed"
            for item in local_results.values()
        ) and all(
            isinstance(item, dict)
            and item.get("deletedCount") == 0
            and item.get("status") in {"not-attempted", "failed"}
            for item in local_results.values()
        )
        if (
            blockers
            or blocker_codes
            or not failure_codes
            or not targets_valid
            or data.get("deletionCommittedAt") is not None
        ):
            issues.append("DELETION_RESULT_1_1_FAILED_INVARIANT")
    else:
        issues.append("DELETION_RESULT_1_1_OUTCOME_INVALID")
    issues.extend(_map_task0_issues(task0._backup_issues(data)))
    return sorted(set(issues))


def production_wire_issues(project_root: Path, raw_bytes: Any) -> list[str]:
    """Validate actual UTF-8 bytes, not a reserialized approximation."""
    if not isinstance(raw_bytes, (bytes, bytearray)):
        return ["DELETION_RESULT_1_1_WIRE_INVALID"]
    payload = bytes(raw_bytes)
    issues: list[str] = []
    if len(payload) > MAX_EVENT_BYTES:
        issues.append("DELETION_RESULT_1_1_PAYLOAD_TOO_LARGE")
    try:
        event = parse_json_bytes(payload)
    except (TypeError, ValueError):
        return sorted(set(issues + ["DELETION_RESULT_1_1_WIRE_JSON_INVALID"]))
    issues.extend(production_event_issues(project_root, event))
    return sorted(set(issues))


def production_lineage_issues(events: Any) -> list[str]:
    """Validate replay/idempotency and the direct-successor event lineage."""
    if not isinstance(events, list):
        return ["DELETION_RESULT_1_1_LINEAGE_INVALID"]
    issues: list[str] = []
    unique_events: dict[str, dict[str, Any]] = {}
    for event in events:
        if isinstance(event, dict) and isinstance(event.get("id"), str):
            unique_events.setdefault(event["id"], event)
    event_ids = set(unique_events)
    authority_ids: list[str] = []
    for event in unique_events.values():
        if not isinstance(event, dict) or not isinstance(event.get("data"), dict):
            issues.append("DELETION_RESULT_1_1_LINEAGE_INVALID")
        elif event["data"].get("resultContractVersion") != RESULT_CONTRACT_VERSION:
            issues.append("DELETION_RESULT_1_1_LINEAGE_VERSION_INVALID")
        else:
            registry = event["data"].get("guards", {}).get("consumerRegistry")
            authority = (
                registry.get("authorityEvidence")
                if isinstance(registry, dict) else None
            )
            authority_id = None
            if isinstance(authority, dict):
                authority_id = authority.get("authorityEvidenceId")
            elif (
                isinstance(registry, dict)
                and registry.get("status") == "unavailable"
                and event["data"].get("aggregateVersion") == 1
                and event["data"].get("supersedesResultId") is None
            ):
                # A root unavailable result still reserves the owner-generated
                # authority attempt id carried by causationId. It must never
                # become a later deletion-result event id or be reused as a
                # different authority identity in the same lineage.
                authority_id = event["data"].get("causationId")
            if isinstance(authority_id, str):
                authority_ids.append(authority_id)
                if authority_id in event_ids:
                    issues.append(
                        "DELETION_RESULT_1_1_LINEAGE_AUTHORITY_ID_ALIAS"
                    )
    if len(authority_ids) != len(set(authority_ids)):
        issues.append("DELETION_RESULT_1_1_LINEAGE_AUTHORITY_ID_REUSE")
    issues.extend(_map_task0_issues(task0.deletion_result_lineage_issues(events)))
    return sorted(set(issues))


def production_delivery_decision(
    current_watermark: int,
    incoming_version: int,
    *,
    same_payload: bool,
) -> str:
    return task0.deletion_delivery_decision(
        current_watermark, incoming_version, same_payload=same_payload
    )


def predecessor_issues(project_root: Path) -> list[str]:
    """Prove Task 0 schema, fixtures, ordering vector, and aggregate lock are untouched."""
    root = project_root.resolve()
    issues: list[str] = []
    for relative, expected in PREDECESSOR_RAW_DIGESTS.items():
        path = root / relative
        if not path.is_file() or _raw_digest(path) != expected:
            issues.append(f"DELETION_RESULT_1_1_PREDECESSOR_RAW_DRIFT: {relative}")
    aggregate_lock_path = root / (
        "contracts/ingestion-quality/batch-quality/"
        "executable-quality-contract-lock-1.0.0.json"
    )
    try:
        aggregate_lock = load_json(aggregate_lock_path)
    except (OSError, TypeError, ValueError):
        return sorted(set(issues + [
            "DELETION_RESULT_1_1_PREDECESSOR_LOCK_INVALID"
        ]))
    locked = aggregate_lock.get("digests")
    if not isinstance(locked, dict):
        issues.append("DELETION_RESULT_1_1_PREDECESSOR_LOCK_INVALID")
    else:
        for relative, digest in PREDECESSOR_RAW_DIGESTS.items():
            if relative == str(aggregate_lock_path.relative_to(root)):
                continue
            if locked.get(relative) != digest:
                issues.append(
                    f"DELETION_RESULT_1_1_PREDECESSOR_LOCK_INVALID: {relative}"
                )
    return sorted(set(issues))


def successor_lock_issues(project_root: Path) -> list[str]:
    root = project_root.resolve()
    issues: list[str] = []
    try:
        lock_schema = load_json(root / LOCK_SCHEMA)
        lock = load_json(root / LOCK)
    except (OSError, TypeError, ValueError):
        return ["DELETION_RESULT_1_1_LOCK_INVALID"]
    if schema_definition_issues(lock_schema) or schema_issues(lock, lock_schema):
        issues.append("DELETION_RESULT_1_1_LOCK_INVALID")

    predecessor_entries = lock.get("predecessorFiles")
    successor_entries = lock.get("successorFiles")
    if not isinstance(predecessor_entries, list) or not isinstance(
        successor_entries, list
    ):
        return sorted(set(issues + ["DELETION_RESULT_1_1_LOCK_INVALID"]))
    predecessor_map = {
        entry.get("path"): entry.get("rawSha256")
        for entry in predecessor_entries if isinstance(entry, dict)
    }
    if (
        len(predecessor_map) != len(predecessor_entries)
        or predecessor_map != PREDECESSOR_RAW_DIGESTS
        or [entry.get("path") for entry in predecessor_entries]
        != sorted(PREDECESSOR_RAW_DIGESTS)
    ):
        issues.append("DELETION_RESULT_1_1_PREDECESSOR_LOCK_INVALID")

    successor_map = {
        entry.get("path"): entry
        for entry in successor_entries if isinstance(entry, dict)
    }
    if (
        len(successor_map) != len(successor_entries)
        or set(successor_map) != SUCCESSOR_LOCKED_FILES
        or [entry.get("path") for entry in successor_entries]
        != sorted(SUCCESSOR_LOCKED_FILES)
    ):
        issues.append("DELETION_RESULT_1_1_LOCK_FILE_SET_INVALID")
    for relative in SUCCESSOR_LOCKED_FILES:
        path = root / relative
        entry = successor_map.get(relative)
        if not path.is_file() or not isinstance(entry, dict):
            issues.append(f"DELETION_RESULT_1_1_LOCK_FILE_MISSING: {relative}")
            continue
        try:
            document = load_json(path)
        except (OSError, TypeError, ValueError):
            issues.append(f"DELETION_RESULT_1_1_LOCK_FILE_INVALID: {relative}")
            continue
        if entry.get("rawSha256") != _raw_digest(path):
            issues.append(f"DELETION_RESULT_1_1_LOCK_RAW_MISMATCH: {relative}")
        if entry.get("canonicalDigest") != _canonical_digest(document):
            issues.append(
                f"DELETION_RESULT_1_1_LOCK_CANONICAL_MISMATCH: {relative}"
            )
    return sorted(set(issues))


def _json_pointer_parent(document: Any, pointer: str) -> tuple[Any, str]:
    if not isinstance(pointer, str) or not pointer.startswith("/"):
        raise ValueError("mutation path must be an absolute JSON pointer")
    tokens = [
        token.replace("~1", "/").replace("~0", "~")
        for token in pointer[1:].split("/")
    ]
    if not tokens:
        raise ValueError("root replacement is forbidden")
    current = document
    for token in tokens[:-1]:
        if isinstance(current, list):
            current = current[int(token)]
        elif isinstance(current, dict):
            current = current[token]
        else:
            raise ValueError("mutation path crosses a scalar")
    return current, tokens[-1]


def _apply_mutation(document: Any, mutation: Any) -> None:
    if not isinstance(mutation, dict):
        raise ValueError("mutation must be an object")
    operation = mutation.get("op")
    path = mutation.get("path")
    parent, token = _json_pointer_parent(document, path)
    if operation == "append-copy":
        if not isinstance(parent, list):
            raise ValueError("append-copy path must select a list item")
        parent.append(copy.deepcopy(parent[int(token)]))
        return
    target = parent[int(token)] if isinstance(parent, list) else parent.get(token)
    if operation == "append":
        if not isinstance(target, list):
            raise ValueError("append path must select a list")
        target.append(copy.deepcopy(mutation.get("value")))
    elif operation in {"replace", "add"}:
        value = copy.deepcopy(mutation.get("value"))
        if isinstance(parent, list):
            parent[int(token)] = value
        elif isinstance(parent, dict):
            if operation == "replace" and token not in parent:
                raise ValueError("replace target is absent")
            parent[token] = value
        else:
            raise ValueError("mutation target is not a container")
    elif operation == "remove":
        if isinstance(parent, list):
            del parent[int(token)]
        elif isinstance(parent, dict):
            del parent[token]
        else:
            raise ValueError("mutation target is not a container")
    else:
        raise ValueError(f"unsupported mutation operation: {operation}")


def execute_negative_case(
    project_root: Path,
    case: Any,
) -> list[str]:
    root = project_root.resolve()
    if not isinstance(case, dict):
        return ["DELETION_RESULT_1_1_NEGATIVE_FIXTURE_INVALID"]
    lineage_fixture_names = case.get("lineageFixtures")
    if lineage_fixture_names is not None:
        if (
            not isinstance(lineage_fixture_names, list)
            or not lineage_fixture_names
            or not all(name in VALID_FIXTURES for name in lineage_fixture_names)
        ):
            return ["DELETION_RESULT_1_1_NEGATIVE_FIXTURE_INVALID"]
        try:
            lineage_events = {
                name: load_json(root / VALID_FIXTURES[name])
                for name in lineage_fixture_names
            }
            for mutation in case.get("mutations", []):
                if not isinstance(mutation, dict):
                    raise ValueError("lineage mutation must be an object")
                target = mutation.get("targetFixture")
                if target not in lineage_events:
                    raise ValueError("lineage mutation target is absent")
                effective = {
                    key: value for key, value in mutation.items()
                    if key != "targetFixture"
                }
                _apply_mutation(lineage_events[target], effective)
        except (OSError, IndexError, KeyError, TypeError, ValueError):
            return ["DELETION_RESULT_1_1_NEGATIVE_FIXTURE_INVALID"]
        return production_lineage_issues([
            lineage_events[name] for name in lineage_fixture_names
        ])
    fixture_name = case.get("baseFixture")
    fixture_path = VALID_FIXTURES.get(fixture_name)
    if fixture_path is None:
        return ["DELETION_RESULT_1_1_NEGATIVE_FIXTURE_INVALID"]
    try:
        raw = (root / fixture_path).read_bytes()
        event = parse_json_bytes(raw)
    except (OSError, TypeError, ValueError):
        return ["DELETION_RESULT_1_1_NEGATIVE_FIXTURE_INVALID"]

    wire_mutation = case.get("wireMutation")
    if wire_mutation is not None:
        if wire_mutation == "bom-prefix":
            raw = b"\xef\xbb\xbf" + raw
        elif wire_mutation == "duplicate-top-level-id":
            raw = b'{"id":"duplicate",' + raw[1:]
        elif wire_mutation == "unsafe-aggregate-version":
            raw = raw.replace(
                b'"aggregateVersion": 2',
                b'"aggregateVersion": 9007199254740992',
                1,
            )
        elif wire_mutation == "oversize-padding":
            raw += b" " * (MAX_EVENT_BYTES - len(raw) + 1)
        else:
            return ["DELETION_RESULT_1_1_NEGATIVE_FIXTURE_INVALID"]
        return production_wire_issues(root, raw)

    try:
        for mutation in case.get("mutations", []):
            _apply_mutation(event, mutation)
    except (IndexError, KeyError, TypeError, ValueError):
        return ["DELETION_RESULT_1_1_NEGATIVE_FIXTURE_INVALID"]
    return production_event_issues(root, event)


def _negative_fixture_issues(project_root: Path) -> list[str]:
    root = project_root.resolve()
    try:
        catalog = load_json(root / NEGATIVE_FIXTURES)
    except (OSError, TypeError, ValueError):
        return ["DELETION_RESULT_1_1_NEGATIVE_FIXTURE_INVALID"]
    if not isinstance(catalog, dict) or (
        catalog.get("fixtureVersion")
        != "QUALITY-SNAPSHOT-DELETION-RESULT-PRODUCTION-NEGATIVE-FIXTURES-1.1.0"
        or catalog.get("baseContractVersion") != RESULT_CONTRACT_VERSION
        or not isinstance(catalog.get("cases"), list)
    ):
        return ["DELETION_RESULT_1_1_NEGATIVE_FIXTURE_INVALID"]
    cases = catalog["cases"]
    case_ids = [
        case.get("caseId") for case in cases if isinstance(case, dict)
    ]
    issues: list[str] = []
    if (
        len(case_ids) != len(cases)
        or len(case_ids) != len(set(case_ids))
        or not MANDATORY_NEGATIVE_CASES.issubset(case_ids)
    ):
        issues.append("DELETION_RESULT_1_1_NEGATIVE_FIXTURE_INVALID")
    for case in cases:
        if not isinstance(case, dict) or not isinstance(
            case.get("expectedCode"), str
        ):
            issues.append("DELETION_RESULT_1_1_NEGATIVE_FIXTURE_INVALID")
            continue
        actual = execute_negative_case(root, case)
        if not any(
            item.startswith(case["expectedCode"]) for item in actual
        ):
            issues.append(
                "DELETION_RESULT_1_1_NEGATIVE_CASE_FALSE_GREEN: "
                f"{case.get('caseId')} expected={case.get('expectedCode')} "
                f"actual={actual}"
            )
    return sorted(set(issues))


def validate(project_root: Path) -> list[str]:
    root = project_root.resolve()
    issues: list[str] = []
    issues.extend(predecessor_issues(root))
    try:
        schema = load_json(root / SCHEMA)
    except (OSError, TypeError, ValueError):
        schema = None
    if schema is None or schema_definition_issues(schema):
        issues.append("DELETION_RESULT_1_1_SCHEMA_REJECTED")
    elif (
        schema.get("$id") != "quality-snapshot-deletion-result-1.1.0.schema.json"
        or "1.1.0" not in str(schema.get("$id"))
    ):
        issues.append("DELETION_RESULT_1_1_SCHEMA_ID_INVALID")

    events: dict[str, dict[str, Any]] = {}
    for name, relative in VALID_FIXTURES.items():
        try:
            raw = (root / relative).read_bytes()
            event = parse_json_bytes(raw)
        except (OSError, TypeError, ValueError):
            issues.append(f"DELETION_RESULT_1_1_FIXTURE_INVALID: {name}")
            continue
        events[name] = event
        for issue in production_wire_issues(root, raw):
            issues.append(f"{issue}: {name}")
    ordinary = {
        name: event.get("data", {}).get("result")
        for name, event in events.items()
        if name in {"completed", "blocked", "partial", "failed"}
    }
    if ordinary != {
        "completed": "completed",
        "blocked": "blocked",
        "partial": "partial",
        "failed": "failed",
    }:
        issues.append("DELETION_RESULT_1_1_OUTCOME_FIXTURE_SET_INVALID")
    missing = events.get("missing-authority-blocked", {}).get("data", {})
    missing_registry = (
        missing.get("guards", {}).get("consumerRegistry")
        if isinstance(missing, dict) else None
    )
    if (
        missing.get("result") != "blocked"
        or missing.get("blockerCodes") != ["CONSUMER_REGISTRY_UNAVAILABLE"]
        or not isinstance(missing_registry, dict)
        or missing_registry.get("status") != "unavailable"
        or missing_registry.get("members") != []
        or missing_registry.get("authorityEvidence") is not None
    ):
        issues.append("DELETION_RESULT_1_1_MISSING_AUTHORITY_FIXTURE_INVALID")
    if {"blocked", "completed"}.issubset(events):
        issues.extend(production_lineage_issues([
            events["blocked"], events["completed"]
        ]))
    issues.extend(_negative_fixture_issues(root))
    issues.extend(successor_lock_issues(root))
    return sorted(set(issues))


def main(argv: list[str]) -> int:
    root = Path(argv[1]).resolve() if len(argv) > 1 else Path.cwd()
    issues = validate(root)
    if issues:
        for issue in issues:
            print(issue, file=sys.stderr)
        return 1
    print("QUALITY_SNAPSHOT_DELETION_RESULT_1_1_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
