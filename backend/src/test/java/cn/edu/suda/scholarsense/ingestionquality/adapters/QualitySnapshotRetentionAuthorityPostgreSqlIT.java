package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionScopeCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * PostgreSQL 18.4 contract for the Task 3 production retention-authority seam.
 *
 * <p>Production success authority is ingested only through its independent workload role;
 * administrator rows below are bounded negative, chronology, fairness, and race fixtures.
 */
@TestMethodOrder(OrderAnnotation.class)
class QualitySnapshotRetentionAuthorityPostgreSqlIT {
    private static final String BATCH_OWNER =
            "scholarsense_ingestion_quality_batch_owner";
    private static final String ONLINE = "scholarsense_ingestion_quality_online";
    private static final String QUALITY_WORKER =
            "scholarsense_ingestion_quality_quality_worker";
    private static final String RELAY = "scholarsense_ingestion_quality_relay";
    private static final String RETENTION =
            "scholarsense_ingestion_quality_retention_executor";
    private static final String AUTHORITY =
            "scholarsense_ingestion_quality_consumer_registry_authority";

    private static final String WORKER_LOGIN =
            "scholarsense_iq_ret_auth_worker_test_login";
    private static final String RETENTION_LOGIN =
            "scholarsense_iq_ret_auth_executor_test_login";
    private static final String AUTHORITY_LOGIN =
            "scholarsense_iq_ret_auth_authority_test_login";

    private static final UUID BATCH_ID = uuid("019fe530-0000-7000-8000-000000000001");
    private static final UUID LINEAGE_ID = uuid("019fe530-0000-7000-8000-000000000002");
    private static final UUID SNAPSHOT_ID = uuid("019fe530-0000-7000-8000-000000000003");
    private static final UUID FAIR_BATCH_ID = uuid("019fe531-0000-7000-8000-000000000001");
    private static final UUID FAIR_LINEAGE_ID = uuid("019fe531-0000-7000-8000-000000000002");
    private static final UUID FAIR_SNAPSHOT_ID = uuid("019fe531-0000-7000-8000-000000000003");
    private static final UUID RACE_BATCH_ID = uuid("019fe532-0000-7000-8000-000000000001");
    private static final UUID RACE_LINEAGE_ID = uuid("019fe532-0000-7000-8000-000000000002");
    private static final UUID RACE_SNAPSHOT_ID = uuid("019fe532-0000-7000-8000-000000000003");
    private static final UUID CHRONOLOGY_BATCH_ID =
            uuid("019fe533-0000-7000-8000-000000000001");
    private static final UUID CHRONOLOGY_LINEAGE_ID =
            uuid("019fe533-0000-7000-8000-000000000002");
    private static final UUID CHRONOLOGY_SNAPSHOT_ID =
            uuid("019fe533-0000-7000-8000-000000000003");
    private static final String SOURCE_ID = "SRC-P0-CARD-001";
    private static final int EXPECTED_MEASUREMENT_COUNT = 14;
    private static final byte[] BUSINESS_KEY =
            "retention-authority-fixture\0\u6743\u5a01".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WATERMARK =
            "src-p0-card-001@2026-08-10".getBytes(StandardCharsets.UTF_8);
    private static final String TRACE = "123456789abcdef0123456789abcdef0";
    private static final String RETENTION_TRACE = "abcdef0123456789abcdef0123456789";
    private static final String MANIFEST_DIGEST = digest('a');
    private static final String SCHEMA_DIGEST =
            DataBatchPostgreSqlEvidenceFixtures.CARD.schemaBinding().canonicalDigest();
    private static final String CATALOG_DIGEST = DataBatchPostgreSqlEvidenceFixtures.CONTRACT
            .policy().controlledInputs().dataCatalog().canonicalDigest();
    private static final String GATE_DIGEST = DataBatchPostgreSqlEvidenceFixtures.CONTRACT
            .policy().controlledInputs().qualityGate().canonicalDigest();
    private static final String QMDP_DIGEST = DataBatchPostgreSqlEvidenceFixtures.CONTRACT
            .attestation().qmdpPolicyCanonicalDigest();
    private static final String OTHER_SCOPE = digest('2');
    private static final String MEMBERS_DIGEST = prefixedSha256("[]");
    private static final String REGISTRY_DIGEST = prefixedSha256(
            "{\"members\":[],\"registryVersion\":"
                    + "\"QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0\"}");
    private static final String EVIDENCE_COPY_DIGEST = MEMBERS_DIGEST;
    private static final byte[] ATTESTATIONS =
            "{\"consumers\":[]}".getBytes(StandardCharsets.UTF_8);
    private static final String ATTESTATIONS_DIGEST = sha256(ATTESTATIONS);
    private static final byte[] EVENT_PAYLOAD = "{}".getBytes(StandardCharsets.UTF_8);
    private static final String EVENT_PAYLOAD_DIGEST = sha256(EVENT_PAYLOAD);
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-09T20:00:00Z");
    private static final Instant CUTOFF_AT = RECEIVED_AT.minusSeconds(10);
    private static final Instant OBSERVATION_START_AT =
            CUTOFF_AT.minusSeconds(720L * 3_600L);
    private static final Instant SEALED_AT = Instant.parse("2026-08-09T20:10:00Z");
    private static final Instant EVALUATED_AT = Instant.parse("2026-08-09T21:00:00Z");
    private static final Instant RETENTION_DUE_AT = Instant.parse("2028-08-09T21:00:00Z");
    private static final Instant TRUSTED_OBSERVED_AT =
            Instant.parse("2028-08-09T21:30:00Z");
    private static final Instant AUTHORITY_ISSUED_AT =
            Instant.parse("2026-08-09T19:00:00Z");
    private static final Instant AUTHORITY_EXPIRES_AT =
            Instant.parse("2028-08-11T00:00:00Z");
    private static final String IMMUTABLE_HASH =
            DataBatchPostgreSqlEvidenceFixtures.immutableHash(
                    BATCH_ID,
                    LINEAGE_ID,
                    SNAPSHOT_ID,
                    OBSERVATION_START_AT,
                    CUTOFF_AT,
                    CUTOFF_AT,
                    WATERMARK,
                    List.of(),
                    MANIFEST_DIGEST,
                    EVALUATED_AT,
                    TRACE);
    private static final String RETENTION_SCOPE =
            QualitySnapshotRetentionScopeCanonicalizer.digest(
                    new QualitySnapshotRetentionScopeCanonicalizer.Material(
                            "QualitySnapshot", SNAPSHOT_ID, SOURCE_ID, 3L,
                            EVALUATED_AT, RETENTION_DUE_AT, IMMUTABLE_HASH,
                            "QUALITY-SNAPSHOT-RETENTION-1.0.0", "RS-1.0.0"));
    private static final Instant FAIR_EVALUATED_AT = Instant.parse("2024-08-09T20:00:00Z");
    private static final Instant FAIR_DUE_AT = Instant.parse("2026-08-09T20:00:00Z");
    private static final String FAIR_HASH = digest('b');
    private static final String FAIR_SCOPE = directScope(
            FAIR_SNAPSHOT_ID, FAIR_EVALUATED_AT, FAIR_DUE_AT, FAIR_HASH);
    private static final Instant RACE_EVALUATED_AT = Instant.parse("2024-08-09T23:10:00Z");
    private static final Instant RACE_DUE_AT = Instant.parse("2026-08-09T23:10:00Z");
    private static final String RACE_HASH = digest('c');
    private static final String RACE_SCOPE = directScope(
            RACE_SNAPSHOT_ID, RACE_EVALUATED_AT, RACE_DUE_AT, RACE_HASH);
    private static final Instant CHRONOLOGY_EVALUATED_AT =
            Instant.parse("2024-08-09T19:20:00Z");
    private static final Instant CHRONOLOGY_DUE_AT =
            Instant.parse("2026-08-09T19:20:00Z");
    private static final String CHRONOLOGY_HASH = digest('d');
    private static final String CHRONOLOGY_SCOPE = directScope(
            CHRONOLOGY_SNAPSHOT_ID, CHRONOLOGY_EVALUATED_AT,
            CHRONOLOGY_DUE_AT, CHRONOLOGY_HASH);
    private static final UUID CONFORMANCE_EVIDENCE_ID =
            uuid("019fe530-0000-7000-8000-000000000100");
    private static final UUID EXPIRED_EVIDENCE_ID =
            uuid("019fe530-0000-7000-8000-000000000101");
    private static final UUID MISMATCHED_EVIDENCE_ID =
            uuid("019fe530-0000-7000-8000-000000000102");
    private static final UUID CONSUMED_EVIDENCE_ID =
            uuid("019fe530-0000-7000-8000-000000000103");
    private static final UUID VALID_EVIDENCE_ID =
            uuid("019fe530-0000-7000-8000-000000000104");
    private static final UUID NEGATIVE_EVIDENCE_ID =
            uuid("019fe530-0000-7000-8000-000000000105");
    private static final UUID FAIR_EVIDENCE_ID =
            uuid("019fe531-0000-7000-8000-000000000104");
    private static final UUID RACE_EXECUTION_EVIDENCE_ID =
            uuid("019fe532-0000-7000-8000-000000000104");
    private static final UUID RACE_ALIAS_ID =
            uuid("019fe532-0000-7000-8000-000000000105");
    private static final UUID PRE_SNAPSHOT_EVIDENCE_ID =
            uuid("019fe533-0000-7000-8000-000000000104");
    private static final UUID PRE_DUE_CHECK_EVIDENCE_ID =
            uuid("019fe533-0000-7000-8000-000000000105");
    private static final UUID FUTURE_BLOCKED_EVIDENCE_ID =
            uuid("019fe533-0000-7000-8000-000000000106");
    private static final UUID FUTURE_MISSING_EVIDENCE_ID =
            uuid("019fe533-0000-7000-8000-000000000107");
    private static final UUID FUTURE_ALIAS_PROBE_EVIDENCE_ID =
            uuid("019fe533-0000-7000-8000-000000000108");

