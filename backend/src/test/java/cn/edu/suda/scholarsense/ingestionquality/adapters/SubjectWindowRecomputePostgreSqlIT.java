package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcSubjectWindowRecomputeStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.TransactionalSubjectMappingChangedConsumer;
import cn.edu.suda.scholarsense.ingestionquality.application.MappingRecomputePlanner;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectMappingCorrectionCoordinator;
import cn.edu.suda.scholarsense.ingestionquality.domain.MappingRecomputeJobStatus;
import cn.edu.suda.scholarsense.subjectregistry.api.SubjectMappingChangedEvent;
import cn.edu.suda.scholarsense.subjectregistry.api.SubjectMappingConsumptionOutcome;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** PostgreSQL 18.4 subject-window/job actual-login evidence. */
class SubjectWindowRecomputePostgreSqlIT {
    private static final String ONLINE_LOGIN = "scholarsense_iq_window_online_test_login";
    private static final String TRACE = "00112233445566778899aabbccddeeff";
    private static final String SUBJECT = "019fcfea-6400-7000-8000-000000000001";
    private static final UUID LINEAGE = uuid("019fcfea-6400-7000-8000-000000000002");
    private static final UUID AGGREGATE = uuid("019fcfea-6400-7000-8000-000000000003");
    private static final UUID REQUEST = uuid("019fcfea-6400-7000-8000-000000000019");
    private static final Instant NOW = Instant.parse("2026-08-06T08:00:00Z");
    private JdbcTemplate admin;
    private JdbcTemplate online;
    private DataSource onlineDataSource;

    @BeforeEach
    void setUp() {
        admin = new JdbcTemplate(dataSource(required("scholarsense.audit.pg.user")));
        ensureOnlineLogin();
        onlineDataSource = dataSource(ONLINE_LOGIN);
        online = new JdbcTemplate(onlineDataSource);
        admin.execute("""
                truncate table
                  ingestion_quality.iq_mapping_recompute_outbox,
                  ingestion_quality.iq_mapping_recompute_result,
                  ingestion_quality.iq_mapping_recompute_job,
                  ingestion_quality.iq_mapping_recompute_request,
                  ingestion_quality.iq_subject_mapping_event_quarantine,
                  ingestion_quality.iq_subject_mapping_event_inbox,
                  ingestion_quality.iq_subject_mapping_consumer_cursor,
                  ingestion_quality.iq_historical_window cascade
                """);
    }

    @Test
    void schemaUsesOwnerTablesFunctionOnlyWritesAndDatabaseOverlapProtection() {
        assertEquals("180004", admin.queryForObject(
                "select current_setting('server_version_num')", String.class));
        assertEquals(83, admin.queryForObject("""
                select count(*) from information_schema.tables
                 where table_schema='ingestion_quality' and table_type='BASE TABLE'
                """, Integer.class));
        for (String table : List.of(
                "iq_historical_window", "iq_subject_mapping_event_inbox",
                "iq_subject_mapping_event_quarantine", "iq_subject_mapping_consumer_cursor",
                "iq_mapping_recompute_request", "iq_mapping_recompute_job",
                "iq_mapping_recompute_result",
                "iq_mapping_recompute_outbox")) {
            for (String privilege : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
                assertFalse(Boolean.TRUE.equals(admin.queryForObject(
                        "select has_table_privilege('scholarsense_ingestion_quality_online',?,?)",
                        Boolean.class, "ingestion_quality." + table, privilege)), table + " " + privilege);
            }
        }
        for (String function : List.of(
                "iq_record_historical_window", "iq_accept_subject_mapping_event",
                "iq_reconcile_subject_mapping_consumer", "iq_record_mapping_recompute_plan",
                "iq_enqueue_mapping_recompute",
                "iq_claim_mapping_recompute_job", "iq_checkpoint_mapping_recompute_job",
                "iq_complete_mapping_recompute_job", "iq_fail_mapping_recompute_job",
                "iq_requeue_mapping_recompute_job", "iq_cancel_mapping_recompute_job")) {
            assertTrue(functionPrivilege(function), function);
        }

        recordWindow("baseline-a", NOW.minusSeconds(28L * 86400), NOW);
        recordWindow("baseline-b", NOW, NOW.plusSeconds(28L * 86400));
        assertThrows(DataAccessException.class, () -> recordWindow(
                "baseline-overlap", NOW.minusNanos(1), NOW.plusSeconds(1)));
        assertThrows(DataAccessException.class, () -> online.update("""
                update ingestion_quality.iq_historical_window set mapping_version=99
                 where window_id='baseline-a'
                """));
    }

