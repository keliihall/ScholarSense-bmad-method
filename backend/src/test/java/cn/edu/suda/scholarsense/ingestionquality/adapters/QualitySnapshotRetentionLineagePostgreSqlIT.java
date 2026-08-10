package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionScopeCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * PostgreSQL 18.4 contract for retention-result execution lineage.
 *
 * <p>Authority records here are synthetic administrator fixtures; the independent production
 * authority-login ingress is covered by {@link QualitySnapshotRetentionAuthorityPostgreSqlIT}.
 */
@TestMethodOrder(OrderAnnotation.class)
class QualitySnapshotRetentionLineagePostgreSqlIT {
    private static final String RETENTION_LOGIN =
            "scholarsense_iq_ret_lineage_executor_test_login";
    private static final String RESULT_EVENT_TYPE =
            "scholarsense.ingestion-quality.quality-snapshot.deletion-result.v1";

    private static final UUID BATCH_ID = uuid("019fe560-0000-7000-8000-000000000001");
    private static final UUID LINEAGE_ID = uuid("019fe560-0000-7000-8000-000000000002");
    private static final UUID SNAPSHOT_ID = uuid("019fe560-0000-7000-8000-000000000003");
    private static final UUID OTHER_SNAPSHOT_ID =
            uuid("019fe560-0000-7000-8000-000000000004");
    private static final UUID MAIN_EXECUTION_ID = SNAPSHOT_ID;
    private static final UUID OTHER_EXECUTION_ID =
            uuid("019fe560-0000-7000-8000-000000000101");
    private static final UUID CONSUMING_EXECUTION_ID =
            uuid("019fe560-0000-7000-8000-000000000102");

    private static final UUID STALE_EVIDENCE_ID =
            uuid("019fe560-0000-7000-8000-000000000200");
    private static final UUID MISSING_EVIDENCE_ID =
            uuid("019fe560-0000-7000-8000-000000000201");
    private static final UUID MISMATCHED_EVIDENCE_ID =
            uuid("019fe560-0000-7000-8000-000000000202");
    private static final UUID CONSUMED_EVIDENCE_ID =
            uuid("019fe560-0000-7000-8000-000000000203");
    private static final UUID VALID_NOT_REQUIRED_EVIDENCE_ID =
            uuid("019fe560-0000-7000-8000-000000000204");

    private static final UUID BLOCKED_STALE_EVENT_ID =
            uuid("019fe560-0000-7000-8000-000000000301");
    private static final UUID BLOCKED_MISSING_EVENT_ID =
            uuid("019fe560-0000-7000-8000-000000000302");
    private static final UUID BLOCKED_SCOPE_EVENT_ID =
            uuid("019fe560-0000-7000-8000-000000000303");
    private static final UUID BLOCKED_CONSUMED_EVENT_ID =
            uuid("019fe560-0000-7000-8000-000000000304");
    private static final UUID OTHER_EXECUTION_ROOT_EVENT_ID =
            uuid("019fe560-0000-7000-8000-000000000305");
    private static final UUID COMPLETED_EVENT_ID =
            uuid("019fe560-0000-7000-8000-000000000306");
    private static final UUID AFTER_COMPLETED_EVENT_ID =
            uuid("019fe560-0000-7000-8000-000000000307");
    private static final UUID SCOPE_DRIFT_EVENT_ID =
            uuid("019fe560-0000-7000-8000-000000000308");
    private static final UUID HASH_DRIFT_EVENT_ID =
            uuid("019fe560-0000-7000-8000-000000000309");
    private static final UUID SNAPSHOT_DRIFT_EVENT_ID =
            uuid("019fe560-0000-7000-8000-00000000030a");