    private static final UUID MISSING_RESULT_ID =
            uuid("019fe530-0000-7000-8000-000000000200");
    private static final UUID EXPIRED_RESULT_ID =
            uuid("019fe530-0000-7000-8000-000000000201");
    private static final UUID MISMATCHED_RESULT_ID =
            uuid("019fe530-0000-7000-8000-000000000202");
    private static final UUID CONSUMED_RESULT_ID =
            uuid("019fe530-0000-7000-8000-000000000203");
    private static final UUID COLLISION_RESULT_ID =
            uuid("019fe530-0000-7000-8000-000000000204");
    private static final UUID COMPLETED_RESULT_ID =
            uuid("019fe530-0000-7000-8000-000000000205");
    private static final UUID NEGATIVE_RESULT_ID =
            uuid("019fe530-0000-7000-8000-000000000206");
    private static final UUID FAIR_RESULT_ID =
            uuid("019fe531-0000-7000-8000-000000000205");
    private static final UUID RACE_CLEANUP_RESULT_ID =
            uuid("019fe532-0000-7000-8000-000000000206");
    private static final UUID PRE_SNAPSHOT_RESULT_ID =
            uuid("019fe533-0000-7000-8000-000000000204");
    private static final UUID PRE_DUE_CHECK_RESULT_ID =
            uuid("019fe533-0000-7000-8000-000000000205");
    private static final UUID FUTURE_BLOCKED_RESULT_ID =
            uuid("019fe533-0000-7000-8000-000000000206");
    private static final UUID FUTURE_MISSING_RESULT_ID =
            uuid("019fe533-0000-7000-8000-000000000207");