    @Test
    void eventConsumerHandlesDuplicateOldGapBackfillPoisonAndReconciliation() {
        assertEquals("APPLIED", acceptEvent(
                "019fcfea-6400-7000-8000-000000000010", 1, "normal", true));
        assertEquals("DUPLICATE", acceptEvent(
                "019fcfea-6400-7000-8000-000000000010", 1, "normal", true));
        assertEquals("OLD_VERSION", acceptEvent(
                "019fcfea-6400-7000-8000-000000000011", 1, "normal", true));
        assertEquals("GAP_PAUSED", acceptEvent(
                "019fcfea-6400-7000-8000-000000000013", 3, "normal", true));
        assertEquals("BACKFILL_APPLIED", acceptEvent(
                "019fcfea-6400-7000-8000-000000000012", 2, "backfill", true));
        assertEquals("APPLIED", acceptEvent(
                "019fcfea-6400-7000-8000-000000000013", 3, "normal", true));
        assertTrue(Boolean.TRUE.equals(online.queryForObject("""
                select ingestion_quality.iq_reconcile_subject_mapping_consumer(
                  'ingestion-quality-subject-window', ?, 3, ?)
                """, Boolean.class, AGGREGATE, Timestamp.from(NOW.plusSeconds(20)))));
        assertEquals("active", admin.queryForObject("""
                select lifecycle from ingestion_quality.iq_subject_mapping_consumer_cursor
                 where consumer_id='ingestion-quality-subject-window' and aggregate_id=?
                """, String.class, AGGREGATE));
        assertEquals(3L, admin.queryForObject("""
                select aggregate_version from ingestion_quality.iq_subject_mapping_consumer_cursor
                 where consumer_id='ingestion-quality-subject-window' and aggregate_id=?
                """, Long.class, AGGREGATE));
        assertEquals("POISON_QUARANTINED", acceptEvent(
                "019fcfea-6400-7000-8000-000000000014", 4, "normal", false));
        assertFalse(Boolean.TRUE.equals(online.queryForObject("""
                select ingestion_quality.iq_reconcile_subject_mapping_consumer(
                  'ingestion-quality-subject-window', ?, 3, ?)
                """, Boolean.class, AGGREGATE, Timestamp.from(NOW.plusSeconds(21)))));
        assertEquals("quarantined", admin.queryForObject("""
                select lifecycle from ingestion_quality.iq_subject_mapping_consumer_cursor
                 where consumer_id='ingestion-quality-subject-window' and aggregate_id=?
                """, String.class, AGGREGATE));
    }

    @Test
    void enqueueIsSevenFieldIdempotentAndLeaseFencingRechecksExpiryAtPublication() {
        UUID firstId = uuid("019fcfea-6400-7000-8000-000000000020");
        UUID replayId = uuid("019fcfea-6400-7000-8000-000000000021");
        UUID first = enqueue(firstId, "sha256:" + "a".repeat(64), NOW.plusSeconds(60));
        UUID replay = enqueue(replayId, "sha256:" + "a".repeat(64), NOW.plusSeconds(60));
        UUID successor = enqueue(replayId, "sha256:" + "b".repeat(64), NOW.plusSeconds(60));

        assertEquals(firstId, first);
        assertEquals(first, replay);
        assertEquals(replayId, successor);
        assertNull(enqueue(uuid("019fcfea-6400-7000-8000-000000000022"),
                "sha256:" + "c".repeat(64), NOW));

        long firstFence = claim(first, "worker-a", NOW, NOW.plusSeconds(5));
        assertTrue(checkpoint(first, firstFence, 1, NOW.plusSeconds(1)));
        long secondFence = claim(first, "worker-b", NOW.plusSeconds(5), NOW.plusSeconds(120));
        assertTrue(secondFence > firstFence);
        assertThrows(DataAccessException.class,
                () -> checkpoint(first, firstFence, 2, NOW.plusSeconds(6)));

        JsonNode result = complete(
                first, secondFence, NOW.plusSeconds(60),
                uuid("019fcfea-6400-7000-8000-000000000030"));
        assertEquals("EXPIRED_HISTORY_ONLY", result.get("resultCode").asText());
        assertTrue(result.get("historyCorrected").asBoolean());
        assertFalse(result.get("businessPublicationCreated").asBoolean());
        assertEquals(1, admin.queryForObject(
                "select count(*) from ingestion_quality.iq_mapping_recompute_result",
                Integer.class));
        assertEquals(1, admin.queryForObject(
                "select count(*) from ingestion_quality.iq_mapping_recompute_outbox",
                Integer.class));
    }