    private static final String SOURCE_ID = "SRC-P0-CARD-001";
    private static final byte[] BUSINESS_KEY =
            "retention-lineage\0\u6267\u884c".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WATERMARK =
            "retention-lineage-watermark\0\u7ec8".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ATTESTATIONS =
            "{\"consumers\":[]}".getBytes(StandardCharsets.UTF_8);
    private static final String ATTESTATIONS_DIGEST = sha256(ATTESTATIONS);
    private static final String IMMUTABLE_HASH = digest('a');
    private static final String OTHER_IMMUTABLE_HASH = digest('b');
    private static final String OTHER_SCOPE = digest('d');
    private static final String MEMBERS_DIGEST =
            "sha256:" + sha256("[]".getBytes(StandardCharsets.UTF_8));
    private static final String REGISTRY_DIGEST = "sha256:" + sha256(
            ("{\"members\":[],\"registryVersion\":"
                    + "\"QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0\"}")
                    .getBytes(StandardCharsets.UTF_8));
    private static final String EVIDENCE_COPY_DIGEST = MEMBERS_DIGEST;
    private static final String TRACE_ID = "123456789abcdef0123456789abcdef0";
    private static final String RETENTION_TRACE_ID = "abcdef0123456789abcdef0123456789";

    private static final Instant RECEIVED_AT = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant SEALED_AT = Instant.parse("2024-01-01T01:00:00Z");
    private static final Instant EVALUATED_AT = Instant.parse("2024-01-02T00:00:00Z");
    private static final Instant RETENTION_DUE_AT =
            Instant.parse("2026-01-02T00:00:00Z");
    private static final String RETENTION_SCOPE =
            QualitySnapshotRetentionScopeCanonicalizer.digest(
                    new QualitySnapshotRetentionScopeCanonicalizer.Material(
                            "QualitySnapshot", SNAPSHOT_ID, SOURCE_ID, 3L,
                            EVALUATED_AT, RETENTION_DUE_AT, IMMUTABLE_HASH,
                            "QUALITY-SNAPSHOT-RETENTION-1.0.0", "RS-1.0.0"));

    @Test
    @Order(1)
    void deletionResultShapeAndRetentionFunctionAreExecutionScoped() {
        JdbcTemplate jdbc = admin();

        assertEquals(List.of("NO"), jdbc.queryForList("""
                select is_nullable
                  from information_schema.columns
                 where table_schema='ingestion_quality'
                   and table_name='iq_quality_snapshot_deletion_result'
                   and column_name='execution_id'
                """, String.class), "every blocked and completed result must retain executionId");

        String constraints = String.join("\n", jdbc.queryForList("""
                select lower(pg_catalog.pg_get_constraintdef(constraint_record.oid, true))
                  from pg_catalog.pg_constraint constraint_record
                  join pg_catalog.pg_class relation
                    on relation.oid=constraint_record.conrelid
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=relation.relnamespace
                 where namespace.nspname='ingestion_quality'
                   and relation.relname='iq_quality_snapshot_deletion_result'
                """, String.class));
        assertTrue(constraints.contains("unique (execution_id, aggregate_version)"), constraints);
        assertFalse(constraints.contains("unique (snapshot_id, aggregate_version)"),
                "the stable execution lineage, not snapshot/version, owns event uniqueness");
        assertTrue(constraints.contains("(execution_id::text, 15, 1) = '7'"), constraints);
        assertTrue(constraints.contains("(transaction_id::text, 15, 1) = '7'"),
                "a completed database transaction identity must be an independent UUIDv7");

        String authorityConstraints = String.join("\n", jdbc.queryForList("""
                select lower(pg_catalog.pg_get_constraintdef(constraint_record.oid, true))
                  from pg_catalog.pg_constraint constraint_record
                  join pg_catalog.pg_class relation
                    on relation.oid=constraint_record.conrelid
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=relation.relnamespace
                 where namespace.nspname='ingestion_quality'
                   and relation.relname=
                     'iq_quality_snapshot_retention_authority_evidence'
                """, String.class));
        assertTrue(authorityConstraints.contains("evidence_copy_status"), authorityConstraints);
        assertTrue(authorityConstraints.contains("'copied'"), authorityConstraints);
        assertTrue(authorityConstraints.contains("'not-required'"),
                "the frozen contract permits a bound not-required copy attestation");

        String function = jdbc.queryForObject("""
                select lower(pg_catalog.pg_get_functiondef(procedure.oid))
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality'
                   and procedure.proname='iq_execute_quality_snapshot_retention'
                """, String.class);
        assertNotNull(function);
        for (String token : List.of(
                "requested_execution_id",
                "clock_timestamp",
                "expires_at",
                "aggregateid",
                "not-required",
                "quality-snapshot-deletion-result-1.1.0",
                "requested_trace_id")) {
            assertTrue(function.contains(token), "retention function missing " + token);
        }
    }