    @Test
    @Order(1)
    void appendOnlyAuthorityEvidenceShapeSignatureAndPrivilegesAreExact() {
        JdbcTemplate jdbc = admin();
        Set<String> expectedColumns = Set.of(
                "authority_evidence_id",
                "authority_ref",
                "snapshot_id",
                "snapshot_immutable_hash",
                "scope_digest",
                "registry_version",
                "registry_digest",
                "members_digest",
                "legal_hold_clear",
                "legal_hold_checked_scope_digest",
                "legal_hold_checked_at",
                "consumer_attestations_payload_utf8",
                "consumer_attestations_digest",
                "watermarks_checked_at",
                "evidence_copy_status",
                "evidence_copy_digest",
                "trusted_observed_at",
                "issued_at",
                "expires_at",
                "consumed_at",
                "execution_id",
                "runtime_evidence_claim",
                "verification_status");
        Set<String> actualColumns = Set.copyOf(jdbc.queryForList("""
                select column_name
                  from information_schema.columns
                 where table_schema='ingestion_quality'
                   and table_name='iq_quality_snapshot_retention_authority_evidence'
                """, String.class));
        assertEquals(expectedColumns, actualColumns,
                "the append-only authority record must stay minimal and independently auditable");

        assertEquals("uuid", jdbc.queryForObject("""
                select data_type
                  from information_schema.columns
                 where table_schema='ingestion_quality'
                   and table_name='iq_quality_snapshot_retention_authority_evidence'
                   and column_name='authority_evidence_id'
                """, String.class));
        assertEquals("bytea", jdbc.queryForObject("""
                select data_type
                  from information_schema.columns
                 where table_schema='ingestion_quality'
                   and table_name='iq_quality_snapshot_retention_authority_evidence'
                   and column_name='consumer_attestations_payload_utf8'
                """, String.class));
        assertEquals(BATCH_OWNER, jdbc.queryForObject("""
                select pg_catalog.pg_get_userbyid(relation.relowner)
                  from pg_catalog.pg_class relation
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=relation.relnamespace
                 where namespace.nspname='ingestion_quality'
                   and relation.relname='iq_quality_snapshot_retention_authority_evidence'
                """, String.class));

        String constraints = String.join("\n", jdbc.queryForList("""
                select lower(pg_catalog.pg_get_constraintdef(constraint_record.oid, true))
                  from pg_catalog.pg_constraint constraint_record
                  join pg_catalog.pg_class relation
                    on relation.oid=constraint_record.conrelid
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=relation.relnamespace
                 where namespace.nspname='ingestion_quality'
                   and relation.relname='iq_quality_snapshot_retention_authority_evidence'
                """, String.class));
        for (String token : List.of(
                "primary key (authority_evidence_id)",
                "runtime_evidence_claim",
                "production-verified",
                "verification_status",
                "verified",
                "consumer_attestations_digest",
                "sha256(consumer_attestations_payload_utf8)",
                "consumed_at",
                "execution_id",
                "expires_at",
                "issued_at")) {
            assertTrue(constraints.contains(token), "authority constraint missing " + token);
        }

        String triggerDefinitions = String.join("\n", jdbc.queryForList("""
                select lower(pg_catalog.pg_get_triggerdef(trigger_record.oid, true))
                  from pg_catalog.pg_trigger trigger_record
                  join pg_catalog.pg_class relation
                    on relation.oid=trigger_record.tgrelid
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=relation.relnamespace
                 where namespace.nspname='ingestion_quality'
                   and relation.relname='iq_quality_snapshot_retention_authority_evidence'
                   and not trigger_record.tgisinternal
                """, String.class));
        assertTrue(triggerDefinitions.contains("before delete or update"),
                "authority evidence must reject delete and all but its one-way consume stamp");

        for (String role : List.of(ONLINE, QUALITY_WORKER, RELAY, RETENTION, AUTHORITY)) {
            for (String privilege : List.of(
                    "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
                assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                                "select has_table_privilege(?,?,?)",
                                Boolean.class,
                                role,
                                "ingestion_quality."
                                        + "iq_quality_snapshot_retention_authority_evidence",
                                privilege)),
                        role + " must have zero raw authority-table " + privilege);
            }
        }

        var retentionFunctions = jdbc.queryForList("""
                select pg_catalog.oidvectortypes(procedure.proargtypes) as argument_types,
                       pg_catalog.array_to_string(procedure.proargnames, ',') as argument_names,
                       lower(pg_catalog.pg_get_functiondef(procedure.oid)) as definition,
                       procedure.prosecdef as security_definer,
                       procedure.proconfig as settings,
                       pg_catalog.pg_get_userbyid(procedure.proowner) as owner
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality'
                   and procedure.proname='iq_execute_quality_snapshot_retention'
                """);
        assertEquals(1, retentionFunctions.size(), "no legacy self-reported overload may remain");
        var retentionFunction = retentionFunctions.getFirst();
        assertEquals(
                "uuid, uuid, uuid, character, character, uuid, character",
                retentionFunction.get("argument_types"));
        assertEquals(
                "requested_execution_id,requested_result_event_id,requested_snapshot_id,"
                        + "requested_snapshot_immutable_hash,requested_scope_digest,"
                        + "requested_authority_evidence_id,requested_trace_id",
                retentionFunction.get("argument_names"));
        assertEquals(true, retentionFunction.get("security_definer"));
        assertEquals(BATCH_OWNER, retentionFunction.get("owner"));
        assertTrue(String.valueOf(retentionFunction.get("settings"))
                .contains("search_path=pg_catalog"));

        String function = (String) retentionFunction.get("definition");
        for (String token : List.of(
                "iq_quality_snapshot_retention_authority_evidence",
                "requested_authority_evidence_id",
                "for update",
                "runtime_evidence_claim",
                "verification_status",
                "expires_at",
                "consumed_at",
                "execution_id",
                "delete from ingestion_quality.iq_quality_snapshot_metric",
                "delete from ingestion_quality.iq_quality_snapshot",
                "iq_quality_snapshot_deletion_result",
                "iq_batch_quality_outbox")) {
            assertTrue(function.contains(token), "retention boundary missing " + token);
        }
        for (String forbidden : List.of(
                "requested_trusted_now",
                "requested_legal_hold_clear",
                "requested_legal_hold_scope_digest",
                "requested_consumer_registry_status",
                "requested_consumer_registry_digest",
                "requested_watermark_status",
                "requested_evidence_copy_status",
                "requested_runtime_evidence_claim")) {
            assertFalse(function.contains(forbidden),
                    "caller must not self-report authority guard " + forbidden);
        }

        for (String role : List.of("public", ONLINE, QUALITY_WORKER, RELAY, AUTHORITY)) {
            assertFalse(canExecute(jdbc, role), role + " must not execute retention");
        }
        assertTrue(canExecute(jdbc, RETENTION));
        assertEquals(1, jdbc.queryForObject("""
                select count(*)
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality'
                   and procedure.proname like 'iq_%retention_authority%'
                   and procedure.prosecdef
                """, Integer.class),
                "only the closed production authority ingest may be a security definer");
        assertTrue(canExecute(jdbc, AUTHORITY,
                "iq_ingest_quality_snapshot_retention_authority"));
        assertFalse(canExecute(jdbc, RETENTION,
                "iq_ingest_quality_snapshot_retention_authority"));

        Set<String> deletionResultColumns = Set.copyOf(jdbc.queryForList("""
                select column_name
                  from information_schema.columns
                 where table_schema='ingestion_quality'
                   and table_name='iq_quality_snapshot_deletion_result'
                """, String.class));
        assertTrue(deletionResultColumns.containsAll(Set.of(
                        "execution_id",
                        "authority_evidence_id",
                        "aggregate_version",
                        "supersedes_result_event_id")),
                "the frozen replay and direct-successor evidence must be durable");
    }

    @Test
    @Order(2)
    void missingConformanceExpiredMismatchedAndConsumedEvidenceOnlyBlock() {
        ensureWorkloadLogins();
        JdbcTemplate admin = admin();
        JdbcTemplate worker = workload(WORKER_LOGIN);
        JdbcTemplate retention = workload(RETENTION_LOGIN);
        createAssessedSnapshot(worker);

        assertDatabaseFailure("permission denied", () -> retention.update("""
                insert into ingestion_quality.iq_quality_snapshot_retention_authority_evidence
                default values
                """));

        assertDatabaseFailure("check constraint", () -> seedEvidence(
                admin,
                CONFORMANCE_EVIDENCE_ID,
                RETENTION_SCOPE,
                AUTHORITY_ISSUED_AT,
                AUTHORITY_EXPIRES_AT,
                null,
                null,
                "none",
                "contract-conformance"));
        assertEquals("blocked", executeRetention(
                retention,
                SNAPSHOT_ID,
                MISSING_RESULT_ID,
                CONFORMANCE_EVIDENCE_ID));
        assertBlocked(admin, MISSING_RESULT_ID, 1L, null,
                "CONSUMER_REGISTRY_UNAVAILABLE");
        assertBlockerCodes(admin, MISSING_RESULT_ID, List.of(
                "CONSUMER_REGISTRY_UNAVAILABLE", "RETENTION_NOT_DUE"));
        assertSnapshotPresent(admin);

        seedEvidence(
                admin,
                EXPIRED_EVIDENCE_ID,
                RETENTION_SCOPE,
                Instant.parse("2026-08-08T00:00:00Z"),
                Instant.parse("2026-08-09T00:00:00Z"),
                null,
                null,
                "production-verified",
                "verified");
        assertEquals("blocked", executeRetention(
                retention,
                SNAPSHOT_ID,
                EXPIRED_RESULT_ID,
                EXPIRED_EVIDENCE_ID));
        assertBlocked(admin, EXPIRED_RESULT_ID, 2L, MISSING_RESULT_ID,
                "CONSUMER_REGISTRY_UNAVAILABLE");
        assertBlockerCodes(admin, EXPIRED_RESULT_ID, List.of(
                "CONSUMER_REGISTRY_UNAVAILABLE", "RETENTION_NOT_DUE"));
        assertEvidenceUnconsumed(admin, EXPIRED_EVIDENCE_ID);
        assertSnapshotPresent(admin);

        seedEvidence(
                admin,
                MISMATCHED_EVIDENCE_ID,
                OTHER_SCOPE,
                AUTHORITY_ISSUED_AT,
                AUTHORITY_EXPIRES_AT,
                null,
                null,
                "production-verified",
                "verified");
        assertEquals("blocked", executeRetention(
                retention,
                SNAPSHOT_ID,
                MISMATCHED_RESULT_ID,
                MISMATCHED_EVIDENCE_ID));
        assertBlocked(admin, MISMATCHED_RESULT_ID, 3L, EXPIRED_RESULT_ID,
                "CONSUMER_REGISTRY_UNAVAILABLE");
        assertBlockerCodes(admin, MISMATCHED_RESULT_ID, List.of(
                "CONSUMER_REGISTRY_UNAVAILABLE", "RETENTION_NOT_DUE"));
        assertEvidenceUnconsumed(admin, MISMATCHED_EVIDENCE_ID);
        assertSnapshotPresent(admin);

        UUID alreadyConsumedBy = uuid("019fe530-0000-7000-8000-000000000303");
        seedEvidence(
                admin,
                CONSUMED_EVIDENCE_ID,
                RETENTION_SCOPE,
                AUTHORITY_ISSUED_AT,
                AUTHORITY_EXPIRES_AT,
                TRUSTED_OBSERVED_AT.minusSeconds(1),
                alreadyConsumedBy,
                "production-verified",
                "verified");
        assertEquals("blocked", executeRetention(
                retention,
                SNAPSHOT_ID,
                CONSUMED_RESULT_ID,
                CONSUMED_EVIDENCE_ID));
        assertBlocked(admin, CONSUMED_RESULT_ID, 4L, MISMATCHED_RESULT_ID,
                "CONSUMER_REGISTRY_UNAVAILABLE");
        assertBlockerCodes(admin, CONSUMED_RESULT_ID, List.of(
                "CONSUMER_REGISTRY_UNAVAILABLE", "RETENTION_NOT_DUE"));
        assertEquals(alreadyConsumedBy, admin.queryForObject("""
                select execution_id
                  from ingestion_quality.iq_quality_snapshot_retention_authority_evidence
                 where authority_evidence_id=?
                """, UUID.class, CONSUMED_EVIDENCE_ID));
        assertSnapshotPresent(admin);
    }

    @Test
    @Order(3)
    void trustedNegativeAuthorityStaysAvailableAndMapsEveryExactBlocker() {
        ensureWorkloadLogins();
        JdbcTemplate admin = admin();
        JdbcTemplate authority = workload(AUTHORITY_LOGIN);
        JdbcTemplate retention = workload(RETENTION_LOGIN);
        createAssessedSnapshotIfAbsent(workload(WORKER_LOGIN), admin);

        byte[] payload = negativeAuthorityPayload(admin, NEGATIVE_EVIDENCE_ID)
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(NEGATIVE_EVIDENCE_ID, authority.queryForObject("""
                select ingestion_quality.iq_ingest_quality_snapshot_retention_authority(
                  ?, ?, ?)
                """, UUID.class, NEGATIVE_EVIDENCE_ID, payload, sha256(payload)));
        assertEquals("blocked", executeRetention(
                retention, SNAPSHOT_ID, NEGATIVE_RESULT_ID, NEGATIVE_EVIDENCE_ID));
        assertBlocked(admin, NEGATIVE_RESULT_ID, 5L, CONSUMED_RESULT_ID,
                "CONSUMER_WATERMARK_BEHIND");
        assertEquals(List.of(
                "CONSUMER_WATERMARK_BEHIND",
                "CONSUMER_WATERMARK_MISSING",
                "CONSUMER_WATERMARK_UNKNOWN",
                "EVIDENCE_COPY_ACK_MISSING",
                "WATERMARK_DEPENDENCY_UNAVAILABLE"), admin.queryForList("""
                select jsonb_array_elements_text(
                         convert_from(payload_utf8, 'UTF8')::jsonb #> '{data,blockerCodes}')
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id=?
                """, String.class, NEGATIVE_RESULT_ID));
        assertEquals("available", admin.queryForObject("""
                select convert_from(payload_utf8, 'UTF8')::jsonb
                         #>> '{data,guards,consumerRegistry,status}'
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id=?
                """, String.class, NEGATIVE_RESULT_ID));
        var consumed = admin.queryForMap("""
                select consumed_at, execution_id
                  from ingestion_quality.iq_quality_snapshot_retention_authority_evidence
                 where authority_evidence_id=?
                """, NEGATIVE_EVIDENCE_ID);
        assertNotNull(consumed.get("consumed_at"));
        assertEquals(SNAPSHOT_ID, consumed.get("execution_id"));
        assertSnapshotPresent(admin);
    }

    @Test
    @Order(5)
    void blockedOldestRotatesToNeverAttemptedDueSnapshotWithoutForking() {
        JdbcTemplate admin = admin();
        JdbcTemplate retention = workload(RETENTION_LOGIN);
        seedDirectSnapshot(
                admin, FAIR_BATCH_ID, FAIR_LINEAGE_ID, FAIR_SNAPSHOT_ID,
                FAIR_EVALUATED_AT, FAIR_DUE_AT, FAIR_HASH, FAIR_SCOPE);
        Instant fairObservedAt = FAIR_DUE_AT.plusSeconds(1800);
        seedEvidenceForSnapshot(
                admin, FAIR_EVIDENCE_ID, FAIR_SNAPSHOT_ID, FAIR_HASH, FAIR_SCOPE,
                true, fairObservedAt, fairObservedAt, fairObservedAt,
                AUTHORITY_ISSUED_AT, AUTHORITY_EXPIRES_AT);

        var candidate = retention.queryForMap("""
                select execution_id, snapshot_id
                  from ingestion_quality.iq_find_next_due_quality_snapshot_retention()
                """);
        assertEquals(FAIR_SNAPSHOT_ID, candidate.get("snapshot_id"),
                "a never-attempted due snapshot must outrank the repeatedly blocked oldest one");
        assertEquals(FAIR_SNAPSHOT_ID, candidate.get("execution_id"));
        assertEquals("completed", executeRetentionForSnapshot(
                retention, FAIR_SNAPSHOT_ID, FAIR_RESULT_ID, FAIR_SNAPSHOT_ID,
                FAIR_HASH, FAIR_SCOPE, FAIR_EVIDENCE_ID, RETENTION_TRACE));
        assertEquals(0, count(admin, "iq_quality_snapshot", FAIR_SNAPSHOT_ID));
        assertSnapshotPresent(admin);

        var retry = retention.queryForMap("""
                select execution_id, snapshot_id
                  from ingestion_quality.iq_find_next_due_quality_snapshot_retention()
                """);
        assertEquals(CHRONOLOGY_SNAPSHOT_ID, retry.get("snapshot_id"));
        assertEquals(CHRONOLOGY_SNAPSHOT_ID, retry.get("execution_id"),
                "the blocked same-scope chain must retain one stable execution identity");
        assertEquals(4, admin.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where execution_id=?
                """, Integer.class, CHRONOLOGY_SNAPSHOT_ID),
                "fair rotation must not fork or append the blocked chain");
    }

    @Test
    @Order(6)
    void validFixtureAuthorityConsumesOnceAtomicallyAndDeletionResultReplaysExactly() {
        ensureWorkloadLogins();
        JdbcTemplate admin = admin();
        JdbcTemplate worker = workload(WORKER_LOGIN);
        JdbcTemplate authority = workload(AUTHORITY_LOGIN);
        JdbcTemplate retention = workload(RETENTION_LOGIN);
        createAssessedSnapshotIfAbsent(worker, admin);

        byte[] authorityPayload = emptyAuthorityPayload(
                VALID_EVIDENCE_ID, SNAPSHOT_ID, EVALUATED_AT, RETENTION_DUE_AT,
                IMMUTABLE_HASH, RETENTION_SCOPE, TRUSTED_OBSERVED_AT,
                AUTHORITY_ISSUED_AT, AUTHORITY_EXPIRES_AT)
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(VALID_EVIDENCE_ID, authority.queryForObject("""
                select ingestion_quality.iq_ingest_quality_snapshot_retention_authority(
                  ?, ?, ?)
                """, UUID.class, VALID_EVIDENCE_ID, authorityPayload,
                sha256(authorityPayload)));
        assertDatabaseFailure("INGESTION_QUALITY_RETENTION_AUTHORITY_IMMUTABLE",
                () -> admin.update("""
                        update ingestion_quality
                           .iq_quality_snapshot_retention_authority_evidence
                           set authority_ref='tampered'
                         where authority_evidence_id=?
                        """, VALID_EVIDENCE_ID));
        assertDatabaseFailure("INGESTION_QUALITY_RETENTION_AUTHORITY_IMMUTABLE",
                () -> admin.update("""
                        delete from ingestion_quality
                           .iq_quality_snapshot_retention_authority_evidence
                         where authority_evidence_id=?
                        """, VALID_EVIDENCE_ID));

        admin.update("""
                insert into ingestion_quality.iq_batch_quality_outbox
                  (event_id, aggregate_id, aggregate_version, event_type, schema_version,
                   payload_utf8, payload_digest, available_at, created_at)
                values (?, ?, 1, 'scholarsense.ingestion-quality.synthetic-collision.v1',
                        'TEST-COLLISION-1.0.0', ?, ?, ?, ?)
                """, COLLISION_RESULT_ID,
                uuid("019fe530-0000-7000-8000-000000000400"),
                EVENT_PAYLOAD, EVENT_PAYLOAD_DIGEST,
                Timestamp.from(TRUSTED_OBSERVED_AT), Timestamp.from(TRUSTED_OBSERVED_AT));
        assertDatabaseFailure("INGESTION_QUALITY_DELETION_RESULT_IDEMPOTENCY_CONFLICT",
                () -> executeRetention(
                        retention,
                        SNAPSHOT_ID,
                        COLLISION_RESULT_ID,
                        VALID_EVIDENCE_ID));
        assertEvidenceUnconsumed(admin, VALID_EVIDENCE_ID);
        assertSnapshotPresent(admin);
        assertEquals(0, admin.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id=?
                """, Integer.class, COLLISION_RESULT_ID));

        UUID executionId = SNAPSHOT_ID;
        assertEquals("completed", executeRetention(
                retention, executionId, COMPLETED_RESULT_ID, VALID_EVIDENCE_ID));

        assertEquals(0, count(admin, "iq_quality_snapshot"));
        assertEquals(0, count(admin, "iq_quality_snapshot_metric"));
        assertEquals(executionId, admin.queryForObject("""
                select execution_id
                  from ingestion_quality.iq_quality_snapshot_retention_authority_evidence
                 where authority_evidence_id=?
                """, UUID.class, VALID_EVIDENCE_ID));
        assertNotNull(admin.queryForObject("""
                select consumed_at
                  from ingestion_quality.iq_quality_snapshot_retention_authority_evidence
                 where authority_evidence_id=?
                """, Timestamp.class, VALID_EVIDENCE_ID));

        var completed = admin.queryForMap("""
                select result, authority_evidence_id, aggregate_version,
                       supersedes_result_event_id
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id=?
                """, COMPLETED_RESULT_ID);
        assertEquals("completed", completed.get("result"));
        assertEquals(VALID_EVIDENCE_ID, completed.get("authority_evidence_id"));
        assertEquals(6L, completed.get("aggregate_version"));
        assertEquals(NEGATIVE_RESULT_ID, completed.get("supersedes_result_event_id"));
        assertEquals(6L, admin.queryForObject("""
                select aggregate_version
                  from ingestion_quality.iq_batch_quality_outbox
                 where event_id=?
                """, Long.class, COMPLETED_RESULT_ID));
        var canonicalResult = admin.queryForMap("""
                select payload_utf8, payload_digest,
                       convert_from(payload_utf8, 'UTF8')::jsonb
                         #>> '{data,resultContractVersion}' as contract_version,
                       convert_from(payload_utf8, 'UTF8')::jsonb
                         #>> '{data,result}' as payload_result,
                       (convert_from(payload_utf8, 'UTF8')::jsonb
                         #> '{data,guards,consumerRegistry,authorityEvidence}')
                         ->> 'authorityEvidenceId' as payload_authority_id,
                       convert_from(payload_utf8, 'UTF8')::jsonb
                         #>> '{data,traceId}' as payload_trace_id,
                       convert_from(payload_utf8, 'UTF8')::jsonb
                         #>> '{data,occurredAt}' as payload_occurred_at
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id=?
                """, COMPLETED_RESULT_ID);
        var canonicalOutbox = admin.queryForMap("""
                select payload_utf8, payload_digest, available_at, created_at
                  from ingestion_quality.iq_batch_quality_outbox
                 where event_id=?
                """, COMPLETED_RESULT_ID);
        assertEquals("QUALITY-SNAPSHOT-DELETION-RESULT-1.1.0",
                canonicalResult.get("contract_version"));
        assertEquals("completed", canonicalResult.get("payload_result"));
        assertEquals(VALID_EVIDENCE_ID.toString(),
                canonicalResult.get("payload_authority_id"));
        assertEquals(RETENTION_TRACE, canonicalResult.get("payload_trace_id"));
        assertEquals(canonicalInstant(TRUSTED_OBSERVED_AT),
                canonicalResult.get("payload_occurred_at"));
        assertArrayEquals((byte[]) canonicalResult.get("payload_utf8"),
                (byte[]) canonicalOutbox.get("payload_utf8"));
        assertEquals(canonicalResult.get("payload_digest"),
                canonicalOutbox.get("payload_digest"));
        assertEquals(sha256((byte[]) canonicalResult.get("payload_utf8")),
                canonicalResult.get("payload_digest"));
        assertEquals(canonicalOutbox.get("available_at"),
                canonicalOutbox.get("created_at"));
        Instant outboxAvailableAt = ((Timestamp) canonicalOutbox.get("available_at"))
                .toInstant();
        Instant observedAfterCompletion = admin.queryForObject(
                "select clock_timestamp()", Timestamp.class).toInstant();
        assertFalse(outboxAvailableAt.isAfter(observedAfterCompletion));
        assertTrue(outboxAvailableAt.isBefore(TRUSTED_OBSERVED_AT),
                "future trusted time must not delay relay control availability");

        int resultCount = resultCount(admin);
        int outboxCount = deletionOutboxCount(admin);
        assertEquals("completed", executeRetention(
                retention, executionId, COMPLETED_RESULT_ID, VALID_EVIDENCE_ID));
        assertEquals(resultCount, resultCount(admin),
                "same canonical event identity must replay without another result");
        assertEquals(outboxCount, deletionOutboxCount(admin),
                "same canonical event identity must replay without another outbox row");

        assertDatabaseFailure("INGESTION_QUALITY_DELETION_RESULT_IDEMPOTENCY_CONFLICT",
                () -> executeRetentionWithTrace(
                        retention,
                        executionId,
                        COMPLETED_RESULT_ID,
                        VALID_EVIDENCE_ID,
                        "ffffffffffffffffffffffffffffffff"));
        assertEquals(resultCount, resultCount(admin));
        assertEquals(outboxCount, deletionOutboxCount(admin));
    }

    @Test
    @Order(7)
    void authorityAndResultIdentityRaceHasExactlyOneWinnerAndNoAlias() throws Exception {
        JdbcTemplate admin = admin();
        seedDirectSnapshot(
                admin, RACE_BATCH_ID, RACE_LINEAGE_ID, RACE_SNAPSHOT_ID,
                RACE_EVALUATED_AT, RACE_DUE_AT, RACE_HASH, RACE_SCOPE);
        Instant raceObservedAt = RACE_DUE_AT.plusSeconds(1800);
        seedEvidenceForSnapshot(
                admin, RACE_EXECUTION_EVIDENCE_ID, RACE_SNAPSHOT_ID,
                RACE_HASH, RACE_SCOPE, true, raceObservedAt, raceObservedAt,
                raceObservedAt, AUTHORITY_ISSUED_AT, AUTHORITY_EXPIRES_AT);
        byte[] aliasPayload = emptyAuthorityPayload(
                RACE_ALIAS_ID, RACE_SNAPSHOT_ID, RACE_EVALUATED_AT,
                RACE_DUE_AT, RACE_HASH, RACE_SCOPE, raceObservedAt,
                AUTHORITY_ISSUED_AT, AUTHORITY_EXPIRES_AT)
                .getBytes(StandardCharsets.UTF_8);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RaceOutcome> ingest = executor.submit(() -> raceAuthorityIngest(
                    ready, start, RACE_ALIAS_ID, aliasPayload));
            Future<RaceOutcome> execute = executor.submit(() -> raceRetentionExecute(
                    ready, start, RACE_ALIAS_ID));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "race workers did not become ready");
            start.countDown();
            RaceOutcome ingestOutcome = ingest.get(20, TimeUnit.SECONDS);
            RaceOutcome executeOutcome = execute.get(20, TimeUnit.SECONDS);
            assertTrue(ingestOutcome.success() ^ executeOutcome.success(),
                    "the shared identity namespace must permit exactly one writer: "
                            + ingestOutcome + " / " + executeOutcome);
            RaceOutcome loser = ingestOutcome.success() ? executeOutcome : ingestOutcome;
            assertTrue(loser.error().contains("IDENTITY_CONFLICT")
                            || loser.error().contains("AUTHORITY_ID_CONFLICT"),
                    loser.error());
        }

        int authorityAlias = admin.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_quality_snapshot_retention_authority_evidence
                 where authority_evidence_id=?
                """, Integer.class, RACE_ALIAS_ID);
        int resultAlias = admin.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id=?
                """, Integer.class, RACE_ALIAS_ID);
        assertEquals(1, authorityAlias + resultAlias);
        assertFalse(authorityAlias == 1 && resultAlias == 1,
                "authorityEvidenceId may never alias any deletion result eventId");

        if (authorityAlias == 1) {
            assertEquals("completed", executeRetentionForSnapshot(
                    workload(RETENTION_LOGIN), RACE_SNAPSHOT_ID,
                    RACE_CLEANUP_RESULT_ID, RACE_SNAPSHOT_ID, RACE_HASH,
                    RACE_SCOPE, RACE_ALIAS_ID, RETENTION_TRACE));
        }
        assertEquals(0, count(admin, "iq_quality_snapshot", RACE_SNAPSHOT_ID));
    }

    @Test
    @Order(4)
    void authorityChronologyBoundsPreventDeletionAndUnavailableSuccessorIsMonotonic() {
        ensureWorkloadLogins();
        JdbcTemplate admin = admin();
        JdbcTemplate authority = workload(AUTHORITY_LOGIN);
        JdbcTemplate retention = workload(RETENTION_LOGIN);
        seedDirectSnapshot(
                admin, CHRONOLOGY_BATCH_ID, CHRONOLOGY_LINEAGE_ID,
                CHRONOLOGY_SNAPSHOT_ID, CHRONOLOGY_EVALUATED_AT,
                CHRONOLOGY_DUE_AT, CHRONOLOGY_HASH, CHRONOLOGY_SCOPE);

        Instant beforeEvaluation = CHRONOLOGY_EVALUATED_AT.minusMillis(1);
        byte[] preSnapshotPayload = emptyAuthorityPayload(
                PRE_SNAPSHOT_EVIDENCE_ID, CHRONOLOGY_SNAPSHOT_ID,
                CHRONOLOGY_EVALUATED_AT, CHRONOLOGY_DUE_AT,
                CHRONOLOGY_HASH, CHRONOLOGY_SCOPE, beforeEvaluation,
                beforeEvaluation.minusSeconds(60), AUTHORITY_EXPIRES_AT)
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(PRE_SNAPSHOT_EVIDENCE_ID, authority.queryForObject("""
                select ingestion_quality
                  .iq_ingest_quality_snapshot_retention_authority(?, ?, ?)
                """, UUID.class, PRE_SNAPSHOT_EVIDENCE_ID,
                preSnapshotPayload, sha256(preSnapshotPayload)));
        assertEquals(1, countAuthority(admin, PRE_SNAPSHOT_EVIDENCE_ID));
        assertEquals("blocked", executeRetentionForSnapshot(
                retention, CHRONOLOGY_SNAPSHOT_ID, PRE_SNAPSHOT_RESULT_ID,
                CHRONOLOGY_SNAPSHOT_ID, CHRONOLOGY_HASH, CHRONOLOGY_SCOPE,
                PRE_SNAPSHOT_EVIDENCE_ID, RETENTION_TRACE));
        assertBlocked(admin, PRE_SNAPSHOT_RESULT_ID,
                1L, null, "CONSUMER_REGISTRY_UNAVAILABLE");
        assertEvidenceUnconsumed(admin, PRE_SNAPSHOT_EVIDENCE_ID);
        assertEquals(1, count(admin, "iq_quality_snapshot", CHRONOLOGY_SNAPSHOT_ID));

        Instant checkedBeforeDue = CHRONOLOGY_DUE_AT.minusMillis(1);
        Instant observedAfterDue = CHRONOLOGY_DUE_AT.plusSeconds(1800);
        byte[] preDueCheckPayload = emptyAuthorityPayload(
                PRE_DUE_CHECK_EVIDENCE_ID, CHRONOLOGY_SNAPSHOT_ID,
                CHRONOLOGY_EVALUATED_AT, CHRONOLOGY_DUE_AT,
                CHRONOLOGY_HASH, CHRONOLOGY_SCOPE, checkedBeforeDue,
                observedAfterDue, AUTHORITY_ISSUED_AT, AUTHORITY_EXPIRES_AT)
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(PRE_DUE_CHECK_EVIDENCE_ID, authority.queryForObject("""
                select ingestion_quality
                  .iq_ingest_quality_snapshot_retention_authority(?, ?, ?)
                """, UUID.class, PRE_DUE_CHECK_EVIDENCE_ID,
                preDueCheckPayload, sha256(preDueCheckPayload)));
        assertEquals(1, countAuthority(admin, PRE_DUE_CHECK_EVIDENCE_ID));
        assertEquals("blocked", executeRetentionForSnapshot(
                retention, CHRONOLOGY_SNAPSHOT_ID, PRE_DUE_CHECK_RESULT_ID,
                CHRONOLOGY_SNAPSHOT_ID, CHRONOLOGY_HASH, CHRONOLOGY_SCOPE,
                PRE_DUE_CHECK_EVIDENCE_ID, RETENTION_TRACE));
        assertBlocked(admin, PRE_DUE_CHECK_RESULT_ID,
                2L, PRE_SNAPSHOT_RESULT_ID, "CONSUMER_REGISTRY_UNAVAILABLE");
        assertEvidenceUnconsumed(admin, PRE_DUE_CHECK_EVIDENCE_ID);
        assertEquals(1, count(admin, "iq_quality_snapshot", CHRONOLOGY_SNAPSHOT_ID));

        seedEvidenceForSnapshot(
                admin, FUTURE_BLOCKED_EVIDENCE_ID, CHRONOLOGY_SNAPSHOT_ID,
                CHRONOLOGY_HASH, CHRONOLOGY_SCOPE, false, TRUSTED_OBSERVED_AT,
                TRUSTED_OBSERVED_AT, TRUSTED_OBSERVED_AT, AUTHORITY_ISSUED_AT,
                AUTHORITY_EXPIRES_AT);
        assertEquals("blocked", executeRetentionForSnapshot(
                retention, CHRONOLOGY_SNAPSHOT_ID, FUTURE_BLOCKED_RESULT_ID,
                CHRONOLOGY_SNAPSHOT_ID, CHRONOLOGY_HASH, CHRONOLOGY_SCOPE,
                FUTURE_BLOCKED_EVIDENCE_ID, RETENTION_TRACE));
        assertBlocked(admin, FUTURE_BLOCKED_RESULT_ID,
                3L, PRE_DUE_CHECK_RESULT_ID, "LEGAL_HOLD_MATCHED");
        assertBlockerCodes(admin, FUTURE_BLOCKED_RESULT_ID,
                List.of("LEGAL_HOLD_MATCHED"));

        assertEquals("blocked", executeRetentionForSnapshot(
                retention, CHRONOLOGY_SNAPSHOT_ID, FUTURE_MISSING_RESULT_ID,
                CHRONOLOGY_SNAPSHOT_ID, CHRONOLOGY_HASH, CHRONOLOGY_SCOPE,
                FUTURE_MISSING_EVIDENCE_ID, RETENTION_TRACE));
        assertBlocked(admin, FUTURE_MISSING_RESULT_ID,
                4L, FUTURE_BLOCKED_RESULT_ID, "CONSUMER_REGISTRY_UNAVAILABLE");
        assertBlockerCodes(admin, FUTURE_MISSING_RESULT_ID,
                List.of("CONSUMER_REGISTRY_UNAVAILABLE"));
        var successorChronology = admin.queryForMap("""
                select occurred_at,
                       convert_from(payload_utf8, 'UTF8')::jsonb
                         #>> '{data,guards,trustedTime,observedAt}' as observed_at,
                       convert_from(payload_utf8, 'UTF8')::jsonb
                         #>> '{data,guards,legalHold,checkedAt}' as checked_at
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id=?
                """, FUTURE_MISSING_RESULT_ID);
        assertEquals(Timestamp.from(TRUSTED_OBSERVED_AT),
                successorChronology.get("occurred_at"));
        assertEquals(canonicalInstant(TRUSTED_OBSERVED_AT),
                successorChronology.get("observed_at"));
        assertEquals(canonicalInstant(TRUSTED_OBSERVED_AT),
                successorChronology.get("checked_at"));
        assertEquals(1, count(admin, "iq_quality_snapshot", CHRONOLOGY_SNAPSHOT_ID));

        int resultFootprint = admin.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where execution_id=?
                """, Integer.class, CHRONOLOGY_SNAPSHOT_ID);
        int outboxFootprint = deletionOutboxCount(admin);
        assertDatabaseFailure("INGESTION_QUALITY_RETENTION_IDENTITY_CONFLICT", () ->
                executeRetentionForSnapshot(
                        retention, CHRONOLOGY_SNAPSHOT_ID,
                        FUTURE_MISSING_EVIDENCE_ID, CHRONOLOGY_SNAPSHOT_ID,
                        CHRONOLOGY_HASH, CHRONOLOGY_SCOPE,
                        FUTURE_ALIAS_PROBE_EVIDENCE_ID, RETENTION_TRACE));
        assertEquals(resultFootprint, admin.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where execution_id=?
                """, Integer.class, CHRONOLOGY_SNAPSHOT_ID));
        assertEquals(outboxFootprint, deletionOutboxCount(admin));
        assertEquals(0, admin.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id=?
                """, Integer.class, FUTURE_MISSING_EVIDENCE_ID));
        assertEquals(0, countAuthority(admin, FUTURE_ALIAS_PROBE_EVIDENCE_ID));
        assertEquals(1, count(admin, "iq_quality_snapshot", CHRONOLOGY_SNAPSHOT_ID));
    }

    private static void createAssessedSnapshotIfAbsent(
            JdbcTemplate worker, JdbcTemplate admin) {
        if (Boolean.TRUE.equals(admin.queryForObject("""
                select exists(select 1 from ingestion_quality.iq_quality_snapshot
                               where snapshot_id=?)
                """, Boolean.class, SNAPSHOT_ID))) {
            return;
        }
        createAssessedSnapshot(worker);
    }

    private static void createAssessedSnapshot(JdbcTemplate worker) {
        UUID receiveCommand = uuid("019fe530-0000-7000-8000-000000000010");
        String receiveRequest = digest('6');
        DataBatchPostgreSqlEvidenceFixtures.AuditEnvelope receiveAudit =
                DataBatchPostgreSqlEvidenceFixtures.acceptedAudit(
                        WORKER_LOGIN,
                        receiveCommand,
                        BATCH_ID,
                        "data-batch.receive",
                        1L,
                        TRACE,
                        receiveRequest,
                        RECEIVED_AT);
        assertEquals(BATCH_ID, worker.queryForObject("""
                select batch_id from ingestion_quality.iq_receive_data_batch(
                  ?, ?, ?, ?, 1, ?, null, null, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.class,
                receiveCommand, BATCH_ID,
                SOURCE_ID, BUSINESS_KEY, LINEAGE_ID,
                Timestamp.from(RECEIVED_AT.minusSeconds(60)), MANIFEST_DIGEST,
                Timestamp.from(RECEIVED_AT), TRACE, "a".repeat(64), receiveRequest,
                receiveAudit.payload(), receiveAudit.digest()));
        recordMeasurements(worker);

        UUID sealCommand = uuid("019fe530-0000-7000-8000-000000000011");
        String sealRequest = digest('8');
        DataBatchPostgreSqlEvidenceFixtures.AuditEnvelope sealAudit =
                DataBatchPostgreSqlEvidenceFixtures.acceptedAudit(
                        WORKER_LOGIN,
                        sealCommand,
                        BATCH_ID,
                        "data-batch.seal",
                        2L,
                        TRACE,
                        sealRequest,
                        SEALED_AT);
        assertTrue(Boolean.TRUE.equals(worker.queryForObject("""
                select ingestion_quality.iq_seal_data_batch(
                  ?, ?, 1, 1, 0, 1, ?, ?, ?, 'Asia/Shanghai', ?,
                  'CARD-SLICE-1.0.0', ?, 'DCC-1.1.0', ?, 'QG-1.0.0', ?,
                  'QMDP-1.0.0', ?, ?, ?, ?, 'transaction-record', ?, ?::jsonb,
                  ?, ?, ?, ?, ?::jsonb, ?)
                """, Boolean.class,
                sealCommand, BATCH_ID,
                Timestamp.from(OBSERVATION_START_AT), Timestamp.from(CUTOFF_AT),
                Timestamp.from(CUTOFF_AT), WATERMARK,
                SCHEMA_DIGEST, CATALOG_DIGEST, GATE_DIGEST, QMDP_DIGEST,
                Timestamp.from(RECEIVED_AT.minusSeconds(20)),
                Timestamp.from(RECEIVED_AT.plusSeconds(300)),
                Timestamp.from(RECEIVED_AT), MANIFEST_DIGEST,
                DataBatchPostgreSqlEvidenceFixtures.sealedEvidenceJson(),
                Timestamp.from(SEALED_AT), TRACE, "b".repeat(64), sealRequest,
                sealAudit.payload(), sealAudit.digest())));

        JdbcTemplate jdbc = admin();
        assertEquals(1, jdbc.update("""
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
                       ?, ?, ?, 'RS-1.0.0', qmdp_version, qmdp_digest,
                       quality_gate_version, quality_gate_digest,
                       'SCHOLARSENSE-CANONICAL-JSON-1.0.0', declared_manifest_digest,
                       source_schema_version, source_schema_digest, lineage_id, ?, ?, 3,
                       ?, ?, false, ?
                  from ingestion_quality.iq_data_batch
                 where batch_id=?
                """,
                SNAPSHOT_ID,
                DataBatchPostgreSqlEvidenceFixtures.CONTRACT.attestation()
                        .qshmProfileCanonicalDigest(),
                DataBatchPostgreSqlEvidenceFixtures.CARD.owner(),
                DataBatchPostgreSqlEvidenceFixtures.CONTRACT.policy().approvalRef(),
                Timestamp.from(DataBatchPostgreSqlEvidenceFixtures.CONTRACT
                        .policy().effectiveAt()),
                Timestamp.from(EVALUATED_AT), TRACE, IMMUTABLE_HASH,
                Timestamp.from(RETENTION_DUE_AT), RETENTION_SCOPE, BATCH_ID));
        assertEquals(EXPECTED_MEASUREMENT_COUNT, jdbc.update("""
                insert into ingestion_quality.iq_quality_snapshot_metric
                  (snapshot_id, metric_ordinal, metric_id, formula_id, formula_version,
                   result, applicable, numerator, denominator, value_basis_points,
                   unit, operator, threshold_numerator, threshold_denominator,
                   boundary, reason_code)
                select ?, ordinality - 1, value ->> 'metricId', value ->> 'formulaId',
                       value ->> 'formulaVersion', value ->> 'result',
                       (value ->> 'applicable')::boolean,
                       (value ->> 'numerator')::bigint,
                       (value ->> 'denominator')::bigint,
                       case when value -> 'valueBasisPoints' = 'null'::jsonb then null
                            else (value ->> 'valueBasisPoints')::bigint end,
                       value ->> 'unit', value ->> 'operator',
                       (value ->> 'thresholdNumerator')::bigint,
                       (value ->> 'thresholdDenominator')::bigint,
                       value ->> 'boundary', value ->> 'reasonCode'
                  from jsonb_array_elements(?::jsonb) with ordinality metric(value, ordinality)
                """, SNAPSHOT_ID,
                DataBatchPostgreSqlEvidenceFixtures.PASSING_CARD.metricsJson()));
        assertEquals(1, jdbc.update("""
                update ingestion_quality.iq_data_batch
                   set status='quality-passed', aggregate_version=3, evaluated_at=?
                 where batch_id=? and status='sealed' and aggregate_version=2
                """, Timestamp.from(EVALUATED_AT), BATCH_ID));
    }

    private static void recordMeasurements(JdbcTemplate worker) {
        for (int ordinal = 0; ordinal < EXPECTED_MEASUREMENT_COUNT; ordinal++) {
            var definition = DataBatchPostgreSqlEvidenceFixtures.PASSING_CARD
                    .definitions().get(ordinal);
            var input = DataBatchPostgreSqlEvidenceFixtures.PASSING_CARD
                    .inputs().get(definition.formulaId());
            String operands = DataBatchPostgreSqlEvidenceFixtures.json(input.operands());
            assertTrue(Boolean.TRUE.equals(worker.queryForObject("""
                    select ingestion_quality.iq_record_batch_quality_measurement(
                      ?, ?, ?, ?, ?::jsonb, ?, ?)
                    """, Boolean.class, BATCH_ID, definition.formulaId(), ordinal,
                    input.applicable(), operands,
                    DataBatchPostgreSqlEvidenceFixtures.evidenceDigest(operands),
                    Timestamp.from(RECEIVED_AT.plusSeconds(1L + ordinal)))));
        }
    }

    private static void seedDirectSnapshot(
            JdbcTemplate jdbc,
            UUID batchId,
            UUID lineageId,
            UUID snapshotId,
            Instant evaluatedAt,
            Instant dueAt,
            String immutableHash,
            String scopeDigest) {
        byte[] businessKey = ("retention-authority-direct\0" + batchId)
                .getBytes(StandardCharsets.UTF_8);
        byte[] watermark = ("retention-authority-watermark\0" + snapshotId)
                .getBytes(StandardCharsets.UTF_8);
        Instant receivedAt = evaluatedAt.minusSeconds(1800);
        assertEquals(1, jdbc.update("""
                insert into ingestion_quality.iq_data_batch
                  (batch_id, source_id, business_key_utf8, business_key_digest,
                   source_version, lineage_id, effective_at, declared_manifest_digest,
                   status, aggregate_version, received_at, trace_id)
                values (?, ?, ?, encode(sha256(?), 'hex'), 1, ?, ?, ?,
                        'receiving', 1, ?, ?)
                """, batchId, SOURCE_ID, businessKey, businessKey, lineageId,
                Timestamp.from(evaluatedAt.minusSeconds(3600)), digest('d'),
                Timestamp.from(receivedAt), TRACE));
        assertEquals(1, jdbc.update("""
                update ingestion_quality.iq_data_batch
                   set status='sealed', aggregate_version=2,
                       record_count=0, valid_record_count=0, rejected_record_count=0,
                       observation_start_at=?, observation_end_at=?, cutoff_at=?,
                       business_timezone='Asia/Shanghai', watermark_utf8=?,
                       source_schema_version='CARD-1.0.0', source_schema_digest=?,
                       data_catalog_version='DCC-1.1.0', data_catalog_digest=?,
                       quality_gate_version='QG-1.0.0', quality_gate_digest=?,
                       qmdp_version='QMDP-1.0.0', qmdp_digest=?,
                       source_occurred_at=?, scheduled_due_at=?,
                       lane_id='retention-authority-direct',
                       sealed_contract_evidence='{"fixture":"retention-authority"}'::jsonb,
                       sealed_at=?
                 where batch_id=?
                """, Timestamp.from(receivedAt.plusSeconds(10)),
                Timestamp.from(receivedAt.plusSeconds(20)),
                Timestamp.from(receivedAt.plusSeconds(20)), watermark,
                digest('e'), digest('f'), digest('6'), QMDP_DIGEST,
                Timestamp.from(receivedAt), Timestamp.from(receivedAt.plusSeconds(300)),
                Timestamp.from(receivedAt.plusSeconds(60)), batchId));
        assertEquals(1, jdbc.update("""
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
                       ?, 'AUTH-2026-08-08-001', ?, 'RS-1.0.0', qmdp_version, qmdp_digest,
                       quality_gate_version, quality_gate_digest,
                       'SCHOLARSENSE-CANONICAL-JSON-1.0.0', declared_manifest_digest,
                       source_schema_version, source_schema_digest, lineage_id, ?, ?, 3,
                       ?, ?, false, ?
                  from ingestion_quality.iq_data_batch
                 where batch_id=?
                """, snapshotId, digest('7'),
                DataBatchPostgreSqlEvidenceFixtures.CARD.owner(),
                Timestamp.from(evaluatedAt.minusSeconds(3600)),
                Timestamp.from(evaluatedAt), TRACE, immutableHash,
                Timestamp.from(dueAt), scopeDigest, batchId));
        assertEquals(1, jdbc.update("""
                insert into ingestion_quality.iq_quality_snapshot_metric
                  (snapshot_id, metric_ordinal, metric_id, formula_id, formula_version,
                   result, applicable, numerator, denominator, value_basis_points,
                   unit, operator, threshold_numerator, threshold_denominator,
                   boundary, reason_code)
                values (?, 0, 'RETENTION-AUTHORITY-DIRECT',
                        'QMDP-1.0.0/RETENTION-AUTHORITY-DIRECT', '1.0.0',
                        'passed', true, 1, 1, 10000, 'basis-point', '>=', 1, 1,
                        'inclusive', null)
                """, snapshotId));
        assertEquals(1, jdbc.update("""
                update ingestion_quality.iq_data_batch
                   set status='quality-passed', aggregate_version=3, evaluated_at=?
                 where batch_id=? and status='sealed' and aggregate_version=2
                """, Timestamp.from(evaluatedAt), batchId));
    }

    private static void seedEvidenceForSnapshot(
            JdbcTemplate jdbc,
            UUID evidenceId,
            UUID snapshotId,
            String immutableHash,
            String scopeDigest,
            Instant observedAt) {
        seedEvidenceForSnapshot(
                jdbc, evidenceId, snapshotId, immutableHash, scopeDigest, true,
                observedAt, observedAt, observedAt, observedAt.minusSeconds(60),
                observedAt.plusSeconds(3600));
    }

    private static void seedEvidenceForSnapshot(
            JdbcTemplate jdbc,
            UUID evidenceId,
            UUID snapshotId,
            String immutableHash,
            String scopeDigest,
            boolean legalHoldClear,
            Instant legalHoldCheckedAt,
            Instant watermarksCheckedAt,
            Instant observedAt,
            Instant issuedAt,
            Instant expiresAt) {
        jdbc.update("""
                insert into ingestion_quality
                  .iq_quality_snapshot_retention_authority_evidence
                  (authority_evidence_id, authority_ref, snapshot_id,
                   snapshot_immutable_hash, scope_digest, registry_version,
                   registry_digest, members_digest, legal_hold_clear,
                   legal_hold_checked_scope_digest, legal_hold_checked_at,
                   consumer_attestations_payload_utf8, consumer_attestations_digest,
                   watermarks_checked_at, evidence_copy_status, evidence_copy_digest,
                   trusted_observed_at, issued_at, expires_at,
                   runtime_evidence_claim, verification_status)
                values (?, ?, ?, ?, ?, 'QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0',
                        ?, ?, ?, ?, ?, ?, ?, ?, 'not-required', ?, ?, ?, ?,
                        'production-verified', 'verified')
                """, evidenceId,
                "consumer-registry-authority://production/quality-snapshot/" + evidenceId,
                snapshotId, immutableHash, scopeDigest, REGISTRY_DIGEST, MEMBERS_DIGEST,
                legalHoldClear, scopeDigest, Timestamp.from(legalHoldCheckedAt),
                ATTESTATIONS, ATTESTATIONS_DIGEST, Timestamp.from(watermarksCheckedAt),
                EVIDENCE_COPY_DIGEST, Timestamp.from(observedAt),
                Timestamp.from(issuedAt), Timestamp.from(expiresAt));
    }

    private static void seedEvidence(
            JdbcTemplate jdbc,
            UUID evidenceId,
            String scopeDigest,
            Instant issuedAt,
            Instant expiresAt,
            Instant consumedAt,
            UUID executionId,
            String runtimeEvidenceClaim,
            String verificationStatus) {
        Instant observedAt = expiresAt.isAfter(RETENTION_DUE_AT)
                ? TRUSTED_OBSERVED_AT
                : expiresAt.minusSeconds(1);
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
                        ?, ?, true, ?, ?, ?, ?, ?, 'not-required', ?, ?, ?, ?, ?, ?, ?, ?)
                """, evidenceId,
                "consumer-registry-authority://production/quality-snapshot/" + evidenceId,
                SNAPSHOT_ID, IMMUTABLE_HASH, scopeDigest,
                REGISTRY_DIGEST, MEMBERS_DIGEST, scopeDigest,
                Timestamp.from(observedAt),
                ATTESTATIONS, ATTESTATIONS_DIGEST,
                Timestamp.from(observedAt),
                EVIDENCE_COPY_DIGEST, Timestamp.from(observedAt),
                Timestamp.from(issuedAt), Timestamp.from(expiresAt),
                consumedAt == null ? null : Timestamp.from(consumedAt), executionId,
                runtimeEvidenceClaim, verificationStatus);
    }

    private static String executeRetention(
            JdbcTemplate retention,
            UUID executionId,
            UUID resultEventId,
            UUID evidenceId) {
        return executeRetentionWithTrace(
                retention, executionId, resultEventId, evidenceId, RETENTION_TRACE);
    }

    private static String executeRetentionWithTrace(
            JdbcTemplate retention,
            UUID executionId,
            UUID resultEventId,
            UUID evidenceId,
            String traceId) {
        return executeRetentionForSnapshot(
                retention, executionId, resultEventId, SNAPSHOT_ID, IMMUTABLE_HASH,
                RETENTION_SCOPE, evidenceId, traceId);
    }

    private static String executeRetentionForSnapshot(
            JdbcTemplate retention,
            UUID executionId,
            UUID resultEventId,
            UUID snapshotId,
            String immutableHash,
            String scopeDigest,
            UUID evidenceId,
            String traceId) {
        return retention.queryForObject("""
                select ingestion_quality.iq_execute_quality_snapshot_retention(
                  ?, ?, ?, ?, ?, ?, ?)
                """, String.class, executionId, resultEventId, snapshotId,
                immutableHash, scopeDigest, evidenceId, traceId);
    }

    private static void assertBlocked(
            JdbcTemplate jdbc,
            UUID resultEventId,
            long expectedVersion,
            UUID expectedPredecessor,
            String blockerCode) {
        var result = jdbc.queryForMap("""
                select execution_id, result, blocker_code, transaction_id, authority_evidence_id,
                       aggregate_version, supersedes_result_event_id
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id=?
                """, resultEventId);
        assertEquals("blocked", result.get("result"));
        assertEquals(blockerCode, result.get("blocker_code"));
        assertNull(result.get("transaction_id"));
        assertEquals(expectedVersion, result.get("aggregate_version"));
        assertEquals(expectedPredecessor, result.get("supersedes_result_event_id"));
        var outbox = jdbc.queryForMap("""
                select aggregate_id, aggregate_version
                  from ingestion_quality.iq_batch_quality_outbox
                 where event_id=?
                """, resultEventId);
        assertEquals(result.get("execution_id"), outbox.get("aggregate_id"));
        assertEquals(expectedVersion, outbox.get("aggregate_version"));
    }

    private static void assertBlockerCodes(
            JdbcTemplate jdbc, UUID resultEventId, List<String> expected) {
        assertEquals(expected, jdbc.queryForList("""
                select jsonb_array_elements_text(
                         convert_from(payload_utf8, 'UTF8')::jsonb
                           #> '{data,blockerCodes}')
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id=?
                """, String.class, resultEventId));
    }

    private static void assertEvidenceUnconsumed(JdbcTemplate jdbc, UUID evidenceId) {
        var evidence = jdbc.queryForMap("""
                select consumed_at, execution_id
                  from ingestion_quality.iq_quality_snapshot_retention_authority_evidence
                 where authority_evidence_id=?
                """, evidenceId);
        assertNull(evidence.get("consumed_at"));
        assertNull(evidence.get("execution_id"));
    }

    private static void assertSnapshotPresent(JdbcTemplate jdbc) {
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_quality_snapshot
                 where snapshot_id=?
                """, Integer.class, SNAPSHOT_ID));
        assertEquals(EXPECTED_MEASUREMENT_COUNT, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_quality_snapshot_metric
                 where snapshot_id=?
                """, Integer.class, SNAPSHOT_ID));
    }

    private static int resultCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where snapshot_id=? and scope_digest=?
                """, Integer.class, SNAPSHOT_ID, RETENTION_SCOPE);
    }

    private static int deletionOutboxCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_batch_quality_outbox
                 where event_type=
                     'scholarsense.ingestion-quality.quality-snapshot.deletion-result.v1'
                """, Integer.class);
    }

    private static String negativeAuthorityPayload(JdbcTemplate jdbc, UUID evidenceId) {
        Instant observedAt = TRUSTED_OBSERVED_AT;
        Instant issuedAt = AUTHORITY_ISSUED_AT;
        Instant expiresAt = AUTHORITY_EXPIRES_AT;
        String projection = """
                [
                  {"consumerId":"consumer-behind","registryMembership":"required-for-snapshot","lifecycleStatus":"active"},
                  {"consumerId":"consumer-dependency","registryMembership":"required-for-snapshot","lifecycleStatus":"active"},
                  {"consumerId":"consumer-evidence","registryMembership":"required-for-snapshot","lifecycleStatus":"active"},
                  {"consumerId":"consumer-missing","registryMembership":"required-for-snapshot","lifecycleStatus":"active"},
                  {"consumerId":"consumer-unknown","registryMembership":"required-for-snapshot","lifecycleStatus":"active"}
                ]
                """;
        String membersDigest = canonicalDigest(jdbc, projection);
        String registryDigest = canonicalDigest(jdbc, """
                {"registryVersion":"QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0",
                 "members":%s}
                """.formatted(projection));
        String attestedAt = observedAt.toString();
        String members = "[" + String.join(",",
                authorityMember(
                        "consumer-behind", "confirmed", "2", "acked", "acked",
                        "copied", authorityAttestation(
                                "consumer-behind", registryDigest, attestedAt)),
                authorityMember(
                        "consumer-dependency", "confirmed", "3", "missing", "acked",
                        "copied", authorityAttestation(
                                "consumer-dependency", registryDigest, attestedAt)),
                authorityMember(
                        "consumer-evidence", "confirmed", "3", "acked", "acked",
                        "missing", "null"),
                authorityMember(
                        "consumer-missing", "missing", "null", "acked", "acked",
                        "copied", authorityAttestation(
                                "consumer-missing", registryDigest, attestedAt)),
                authorityMember(
                        "consumer-unknown", "unknown", "null", "acked", "acked",
                        "copied", authorityAttestation(
                                "consumer-unknown", registryDigest, attestedAt))) + "]";
        return """
                {
                  "authorityEvidenceId":"%s",
                  "provider":"consumer-registry-authority",
                  "evidenceRef":"consumer-registry-authority://production/quality-snapshot/%s",
                  "scope":{
                    "objectType":"QualitySnapshot",
                    "snapshotId":"%s",
                    "sourceId":"%s",
                    "snapshotAggregateVersion":3,
                    "evaluatedAt":"%s",
                    "retentionDueAt":"%s",
                    "snapshotImmutableHash":"%s",
                    "retentionPolicyVersion":"QUALITY-SNAPSHOT-RETENTION-1.0.0",
                    "retentionScheduleVersion":"RS-1.0.0"
                  },
                  "scopeDigest":"%s",
                  "registryVersion":"QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0",
                  "registryDigest":"%s",
                  "membersDigest":"%s",
                  "members":%s,
                  "legalHold":{"status":"clear","checkedScopeDigest":"%s","checkedAt":"%s"},
                  "watermarksCheckedAt":"%s",
                  "trustedObservedAt":"%s",
                  "issuedAt":"%s",
                  "expiresAt":"%s"
                }
                """.formatted(
                evidenceId, evidenceId, SNAPSHOT_ID, SOURCE_ID, EVALUATED_AT,
                RETENTION_DUE_AT, IMMUTABLE_HASH, RETENTION_SCOPE, registryDigest,
                membersDigest, members, RETENTION_SCOPE,
                observedAt, observedAt, observedAt, issuedAt, expiresAt);
    }

    private static String emptyAuthorityPayload(
            UUID evidenceId,
            UUID snapshotId,
            Instant evaluatedAt,
            Instant dueAt,
            String immutableHash,
            String scopeDigest,
            Instant observedAt,
            Instant issuedAt,
            Instant expiresAt) {
        return emptyAuthorityPayload(
                evidenceId, snapshotId, evaluatedAt, dueAt, immutableHash, scopeDigest,
                observedAt, observedAt, issuedAt, expiresAt);
    }

    private static String emptyAuthorityPayload(
            UUID evidenceId,
            UUID snapshotId,
            Instant evaluatedAt,
            Instant dueAt,
            String immutableHash,
            String scopeDigest,
            Instant checkedAt,
            Instant observedAt,
            Instant issuedAt,
            Instant expiresAt) {
        return """
                {
                  "authorityEvidenceId":"%s",
                  "provider":"consumer-registry-authority",
                  "evidenceRef":"consumer-registry-authority://production/quality-snapshot/%s",
                  "scope":{
                    "objectType":"QualitySnapshot","snapshotId":"%s","sourceId":"%s",
                    "snapshotAggregateVersion":3,"evaluatedAt":"%s",
                    "retentionDueAt":"%s","snapshotImmutableHash":"%s",
                    "retentionPolicyVersion":"QUALITY-SNAPSHOT-RETENTION-1.0.0",
                    "retentionScheduleVersion":"RS-1.0.0"
                  },
                  "scopeDigest":"%s",
                  "registryVersion":"QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0",
                  "registryDigest":"%s","membersDigest":"%s","members":[],
                  "legalHold":{"status":"clear","checkedScopeDigest":"%s","checkedAt":"%s"},
                  "watermarksCheckedAt":"%s","trustedObservedAt":"%s",
                  "issuedAt":"%s","expiresAt":"%s"
                }
                """.formatted(
                evidenceId, evidenceId, snapshotId, SOURCE_ID, evaluatedAt, dueAt,
                immutableHash, scopeDigest, REGISTRY_DIGEST, MEMBERS_DIGEST, scopeDigest,
                checkedAt, checkedAt, observedAt, issuedAt, expiresAt);
    }

    private static String authorityMember(
            String consumerId,
            String watermarkStatus,
            String confirmedAggregateVersion,
            String transportAck,
            String inboxAck,
            String evidenceCopyAck,
            String attestation) {
        return """
                {"consumerId":"%s","registryMembership":"required-for-snapshot",
                 "lifecycleStatus":"active","watermarkStatus":"%s",
                 "requiredAggregateVersion":3,"confirmedAggregateVersion":%s,
                 "transportAck":"%s","inboxAck":"%s","evidenceCopyAck":"%s",
                 "attestation":%s}
                """.formatted(
                consumerId, watermarkStatus, confirmedAggregateVersion,
                transportAck, inboxAck, evidenceCopyAck, attestation);
    }

    private static String authorityAttestation(
            String consumerId, String registryDigest, String attestedAt) {
        return """
                {"kind":"evidence-copy","snapshotId":"%s",
                 "snapshotImmutableHash":"%s","requiredAggregateVersion":3,
                 "consumerId":"%s",
                 "registryVersion":"QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0",
                 "registryDigest":"%s","scopeDigest":"%s","attestedAt":"%s"}
                """.formatted(
                SNAPSHOT_ID, IMMUTABLE_HASH, consumerId, registryDigest,
                RETENTION_SCOPE, attestedAt);
    }

    private static String canonicalDigest(JdbcTemplate jdbc, String json) {
        return jdbc.queryForObject("""
                select 'sha256:' || encode(sha256(convert_to(
                         ingestion_quality.iq_json_canonical(?::jsonb), 'UTF8')), 'hex')
                """, String.class, json);
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return count(jdbc, table, SNAPSHOT_ID);
    }

    private static int count(JdbcTemplate jdbc, String table, UUID snapshotId) {
        return jdbc.queryForObject(
                "select count(*) from ingestion_quality." + table + " where snapshot_id=?",
                Integer.class,
                snapshotId);
    }

    private static int countAuthority(JdbcTemplate jdbc, UUID evidenceId) {
        return jdbc.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_quality_snapshot_retention_authority_evidence
                 where authority_evidence_id=?
                """, Integer.class, evidenceId);
    }

    private static boolean canExecute(JdbcTemplate jdbc, String role) {
        return canExecute(jdbc, role, "iq_execute_quality_snapshot_retention");
    }

    private static boolean canExecute(JdbcTemplate jdbc, String role, String functionName) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select count(*)=1
                       and bool_and(has_function_privilege(?, procedure.oid, 'EXECUTE'))
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality'
                   and procedure.proname=?
                """, Boolean.class, role, functionName));
    }

    private static RaceOutcome raceAuthorityIngest(
            CountDownLatch ready,
            CountDownLatch start,
            UUID evidenceId,
            byte[] payload) throws InterruptedException {
        try (Connection connection = dataSource(AUTHORITY_LOGIN).getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select ingestion_quality
                       .iq_ingest_quality_snapshot_retention_authority(?, ?, ?)
                     """)) {
            connection.setAutoCommit(false);
            statement.setObject(1, evidenceId);
            statement.setBytes(2, payload);
            statement.setString(3, sha256(payload));
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                return new RaceOutcome(false, "race start timed out");
            }
            try {
                statement.executeQuery();
                connection.commit();
                return new RaceOutcome(true, "");
            } catch (SQLException failure) {
                connection.rollback();
                return new RaceOutcome(false, String.valueOf(failure.getMessage()));
            }
        } catch (SQLException failure) {
            ready.countDown();
            return new RaceOutcome(false, String.valueOf(failure.getMessage()));
        }
    }

    private static RaceOutcome raceRetentionExecute(
            CountDownLatch ready,
            CountDownLatch start,
            UUID resultEventId) throws InterruptedException {
        try (Connection connection = dataSource(RETENTION_LOGIN).getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select ingestion_quality.iq_execute_quality_snapshot_retention(
                       ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            connection.setAutoCommit(false);
            statement.setObject(1, RACE_SNAPSHOT_ID);
            statement.setObject(2, resultEventId);
            statement.setObject(3, RACE_SNAPSHOT_ID);
            statement.setString(4, RACE_HASH);
            statement.setString(5, RACE_SCOPE);
            statement.setObject(6, RACE_EXECUTION_EVIDENCE_ID);
            statement.setString(7, RETENTION_TRACE);
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                return new RaceOutcome(false, "race start timed out");
            }
            try {
                statement.executeQuery();
                connection.commit();
                return new RaceOutcome(true, "");
            } catch (SQLException failure) {
                connection.rollback();
                return new RaceOutcome(false, String.valueOf(failure.getMessage()));
            }
        } catch (SQLException failure) {
            ready.countDown();
            return new RaceOutcome(false, String.valueOf(failure.getMessage()));
        }
    }

    private static void ensureWorkloadLogins() {
        admin().execute("""
                do $role_test$
                begin
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_ret_auth_worker_test_login') then
                        create role scholarsense_iq_ret_auth_worker_test_login login inherit;
                    end if;
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_ret_auth_executor_test_login') then
                        create role scholarsense_iq_ret_auth_executor_test_login login inherit;
                    end if;
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_ret_auth_authority_test_login') then
                        create role scholarsense_iq_ret_auth_authority_test_login login inherit;
                    end if;
                end
                $role_test$;
                alter role scholarsense_iq_ret_auth_worker_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_ret_auth_executor_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_ret_auth_authority_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                revoke scholarsense_ingestion_quality_online,
                       scholarsense_ingestion_quality_quality_worker,
                       scholarsense_ingestion_quality_relay,
                       scholarsense_ingestion_quality_retention_executor,
                       scholarsense_ingestion_quality_consumer_registry_authority,
                       scholarsense_ingestion_quality_batch_owner
                    from scholarsense_iq_ret_auth_worker_test_login,
                         scholarsense_iq_ret_auth_executor_test_login,
                         scholarsense_iq_ret_auth_authority_test_login;
                grant scholarsense_ingestion_quality_quality_worker
                    to scholarsense_iq_ret_auth_worker_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_retention_executor
                    to scholarsense_iq_ret_auth_executor_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_consumer_registry_authority
                    to scholarsense_iq_ret_auth_authority_test_login
                    with inherit true, set false;
                """);
    }

    private static String metricsJson() {
        StringBuilder metrics = new StringBuilder("[");
        for (int ordinal = 0; ordinal < EXPECTED_MEASUREMENT_COUNT; ordinal++) {
            if (ordinal > 0) metrics.append(',');
            if (ordinal == EXPECTED_MEASUREMENT_COUNT - 1) {
                metrics.append("""
                        {
                          "metricId":"%s",
                          "formulaId":"%s",
                          "formulaVersion":"1.0.0",
                          "result":"not-applicable",
                          "applicable":false,
                          "numerator":0,
                          "denominator":0,
                          "valueBasisPoints":null,
                          "unit":"count",
                          "operator":"=",
                          "thresholdNumerator":0,
                          "thresholdDenominator":1,
                          "boundary":"inclusive",
                          "reasonCode":null
                        }
                        """.formatted(metricId(ordinal), formulaId(ordinal)));
                continue;
            }
            metrics.append("""
                    {
                      "metricId":"%s",
                      "formulaId":"%s",
                      "formulaVersion":"1.0.0",
                      "result":"passed",
                      "applicable":true,
                      "numerator":1,
                      "denominator":1,
                      "valueBasisPoints":10000,
                      "unit":"basis-point",
                      "operator":">=",
                      "thresholdNumerator":1,
                      "thresholdDenominator":1,
                      "boundary":"inclusive",
                      "reasonCode":null
                    }
                    """.formatted(metricId(ordinal), formulaId(ordinal)));
        }
        return metrics.append(']').toString();
    }

    private static String metricId(int ordinal) {
        return ordinal == 0 ? "freshness" : "bounded-" + ordinal;
    }

    private static String formulaId(int ordinal) {
        return "QMDP-1.0.0/" + metricId(ordinal);
    }

    private static String sealedContract() {
        return """
                {
                  "qshmProfileVersion":"QSHM-1.0.0",
                  "qshmProfileCanonicalDigest":"sha256:%s",
                  "qmdpApprovalRef":"AUTH-2026-08-08-001",
                  "qmdpEffectiveAt":"2024-08-01T00:00:00Z",
                  "qmdpProfileVersion":"QMDP-1.0.0",
                  "qmdpPolicyCanonicalDigest":"%s"
                }
                """.formatted("6".repeat(64), QMDP_DIGEST);
    }

    private static void assertDatabaseFailure(String messageFragment, Runnable command) {
        DataAccessException failure = assertThrows(DataAccessException.class, command::run);
        String message = failure.getMostSpecificCause().getMessage();
        assertNotNull(message);
        assertTrue(message.toLowerCase().contains(messageFragment.toLowerCase()), message);
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

    private static String directScope(
            UUID snapshotId, Instant evaluatedAt, Instant dueAt, String immutableHash) {
        return QualitySnapshotRetentionScopeCanonicalizer.digest(
                new QualitySnapshotRetentionScopeCanonicalizer.Material(
                        "QualitySnapshot", snapshotId, SOURCE_ID, 3L, evaluatedAt, dueAt,
                        immutableHash, "QUALITY-SNAPSHOT-RETENTION-1.0.0", "RS-1.0.0"));
    }

    private static String canonicalInstant(Instant value) {
        return DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                .withZone(ZoneOffset.UTC)
                .format(value);
    }

    private static String prefixedSha256(String value) {
        return "sha256:" + sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record RaceOutcome(boolean success, String error) {}
}