    @Test
    void failureRetryTakeoverCheckpointAndCancelUseCasAndMonotonicFences() {
        UUID jobId = uuid("019fcfea-6400-7000-8000-000000000024");
        assertEquals(jobId, enqueue(
                jobId, "sha256:" + "e".repeat(64), NOW.plusSeconds(600)));

        long firstFence = claim(jobId, "worker-a", NOW, NOW.plusSeconds(60));
        assertTrue(checkpoint(jobId, firstFence, 4, NOW.plusSeconds(1)));
        assertTrue(fail(jobId, firstFence, NOW.plusSeconds(2),
                "INGESTION_QUALITY_RECOMPUTE_EXECUTION_FAILED"));
        long failedVersion = objectVersion(jobId);
        assertEquals("failed", jobStatus(jobId));
        assertEquals("INGESTION_QUALITY_RECOMPUTE_EXECUTION_FAILED", admin.queryForObject(
                "select failure_code from ingestion_quality.iq_mapping_recompute_job where job_id=?",
                String.class, jobId));
        assertThrows(DataAccessException.class,
                () -> checkpoint(jobId, firstFence, 5, NOW.plusSeconds(3)));

        assertTrue(requeue(jobId, failedVersion, NOW.plusSeconds(4)));
        assertThrows(DataAccessException.class,
                () -> requeue(jobId, failedVersion, NOW.plusSeconds(5)));
        long secondFence = claim(jobId, "worker-b", NOW.plusSeconds(6), NOW.plusSeconds(66));
        assertTrue(secondFence > firstFence);
        assertEquals(2, admin.queryForObject(
                "select attempt_no from ingestion_quality.iq_mapping_recompute_job where job_id=?",
                Integer.class, jobId));
        assertTrue(checkpoint(jobId, secondFence, 1, NOW.plusSeconds(7)));

        long runningVersion = objectVersion(jobId);
        assertTrue(cancel(jobId, runningVersion, NOW.plusSeconds(8)));
        assertEquals("cancelled", jobStatus(jobId));
        assertThrows(DataAccessException.class, () -> complete(
                jobId, secondFence, NOW.plusSeconds(9),
                uuid("019fcfea-6400-7000-8000-000000000031")));
        assertThrows(DataAccessException.class,
                () -> cancel(jobId, runningVersion, NOW.plusSeconds(10)));
    }

    @Test
    void requestPlanIsExactlyIdempotentAndRetainsOwnerSourceEvidence() {
        assertTrue(recordPlan(REQUEST, 2, 1));
        assertTrue(recordPlan(REQUEST, 2, 1));
        assertEquals("SRC-P0-CARD-001", admin.queryForObject("""
                select owner_source_id
                  from ingestion_quality.iq_mapping_recompute_request
                 where request_id=?
                """, String.class, REQUEST));
        assertEquals(2, admin.queryForObject("""
                select job_count
                  from ingestion_quality.iq_mapping_recompute_request
                 where request_id=?
                """, Integer.class, REQUEST));
        var request = new JdbcSubjectWindowRecomputeStore(
                online, new tools.jackson.databind.ObjectMapper())
                .findJob(REQUEST).orElseThrow();
        assertEquals(REQUEST, request.jobId());
        assertEquals(MappingRecomputeJobStatus.QUEUED, request.status());
        assertEquals("SRC-P0-CARD-001", request.ownerSourceId());
        assertEquals(1L, request.objectVersion());
        assertThrows(DataAccessException.class, () -> recordPlan(REQUEST, 3, 1));
    }