    @Test
    @Order(2)
    void blockedResultsCompleteOnlyAsDirectSuccessorsWithinTheirExecution() {
        ensureRetentionLogin();
        JdbcTemplate admin = admin();
        JdbcTemplate retention = workload(RETENTION_LOGIN);
        seedAssessedSnapshot(admin);

        Instant databaseNow = admin.queryForObject(
                "select statement_timestamp()", Timestamp.class).toInstant();

        seedEvidence(
                admin,
                STALE_EVIDENCE_ID,
                SNAPSHOT_ID,
                IMMUTABLE_HASH,
                RETENTION_SCOPE,
                databaseNow.minusSeconds(120),
                databaseNow.minusSeconds(180),
                databaseNow.minusSeconds(60),
                null,
                null,
                "copied");
        assertEquals("blocked", executeRetention(
                retention,
                MAIN_EXECUTION_ID,
                BLOCKED_STALE_EVENT_ID,
                SNAPSHOT_ID,
                IMMUTABLE_HASH,
                RETENTION_SCOPE,
                STALE_EVIDENCE_ID));
        assertResult(
                admin,
                MAIN_EXECUTION_ID,
                BLOCKED_STALE_EVENT_ID,
                1L,
                null,
                "blocked",
                "CONSUMER_REGISTRY_UNAVAILABLE");

        int resultsAfterFirstBlock = resultCount(admin, MAIN_EXECUTION_ID);
        int outboxAfterFirstBlock = outboxCount(admin, MAIN_EXECUTION_ID);
        assertDatabaseFailure("INGESTION_QUALITY_DELETION_RESULT_IDEMPOTENCY_CONFLICT", () ->
                executeRetention(
                        retention,
                        OTHER_EXECUTION_ID,
                        BLOCKED_STALE_EVENT_ID,
                        SNAPSHOT_ID,
                        IMMUTABLE_HASH,
                        RETENTION_SCOPE,
                        STALE_EVIDENCE_ID));
        assertEquals(resultsAfterFirstBlock, resultCount(admin, MAIN_EXECUTION_ID));
        assertEquals(outboxAfterFirstBlock, outboxCount(admin, MAIN_EXECUTION_ID));

        assertAggregateDriftRejected(
                retention,
                admin,
                SCOPE_DRIFT_EVENT_ID,
                SNAPSHOT_ID,
                IMMUTABLE_HASH,
                OTHER_SCOPE);
        assertAggregateDriftRejected(
                retention,
                admin,
                HASH_DRIFT_EVENT_ID,
                SNAPSHOT_ID,
                OTHER_IMMUTABLE_HASH,
                RETENTION_SCOPE);
        assertAggregateDriftRejected(
                retention,
                admin,
                SNAPSHOT_DRIFT_EVENT_ID,
                OTHER_SNAPSHOT_ID,
                IMMUTABLE_HASH,
                RETENTION_SCOPE);

        assertEquals("blocked", executeRetention(
                retention,
                MAIN_EXECUTION_ID,
                BLOCKED_MISSING_EVENT_ID,
                SNAPSHOT_ID,
                IMMUTABLE_HASH,
                RETENTION_SCOPE,
                MISSING_EVIDENCE_ID));
        assertResult(
                admin,
                MAIN_EXECUTION_ID,
                BLOCKED_MISSING_EVENT_ID,
                2L,
                BLOCKED_STALE_EVENT_ID,
                "blocked",
                "CONSUMER_REGISTRY_UNAVAILABLE");

        Instant mismatchObservedAt = admin.queryForObject(
                "select statement_timestamp()", Timestamp.class).toInstant();
        seedEvidence(
                admin,
                MISMATCHED_EVIDENCE_ID,
                SNAPSHOT_ID,
                IMMUTABLE_HASH,
                OTHER_SCOPE,
                mismatchObservedAt,
                databaseNow.minusSeconds(60),
                databaseNow.plusSeconds(3600),
                null,
                null,
                "copied");
        assertEquals("blocked", executeRetention(
                retention,
                MAIN_EXECUTION_ID,
                BLOCKED_SCOPE_EVENT_ID,
                SNAPSHOT_ID,
                IMMUTABLE_HASH,
                RETENTION_SCOPE,
                MISMATCHED_EVIDENCE_ID));
        assertResult(
                admin,
                MAIN_EXECUTION_ID,
                BLOCKED_SCOPE_EVENT_ID,
                3L,
                BLOCKED_MISSING_EVENT_ID,
                "blocked",
                "CONSUMER_REGISTRY_UNAVAILABLE");

        Instant consumedObservedAt = admin.queryForObject(
                "select statement_timestamp()", Timestamp.class).toInstant();
        seedEvidence(
                admin,
                CONSUMED_EVIDENCE_ID,
                SNAPSHOT_ID,
                IMMUTABLE_HASH,
                RETENTION_SCOPE,
                consumedObservedAt,
                databaseNow.minusSeconds(60),
                databaseNow.plusSeconds(3600),
                consumedObservedAt.minusMillis(1),
                CONSUMING_EXECUTION_ID,
                "copied");
        assertEquals("blocked", executeRetention(
                retention,
                MAIN_EXECUTION_ID,
                BLOCKED_CONSUMED_EVENT_ID,
                SNAPSHOT_ID,
                IMMUTABLE_HASH,
                RETENTION_SCOPE,
                CONSUMED_EVIDENCE_ID));
        assertResult(
                admin,
                MAIN_EXECUTION_ID,
                BLOCKED_CONSUMED_EVENT_ID,
                4L,
                BLOCKED_SCOPE_EVENT_ID,
                "blocked",
                "CONSUMER_REGISTRY_UNAVAILABLE");

        assertDatabaseFailure("INGESTION_QUALITY_RETENTION_EXECUTION_ID_INVALID", () ->
                executeRetention(
                        retention,
                        OTHER_EXECUTION_ID,
                        OTHER_EXECUTION_ROOT_EVENT_ID,
                        SNAPSHOT_ID,
                        IMMUTABLE_HASH,
                        RETENTION_SCOPE,
                        MISSING_EVIDENCE_ID));

        Instant completedObservedAt = admin.queryForObject(
                "select statement_timestamp()", Timestamp.class).toInstant();
        seedEvidence(
                admin,
                VALID_NOT_REQUIRED_EVIDENCE_ID,
                SNAPSHOT_ID,
                IMMUTABLE_HASH,
                RETENTION_SCOPE,
                completedObservedAt,
                databaseNow.minusSeconds(60),
                databaseNow.plusSeconds(3600),
                null,
                null,
                "not-required");
        assertEquals("completed", executeRetention(
                retention,
                MAIN_EXECUTION_ID,
                COMPLETED_EVENT_ID,
                SNAPSHOT_ID,
                IMMUTABLE_HASH,
                RETENTION_SCOPE,
                VALID_NOT_REQUIRED_EVIDENCE_ID));
        assertResult(
                admin,
                MAIN_EXECUTION_ID,
                COMPLETED_EVENT_ID,
                5L,
                BLOCKED_CONSUMED_EVENT_ID,
                "completed",
                null);
        Instant predecessorOccurredAt = admin.queryForObject("""
                select occurred_at
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id=?
                """, Timestamp.class, BLOCKED_CONSUMED_EVENT_ID).toInstant();
        Map<String, Object> completedGuards = admin.queryForMap("""
                select convert_from(payload_utf8, 'UTF8')::jsonb
                         #>> '{data,guards,trustedTime,observedAt}' as trusted_at,
                       convert_from(payload_utf8, 'UTF8')::jsonb
                         #>> '{data,guards,legalHold,checkedAt}' as legal_at,
                       convert_from(payload_utf8, 'UTF8')::jsonb
                         #>> '{data,guards,consumerRegistry,checkedAt}' as registry_at
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id=?
                """, COMPLETED_EVENT_ID);
        for (String key : List.of("trusted_at", "legal_at", "registry_at")) {
            Instant guardAt = Instant.parse((String) completedGuards.get(key));
            assertFalse(guardAt.isBefore(predecessorOccurredAt),
                    key + " must be monotonic across the direct-successor lineage");
        }

        UUID transactionId = admin.queryForObject("""
                select transaction_id
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id=?
                """, UUID.class, COMPLETED_EVENT_ID);
        assertNotNull(transactionId);
        assertNotEquals(MAIN_EXECUTION_ID, transactionId,
                "execution identity and committed database transaction identity are distinct");
        assertUuidV7(transactionId);
        assertEquals(MAIN_EXECUTION_ID, admin.queryForObject("""
                select aggregate_id
                  from ingestion_quality.iq_batch_quality_outbox
                 where event_id=?
                """, UUID.class, COMPLETED_EVENT_ID));
        assertEquals(0, admin.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_quality_snapshot
                 where snapshot_id=?
                """, Integer.class, SNAPSHOT_ID));

        int completedResultCount = resultCount(admin, MAIN_EXECUTION_ID);
        int completedOutboxCount = outboxCount(admin, MAIN_EXECUTION_ID);
        assertEquals("completed", executeRetention(
                retention,
                MAIN_EXECUTION_ID,
                COMPLETED_EVENT_ID,
                SNAPSHOT_ID,
                IMMUTABLE_HASH,
                RETENTION_SCOPE,
                VALID_NOT_REQUIRED_EVIDENCE_ID));
        assertEquals(completedResultCount, resultCount(admin, MAIN_EXECUTION_ID));
        assertEquals(completedOutboxCount, outboxCount(admin, MAIN_EXECUTION_ID));

        assertEquals("completed", executeRetention(
                retention,
                MAIN_EXECUTION_ID,
                AFTER_COMPLETED_EVENT_ID,
                SNAPSHOT_ID,
                IMMUTABLE_HASH,
                RETENTION_SCOPE,
                MISSING_EVIDENCE_ID));
        assertEquals(completedResultCount, resultCount(admin, MAIN_EXECUTION_ID));
        assertEquals(completedOutboxCount, outboxCount(admin, MAIN_EXECUTION_ID));

        assertExecutionLineage(admin, MAIN_EXECUTION_ID, List.of(
                BLOCKED_STALE_EVENT_ID,
                BLOCKED_MISSING_EVENT_ID,
                BLOCKED_SCOPE_EVENT_ID,
                BLOCKED_CONSUMED_EVENT_ID,
                COMPLETED_EVENT_ID));
    }

    private static void assertAggregateDriftRejected(
            JdbcTemplate retention,
            JdbcTemplate admin,
            UUID resultEventId,
            UUID snapshotId,
            String immutableHash,
            String scopeDigest) {
        int before = resultCount(admin, MAIN_EXECUTION_ID);
        assertDatabaseFailure("INGESTION_QUALITY_DELETION_RESULT_AGGREGATE_CONFLICT", () ->
                executeRetention(
                        retention,
                        MAIN_EXECUTION_ID,
                        resultEventId,
                        snapshotId,
                        immutableHash,
                        scopeDigest,
                        MISSING_EVIDENCE_ID));
        assertEquals(before, resultCount(admin, MAIN_EXECUTION_ID));
    }

    private static void assertResult(
            JdbcTemplate jdbc,
            UUID executionId,
            UUID resultEventId,
            long expectedVersion,
            UUID expectedPredecessor,
            String expectedResult,
            String expectedBlocker) {
        Map<String, Object> result = jdbc.queryForMap("""
                select execution_id, snapshot_id, snapshot_immutable_hash, scope_digest,
                       result, blocker_code, aggregate_version, supersedes_result_event_id
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id=?
                """, resultEventId);
        assertEquals(executionId, result.get("execution_id"));
        assertEquals(SNAPSHOT_ID, result.get("snapshot_id"));
        assertEquals(IMMUTABLE_HASH, result.get("snapshot_immutable_hash"));
        assertEquals(RETENTION_SCOPE, result.get("scope_digest"));
        assertEquals(expectedResult, result.get("result"));
        assertEquals(expectedBlocker, result.get("blocker_code"));
        assertEquals(expectedVersion, result.get("aggregate_version"));
        assertEquals(expectedPredecessor, result.get("supersedes_result_event_id"));

        Map<String, Object> outbox = jdbc.queryForMap("""
                select aggregate_id, aggregate_version
                  from ingestion_quality.iq_batch_quality_outbox
                 where event_id=? and event_type=?
                """, resultEventId, RESULT_EVENT_TYPE);
        assertEquals(executionId, outbox.get("aggregate_id"));
        assertEquals(expectedVersion, outbox.get("aggregate_version"));
    }

    private static void assertExecutionLineage(
            JdbcTemplate jdbc, UUID executionId, List<UUID> expectedEventIds) {
        List<Map<String, Object>> results = jdbc.queryForList("""
                select result_event_id, aggregate_version, supersedes_result_event_id,
                       snapshot_id, snapshot_immutable_hash, scope_digest, occurred_at
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where execution_id=?
                 order by aggregate_version
                """, executionId);
        assertEquals(expectedEventIds.size(), results.size());
        Instant previousOccurredAt = null;
        for (int index = 0; index < expectedEventIds.size(); index++) {
            Map<String, Object> result = results.get(index);
            assertEquals(expectedEventIds.get(index), result.get("result_event_id"));
            assertEquals((long) index + 1L, result.get("aggregate_version"));
            assertEquals(index == 0 ? null : expectedEventIds.get(index - 1),
                    result.get("supersedes_result_event_id"));
            assertEquals(SNAPSHOT_ID, result.get("snapshot_id"));
            assertEquals(IMMUTABLE_HASH, result.get("snapshot_immutable_hash"));
            assertEquals(RETENTION_SCOPE, result.get("scope_digest"));
            Instant occurredAt = ((Timestamp) result.get("occurred_at")).toInstant();
            if (previousOccurredAt != null) {
                assertFalse(occurredAt.isBefore(previousOccurredAt),
                        "a direct successor cannot predate its predecessor");
            }
            previousOccurredAt = occurredAt;
        }
    }

    private static int resultCount(JdbcTemplate jdbc, UUID executionId) {
        return jdbc.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where execution_id=?
                """, Integer.class, executionId);
    }

