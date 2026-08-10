from __future__ import annotations

import copy
import hashlib
import json
import re
import shutil
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import check_ingestion_batch_contracts as checker  # noqa: E402
from release_json import (  # noqa: E402
    canonical_bytes,
    canonical_sha256,
    load_json,
    schema_definition_issues,
    schema_issues,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
BATCH_QUALITY = PROJECT_ROOT / "contracts/ingestion-quality/batch-quality"
EVENT_ROOT = PROJECT_ROOT / "contracts/events/ingestion-quality"

LIFECYCLE_SCHEMA = BATCH_QUALITY / "batch-quality-lifecycle.schema.json"
LIFECYCLE = BATCH_QUALITY / "batch-quality-lifecycle-1.0.0.json"
RETENTION_SCHEMA = BATCH_QUALITY / "quality-snapshot-retention.schema.json"
RETENTION = BATCH_QUALITY / "quality-snapshot-retention-1.0.0.json"
RETENTION_VECTOR_SCHEMA = (
    BATCH_QUALITY / "quality-snapshot-retention-vectors.schema.json"
)
RETENTION_VECTORS = (
    BATCH_QUALITY
    / "fixtures/valid/quality-snapshot-retention-vectors-1.0.0.json"
)
TASK_0_4_NEGATIVE_FIXTURES = (
    BATCH_QUALITY / "fixtures/invalid/task-0-4-negative-fixtures-1.0.0.json"
)

DELETION_RESULT_SCHEMA = EVENT_ROOT / "quality-snapshot-deletion-result.schema.json"
DELETION_RESULT_FIXTURES = {
    outcome: EVENT_ROOT
    / f"fixtures/valid/quality-snapshot-deletion-{outcome}-v1.json"
    for outcome in ("completed", "blocked", "partial", "failed")
}
DELETION_ORDERING = (
    EVENT_ROOT
    / "fixtures/ordering/quality-snapshot-deletion-ordering-1.0.0.json"
)

UUID_V7 = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
MAX_SAFE_INTEGER = 9_007_199_254_740_991
MAX_EVENT_BYTES = 64 * 1024
OWNER_LOCAL_RESULT_KEYS = {
    "onlineSnapshot",
    "onlineMetrics",
    "readModels",
    "indexes",
    "caches",
    "objects",
}

TASK_0_4_LOCKED_FILES = {
    "contracts/ingestion-quality/batch-quality/batch-quality-lifecycle.schema.json",
    "contracts/ingestion-quality/batch-quality/batch-quality-lifecycle-1.0.0.json",
    "contracts/ingestion-quality/batch-quality/quality-snapshot-retention.schema.json",
    "contracts/ingestion-quality/batch-quality/quality-snapshot-retention-1.0.0.json",
    "contracts/ingestion-quality/batch-quality/quality-snapshot-retention-vectors.schema.json",
    "contracts/ingestion-quality/batch-quality/fixtures/valid/quality-snapshot-retention-vectors-1.0.0.json",
    "contracts/ingestion-quality/batch-quality/fixtures/invalid/task-0-4-negative-fixtures-1.0.0.json",
    "contracts/events/envelope.schema.json",
    "contracts/events/ingestion-quality/quality-snapshot-deletion-result.schema.json",
    "contracts/events/ingestion-quality/fixtures/valid/quality-snapshot-deletion-completed-v1.json",
    "contracts/events/ingestion-quality/fixtures/valid/quality-snapshot-deletion-blocked-v1.json",
    "contracts/events/ingestion-quality/fixtures/valid/quality-snapshot-deletion-partial-v1.json",
    "contracts/events/ingestion-quality/fixtures/valid/quality-snapshot-deletion-failed-v1.json",
    "contracts/events/ingestion-quality/fixtures/ordering/quality-snapshot-deletion-ordering-1.0.0.json",
}


class IngestionBatchLifecycleRetentionContractTest(unittest.TestCase):
    def test_task_zero_four_controlled_documents_exist_and_validate(self) -> None:
        pairs = [
            (LIFECYCLE, LIFECYCLE_SCHEMA),
            (RETENTION, RETENTION_SCHEMA),
            (RETENTION_VECTORS, RETENTION_VECTOR_SCHEMA),
            *(
                (path, DELETION_RESULT_SCHEMA)
                for path in DELETION_RESULT_FIXTURES.values()
            ),
        ]
        for document_path, schema_path in pairs:
            with self.subTest(document=document_path.relative_to(PROJECT_ROOT)):
                document = self.load(document_path)
                schema = self.load(schema_path)
                self.assertEqual([], schema_definition_issues(schema))
                self.assertEqual([], schema_issues(document, schema))

    def test_batch_lifecycle_freezes_only_the_two_approved_paths(self) -> None:
        lifecycle = self.load(LIFECYCLE)

        self.assertEqual("BATCH-QUALITY-LIFECYCLE-1.0.0", lifecycle["contractVersion"])
        self.assertEqual("ingestion-quality", lifecycle["owner"])
        self.assertEqual("receiving", lifecycle["initialState"])
        self.assertEqual(
            {"published", "quality-failed"}, set(lifecycle["terminalStates"])
        )
        self.assertEqual(
            {
                ("receiving", "sealed"),
                ("sealed", "quality-passed"),
                ("sealed", "quality-failed"),
                ("quality-passed", "published"),
            },
            {
                (transition["from"], transition["to"])
                for transition in lifecycle["allowedTransitions"]
            },
        )
        self.assertFalse(lifecycle["reopenAllowed"])
        self.assertFalse(lifecycle["manualResultOverrideAllowed"])
        self.assertEqual(
            {
                "recordCount",
                "validRecordCount",
                "rejectedRecordCount",
                "observationWindow.startAt",
                "observationWindow.endAt",
                "cutoffAt",
                "timezone",
                "watermark",
                "sourceSchemaVersion",
                "sourceSchemaDigest",
                "dataCatalogVersion",
                "dataCatalogDigest",
                "qualityGateVersion",
                "qualityGateDigest",
                "qualityMetricDecisionProfileVersion",
                "qualityMetricDecisionProfileDigest",
                "manifestDigest",
                "sourceOccurredAt",
                "scheduledDueAt",
                "receivedAt",
                "laneId",
            },
            set(lifecycle["sealedManifestFields"]),
        )
        self.assertEqual("all-fields-frozen", lifecycle["sealImmutability"])
        self.assertEqual(
            {"receiving", "sealed", "quality-failed"},
            set(lifecycle["factVisibility"]["invisibleStates"]),
        )
        self.assertEqual("published", lifecycle["factVisibility"]["visibleState"])
        self.assertEqual(
            "whole-batch-atomic",
            lifecycle["factVisibility"]["publicationUnit"],
        )

        evaluation = lifecycle["evaluation"]
        self.assertEqual("quality-passed", evaluation["validPassingResultState"])
        self.assertEqual("quality-failed", evaluation["validFailingResultState"])
        self.assertEqual("sealed", evaluation["technicalErrorState"])
        self.assertFalse(evaluation["technicalErrorCreatesSnapshot"])
        self.assertFalse(evaluation["technicalErrorPublishesFacts"])
        self.assertTrue(evaluation["validEvaluationCreatesUniqueSnapshot"])

    def test_batch_lifecycle_mutations_fail_closed(self) -> None:
        lifecycle = self.load(LIFECYCLE)
        lifecycle_issues = self.function("lifecycle_issues")
        mutations: list[tuple[dict, str]] = []

        failed_publish = copy.deepcopy(lifecycle)
        failed_publish["allowedTransitions"].append(
            {"from": "quality-failed", "to": "published"}
        )
        mutations.append(
            (failed_publish, "BATCH_LIFECYCLE_TRANSITION_INVALID")
        )

        reopen = copy.deepcopy(lifecycle)
        reopen["reopenAllowed"] = True
        mutations.append((reopen, "BATCH_LIFECYCLE_REOPEN_INVALID"))

        wrong_initial = copy.deepcopy(lifecycle)
        wrong_initial["initialState"] = "sealed"
        mutations.append(
            (wrong_initial, "BATCH_LIFECYCLE_INITIAL_STATE_INVALID")
        )

        replay_creates_new = copy.deepcopy(lifecycle)
        replay_creates_new["identity"]["sameIdentitySameDigest"] = "create-new"
        mutations.append(
            (replay_creates_new, "BATCH_LIFECYCLE_IDENTITY_REPLAY_INVALID")
        )

        replay_overwrites = copy.deepcopy(lifecycle)
        replay_overwrites["identity"]["sameIdentityDifferentDigest"] = "replace"
        mutations.append(
            (replay_overwrites, "BATCH_LIFECYCLE_IDENTITY_REPLAY_INVALID")
        )

        correction_rewrites = copy.deepcopy(lifecycle)
        correction_rewrites["correction"]["mutatePredecessor"] = True
        mutations.append(
            (correction_rewrites, "BATCH_LIFECYCLE_CORRECTION_INVALID")
        )

        closed_window = copy.deepcopy(lifecycle)
        closed_window["timeSemantics"]["observationWindow"] = "[startAt,endAt]"
        mutations.append(
            (closed_window, "BATCH_LIFECYCLE_TIME_SEMANTICS_INVALID")
        )

        runtime_cutoff = copy.deepcopy(lifecycle)
        runtime_cutoff["timeSemantics"]["cutoffSource"] = "database-current-time"
        mutations.append(
            (runtime_cutoff, "BATCH_LIFECYCLE_TIME_SEMANTICS_INVALID")
        )

        error_becomes_failure = copy.deepcopy(lifecycle)
        error_becomes_failure["evaluation"]["technicalErrorState"] = "quality-failed"
        mutations.append(
            (error_becomes_failure, "BATCH_LIFECYCLE_TECHNICAL_ERROR_INVALID")
        )

        for candidate, expected in mutations:
            with self.subTest(expected=expected):
                self.assert_reason(
                    lifecycle_issues(PROJECT_ROOT, candidate), expected
                )

    def test_batch_identity_replay_conflict_and_version_regression_are_exact(self) -> None:
        lifecycle = self.load(LIFECYCLE)
        evaluate_identity = self.function("evaluate_batch_identity")
        digest_a = "sha256:" + "a" * 64
        digest_b = "sha256:" + "b" * 64
        base = {
            "sourceId": "SRC-P0-STUDENT-001",
            "businessKey": "student-full-2026-08-09",
            "sourceVersion": 2,
            "manifestDigest": digest_a,
        }
        cases = [
            {
                "caseId": "new-identity",
                "existing": None,
                "incoming": base,
                "expectedDecision": "CREATE",
            },
            {
                "caseId": "same-identity-same-digest",
                "existing": base,
                "incoming": copy.deepcopy(base),
                "expectedDecision": "REPLAY_EXISTING",
            },
            {
                "caseId": "same-identity-different-digest",
                "existing": base,
                "incoming": {**base, "manifestDigest": digest_b},
                "expectedDecision": "IDENTITY_CONFLICT",
            },
            {
                "caseId": "source-version-regression",
                "existing": base,
                "incoming": {**base, "sourceVersion": 1},
                "expectedDecision": "VERSION_REGRESSION",
            },
        ]
        for case in cases:
            with self.subTest(case=case["caseId"]):
                self.assertEqual(
                    case["expectedDecision"], evaluate_identity(lifecycle, case)
                )

    def test_correction_is_a_direct_successor_without_history_rewrite_or_fork(self) -> None:
        lifecycle = self.load(LIFECYCLE)
        lineage_issues = self.function("batch_correction_lineage_issues")
        parent = {
            "batchId": "019d2c7d-4000-7000-8000-000000000101",
            "sourceId": "SRC-P0-STUDENT-001",
            "businessKey": "student-full-2026-08-09",
            "sourceVersion": 1,
            "lineageId": "019d2c7d-4000-7000-8000-000000000100",
            "supersedesBatchId": None,
            "reasonCode": None,
            "effectiveAt": "2026-08-09T00:00:00Z",
        }
        child = {
            **parent,
            "batchId": "019d2c7d-4000-7000-8000-000000000102",
            "sourceVersion": 2,
            "supersedesBatchId": parent["batchId"],
            "reasonCode": "SOURCE_CORRECTION",
            "effectiveAt": "2026-08-09T01:00:00Z",
        }
        self.assertEqual([], lineage_issues(lifecycle, [parent, child]))

        cross_lineage = copy.deepcopy(child)
        cross_lineage["lineageId"] = "019d2c7d-4000-7000-8000-000000000199"
        self.assert_reason(
            lineage_issues(lifecycle, [parent, cross_lineage]),
            "BATCH_LIFECYCLE_CORRECTION_CROSS_LINEAGE",
        )

        missing_reason = copy.deepcopy(child)
        missing_reason["reasonCode"] = None
        self.assert_reason(
            lineage_issues(lifecycle, [parent, missing_reason]),
            "BATCH_LIFECYCLE_CORRECTION_EVIDENCE_INVALID",
        )

        fork = copy.deepcopy(child)
        fork["batchId"] = "019d2c7d-4000-7000-8000-000000000103"
        fork["sourceVersion"] = 3
        self.assert_reason(
            lineage_issues(lifecycle, [parent, child, fork]),
            "BATCH_LIFECYCLE_CORRECTION_FORK",
        )

    def test_cutoff_evaluated_published_and_window_semantics_are_frozen(self) -> None:
        lifecycle = self.load(LIFECYCLE)
        case_issues = self.function("batch_lifecycle_case_issues")
        window_contains = self.function("batch_window_contains")
        valid = {
            "state": "published",
            "observationWindow": {
                "startAt": "2026-08-08T00:00:00Z",
                "endAt": "2026-08-09T00:00:00Z",
            },
            "cutoffAt": "2026-08-09T00:00:00Z",
            "sealedAt": "2026-08-09T00:01:00Z",
            "evaluationOutcome": "passed",
            "evaluatedAt": "2026-08-09T00:02:00Z",
            "snapshotCreated": True,
            "publishedAt": "2026-08-09T00:03:00Z",
        }
        self.assertEqual([], case_issues(lifecycle, valid))
        self.assertTrue(
            window_contains(
                lifecycle,
                "2026-08-08T00:00:00Z",
                valid["observationWindow"],
            )
        )
        self.assertFalse(
            window_contains(
                lifecycle,
                "2026-08-09T00:00:00Z",
                valid["observationWindow"],
            )
        )

        technical_error = {
            **valid,
            "state": "sealed",
            "evaluationOutcome": "evaluation-error",
            "evaluatedAt": None,
            "snapshotCreated": False,
            "publishedAt": None,
        }
        self.assertEqual([], case_issues(lifecycle, technical_error))

        invalid_cases = []
        missing_cutoff = copy.deepcopy(valid)
        missing_cutoff["cutoffAt"] = None
        invalid_cases.append(missing_cutoff)
        evaluated_before_seal = copy.deepcopy(valid)
        evaluated_before_seal["evaluatedAt"] = "2026-08-09T00:00:30Z"
        invalid_cases.append(evaluated_before_seal)
        published_before_evaluation = copy.deepcopy(valid)
        published_before_evaluation["publishedAt"] = "2026-08-09T00:01:30Z"
        invalid_cases.append(published_before_evaluation)
        failed_published = copy.deepcopy(valid)
        failed_published["state"] = "published"
        failed_published["evaluationOutcome"] = "quality-failed"
        invalid_cases.append(failed_published)
        error_snapshot = copy.deepcopy(technical_error)
        error_snapshot["snapshotCreated"] = True
        invalid_cases.append(error_snapshot)
        for candidate in invalid_cases:
            with self.subTest(candidate=candidate):
                self.assertTrue(case_issues(lifecycle, candidate))

    def test_quality_snapshot_retention_policy_is_the_exact_approved_mapping(self) -> None:
        policy = self.load(RETENTION)

        self.assertEqual("QUALITY-SNAPSHOT-RETENTION-1.0.0", policy["policyVersion"])
        self.assertEqual("DEC-019", policy["decisionId"])
        self.assertEqual("AUTH-2026-08-08-001", policy["authorityRef"])
        self.assertEqual("2026-08-09T10:02:22+08:00", policy["effectiveAt"])
        self.assertEqual("ingestion-quality", policy["owner"])
        self.assertEqual("ingestion-quality-retention-executor", policy["executor"])
        self.assertEqual("QualitySnapshot", policy["objectType"])
        self.assertEqual("RS-1.0.0", policy["retentionScheduleVersion"])
        self.assertEqual(
            "reporting-and-operational-snapshot", policy["retentionClass"]
        )
        self.assertEqual("evaluatedAt", policy["retentionStartField"])
        self.assertEqual("P2Y", policy["retentionPeriod"])
        self.assertEqual(
            "utc-calendar-plus-years-end-of-month-clamp",
            policy["calendarArithmetic"],
        )
        self.assertEqual(
            {"quality-passed", "quality-failed"},
            set(policy["appliesToOverallResults"]),
        )
        self.assertFalse(policy["publishedAtAffectsRetention"])
        self.assertEqual("delete", policy["expirationAction"])
        self.assertFalse(policy["anonymizationFallbackAllowed"])
        self.assertEqual(
            {
                "quality-snapshot-row",
                "quality-snapshot-metric-rows",
                "owner-read-models",
                "owner-indexes",
                "owner-caches",
                "owner-objects",
            },
            set(policy["ownerLocalTargets"]),
        )

        eligibility = policy["eligibility"]
        self.assertTrue(eligibility["trustedTimeRequired"])
        self.assertEqual("trustedNow>=retentionDueAt", eligibility["dueComparison"])
        self.assertEqual("[startAt,endAt)", eligibility["legalHoldWindow"])
        self.assertEqual(
            "required-for-snapshot-plus-unreleased-reference-holders",
            eligibility["consumerDenominator"],
        )
        self.assertEqual(
            "confirmedAggregateVersion>=requiredAggregateVersion",
            eligibility["watermarkRule"],
        )
        self.assertTrue(eligibility["watermarkEqualitySatisfies"])
        self.assertTrue(eligibility["evidenceCopyAckRequired"])
        self.assertFalse(eligibility["transportAckCountsAsEvidenceCopyAck"])
        self.assertEqual("blocked", eligibility["dependencyUnavailableResult"])

        downstream = policy["downstreamEvidence"]
        self.assertEqual("clue-care", downstream["ownerModule"])
        self.assertEqual({"3.4", "3.5"}, set(downstream["ownerStories"]))
        self.assertEqual("EvidenceSnapshot", downstream["objectType"])
        self.assertEqual(
            {"candidate-create", "clue-create"}, set(downstream["copyTriggers"])
        )
        self.assertEqual("self-contained-minimum", downstream["copyMode"])
        self.assertFalse(downstream["ownerSnapshotReferenceMayBeSoleEvidence"])
        self.assertEqual("RS-1.0.0", downstream["retentionScheduleVersion"])
        self.assertEqual("owner-domain-case-closure", downstream["retentionAnchor"])
        self.assertEqual("P3Y", downstream["retentionPeriod"])
        self.assertEqual("none", downstream["runtimeEvidenceClaim"])

    def test_retention_vectors_cover_every_approved_boundary_and_failure(self) -> None:
        policy = self.load(RETENTION)
        vectors = self.load(RETENTION_VECTORS)
        evaluate_retention = self.function("evaluate_retention_vector")
        cases = {case["caseId"]: case for case in vectors["cases"]}
        mandatory = {
            "quality-passed-exact-due",
            "quality-failed-exact-due",
            "published-at-ignored",
            "one-millisecond-before-due",
            "leap-day-end-of-month-clamp",
            "legal-hold-start-inclusive",
            "legal-hold-end-exclusive",
            "watermark-equal",
            "watermark-one-behind",
            "evidence-copy-ack-missing",
            "trusted-time-unavailable",
            "legal-hold-dependency-unavailable",
            "watermark-dependency-unavailable",
            "inactive-reference-holder-blocked",
            "inactive-no-reference-attested-excluded",
            "empty-required-consumer-set",
        }
        self.assertTrue(mandatory.issubset(cases))
        for case in cases.values():
            with self.subTest(case=case["caseId"]):
                self.assertEqual(
                    case["expected"], evaluate_retention(policy, case)
                )

        self.assertEqual(
            "2026-02-28T12:00:00Z",
            cases["leap-day-end-of-month-clamp"]["expected"]["retentionDueAt"],
        )
        self.assertEqual(
            "eligible",
            cases["quality-passed-exact-due"]["expected"]["decision"],
        )
        self.assertEqual(
            "eligible",
            cases["quality-failed-exact-due"]["expected"]["decision"],
        )
        self.assertEqual(
            "blocked",
            cases["one-millisecond-before-due"]["expected"]["decision"],
        )

    def test_retention_policy_mutations_fail_closed(self) -> None:
        policy = self.load(RETENTION)
        retention_issues = self.function("retention_policy_issues")
        mutations: list[tuple[dict, str]] = []

        published_anchor = copy.deepcopy(policy)
        published_anchor["retentionStartField"] = "publishedAt"
        mutations.append(
            (published_anchor, "QUALITY_SNAPSHOT_RETENTION_START_INVALID")
        )
        fixed_days = copy.deepcopy(policy)
        fixed_days["retentionPeriod"] = "P730D"
        mutations.append(
            (fixed_days, "QUALITY_SNAPSHOT_RETENTION_PERIOD_INVALID")
        )
        anonymize = copy.deepcopy(policy)
        anonymize["expirationAction"] = "anonymize"
        mutations.append(
            (anonymize, "QUALITY_SNAPSHOT_RETENTION_ACTION_INVALID")
        )
        missing_cache = copy.deepcopy(policy)
        missing_cache["ownerLocalTargets"].remove("owner-caches")
        mutations.append(
            (missing_cache, "QUALITY_SNAPSHOT_RETENTION_TARGET_SET_INVALID")
        )
        ack_is_transport = copy.deepcopy(policy)
        ack_is_transport["eligibility"]["transportAckCountsAsEvidenceCopyAck"] = True
        mutations.append(
            (ack_is_transport, "QUALITY_SNAPSHOT_RETENTION_ELIGIBILITY_INVALID")
        )
        dependency_defaults_open = copy.deepcopy(policy)
        dependency_defaults_open["eligibility"]["dependencyUnavailableResult"] = (
            "eligible"
        )
        mutations.append(
            (
                dependency_defaults_open,
                "QUALITY_SNAPSHOT_RETENTION_ELIGIBILITY_INVALID",
            )
        )
        downstream_reference = copy.deepcopy(policy)
        downstream_reference["downstreamEvidence"][
            "ownerSnapshotReferenceMayBeSoleEvidence"
        ] = True
        mutations.append(
            (downstream_reference, "QUALITY_SNAPSHOT_RETENTION_HANDOFF_INVALID")
        )

        for candidate, expected in mutations:
            with self.subTest(expected=expected):
                self.assert_reason(
                    retention_issues(PROJECT_ROOT, candidate), expected
                )

    def test_all_four_owner_local_deletion_result_outcomes_are_semantically_valid(self) -> None:
        deletion_issues = self.function("deletion_result_issues")
        events = {
            outcome: self.load(path)
            for outcome, path in DELETION_RESULT_FIXTURES.items()
        }
        for outcome, event in events.items():
            with self.subTest(outcome=outcome):
                self.assertEqual(outcome, event["data"]["result"])
                self.assertEqual([], deletion_issues(PROJECT_ROOT, event))
                self.assertEqual(
                    OWNER_LOCAL_RESULT_KEYS,
                    set(event["data"]["ownerLocalResults"]),
                )

        completed_results = events["completed"]["data"]["ownerLocalResults"]
        self.assertTrue(
            all(
                result["status"]
                in {"deleted", "already-absent", "not-applicable"}
                and result["remainingCount"] == 0
                and result["errorCode"] is None
                for result in completed_results.values()
            )
        )
        self.assertFalse(events["completed"]["data"]["blockerCodes"])
        self.assertFalse(events["completed"]["data"]["failureCodes"])

        blocked = events["blocked"]["data"]
        self.assertIsNone(blocked["deletionCommittedAt"])
        self.assertTrue(blocked["blockerCodes"])
        self.assertTrue(
            all(
                item["status"] == "not-attempted"
                and item["deletedCount"] == 0
                for item in blocked["ownerLocalResults"].values()
            )
        )

        partial_results = events["partial"]["data"]["ownerLocalResults"].values()
        self.assertTrue(any(item["status"] == "deleted" for item in partial_results))
        partial_results = events["partial"]["data"]["ownerLocalResults"].values()
        self.assertTrue(
            any(
                item["status"] == "failed" or item["remainingCount"] > 0
                for item in partial_results
            )
        )
        self.assertTrue(events["partial"]["data"]["failureCodes"])

        failed = events["failed"]["data"]
        self.assertIsNone(failed["deletionCommittedAt"])
        self.assertTrue(failed["failureCodes"])
        self.assertTrue(
            all(
                item["deletedCount"] == 0
                and item["status"] in {"not-attempted", "failed"}
                for item in failed["ownerLocalResults"].values()
            )
        )

    def test_deletion_result_envelope_identity_subject_time_trace_and_scope_digest(self) -> None:
        event = self.load(DELETION_RESULT_FIXTURES["completed"])
        data = event["data"]
        scope = data["scope"]

        self.assertEqual("1.0", event["specversion"])
        self.assertRegex(event["id"], UUID_V7)
        self.assertEqual("urn:scholarsense:ingestion-quality", event["source"])
        self.assertEqual(
            "scholarsense.ingestion-quality.quality-snapshot.deletion-result.v1",
            event["type"],
        )
        self.assertEqual("application/json", event["datacontenttype"])
        self.assertEqual(event["id"], data["eventId"])
        self.assertEqual(
            f"quality-snapshot/{scope['snapshotId']}", event["subject"]
        )
        self.assertEqual(event["time"], data["occurredAt"])
        self.assertEqual(event["traceparent"].split("-")[1], data["traceId"])
        self.assertEqual(data["aggregateId"], data["executionId"])
        self.assertGreaterEqual(data["aggregateVersion"], 1)
        self.assertLessEqual(data["aggregateVersion"], MAX_SAFE_INTEGER)

        material = {
            key: scope[key]
            for key in (
                "objectType",
                "snapshotId",
                "sourceId",
                "snapshotAggregateVersion",
                "evaluatedAt",
                "retentionDueAt",
                "snapshotImmutableHash",
            )
        }
        material["retentionPolicyVersion"] = data["retentionPolicyVersion"]
        material["retentionScheduleVersion"] = data["retentionScheduleVersion"]
        self.assertEqual(
            "sha256:" + canonical_sha256(material), scope["scopeDigest"]
        )

    def test_deletion_result_identity_and_cross_field_mutations_are_rejected(self) -> None:
        event = self.load(DELETION_RESULT_FIXTURES["completed"])
        deletion_issues = self.function("deletion_result_issues")
        mutations: list[tuple[dict, str]] = []

        non_v7 = copy.deepcopy(event)
        non_v7["id"] = "019d2c7d-4000-6000-8000-000000000201"
        non_v7["data"]["eventId"] = non_v7["id"]
        mutations.append((non_v7, "DELETION_RESULT_SCHEMA_REJECTED"))

        id_mismatch = copy.deepcopy(event)
        id_mismatch["data"]["eventId"] = (
            "019d2c7d-4000-7000-8000-000000000299"
        )
        mutations.append((id_mismatch, "DELETION_RESULT_ID_MISMATCH"))

        subject_mismatch = copy.deepcopy(event)
        subject_mismatch["subject"] = (
            "quality-snapshot/019d2c7d-4000-7000-8000-000000000299"
        )
        mutations.append((subject_mismatch, "DELETION_RESULT_SUBJECT_MISMATCH"))

        time_mismatch = copy.deepcopy(event)
        time_mismatch["data"]["occurredAt"] = "2026-08-09T01:00:00Z"
        mutations.append((time_mismatch, "DELETION_RESULT_TIME_MISMATCH"))

        trace_mismatch = copy.deepcopy(event)
        trace_mismatch["data"]["traceId"] = "f" * 32
        mutations.append((trace_mismatch, "DELETION_RESULT_TRACE_MISMATCH"))

        digest_mismatch = copy.deepcopy(event)
        digest_mismatch["data"]["scope"]["scopeDigest"] = "sha256:" + "0" * 64
        mutations.append((digest_mismatch, "DELETION_RESULT_SCOPE_DIGEST_MISMATCH"))

        retention_due_drift = copy.deepcopy(event)
        retention_due_drift["data"]["scope"]["retentionDueAt"] = (
            "2026-08-10T00:00:00Z"
        )
        retention_due_material = {
            key: retention_due_drift["data"]["scope"][key]
            for key in (
                "objectType",
                "snapshotId",
                "sourceId",
                "snapshotAggregateVersion",
                "evaluatedAt",
                "retentionDueAt",
                "snapshotImmutableHash",
            )
        }
        retention_due_material["retentionPolicyVersion"] = (
            retention_due_drift["data"]["retentionPolicyVersion"]
        )
        retention_due_material["retentionScheduleVersion"] = (
            retention_due_drift["data"]["retentionScheduleVersion"]
        )
        retention_due_drift["data"]["scope"]["scopeDigest"] = (
            "sha256:" + canonical_sha256(retention_due_material)
        )
        mutations.append(
            (retention_due_drift, "DELETION_RESULT_RETENTION_SCOPE_INVALID")
        )

        completed_before_due = copy.deepcopy(event)
        completed_before_due["data"]["guards"]["trustedTime"]["observedAt"] = (
            "2026-08-08T23:59:59.999Z"
        )
        mutations.append(
            (completed_before_due, "DELETION_RESULT_COMPLETED_INVARIANT")
        )

        wrong_copy_scope = copy.deepcopy(event)
        wrong_copy_scope["data"]["guards"]["consumerWatermarks"][0][
            "attestation"
        ]["snapshotImmutableHash"] = "sha256:" + "4" * 64
        mutations.append(
            (wrong_copy_scope, "DELETION_RESULT_COMPLETED_INVARIANT")
        )

        duplicate_consumer = copy.deepcopy(event)
        duplicate_consumer["data"]["guards"]["consumerWatermarks"].append(
            copy.deepcopy(
                duplicate_consumer["data"]["guards"]["consumerWatermarks"][0]
            )
        )
        mutations.append(
            (duplicate_consumer, "DELETION_RESULT_CONSUMER_SET_INVALID")
        )

        completed_with_hold = copy.deepcopy(event)
        completed_with_hold["data"]["guards"]["legalHold"]["status"] = "matched"
        completed_with_hold["data"]["guards"]["legalHold"]["matchedCount"] = 1
        mutations.append(
            (completed_with_hold, "DELETION_RESULT_COMPLETED_INVARIANT")
        )

        completed_with_gap = copy.deepcopy(event)
        consumer = completed_with_gap["data"]["guards"]["consumerWatermarks"][0]
        consumer["confirmedAggregateVersion"] = consumer["requiredAggregateVersion"] - 1
        mutations.append(
            (completed_with_gap, "DELETION_RESULT_COMPLETED_INVARIANT")
        )

        completed_without_copy = copy.deepcopy(event)
        completed_without_copy["data"]["guards"]["consumerWatermarks"][0][
            "evidenceCopyAck"
        ] = "missing"
        mutations.append(
            (completed_without_copy, "DELETION_RESULT_COMPLETED_INVARIANT")
        )

        completed_with_remaining = copy.deepcopy(event)
        completed_with_remaining["data"]["ownerLocalResults"]["objects"][
            "remainingCount"
        ] = 1
        mutations.append(
            (completed_with_remaining, "DELETION_RESULT_COMPLETED_INVARIANT")
        )

        blocked_after_delete = self.load(DELETION_RESULT_FIXTURES["blocked"])
        blocked_after_delete["data"]["ownerLocalResults"]["onlineSnapshot"].update(
            {"status": "deleted", "selectedCount": 1, "deletedCount": 1}
        )
        mutations.append((blocked_after_delete, "DELETION_RESULT_BLOCKED_INVARIANT"))

        partial_without_split = self.load(DELETION_RESULT_FIXTURES["partial"])
        for result in partial_without_split["data"]["ownerLocalResults"].values():
            result.update(
                {
                    "status": "deleted",
                    "deletedCount": result["selectedCount"],
                    "remainingCount": 0,
                    "errorCode": None,
                }
            )
        mutations.append((partial_without_split, "DELETION_RESULT_PARTIAL_INVARIANT"))

        failed_after_commit = self.load(DELETION_RESULT_FIXTURES["failed"])
        failed_after_commit["data"]["deletionCommittedAt"] = (
            "2026-08-09T00:00:00Z"
        )
        mutations.append((failed_after_commit, "DELETION_RESULT_FAILED_INVARIANT"))

        for candidate, expected in mutations:
            with self.subTest(expected=expected):
                self.assert_reason(
                    deletion_issues(PROJECT_ROOT, candidate), expected
                )

    def test_backup_due_is_within_p35d_and_owner_result_never_claims_final_receipt(self) -> None:
        for outcome in ("completed", "partial"):
            with self.subTest(outcome=outcome):
                data = self.load(DELETION_RESULT_FIXTURES[outcome])["data"]
                committed = self.instant(data["deletionCommittedAt"])
                due_value = data["backup"]["backupExpiryDueAt"]
                if outcome == "completed":
                    self.assertIsNotNone(due_value)
                if due_value is not None:
                    due = self.instant(due_value)
                    self.assertGreaterEqual(due, committed)
                    self.assertLessEqual(due, committed + timedelta(days=35))
                self.assertEqual("DRP-1.0.0", data["backup"]["policyVersion"])
                self.assertEqual(35, data["backup"]["maximumRetentionDays"])
                self.assertEqual("none", data["backup"]["physicalDeletionClaim"])

        for outcome in ("blocked", "failed"):
            data = self.load(DELETION_RESULT_FIXTURES[outcome])["data"]
            self.assertIsNone(data["backup"]["backupExpiryDueAt"])
            self.assertEqual("none", data["backup"]["physicalDeletionClaim"])

        completed = self.load(DELETION_RESULT_FIXTURES["completed"])
        handoff = completed["data"]["auditHandoff"]
        self.assertEqual("audit-operations", handoff["targetOwner"])
        self.assertEqual("owner-local-deletion-result", handoff["inputKind"])
        self.assertEqual("audit-operations", handoff["finalReceiptOwner"])
        self.assertFalse(handoff["ownerResultIsFinalReceipt"])
        self.assertFalse(handoff["conformanceReceiptSatisfiesProduction"])
        self.assertNotIn("deletionreceiptid", self.normalized_keys(completed))

        old_receipt_schema = (
            PROJECT_ROOT
            / "contracts/audit-retention/deletion-receipt-conformance.schema.json"
        )
        old_receipt = (
            PROJECT_ROOT
            / "contracts/audit-retention/fixtures/valid/deletion-receipt.json"
        )
        self.assertEqual(
            "ac8be47069b23a9885d361ee513ec9d1880b080c86278cdba6ab2791fcc356bc",
            hashlib.sha256(old_receipt_schema.read_bytes()).hexdigest(),
        )
        receipt = self.load(old_receipt)
        self.assertTrue(receipt["conformanceOnly"])
        self.assertFalse(receipt["runtimeIssuable"])

        deletion_issues = self.function("deletion_result_issues")
        forged = copy.deepcopy(completed)
        forged["data"]["deletionReceiptId"] = (
            "019d2c7d-4000-7000-8000-000000000999"
        )
        forged["data"]["auditHandoff"]["ownerResultIsFinalReceipt"] = True
        self.assert_reason(
            deletion_issues(PROJECT_ROOT, forged),
            "DELETION_RESULT_FINAL_RECEIPT_BOUNDARY",
        )

    def test_deletion_result_replay_duplicate_conflict_old_gap_and_lineage(self) -> None:
        ordering = self.load(DELETION_ORDERING)
        decide = self.function("deletion_delivery_decision")
        self.assertEqual(
            "consumerId|producer|aggregateType|aggregateId", ordering["route"]
        )
        cases = {case["caseId"]: case for case in ordering["cases"]}
        mandatory = {
            "next-contiguous",
            "duplicate-same-payload",
            "same-id-different-payload",
            "old-version",
            "version-gap",
        }
        self.assertTrue(mandatory.issubset(cases))
        for case in cases.values():
            with self.subTest(case=case["caseId"]):
                self.assertEqual(
                    case["expectedDecision"],
                    decide(
                        case["currentWatermark"],
                        case["incomingVersion"],
                        same_payload=case["samePayload"],
                    ),
                )

        lineage_issues = self.function("deletion_result_lineage_issues")
        blocked = self.load(DELETION_RESULT_FIXTURES["blocked"])
        completed = self.load(DELETION_RESULT_FIXTURES["completed"])
        self.assertEqual([], lineage_issues([blocked, completed]))

        cross_aggregate = copy.deepcopy(completed)
        cross_aggregate["data"]["aggregateId"] = (
            "019d2c7d-4000-7000-8000-000000000888"
        )
        cross_aggregate["data"]["executionId"] = cross_aggregate["data"][
            "aggregateId"
        ]
        self.assert_reason(
            lineage_issues([blocked, cross_aggregate]),
            "DELETION_RESULT_SUPERSEDES_CROSS_AGGREGATE",
        )

        gap = copy.deepcopy(completed)
        gap["data"]["aggregateVersion"] = blocked["data"]["aggregateVersion"] + 2
        self.assert_reason(
            lineage_issues([blocked, gap]), "DELETION_RESULT_VERSION_GAP"
        )

    def test_deletion_result_wire_size_is_bounded_and_oversize_is_rejected(self) -> None:
        deletion_issues = self.function("deletion_result_issues")
        for outcome, path in DELETION_RESULT_FIXTURES.items():
            with self.subTest(outcome=outcome):
                event = self.load(path)
                self.assertLessEqual(len(canonical_bytes(event)), MAX_EVENT_BYTES)

        oversized = self.load(DELETION_RESULT_FIXTURES["completed"])
        oversized["data"]["rawPayload"] = "x" * MAX_EVENT_BYTES
        self.assert_reason(
            deletion_issues(PROJECT_ROOT, oversized),
            "DELETION_RESULT_PAYLOAD_TOO_LARGE",
        )

    def test_retention_scope_and_registry_digests_are_recomputable(self) -> None:
        policy = self.load(RETENTION)
        vectors = self.load(RETENTION_VECTORS)
        case = next(
            item
            for item in vectors["cases"]
            if item["caseId"] == "quality-passed-exact-due"
        )
        scope = case["scope"]
        self.assertEqual("QualitySnapshot", scope.get("objectType"))
        self.assertEqual(case["evaluatedAt"], scope.get("evaluatedAt"))
        self.assertEqual(
            case["requiredAggregateVersion"],
            scope.get("snapshotAggregateVersion"),
        )
        self.assertEqual(
            case["expected"]["retentionDueAt"], scope.get("retentionDueAt")
        )
        scope_material = {
            key: scope[key]
            for key in (
                "objectType",
                "snapshotId",
                "sourceId",
                "snapshotAggregateVersion",
                "evaluatedAt",
                "retentionDueAt",
                "snapshotImmutableHash",
            )
        }
        scope_material["retentionPolicyVersion"] = policy["policyVersion"]
        scope_material["retentionScheduleVersion"] = policy[
            "retentionScheduleVersion"
        ]
        self.assertEqual(
            "sha256:" + canonical_sha256(scope_material),
            scope.get("scopeDigest"),
        )

        registry = case["consumerRegistry"]
        expected_members = sorted(
            (
                {
                    "consumerId": consumer["consumerId"],
                    "registryMembership": consumer["registryMembership"],
                    "lifecycleStatus": consumer["lifecycleStatus"],
                }
                for consumer in case["consumers"]
            ),
            key=lambda member: member["consumerId"],
        )
        self.assertEqual(expected_members, registry.get("members"))
        registry_material = {
            "registryVersion": registry["registryVersion"],
            "members": expected_members,
        }
        self.assertEqual(
            "sha256:" + canonical_sha256(registry_material),
            registry.get("registryDigest"),
        )

    def test_retention_scope_registry_and_attestation_mutations_have_fixed_codes(self) -> None:
        policy = self.load(RETENTION)
        vectors = self.load(RETENTION_VECTORS)
        evaluate = self.function("evaluate_retention_vector")
        base = next(
            copy.deepcopy(item)
            for item in vectors["cases"]
            if item["caseId"] == "quality-passed-exact-due"
        )

        def members_for(candidate: dict) -> list[dict]:
            return sorted(
                (
                    {
                        "consumerId": consumer["consumerId"],
                        "registryMembership": consumer["registryMembership"],
                        "lifecycleStatus": consumer["lifecycleStatus"],
                    }
                    for consumer in candidate["consumers"]
                ),
                key=lambda member: member["consumerId"],
            )

        def seal_registry(candidate: dict) -> None:
            registry = candidate["consumerRegistry"]
            registry["members"] = members_for(candidate)
            registry["registryDigest"] = "sha256:" + canonical_sha256(
                {
                    "registryVersion": registry["registryVersion"],
                    "members": registry["members"],
                }
            )
            for consumer in candidate["consumers"]:
                attestation = consumer.get("attestation")
                if isinstance(attestation, dict):
                    attestation.setdefault("consumerId", consumer["consumerId"])
                    attestation.setdefault(
                        "registryVersion", registry["registryVersion"]
                    )
                    attestation["registryDigest"] = registry["registryDigest"]

        seal_registry(base)
        mutations: list[tuple[dict, str]] = []

        bad_scope = copy.deepcopy(base)
        bad_scope["scope"]["scopeDigest"] = "sha256:" + "0" * 64
        mutations.append((bad_scope, "RETENTION_SCOPE_DIGEST_MISMATCH"))

        bad_registry_digest = copy.deepcopy(base)
        bad_registry_digest["consumerRegistry"]["registryDigest"] = (
            "sha256:" + "0" * 64
        )
        mutations.append(
            (bad_registry_digest, "CONSUMER_REGISTRY_DIGEST_MISMATCH")
        )

        omitted_consumer = copy.deepcopy(base)
        omitted_consumer["consumerRegistry"]["members"].append(
            {
                "consumerId": "unreported-reference-holder",
                "registryMembership": "unreleased-reference-holder",
                "lifecycleStatus": "inactive",
            }
        )
        mutations.append(
            (omitted_consumer, "CONSUMER_REGISTRY_MEMBER_SET_MISMATCH")
        )

        relabelled_consumer = copy.deepcopy(base)
        relabelled_consumer["consumers"][0]["registryMembership"] = (
            "planned-never-held-reference"
        )
        mutations.append(
            (relabelled_consumer, "CONSUMER_REGISTRY_MEMBER_SET_MISMATCH")
        )

        active_decommission = copy.deepcopy(base)
        active_consumer = active_decommission["consumers"][0]
        active_consumer["registryMembership"] = "unreleased-reference-holder"
        active_consumer["evidenceCopyAck"] = "not-required"
        active_consumer["attestation"]["kind"] = "decommission-no-reference"
        seal_registry(active_decommission)
        mutations.append(
            (active_decommission, "ACTIVE_CONSUMER_DECOMMISSION_INVALID")
        )

        wrong_consumer_binding = copy.deepcopy(base)
        wrong_consumer_binding["consumers"][0]["attestation"]["consumerId"] = (
            "another-consumer"
        )
        mutations.append(
            (wrong_consumer_binding, "ATTESTATION_CONSUMER_BINDING_MISMATCH")
        )

        wrong_registry_binding = copy.deepcopy(base)
        wrong_registry_binding["consumers"][0]["attestation"][
            "registryDigest"
        ] = "sha256:" + "f" * 64
        mutations.append(
            (wrong_registry_binding, "ATTESTATION_REGISTRY_BINDING_MISMATCH")
        )

        reused_attestation = copy.deepcopy(base)
        copied_consumer = copy.deepcopy(reused_attestation["consumers"][0])
        copied_consumer["consumerId"] = "second-consumer"
        reused_attestation["consumers"].append(copied_consumer)
        seal_registry(reused_attestation)
        copied_consumer = reused_attestation["consumers"][1]
        copied_consumer["attestation"] = copy.deepcopy(
            reused_attestation["consumers"][0]["attestation"]
        )
        mutations.append((reused_attestation, "ATTESTATION_REPLAY_DETECTED"))

        for candidate, expected in mutations:
            with self.subTest(expected=expected):
                result = evaluate(policy, candidate)
                self.assertEqual("evaluation-error", result.get("decision"))
                self.assert_reason(result.get("reasonCodes", []), expected)

    def test_retention_consumer_authority_anchor_rejects_omission_and_forgery(self) -> None:
        policy = self.load(RETENTION)
        vectors = self.load(RETENTION_VECTORS)
        evaluate = self.function("evaluate_retention_vector")
        base = next(
            copy.deepcopy(item)
            for item in vectors["cases"]
            if item["caseId"] == "quality-passed-exact-due"
        )

        missing_anchor = copy.deepcopy(base)
        missing_anchor["consumerRegistry"].pop("conformanceAnchorId", None)

        missing_authority_evidence = copy.deepcopy(base)
        missing_authority_evidence["consumerRegistry"].pop(
            "authorityEvidence", None
        )

        wrong_authority_scope = copy.deepcopy(base)
        wrong_authority_scope["consumerRegistry"].setdefault(
            "authorityEvidence", {}
        )["scopeDigest"] = "sha256:" + "f" * 64

        stale_anchor = copy.deepcopy(base)
        registry = stale_anchor["consumerRegistry"]
        registry.setdefault(
            "conformanceAnchorId", "active-clue-care-consumer-set"
        )
        stale_anchor["consumers"] = []
        registry["members"] = []
        registry["registryDigest"] = "sha256:" + canonical_sha256(
            {
                "registryVersion": registry["registryVersion"],
                "members": registry["members"],
            }
        )

        forged_anchor = copy.deepcopy(base)
        forged_anchor["consumerRegistry"]["conformanceAnchorId"] = (
            "forged-unregistered-consumer-set"
        )

        for case_id, candidate in (
            ("missing-authority-anchor", missing_anchor),
            ("missing-authority-evidence", missing_authority_evidence),
            ("wrong-authority-scope", wrong_authority_scope),
            ("omitted-member-with-recomputed-registry-digest", stale_anchor),
            ("forged-unregistered-anchor-id", forged_anchor),
        ):
            with self.subTest(case=case_id):
                result = evaluate(policy, candidate)
                self.assertEqual("evaluation-error", result.get("decision"))
                self.assert_reason(
                    result.get("reasonCodes", []),
                    "RETENTION_CONSUMER_AUTHORITY_BINDING_INVALID",
                )

    def test_retention_attestation_time_and_scope_are_mandatory_bindings(self) -> None:
        policy = self.load(RETENTION)
        vectors = self.load(RETENTION_VECTORS)
        evaluate = self.function("evaluate_retention_vector")
        base = next(
            copy.deepcopy(item)
            for item in vectors["cases"]
            if item["caseId"] == "quality-passed-exact-due"
        )

        future_attestation = copy.deepcopy(base)
        future_attestation["consumers"][0]["attestation"]["attestedAt"] = (
            "2026-08-09T00:00:00.001Z"
        )

        missing_scope_binding = copy.deepcopy(base)
        missing_scope_binding["consumers"][0]["attestation"].pop(
            "scopeDigest", None
        )

        mutations = (
            (
                future_attestation,
                "RETENTION_ATTESTATION_TIME_INVALID",
            ),
            (
                missing_scope_binding,
                "RETENTION_ATTESTATION_SCOPE_BINDING_MISMATCH",
            ),
        )
        for candidate, expected in mutations:
            with self.subTest(expected=expected):
                result = evaluate(policy, candidate)
                self.assertEqual("evaluation-error", result.get("decision"))
                self.assert_reason(result.get("reasonCodes", []), expected)

    def test_deletion_consumer_authority_anchor_rejects_omission_and_forgery(self) -> None:
        deletion_issues = self.function("deletion_result_issues")
        base = self.load(DELETION_RESULT_FIXTURES["completed"])

        missing_anchor = copy.deepcopy(base)
        missing_anchor["data"]["guards"]["consumerRegistry"].pop(
            "conformanceAnchorId", None
        )

        missing_authority_evidence = copy.deepcopy(base)
        missing_authority_evidence["data"]["guards"][
            "consumerRegistry"
        ].pop("authorityEvidence", None)

        wrong_authority_scope = copy.deepcopy(base)
        wrong_authority_scope["data"]["guards"]["consumerRegistry"].setdefault(
            "authorityEvidence", {}
        )["scopeDigest"] = "sha256:" + "f" * 64

        stale_anchor = copy.deepcopy(base)
        guards = stale_anchor["data"]["guards"]
        registry = guards["consumerRegistry"]
        registry.setdefault(
            "conformanceAnchorId", "active-clue-care-consumer-set"
        )
        guards["consumerWatermarks"] = []
        registry["members"] = []
        registry["registryDigest"] = "sha256:" + canonical_sha256(
            {
                "registryVersion": registry["registryVersion"],
                "members": registry["members"],
            }
        )

        forged_anchor = copy.deepcopy(base)
        forged_registry = forged_anchor["data"]["guards"]["consumerRegistry"]
        forged_registry["conformanceAnchorId"] = (
            "forged-unregistered-consumer-set"
        )

        for case_id, candidate in (
            ("missing-authority-anchor", missing_anchor),
            ("missing-authority-evidence", missing_authority_evidence),
            ("wrong-authority-scope", wrong_authority_scope),
            ("omitted-member-with-recomputed-registry-digest", stale_anchor),
            ("forged-unregistered-anchor-id", forged_anchor),
        ):
            with self.subTest(case=case_id):
                self.assert_reason(
                    deletion_issues(PROJECT_ROOT, candidate),
                    "DELETION_RESULT_CONSUMER_AUTHORITY_BINDING_INVALID",
                )

    def test_deletion_guard_chronology_and_scope_mutations_have_fixed_codes(self) -> None:
        deletion_issues = self.function("deletion_result_issues")
        completed = self.load(DELETION_RESULT_FIXTURES["completed"])
        scope_digest = completed["data"]["scope"]["scopeDigest"]
        self.assertEqual(
            scope_digest,
            completed["data"]["guards"]["legalHold"].get(
                "checkedScopeDigest"
            ),
        )
        mutations: list[tuple[dict, str]] = []

        commit_before_due = copy.deepcopy(completed)
        commit_before_due["data"]["deletionCommittedAt"] = (
            "2026-08-08T23:59:59.999Z"
        )
        mutations.append(
            (commit_before_due, "DELETION_RESULT_CHRONOLOGY_INVALID")
        )

        null_checked_at = copy.deepcopy(completed)
        null_checked_at["data"]["guards"]["legalHold"]["checkedAt"] = None
        mutations.append(
            (null_checked_at, "DELETION_RESULT_CHRONOLOGY_INVALID")
        )

        stale_checked_at = copy.deepcopy(completed)
        stale_checked_at["data"]["guards"]["consumerRegistry"]["checkedAt"] = (
            "2026-08-08T23:59:59.999Z"
        )
        mutations.append(
            (stale_checked_at, "DELETION_RESULT_CHRONOLOGY_INVALID")
        )

        future_attestation = copy.deepcopy(completed)
        future_attestation["data"]["guards"]["consumerWatermarks"][0][
            "attestation"
        ]["attestedAt"] = "2026-08-09T00:05:31Z"
        mutations.append(
            (future_attestation, "DELETION_RESULT_ATTESTATION_TIME_INVALID")
        )

        wrong_checked_scope = copy.deepcopy(completed)
        wrong_checked_scope["data"]["guards"]["legalHold"][
            "checkedScopeDigest"
        ] = "sha256:" + "f" * 64
        mutations.append(
            (wrong_checked_scope, "DELETION_RESULT_LEGAL_HOLD_SCOPE_MISMATCH")
        )

        for candidate, expected in mutations:
            with self.subTest(expected=expected):
                self.assert_reason(
                    deletion_issues(PROJECT_ROOT, candidate), expected
                )

    def test_deletion_lineage_rejects_bad_roots_and_scope_drift_but_dedupes_replay(self) -> None:
        lineage_issues = self.function("deletion_result_lineage_issues")
        blocked = self.load(DELETION_RESULT_FIXTURES["blocked"])
        completed = self.load(DELETION_RESULT_FIXTURES["completed"])

        version_two_root = copy.deepcopy(completed)
        version_two_root["data"]["supersedesResultId"] = None
        self.assert_reason(
            lineage_issues([version_two_root]),
            "DELETION_RESULT_LINEAGE_ROOT_VERSION_INVALID",
        )

        second_root = copy.deepcopy(blocked)
        second_root["id"] = "019d2c7d-4000-7000-8000-000000000211"
        second_root["data"]["eventId"] = second_root["id"]
        self.assert_reason(
            lineage_issues([blocked, second_root]),
            "DELETION_RESULT_LINEAGE_MULTIPLE_ROOTS",
        )

        scope_drift = copy.deepcopy(completed)
        scope_drift["data"]["scope"]["snapshotImmutableHash"] = (
            "sha256:" + "9" * 64
        )
        scope_drift["data"]["scope"]["scopeDigest"] = (
            "sha256:" + canonical_sha256(
                {
                    **{
                        key: scope_drift["data"]["scope"][key]
                        for key in (
                            "objectType",
                            "snapshotId",
                            "sourceId",
                            "snapshotAggregateVersion",
                            "evaluatedAt",
                            "retentionDueAt",
                            "snapshotImmutableHash",
                        )
                    },
                    "retentionPolicyVersion": scope_drift["data"][
                        "retentionPolicyVersion"
                    ],
                    "retentionScheduleVersion": scope_drift["data"][
                        "retentionScheduleVersion"
                    ],
                }
            )
        )
        self.assert_reason(
            lineage_issues([blocked, scope_drift]),
            "DELETION_RESULT_LINEAGE_SCOPE_DRIFT",
        )

        self.assertEqual(
            [], lineage_issues([blocked, completed, copy.deepcopy(completed)])
        )

    def test_deletion_lineage_successor_evidence_cannot_predate_predecessor(self) -> None:
        lineage_issues = self.function("deletion_result_lineage_issues")
        blocked = self.load(DELETION_RESULT_FIXTURES["blocked"])
        completed = self.load(DELETION_RESULT_FIXTURES["completed"])
        predecessor_occurred_at = self.instant(blocked["data"]["occurredAt"])
        early = (predecessor_occurred_at - timedelta(milliseconds=1)).isoformat(
            timespec="milliseconds"
        ).replace("+00:00", "Z")

        mutations: list[tuple[str, dict]] = []
        guard_paths = (
            ("trusted-time", "trustedTime", "observedAt"),
            ("legal-hold", "legalHold", "checkedAt"),
            ("consumer-registry", "consumerRegistry", "checkedAt"),
        )
        for label, guard_name, field_name in guard_paths:
            candidate = copy.deepcopy(completed)
            candidate["data"]["guards"][guard_name][field_name] = early
            mutations.append((label, candidate))

        watermarks = copy.deepcopy(completed)
        watermarks["data"]["guards"]["consumerWatermarksCheckedAt"] = early
        mutations.append(("consumer-watermarks", watermarks))

        committed = copy.deepcopy(completed)
        committed["data"]["deletionCommittedAt"] = early
        mutations.append(("deletion-commit", committed))

        occurred = copy.deepcopy(completed)
        occurred["data"]["occurredAt"] = early
        occurred["time"] = early
        mutations.append(("occurred-at", occurred))

        for field, candidate in mutations:
            with self.subTest(field=field):
                self.assert_reason(
                    lineage_issues([blocked, candidate]),
                    "DELETION_RESULT_LINEAGE_CHRONOLOGY_INVALID",
                )

    def test_initial_completed_result_is_a_valid_version_one_root(self) -> None:
        deletion_issues = self.function("deletion_result_issues")
        lineage_issues = self.function("deletion_result_lineage_issues")
        initial = self.load(DELETION_RESULT_FIXTURES["completed"])
        initial["data"]["aggregateVersion"] = 1
        initial["data"]["supersedesResultId"] = None
        self.assertEqual([], lineage_issues([initial]))
        self.assertEqual([], deletion_issues(PROJECT_ROOT, initial))

    def test_mandatory_database_targets_snapshot_cardinality_and_transaction_evidence(self) -> None:
        deletion_issues = self.function("deletion_result_issues")
        completed = self.load(DELETION_RESULT_FIXTURES["completed"])
        results = completed["data"]["ownerLocalResults"]
        transaction_ids = {
            results[key].get("transactionId")
            for key in ("onlineSnapshot", "onlineMetrics")
        }
        transaction_digests = {
            results[key].get("transactionEvidenceDigest")
            for key in ("onlineSnapshot", "onlineMetrics")
        }
        self.assertEqual(1, len(transaction_ids))
        self.assertNotIn(None, transaction_ids)
        self.assertEqual(1, len(transaction_digests))
        self.assertNotIn(None, transaction_digests)

        mutations: list[tuple[dict, str]] = []
        for key in ("onlineSnapshot", "onlineMetrics"):
            not_applicable = copy.deepcopy(completed)
            not_applicable["data"]["ownerLocalResults"][key].update(
                {
                    "status": "not-applicable",
                    "selectedCount": 0,
                    "deletedCount": 0,
                    "remainingCount": 0,
                    "evidenceDigest": None,
                    "errorCode": None,
                }
            )
            mutations.append(
                (not_applicable, "DELETION_RESULT_MANDATORY_DB_TARGET_INVALID")
            )

        two_snapshots = copy.deepcopy(completed)
        two_snapshots["data"]["ownerLocalResults"]["onlineSnapshot"].update(
            {"selectedCount": 2, "deletedCount": 2}
        )
        mutations.append(
            (two_snapshots, "DELETION_RESULT_SNAPSHOT_COUNT_INVALID")
        )

        split_transaction = copy.deepcopy(completed)
        split_transaction["data"]["ownerLocalResults"]["onlineMetrics"][
            "transactionId"
        ] = "019d2c7d-4000-7000-8000-000000000299"
        mutations.append(
            (
                split_transaction,
                "DELETION_RESULT_OWNER_DB_ATOMICITY_EVIDENCE_INVALID",
            )
        )

        for candidate, expected in mutations:
            with self.subTest(expected=expected):
                self.assert_reason(
                    deletion_issues(PROJECT_ROOT, candidate), expected
                )

    def test_database_transaction_evidence_digest_is_recomputable(self) -> None:
        for outcome in ("completed", "partial", "failed"):
            with self.subTest(outcome=outcome):
                event = self.load(DELETION_RESULT_FIXTURES[outcome])
                material = self.database_transaction_evidence_material(event)
                expected = "sha256:" + canonical_sha256(material)
                results = event["data"]["ownerLocalResults"]
                self.assertEqual(
                    expected,
                    results["onlineSnapshot"]["transactionEvidenceDigest"],
                )
                self.assertEqual(
                    expected,
                    results["onlineMetrics"]["transactionEvidenceDigest"],
                )

    def test_forged_equal_database_transaction_digests_are_rejected(self) -> None:
        deletion_issues = self.function("deletion_result_issues")
        completed = self.load(DELETION_RESULT_FIXTURES["completed"])
        forged = copy.deepcopy(completed)
        for key in ("onlineSnapshot", "onlineMetrics"):
            forged["data"]["ownerLocalResults"][key][
                "transactionEvidenceDigest"
            ] = "sha256:" + "f" * 64
        self.assert_reason(
            deletion_issues(PROJECT_ROOT, forged),
            "DELETION_RESULT_OWNER_DB_ATOMICITY_EVIDENCE_INVALID",
        )

        stale = copy.deepcopy(completed)
        stale["data"]["ownerLocalResults"]["onlineMetrics"][
            "evidenceDigest"
        ] = "sha256:" + "9" * 64
        self.assert_reason(
            deletion_issues(PROJECT_ROOT, stale),
            "DELETION_RESULT_OWNER_DB_ATOMICITY_EVIDENCE_INVALID",
        )

    def test_partial_deletion_requires_backup_expiry_due_at(self) -> None:
        deletion_issues = self.function("deletion_result_issues")
        partial = self.load(DELETION_RESULT_FIXTURES["partial"])
        partial["data"]["backup"]["backupExpiryDueAt"] = None
        self.assert_reason(
            deletion_issues(PROJECT_ROOT, partial),
            "DELETION_RESULT_BACKUP_DUE_REQUIRED",
        )

    def test_actual_raw_utf8_wire_bytes_not_canonical_bytes_enforce_64_kib(self) -> None:
        wire_issues = self.function("deletion_result_wire_issues")
        event = self.load(DELETION_RESULT_FIXTURES["completed"])
        self.assertLessEqual(len(canonical_bytes(event)), MAX_EVENT_BYTES)
        raw_payload = b" " * (MAX_EVENT_BYTES + 1) + canonical_bytes(event)
        self.assertGreater(len(raw_payload), MAX_EVENT_BYTES)
        self.assert_reason(
            wire_issues(PROJECT_ROOT, raw_payload),
            "DELETION_RESULT_PAYLOAD_TOO_LARGE",
        )

    def test_deletion_result_wire_rejects_duplicate_keys_but_accepts_pretty_json(self) -> None:
        wire_issues = self.function("deletion_result_wire_issues")
        event = self.load(DELETION_RESULT_FIXTURES["completed"])
        canonical = canonical_bytes(event)
        self.assertEqual([], wire_issues(PROJECT_ROOT, canonical))

        needle = b'"specversion":"1.0"'
        self.assertEqual(1, canonical.count(needle))
        duplicate_key = canonical.replace(
            needle,
            needle + b"," + needle,
            1,
        )
        pretty = json.dumps(
            event,
            ensure_ascii=False,
            sort_keys=True,
            indent=2,
        ).encode("utf-8")
        self.assertLessEqual(len(duplicate_key), MAX_EVENT_BYTES)
        self.assert_reason(
            wire_issues(PROJECT_ROOT, duplicate_key),
            "DELETION_RESULT_CANONICAL_JSON_INVALID",
        )
        self.assertLessEqual(len(pretty), MAX_EVENT_BYTES)
        self.assertEqual([], wire_issues(PROJECT_ROOT, pretty))

    def test_pic_binding_low_cardinality_failures_and_nonzero_trace_are_enforced(self) -> None:
        deletion_issues = self.function("deletion_result_issues")
        completed = self.load(DELETION_RESULT_FIXTURES["completed"])
        data = completed["data"]
        self.assertEqual("PIC-1.0.0", data.get("contractVersion"))
        self.assertIsInstance(data.get("correlationId"), str)
        self.assertTrue(data.get("correlationId"))
        self.assertIsInstance(data.get("causationId"), str)
        self.assertTrue(data.get("causationId"))

        pic_invalid = copy.deepcopy(completed)
        pic_invalid["data"]["contractVersion"] = "PIC-2.0.0"
        self.assert_reason(
            deletion_issues(PROJECT_ROOT, pic_invalid),
            "DELETION_RESULT_PIC_BINDING_INVALID",
        )

        partial = self.load(DELETION_RESULT_FIXTURES["partial"])
        partial["data"]["ownerLocalResults"]["objects"]["errorCode"] = (
            "OBJECT_DELETE_FAILED_SNAPSHOT_019D2C7D4000"
        )
        partial["data"]["failureCodes"] = [
            "INDEX_DELETE_FAILED",
            "OBJECT_DELETE_FAILED_SNAPSHOT_019D2C7D4000",
        ]
        self.assert_reason(
            deletion_issues(PROJECT_ROOT, partial),
            "DELETION_RESULT_FAILURE_CODE_UNCONTROLLED",
        )

        zero_trace = copy.deepcopy(completed)
        zero_trace["traceparent"] = f"00-{'0' * 32}-{'0' * 16}-01"
        zero_trace["data"]["traceId"] = "0" * 32
        self.assert_reason(
            deletion_issues(PROJECT_ROOT, zero_trace),
            "DELETION_RESULT_TRACE_ZERO_INVALID",
        )

        zero_span = copy.deepcopy(completed)
        zero_span["traceparent"] = (
            f"00-{zero_span['data']['traceId']}-{'0' * 16}-01"
        )
        self.assert_reason(
            deletion_issues(PROJECT_ROOT, zero_span),
            "DELETION_RESULT_TRACE_ZERO_INVALID",
        )

    def test_delivery_decision_rejects_bool_negative_and_string_inputs(self) -> None:
        decide = self.function("deletion_delivery_decision")
        invalid_cases = [
            (True, 2, True),
            (0, False, True),
            (-1, 0, True),
            (0, -1, True),
            ("0", 1, True),
            (0, "1", True),
            (0, 1, 1),
            (0, 1, "true"),
        ]
        for current, incoming, same_payload in invalid_cases:
            with self.subTest(
                current=current,
                incoming=incoming,
                same_payload=same_payload,
            ):
                try:
                    decision = decide(
                        current,
                        incoming,
                        same_payload=same_payload,
                    )
                except Exception as error:  # pragma: no cover - RED diagnostic
                    self.fail(f"invalid input must fail closed, not raise: {error}")
                self.assertEqual("INVALID_INPUT", decision)

    def test_task_zero_four_negative_fixture_catalog_is_executable(self) -> None:
        catalog = self.load(TASK_0_4_NEGATIVE_FIXTURES)
        execute = self.function("execute_task_0_4_negative_fixture")
        cases = {case["caseId"]: case for case in catalog["cases"]}
        mandatory = {
            "illegal-failed-publish",
            "reopen-batch",
            "same-identity-different-digest",
            "correction-cross-lineage",
            "closed-observation-window",
            "technical-error-becomes-quality-failed",
            "retention-starts-at-published",
            "retention-fixed-730-days",
            "retention-anonymizes",
            "completed-with-legal-hold",
            "completed-with-watermark-gap",
            "completed-without-evidence-copy",
            "blocked-after-delete",
            "partial-without-split",
            "failed-after-commit",
            "owner-result-forges-final-receipt",
            "backup-due-drift",
            "event-id-mismatch",
            "scope-digest-mismatch",
            "payload-over-64-kib",
        }
        self.assertTrue(mandatory.issubset(cases))
        for case in cases.values():
            with self.subTest(case=case["caseId"]):
                self.assertEqual(
                    case["expectedCode"], execute(case, PROJECT_ROOT)
                )

    def test_aggregate_lock_includes_task_zero_four_cross_directory_artifacts(self) -> None:
        locked_files = set(checker.EXECUTABLE_LOCKED_FILES)
        self.assertTrue(TASK_0_4_LOCKED_FILES.issubset(locked_files))

        lock_path = (
            BATCH_QUALITY / "executable-quality-contract-lock-1.0.0.json"
        )
        lock = self.load(lock_path)
        self.assertTrue(TASK_0_4_LOCKED_FILES.issubset(lock["digests"]))
        self.assertEqual(
            "sha256:" + canonical_sha256(self.load(LIFECYCLE)),
            lock["lifecycleCanonicalDigest"],
        )
        self.assertEqual(
            "sha256:" + canonical_sha256(self.load(RETENTION)),
            lock["retentionCanonicalDigest"],
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in checker.EXECUTABLE_LOCKED_FILES:
                source = PROJECT_ROOT / relative
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, destination)
            lock_relative = (
                "contracts/ingestion-quality/batch-quality/"
                "executable-quality-contract-lock-1.0.0.json"
            )
            source_lock = PROJECT_ROOT / lock_relative
            destination_lock = root / lock_relative
            destination_lock.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source_lock, destination_lock)
            target = root / (
                "contracts/events/ingestion-quality/"
                "quality-snapshot-deletion-result.schema.json"
            )
            target.write_bytes(target.read_bytes() + b"\n")
            self.assert_reason(
                checker.lock_issues(root), "BATCH_QUALITY_LOCK_DIGEST_MISMATCH"
            )

    def test_task_zero_four_is_integrated_into_the_primary_checker(self) -> None:
        required_functions = {
            "lifecycle_issues",
            "evaluate_batch_identity",
            "batch_correction_lineage_issues",
            "batch_lifecycle_case_issues",
            "batch_window_contains",
            "retention_policy_issues",
            "evaluate_retention_vector",
            "deletion_result_issues",
            "deletion_delivery_decision",
            "deletion_result_lineage_issues",
            "execute_task_0_4_negative_fixture",
        }
        missing = sorted(
            name for name in required_functions if not callable(getattr(checker, name, None))
        )
        self.assertEqual([], missing)
        self.assertEqual([], checker.validate(PROJECT_ROOT))

    def function(self, name: str):
        value = getattr(checker, name, None)
        self.assertTrue(callable(value), f"Task 0.4 checker function missing: {name}")
        return value

    def load(self, path: Path) -> dict:
        self.assertTrue(path.is_file(), f"Task 0.4 controlled file missing: {path}")
        return load_json(path)

    def assert_reason(self, issues: list[str], expected: str) -> None:
        self.assertTrue(
            any(issue.startswith(expected) for issue in issues),
            f"expected {expected}, got {issues}",
        )

    @staticmethod
    def database_transaction_evidence_material(event: dict) -> dict:
        data = event["data"]
        results = data["ownerLocalResults"]
        target_fields = (
            "status",
            "selectedCount",
            "deletedCount",
            "remainingCount",
            "evidenceDigest",
            "errorCode",
        )
        transaction_id = results["onlineSnapshot"]["transactionId"]
        return {
            "executionId": data["executionId"],
            "scopeDigest": data["scope"]["scopeDigest"],
            "result": data["result"],
            "deletionCommittedAt": data["deletionCommittedAt"],
            "transactionId": transaction_id,
            "onlineSnapshot": {
                field: results["onlineSnapshot"][field]
                for field in target_fields
            },
            "onlineMetrics": {
                field: results["onlineMetrics"][field]
                for field in target_fields
            },
        }

    @staticmethod
    def instant(value: str) -> datetime:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.astimezone(timezone.utc)

    @classmethod
    def normalized_keys(cls, value: object) -> set[str]:
        result: set[str] = set()
        if isinstance(value, dict):
            for key, child in value.items():
                result.add(key.lower().replace("_", "").replace("-", ""))
                result.update(cls.normalized_keys(child))
        elif isinstance(value, list):
            for child in value:
                result.update(cls.normalized_keys(child))
        return result


if __name__ == "__main__":
    unittest.main()