    @Test
    void productionConsumerAtomicallyCreatesInboxJobsAndRequestPlan() {
        recordWindow("relay-window", NOW.minusSeconds(60), NOW.plusSeconds(3600));
        JdbcSubjectWindowRecomputeStore store = new JdbcSubjectWindowRecomputeStore(
                online, new tools.jackson.databind.ObjectMapper());
        MappingRecomputePlanner planner = new MappingRecomputePlanner(
                store, store, store,
                () -> uuid("019fcfea-6400-7000-8000-000000000060"));
        var consumer = new TransactionalSubjectMappingChangedConsumer(
                new SubjectMappingCorrectionCoordinator(store, planner, store),
                new TransactionTemplate(new DataSourceTransactionManager(onlineDataSource)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(SubjectMappingConsumptionOutcome.APPLIED, consumer.consume(
                new SubjectMappingChangedEvent(
                        REQUEST, LINEAGE, 1, NOW, LINEAGE, "SRC-P0-CARD-001",
                        java.util.Set.of(SUBJECT), "wm-relay-1", TRACE)));
        assertEquals(1, admin.queryForObject(
                "select count(*) from ingestion_quality.iq_subject_mapping_event_inbox",
                Integer.class));
        assertEquals(1, admin.queryForObject(
                "select count(*) from ingestion_quality.iq_mapping_recompute_job",
                Integer.class));
        assertEquals(1, admin.queryForObject(
                "select count(*) from ingestion_quality.iq_mapping_recompute_request",
                Integer.class));
        assertEquals("active", admin.queryForObject("""
                select lifecycle
                  from ingestion_quality.iq_subject_mapping_consumer_cursor
                 where consumer_id='ingestion-quality-subject-window'
                   and aggregate_id=?
                """, String.class, LINEAGE));
        assertTrue(Boolean.TRUE.equals(admin.queryForObject("""
                select reconciliation_passed
                  from ingestion_quality.iq_subject_mapping_consumer_cursor
                 where consumer_id='ingestion-quality-subject-window'
                   and aggregate_id=?
                """, Boolean.class, LINEAGE)));
        assertEquals(MappingRecomputeJobStatus.QUEUED,
                store.findJob(REQUEST).orElseThrow().status());
    }

    @Test
    void planFailureRollsBackConsumerInboxInTheSameIqTransaction() {
        assertTrue(recordPlan(REQUEST, 0, 0));
        UUID conflictingLineage = uuid("019fcfea-6400-7000-8000-000000000061");
        JdbcSubjectWindowRecomputeStore store = new JdbcSubjectWindowRecomputeStore(
                online, new tools.jackson.databind.ObjectMapper());
        var consumer = new TransactionalSubjectMappingChangedConsumer(
                new SubjectMappingCorrectionCoordinator(
                        store, new MappingRecomputePlanner(
                                store, store, store,
                                () -> uuid("019fcfea-6400-7000-8000-000000000062")), store),
                new TransactionTemplate(new DataSourceTransactionManager(onlineDataSource)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(DataAccessException.class, () -> consumer.consume(
                new SubjectMappingChangedEvent(
                        REQUEST, conflictingLineage, 1, NOW, conflictingLineage,
                        "SRC-P0-CARD-001", java.util.Set.of(SUBJECT),
                        "wm-relay-conflict", TRACE)));
        assertEquals(0, admin.queryForObject(
                "select count(*) from ingestion_quality.iq_subject_mapping_event_inbox",
                Integer.class));
    }

    private void recordWindow(String windowId, Instant start, Instant end) {
        online.queryForObject("""
                select ingestion_quality.iq_record_historical_window(
                  ?, ?, ?, ?, 'Asia/Shanghai', '{"SRC-P0-CARD-001":3}'::jsonb,
                  '{"SRC-P0-CARD-001":"wm-42"}'::jsonb, 7, '["QG-1.0.0"]'::jsonb,
                  'personal-baseline', '1.2.0', 'personal-baseline-28d', ?,
                  ?, ?, ?)
                """, Boolean.class, windowId, uuid(SUBJECT), Timestamp.from(start), Timestamp.from(end),
                uuid("019fcfea-6400-7000-8000-000000000040"), "sha256:" + "d".repeat(64),
                Timestamp.from(NOW.plusSeconds(3600)), Timestamp.from(NOW));
    }

    private String acceptEvent(String eventId, long version, String mode, boolean valid) {
        return online.queryForObject("""
                select ingestion_quality.iq_accept_subject_mapping_event(
                  'ingestion-quality-subject-window', 'urn:scholarsense:subject-registry',
                  ?, ?, ?, ?, ?, 'SRC-P0-CARD-001', ?::uuid[], ?, ?, ?, ?)
                """, String.class, uuid(eventId), AGGREGATE, version,
                Timestamp.from(NOW.plusSeconds(version)), LINEAGE,
                new UUID[] {uuid(SUBJECT)}, "wm-" + version, mode, valid,
                Timestamp.from(NOW.plusSeconds(version)));
    }

    private UUID enqueue(UUID jobId, String watermarkDigest, Instant boundary) {
        return online.queryForObject("""
                select ingestion_quality.iq_enqueue_mapping_recompute(
                  ?, ?, 'SRC-P0-CARD-001', ?, 'personal-baseline', '1.2.0', 'personal-baseline-28d',
                  'baseline-a', ?, ?, ?, ?)
                """, UUID.class, jobId, LINEAGE, uuid(SUBJECT), watermarkDigest,
                Timestamp.from(boundary), Timestamp.from(NOW), TRACE);
    }


    private boolean recordPlan(UUID requestId, int jobCount, int historyOnlyWindowCount) {
        return Boolean.TRUE.equals(online.queryForObject("""
                select ingestion_quality.iq_record_mapping_recompute_plan(
                  ?, ?, 'SRC-P0-CARD-001', ?, ?, ?, ?)
                """, Boolean.class, requestId, LINEAGE, jobCount, historyOnlyWindowCount,
                Timestamp.from(NOW), TRACE));
    }

    private long claim(UUID jobId, String worker, Instant now, Instant leaseUntil) {
        return online.queryForObject("""
                select ingestion_quality.iq_claim_mapping_recompute_job(?, ?, ?, ?)
                """, Long.class, jobId, worker, Timestamp.from(now), Timestamp.from(leaseUntil));
    }

    private boolean checkpoint(UUID jobId, long fence, long sequence, Instant now) {
        return Boolean.TRUE.equals(online.queryForObject("""
                select ingestion_quality.iq_checkpoint_mapping_recompute_job(?, ?, ?, ?)
                """, Boolean.class, jobId, fence, sequence, Timestamp.from(now)));
    }

    private boolean fail(UUID jobId, long fence, Instant now, String code) {
        return Boolean.TRUE.equals(online.queryForObject("""
                select ingestion_quality.iq_fail_mapping_recompute_job(?, ?, ?, ?)
                """, Boolean.class, jobId, fence, Timestamp.from(now), code));
    }

    private boolean requeue(UUID jobId, long expectedVersion, Instant now) {
        return Boolean.TRUE.equals(online.queryForObject("""
                select ingestion_quality.iq_requeue_mapping_recompute_job(?, ?, ?)
                """, Boolean.class, jobId, expectedVersion, Timestamp.from(now)));
    }

    private boolean cancel(UUID jobId, long expectedVersion, Instant now) {
        return Boolean.TRUE.equals(online.queryForObject("""
                select ingestion_quality.iq_cancel_mapping_recompute_job(?, ?, ?)
                """, Boolean.class, jobId, expectedVersion, Timestamp.from(now)));
    }

    private long objectVersion(UUID jobId) {
        return online.queryForObject("""
                select object_version from ingestion_quality.iq_mapping_recompute_job where job_id=?
                """, Long.class, jobId);
    }

    private String jobStatus(UUID jobId) {
        return online.queryForObject("""
                select status from ingestion_quality.iq_mapping_recompute_job where job_id=?
                """, String.class, jobId);
    }

    private JsonNode complete(UUID jobId, long fence, Instant now, UUID eventId) {
        String json = online.queryForObject("""
                select ingestion_quality.iq_complete_mapping_recompute_job(?, ?, ?, ?)::text
                """, String.class, jobId, fence, Timestamp.from(now), eventId);
        try {
            return new tools.jackson.databind.ObjectMapper().readTree(json);
        } catch (tools.jackson.core.JacksonException error) {
            throw new IllegalStateException(error);
        }
    }

    private boolean functionPrivilege(String function) {
        return Boolean.TRUE.equals(admin.queryForObject("""
                select count(*)=1 and bool_and(has_function_privilege(
                         'scholarsense_ingestion_quality_online', procedure.oid, 'EXECUTE'))
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality' and procedure.proname=?
                """, Boolean.class, function));
    }

    private void ensureOnlineLogin() {
        admin.execute("""
                do $role_test$
                begin
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_window_online_test_login') then
                        create role scholarsense_iq_window_online_test_login login inherit;
                    end if;
                end
                $role_test$;
                alter role scholarsense_iq_window_online_test_login
                    login inherit nosuperuser nocreatedb nocreaterole
                    noreplication nobypassrls;
                revoke scholarsense_ingestion_quality_online
                    from scholarsense_iq_window_online_test_login;
                grant scholarsense_ingestion_quality_online
                    to scholarsense_iq_window_online_test_login
                    with inherit true, set false;
                """);
    }

    private DataSource dataSource(String username) {
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
}