    private static int outboxCount(JdbcTemplate jdbc, UUID executionId) {
        return jdbc.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_batch_quality_outbox
                 where aggregate_id=? and event_type=?
                """, Integer.class, executionId, RESULT_EVENT_TYPE);
    }

    private static String executeRetention(
            JdbcTemplate retention,
            UUID executionId,
            UUID resultEventId,
            UUID snapshotId,
            String snapshotImmutableHash,
            String scopeDigest,
            UUID authorityEvidenceId) {
        return retention.queryForObject("""
                select ingestion_quality.iq_execute_quality_snapshot_retention(
                  ?, ?, ?, ?, ?, ?, ?)
                """, String.class,
                executionId,
                resultEventId,
                snapshotId,
                snapshotImmutableHash,
                scopeDigest,
                authorityEvidenceId,
                RETENTION_TRACE_ID);
    }

    private static void seedEvidence(
            JdbcTemplate jdbc,
            UUID evidenceId,
            UUID snapshotId,
            String immutableHash,
            String scopeDigest,
            Instant trustedObservedAt,
            Instant issuedAt,
            Instant expiresAt,
            Instant consumedAt,
            UUID executionId,
            String evidenceCopyStatus) {
        Instant checkedAt = trustedObservedAt;
        jdbc.update("""
                insert into ingestion_quality
                  .iq_quality_snapshot_retention_authority_evidence
                  (authority_evidence_id, authority_ref, snapshot_id,
                   snapshot_immutable_hash, scope_digest, registry_version,
                   registry_digest, members_digest, legal_hold_clear,
                   legal_hold_checked_scope_digest, legal_hold_checked_at,
                   consumer_attestations_payload_utf8, consumer_attestations_digest,
                   watermarks_checked_at, evidence_copy_status, evidence_copy_digest,
                   trusted_observed_at, issued_at, expires_at, consumed_at, execution_id,
                   runtime_evidence_claim, verification_status)
                values (?, ?,
                        ?, ?, ?, 'QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0',
                        ?, ?, true, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'production-verified', 'verified')
                """,
                evidenceId,
                "consumer-registry-authority://production/quality-snapshot/" + evidenceId,
                snapshotId,
                immutableHash,
                scopeDigest,
                REGISTRY_DIGEST,
                MEMBERS_DIGEST,
                scopeDigest,
                Timestamp.from(checkedAt),
                ATTESTATIONS,
                ATTESTATIONS_DIGEST,
                Timestamp.from(checkedAt),
                evidenceCopyStatus,
                EVIDENCE_COPY_DIGEST,
                Timestamp.from(trustedObservedAt),
                Timestamp.from(issuedAt),
                Timestamp.from(expiresAt),
                consumedAt == null ? null : Timestamp.from(consumedAt),
                executionId);
    }

    private static void seedAssessedSnapshot(JdbcTemplate jdbc) {
        jdbc.update("""
                insert into ingestion_quality.iq_data_batch
                  (batch_id, source_id, business_key_utf8, business_key_digest,
                   source_version, lineage_id, effective_at, declared_manifest_digest,
                   status, aggregate_version, received_at, trace_id)
                values (?, ?, ?, encode(sha256(?), 'hex'), 1, ?, ?, ?,
                        'receiving', 1, ?, ?)
                """,
                BATCH_ID,
                SOURCE_ID,
                BUSINESS_KEY,
                BUSINESS_KEY,
                LINEAGE_ID,
                Timestamp.from(RECEIVED_AT.minusSeconds(60)),
                digest('2'),
                Timestamp.from(RECEIVED_AT),
                TRACE_ID);
        jdbc.update("""
                update ingestion_quality.iq_data_batch
                   set status='sealed', aggregate_version=2,
                       record_count=0, valid_record_count=0, rejected_record_count=0,
                       observation_start_at=?, observation_end_at=?, cutoff_at=?,
                       business_timezone='Asia/Shanghai', watermark_utf8=?,
                       source_schema_version='CARD-1.0.0', source_schema_digest=?,
                       data_catalog_version='CATALOG-1.0.0', data_catalog_digest=?,
                       quality_gate_version='QG-1.0.0', quality_gate_digest=?,
                       qmdp_version='QMDP-1.0.0', qmdp_digest=?,
                       source_occurred_at=?, scheduled_due_at=?,
                       lane_id='retention-lineage-fixture',
                       sealed_contract_evidence='{\"fixture\":true}'::jsonb,
                       sealed_at=?
                 where batch_id=?
                """,
                Timestamp.from(RECEIVED_AT.minusSeconds(30)),
                Timestamp.from(RECEIVED_AT.minusSeconds(10)),
                Timestamp.from(RECEIVED_AT.minusSeconds(10)),
                WATERMARK,
                digest('3'),
                digest('4'),
                digest('5'),
                digest('6'),
                Timestamp.from(RECEIVED_AT.minusSeconds(20)),
                Timestamp.from(RECEIVED_AT.plusSeconds(300)),
                Timestamp.from(SEALED_AT),
                BATCH_ID);
        jdbc.update("""
                insert into ingestion_quality.iq_quality_snapshot
                  (snapshot_id, batch_id, domain_tag, hash_profile_version,
                   hash_profile_digest, source_id, assessed_batch_status, overall_result,
                   observation_start_at, observation_end_at, cutoff_at, watermark_utf8,
                   source_owner_ref, approval_ref, effective_at,
                   retention_schedule_version, qmdp_version, qmdp_digest,
                   quality_gate_version, quality_gate_digest, canonicalization_profile,
                   manifest_digest, source_schema_version, source_schema_digest,
                   lineage_id, evaluated_at, trace_id, aggregate_version,
                   immutable_hash, retention_due_at, legal_hold, retention_scope_digest)
                select ?, batch_id,
                       'scholarsense.ingestion-quality.quality-snapshot.immutable-hash.v1',
                       'QSHM-1.0.0', ?, source_id, 'quality-passed', 'quality-passed',
                       observation_start_at, observation_end_at, cutoff_at, watermark_utf8,
                       'urn:scholarsense:owner:registrar', 'AUTH-2026-08-08-001', ?,
                       'RS-1.0.0', qmdp_version, qmdp_digest,
                       quality_gate_version, quality_gate_digest,
                       'SCHOLARSENSE-CANONICAL-JSON-1.0.0', declared_manifest_digest,
                       source_schema_version, source_schema_digest, lineage_id, ?, trace_id, 3,
                       ?, ?, false, ?
                  from ingestion_quality.iq_data_batch
                 where batch_id=?
                """,
                SNAPSHOT_ID,
                digest('7'),
                Timestamp.from(RECEIVED_AT.minusSeconds(60)),
                Timestamp.from(EVALUATED_AT),
                IMMUTABLE_HASH,
                Timestamp.from(RETENTION_DUE_AT),
                RETENTION_SCOPE,
                BATCH_ID);
        for (int ordinal = 0; ordinal < 2; ordinal++) {
            jdbc.update("""
                    insert into ingestion_quality.iq_quality_snapshot_metric
                      (snapshot_id, metric_ordinal, metric_id, formula_id, formula_version,
                       result, applicable, numerator, denominator, value_basis_points,
                       unit, operator, threshold_numerator, threshold_denominator,
                       boundary, reason_code)
                    values (?, ?, ?, ?, '1.0.0', 'passed', true, 1, 1, 10000,
                            'basis-point', '>=', 1, 1, 'inclusive', null)
                    """, SNAPSHOT_ID, ordinal, "RETENTION-LINEAGE-" + ordinal,
                    "QMDP-1.0.0/RETENTION-LINEAGE/" + ordinal);
        }
        assertEquals(1, jdbc.update("""
                update ingestion_quality.iq_data_batch
                   set status='quality-passed', aggregate_version=3, evaluated_at=?
                 where batch_id=?
                """, Timestamp.from(EVALUATED_AT), BATCH_ID));
    }

    private static void ensureRetentionLogin() {
        admin().execute("""
                do $role_test$
                begin
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname=
                                      'scholarsense_iq_ret_lineage_executor_test_login') then
                        create role scholarsense_iq_ret_lineage_executor_test_login
                            login inherit;
                    end if;
                end
                $role_test$;
                alter role scholarsense_iq_ret_lineage_executor_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                revoke scholarsense_ingestion_quality_online,
                       scholarsense_ingestion_quality_quality_worker,
                       scholarsense_ingestion_quality_relay,
                       scholarsense_ingestion_quality_retention_executor,
                       scholarsense_ingestion_quality_batch_owner
                    from scholarsense_iq_ret_lineage_executor_test_login;
                grant scholarsense_ingestion_quality_retention_executor
                    to scholarsense_iq_ret_lineage_executor_test_login
                    with inherit true, set false;
                """);
    }

    private static void assertDatabaseFailure(String messageFragment, Runnable command) {
        DataAccessException failure = assertThrows(DataAccessException.class, command::run);
        String message = failure.getMostSpecificCause().getMessage();
        assertNotNull(message);
        assertTrue(message.toLowerCase().contains(messageFragment.toLowerCase()), message);
    }

    private static void assertUuidV7(UUID value) {
        assertEquals(7, value.version());
        assertEquals(2, value.variant());
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(dataSource(required("scholarsense.audit.pg.user")));
    }

    private static JdbcTemplate workload(String username) {
        return new JdbcTemplate(dataSource(username));
    }

    private static DataSource dataSource(String username) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(required("scholarsense.audit.pg.url"));
        source.setUsername(username);
        source.setPassword(System.getProperty("scholarsense.audit.pg.password", ""));
        return source;
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required");
        return value;
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
