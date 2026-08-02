package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcResponsibilityReconciliationAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcResponsibilitySyncRepository;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationChangeKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReason;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientDecision;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientEvidence;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientReason;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStudentSourceReference;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityType;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL 18.4 responsibility persistence and fencing evidence. */
class ResponsibilityAuthorityPostgreSqlIT {
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "responsibility-authority",
            "sandbox-0",
            "responsibility");
    private static final String TRACE =
            "0123456789abcdef0123456789abcdef";
    private static final String RELATION = "rtok_" + "a".repeat(32);
    private static final String STUDENT = "stok_" + "b".repeat(32);
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static final String DIGEST_C = "c".repeat(64);
    private static final String DIGEST_D = "d".repeat(64);
    private static final String STUDENT_EQUIVALENCE = "e".repeat(64);
    private static final String LINEAGE = "lin_" + "f".repeat(32);

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private Instant now;

    @BeforeEach
    void setUp() {
        dataSource = dataSource(
                requiredProperty("scholarsense.audit.pg.url"));
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.execute("""
                truncate table
                  identity_access.ia_responsibility_v2_snapshot_lineage,
                  identity_access.ia_responsibility_v2_snapshot_entry,
                  identity_access.ia_responsibility_v2_cutover_command,
                  identity_access.ia_responsibility_v2_reconciliation_snapshot,
                  identity_access.ia_responsibility_v2_shadow_lineage_head,
                  identity_access.ia_responsibility_v2_shadow_invalidation_fact,
                  identity_access.ia_responsibility_v2_shadow_current,
                  identity_access.ia_responsibility_v2_shadow_checkpoint,
                  identity_access.ia_responsibility_v2_source_fact,
                  identity_access.ia_responsibility_lineage_binding,
                  identity_access.ia_responsibility_slo_compensation,
                  identity_access.ia_responsibility_slo_evidence,
                  identity_access.ia_responsibility_reconciliation_detail,
                  identity_access.ia_responsibility_reconciliation_run,
                  identity_access.ia_responsibility_reconciliation_lease,
                  identity_access.ia_responsibility_reconciliation_attempt,
                  identity_access.ia_responsibility_reconciliation_job,
                  identity_access.ia_responsibility_exception_current,
                  identity_access.ia_responsibility_exception_history,
                  identity_access.ia_responsibility_current,
                  identity_access.ia_responsibility_source_fact,
                  identity_access.ia_responsibility_source_archive,
                  identity_access.ia_responsibility_source_inbox
                cascade
                """);
    }

    @Test
    void cleanAndUpgradeContainResponsibilityTablesIndexesAndRetentionColumns() {
        assertEquals("180004", jdbc.queryForObject(
                "select current_setting('server_version_num')",
                String.class));
        List<String> tables = jdbc.queryForList("""
                select table_name
                  from information_schema.tables
                 where table_schema='identity_access'
                   and table_name like 'ia_responsibility_%'
                 order by table_name
                """, String.class);
        assertEquals(23, tables.size());
        assertTrue(tables.contains(
                "ia_responsibility_v2_shadow_checkpoint"));
        assertTrue(tables.contains(
                "ia_responsibility_v2_shadow_current"));
        assertTrue(tables.contains(
                "ia_responsibility_v2_reconciliation_snapshot"));
        assertTrue(tables.contains(
                "ia_responsibility_v2_cutover_command"));
        assertTrue(tables.contains(
                "ia_responsibility_reconciliation_run"));
        assertTrue(tables.contains(
                "ia_responsibility_slo_compensation"));
        assertTrue(jdbc.queryForObject("""
                select count(*)
                  from pg_indexes
                 where schemaname='identity_access'
                   and indexname like 'ia_responsibility_%'
                """, Integer.class) >= 18);
        assertEquals(17, jdbc.queryForObject("""
                select count(distinct table_name)
                  from information_schema.columns
                 where table_schema='identity_access'
                   and table_name like 'ia_responsibility_%'
                   and column_name='legal_hold'
                """, Integer.class));

        JdbcTemplate upgraded = new JdbcTemplate(dataSource(
                requiredProperty(
                        "scholarsense.audit.pg.upgrade-url")));
        assertEquals(23, upgraded.queryForObject("""
                select count(*)
                  from information_schema.tables
                 where table_schema='identity_access'
                   and table_name like 'ia_responsibility_%'
                """, Integer.class));
    }

    @Test
    void leastPrivilegeAndShapeConstraintsFailClosed() throws Exception {
        assertAllowed(
                "scholarsense_identity_current_reader",
                "select count(*) from "
                        + "identity_access.ia_responsibility_current");
        assertDenied(
                "scholarsense_identity_current_reader",
                "insert into identity_access."
                        + "ia_responsibility_reconciliation_job "
                        + "(job_id,source_id,feed_id,partition_id,"
                        + "consumer_projection,business_date,job_kind,"
                        + "status,requested_at,retry_budget,trace_id,"
                        + "retention_effective_at,expires_at) values "
                        + "('019c1234-0000-7000-8000-000000000101',"
                        + "'SRC-P0-RESPONSIBILITY-001',"
                        + "'responsibility-authority','sandbox-0',"
                        + "'responsibility',current_date,"
                        + "'full-reconciliation','queued',now(),8,'"
                        + TRACE + "',now(),now()+interval '90 days')");
        assertAllowed(
                "scholarsense_identity_sync_worker",
                "select count(*) from "
                        + "identity_access.ia_responsibility_source_fact");
        assertDenied(
                "scholarsense_identity_sync_worker",
                "delete from "
                        + "identity_access.ia_responsibility_source_fact");
        assertAllowed(
                "scholarsense_identity_online",
                "select count(*) from "
                        + "identity_access.ia_responsibility_current");
        assertDenied(
                "scholarsense_identity_online",
                "update identity_access.ia_responsibility_current "
                        + "set source_version=source_version");

        SQLException invalid = assertThrows(
                SQLException.class,
                () -> {
                    try (Connection connection =
                                    dataSource.getConnection();
                            Statement statement =
                                    connection.createStatement()) {
                        statement.execute("""
                                insert into identity_access.ia_responsibility_reconciliation_job (
                                  job_id, source_id, feed_id, partition_id,
                                  consumer_projection, business_date, job_kind,
                                  status, requested_at, retry_budget, trace_id,
                                  retention_effective_at, expires_at)
                                values (
                                  '019c1234-0000-7000-8000-000000000102',
                                  'SRC-P0-RESPONSIBILITY-001',
                                  'responsibility-authority', 'sandbox-0',
                                  'identity-org', current_date,
                                  'full-reconciliation', 'queued', now(), 8,
                                  '0123456789abcdef0123456789abcdef',
                                  now(), now()+interval '90 days')
                                """);
                    }
                });
        assertEquals("23514", invalid.getSQLState());
    }

    @Test
    void atomicRollbackAndProjectionBoundInboxUniquenessHold() {
        TransactionTemplate transaction =
                new TransactionTemplate(manager);
        assertThrows(IllegalStateException.class, () ->
                transaction.execute(status -> {
                    insertInbox(
                            "019c1234-0000-7000-8000-000000000103",
                            1);
                    throw new IllegalStateException("injected");
                }));
        assertEquals(0L, count(
                "ia_responsibility_source_inbox"));

        insertInbox(
                "019c1234-0000-7000-8000-000000000104", 1);
        assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () -> insertInbox(
                        "019c1234-0000-7000-8000-000000000105",
                        1));
    }

    @Test
    void v2ReplayGateAndActiveSuccessorHoldAtThePostgreSqlCommitPoint() {
        JdbcResponsibilitySyncRepository repository =
                new JdbcResponsibilitySyncRepository(jdbc);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        IdentityLease lease = insertSyncLease();

        insertCurrent(
                uuidV7().toString(),
                RELATION,
                KEY.partitionId(),
                DIGEST_A,
                DIGEST_A);
        upsertLiveCheckpoint(1, 1, 1);

        AuthoritativeResponsibilityRelation first = v2Relation(
                uuidV7(), null, 1, 1, 1, 1, DIGEST_B,
                AccessInvalidationChangeKind.CORRECTED,
                AccessInvalidationReason.DIRECT_RESPONSIBILITY_CHANGE,
                ResponsibilityStatus.ACTIVE);
        NormalizedResponsibilityBatch firstBatch =
                v2Batch(1, 0, 1, first);
        applyV2AsWorker(repository, firstBatch, lease, now, first);

        assertEquals(DIGEST_A, livePayload());
        assertEquals(DIGEST_B, shadowPayload());
        assertFalse(repository.v2ShadowActive(KEY));
        assertEquals(1, repository.v2ShadowCheckpoint(KEY)
                .orElseThrow().watermark());

        upsertLiveCheckpoint(2, 2, 2);
        ResponsibilityFullSnapshot staleSnapshot = v2Snapshot(
                repository, first, List.of(first), 1, 1);
        IdentitySyncException stale = assertThrows(
                IdentitySyncException.class,
                () -> inCutoverTransaction(() ->
                        repository.recordV2Reconciliation(
                                staleSnapshot,
                                now.plusSeconds(1))));
        assertEquals(
                "RESPONSIBILITY_V2_RECONCILIATION_STALE",
                stale.code());
        assertEquals(0L, count(
                "ia_responsibility_v2_reconciliation_snapshot"));

        AuthoritativeResponsibilityRelation second = v2Relation(
                uuidV7(), first.relationId(), 2, 2, 2, 2, DIGEST_C,
                AccessInvalidationChangeKind.CORRECTED,
                AccessInvalidationReason.SOURCE_CORRECTION,
                ResponsibilityStatus.ACTIVE);
        NormalizedResponsibilityBatch secondBatch =
                v2Batch(2, 1, 2, second);
        applyV2AsWorker(
                repository,
                secondBatch,
                lease,
                now.plusSeconds(2),
                second);

        ResponsibilityFullSnapshot matchedSnapshot = v2Snapshot(
                repository, second, List.of(first, second), 2, 2);
        ResponsibilityV2ReconciliationEvidence evidence =
                inCutoverTransaction(() ->
                        repository.recordV2Reconciliation(
                                matchedSnapshot,
                                now.plusSeconds(3)));
        assertTrue(evidence.matched());
        ResponsibilityV2CutoverRequest cutover =
                new ResponsibilityV2CutoverRequest(
                        KEY, matchedSnapshot.snapshotId(), TRACE);
        int stagedFacts = inCutoverTransaction(() ->
                repository.v2ShadowInvalidationFacts(KEY).size());
        inCutoverTransaction(() -> {
            repository.markV2InvalidationReplayMaterialized(
                    cutover, stagedFacts);
            return null;
        });

        assertThrows(IllegalStateException.class, () ->
                inCutoverTransaction(() -> {
                    repository.activateV2Shadow(
                            cutover, now.plusSeconds(4));
                    throw new IllegalStateException(
                            "injected-after-v2-activation");
                }));
        assertFalse(repository.v2ShadowActive(KEY));
        assertEquals(DIGEST_A, livePayload());

        IdentityCheckpoint activated = inCutoverTransaction(() ->
                repository.activateV2Shadow(
                        cutover, now.plusSeconds(5)));
        assertEquals(2, activated.sourceVersion());
        assertTrue(repository.v2ShadowActive(KEY));
        assertEquals(DIGEST_C, livePayload());
        assertEquals(2, liveSourceVersion());

        IdentitySyncException legacyRejected = assertThrows(
                IdentitySyncException.class,
                () -> transaction.execute(status -> {
                    repository.recordHeartbeat(
                            v1Heartbeat(2),
                            lease,
                            now.plusSeconds(6));
                    return null;
                }));
        assertEquals(
                "RESPONSIBILITY_V2_REPLAY_REQUIRED",
                legacyRejected.code());

        AuthoritativeResponsibilityRelation successor = v2Relation(
                uuidV7(), second.relationId(), 3, 3, 3, 3, DIGEST_D,
                AccessInvalidationChangeKind.REVOKED,
                AccessInvalidationReason.DIRECT_RESPONSIBILITY_CHANGE,
                ResponsibilityStatus.INACTIVE);
        NormalizedResponsibilityBatch successorBatch =
                v2Batch(3, 2, 3, successor);
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject("""
                select has_table_privilege(
                  'scholarsense_identity_sync_worker',
                  'identity_access.ia_responsibility_current',
                  'DELETE')
                """, Boolean.class)));
        applyV2AsWorker(
                repository,
                successorBatch,
                lease,
                now.plusSeconds(7),
                successor);
        assertEquals(DIGEST_D, livePayload());
        assertEquals(3, liveSourceVersion());
        assertEquals("inactive", jdbc.queryForObject("""
                select relation_status
                  from identity_access.ia_responsibility_current
                 where source_id=? and relation_ref_token=?
                """, String.class, KEY.sourceId(), RELATION));
    }

    @Test
    void doubleWorkerLeaseTransferFencesTheOldAttempt() throws Exception {
        JdbcResponsibilityReconciliationAdapter store = store();
        LocalDate businessDate = LocalDate.now();
        store.enqueue(KEY, businessDate, TRACE);
        UUID jobId = store.nextDue(KEY, now).orElseThrow();

        List<Optional<RunningResponsibilityReconciliationAttempt>> claims;
        List<Callable<Optional<RunningResponsibilityReconciliationAttempt>>>
                workers = List.of(
                        () -> store().start(
                                jobId, "worker-a", Instant.now()),
                        () -> store().start(
                                jobId, "worker-b", Instant.now()));
        try (var executor = Executors.newFixedThreadPool(2)) {
            claims = executor.invokeAll(workers)
                    .stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    })
                    .toList();
        }
        assertEquals(1, claims.stream().filter(Optional::isPresent).count());
        RunningResponsibilityReconciliationAttempt first =
                claims.stream()
                        .flatMap(Optional::stream)
                        .findFirst()
                        .orElseThrow();
        var forgedLease = new ResponsibilityReconciliationLease(
                first.lease().key(),
                first.lease().businessDate(),
                first.lease().jobId(),
                first.lease().attemptNo(),
                first.lease().fencingToken(),
                "worker-forged",
                first.lease().acquiredAt(),
                first.lease().expiresAt());
        var forgedAttempt =
                new RunningResponsibilityReconciliationAttempt(
                        first.jobId(),
                        first.key(),
                        first.businessDate(),
                        first.attemptNo(),
                        first.retryBudget(),
                        first.traceId(),
                        first.startedAt(),
                        forgedLease);
        assertFalse(store.leaseIsCurrent(forgedLease));
        IdentitySyncException forged = assertThrows(
                IdentitySyncException.class,
                () -> store.complete(
                        forgedAttempt,
                        matched(forgedAttempt),
                        Instant.now()));
        assertEquals(
                "RESPONSIBILITY_RECONCILIATION_FENCING_STALE",
                forged.code());
        jdbc.update("""
                update identity_access.ia_responsibility_reconciliation_lease
                   set acquired_at=clock_timestamp()-interval '2 seconds',
                       lease_expires_at=clock_timestamp()-interval '1 second'
                """);
        RunningResponsibilityReconciliationAttempt second =
                store.start(jobId, "worker-c", Instant.now())
                        .orElseThrow();
        assertFalse(store.leaseIsCurrent(first.lease()));
        assertTrue(store.leaseIsCurrent(second.lease()));
        IdentitySyncException stale = assertThrows(
                IdentitySyncException.class,
                () -> store.complete(
                        first,
                        matched(first),
                        Instant.now()));
        assertEquals(
                "RESPONSIBILITY_RECONCILIATION_FENCING_STALE",
                stale.code());
    }

    @Test
    void retryReleasesLeaseAndPriorAttemptCannotFailReplacement() {
        JdbcResponsibilityReconciliationAdapter store = store();
        LocalDate businessDate = LocalDate.now();
        store.enqueue(KEY, businessDate, TRACE);
        UUID jobId = store.nextDue(KEY, now).orElseThrow();
        RunningResponsibilityReconciliationAttempt first =
                store.start(jobId, "worker-a", now)
                        .orElseThrow();

        new TransactionTemplate(manager).execute(status -> {
            store.fail(
                    first,
                    "RESPONSIBILITY_SNAPSHOT_PARTITION_MISSING",
                    true,
                    now.plusSeconds(30),
                    now);
            return null;
        });
        assertTrue(store.start(
                        jobId, "worker-early", now.plusSeconds(29))
                .isEmpty());
        RunningResponsibilityReconciliationAttempt replacement =
                store.start(
                                jobId,
                                "worker-b",
                                now.plusSeconds(31))
                        .orElseThrow();

        IdentitySyncException stale = assertThrows(
                IdentitySyncException.class,
                () -> store.fail(
                        first,
                        "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE",
                        false,
                        null,
                        now.plusSeconds(32)));
        assertEquals(
                "RESPONSIBILITY_RECONCILIATION_FENCING_STALE",
                stale.code());
        assertTrue(store.leaseIsCurrent(
                replacement.lease()));
    }

    @Test
    void asOfWatermarkUsesOneLatestFactPerRelation() {
        insertArchive();
        insertFact(
                "019c1234-0000-7000-8000-000000000111",
                "019c1234-0000-7000-8000-000000000112",
                1,
                DIGEST_A);
        insertFact(
                "019c1234-0000-7000-8000-000000000113",
                "019c1234-0000-7000-8000-000000000114",
                2,
                DIGEST_B);

        List<ResponsibilitySnapshotEntry> atOne =
                store().actualSnapshot(
                        KEY,
                        "RESPONSIBILITY-AUTHORITY-1.0.0",
                        1,
                        now,
                        java.util.Map.of(
                                "identity-authority|sandbox-0",
                                42L));
        List<ResponsibilitySnapshotEntry> atTwo =
                store().actualSnapshot(
                        KEY,
                        "RESPONSIBILITY-AUTHORITY-1.0.0",
                        2,
                        now,
                        java.util.Map.of(
                                "identity-authority|sandbox-0",
                                42L));
        assertEquals(1, atOne.size());
        assertEquals(1, atOne.getFirst().recordVersion());
        assertEquals(DIGEST_A, atOne.getFirst().payloadDigest());
        assertTrue(atOne.getFirst().recipientMapped());
        assertEquals(2, atTwo.getFirst().recordVersion());
        assertEquals(DIGEST_B, atTwo.getFirst().payloadDigest());
        assertFalse(store().actualSnapshot(
                        KEY,
                        "RESPONSIBILITY-AUTHORITY-1.0.0",
                        2,
                        now,
                        java.util.Map.of(
                                "identity-authority|sandbox-0",
                                43L))
                .getFirst()
                .recipientMapped());
    }

    @Test
    void matchedRunResolvesOnlyExceptionsInItsRouteScope() {
        JdbcResponsibilityReconciliationAdapter store = store();
        store.enqueue(KEY, LocalDate.now(), TRACE);
        UUID jobId = store.nextDue(KEY, now).orElseThrow();
        RunningResponsibilityReconciliationAttempt attempt =
                store.start(jobId, "worker-a", now)
                        .orElseThrow();
        UUID scopedException = UUID.fromString(
                "019c1234-0000-7000-8000-000000000131");
        UUID otherException = UUID.fromString(
                "019c1234-0000-7000-8000-000000000132");
        insertCurrent(
                "019c1234-0000-7000-8000-000000000133",
                "rtok_" + "c".repeat(32),
                "sandbox-0",
                DIGEST_A,
                DIGEST_A);
        insertCurrent(
                "019c1234-0000-7000-8000-000000000134",
                "rtok_" + "d".repeat(32),
                "other-0",
                DIGEST_B,
                DIGEST_B);
        insertException(
                scopedException,
                "c".repeat(64),
                DIGEST_A,
                DIGEST_A,
                "open",
                1);
        insertException(
                otherException,
                "d".repeat(64),
                DIGEST_B,
                DIGEST_B,
                "open",
                1);

        List<ResponsibilityExceptionAuditTransition> transitions =
                new TransactionTemplate(manager).execute(status ->
                        store.appendWithAuditTransitions(
                                matched(attempt),
                                attempt.lease()));

        assertEquals(
                List.of(scopedException),
                transitions.stream()
                        .map(ResponsibilityExceptionAuditTransition
                                ::exceptionId)
                        .toList());
        assertEquals(
                "resolved",
                exceptionStatus(scopedException));
        assertEquals(
                "open",
                exceptionStatus(otherException));
    }

    @Test
    void resolvedReconciliationExceptionReopensAndEmitsOpenedTransition() {
        JdbcResponsibilityReconciliationAdapter store = store();
        store.enqueue(KEY, LocalDate.now(), TRACE);
        UUID jobId = store.nextDue(KEY, now).orElseThrow();
        RunningResponsibilityReconciliationAttempt attempt =
                store.start(jobId, "worker-a", now)
                        .orElseThrow();
        UUID exceptionId = UUID.fromString(
                "019c1234-0000-7000-8000-000000000141");
        insertCurrent(
                "019c1234-0000-7000-8000-000000000142",
                RELATION,
                "sandbox-0",
                DIGEST_A,
                DIGEST_A);
        insertException(
                exceptionId,
                digest("reconciliation\0"
                        + DIGEST_A
                        + "\0"
                        + DIGEST_A),
                DIGEST_A,
                DIGEST_A,
                "resolved",
                2);

        List<ResponsibilityExceptionAuditTransition> transitions =
                new TransactionTemplate(manager).execute(status ->
                        store.appendWithAuditTransitions(
                                differences(attempt),
                                attempt.lease()));

        assertEquals(1, transitions.size());
        assertEquals(
                "responsibility.exception.opened",
                transitions.getFirst().action());
        assertEquals("open", exceptionStatus(exceptionId));
        assertEquals(
                "opened",
                jdbc.queryForObject("""
                        select event_type
                          from identity_access
                               .ia_responsibility_exception_history
                         where exception_id=?
                         order by aggregate_version desc
                         limit 1
                        """,
                        String.class,
                        exceptionId));
    }

    private IdentityLease insertSyncLease() {
        UUID jobId = uuidV7();
        Instant acquiredAt = now.minusSeconds(1);
        Instant leaseExpiresAt = now.plusSeconds(600);
        new TransactionTemplate(manager).execute(status -> {
            jdbc.update("""
                    delete from identity_access.ia_identity_sync_lease
                     where source_id=? and feed_id=? and partition_id=?
                       and consumer_projection='responsibility'
                    """, KEY.sourceId(), KEY.feedId(), KEY.partitionId());
            jdbc.update("""
                    insert into identity_access.ia_identity_sync_job (
                      job_id, source_id, feed_id, partition_id,
                      consumer_projection, status, health, freshness,
                      requested_at, retry_budget, trace_id,
                      retention_effective_at, expires_at)
                    values (?, ?, ?, ?, 'responsibility', 'running',
                            'healthy', 'fresh', ?, 8, ?, ?, ?)
                    """,
                    jobId, KEY.sourceId(), KEY.feedId(), KEY.partitionId(),
                    java.sql.Timestamp.from(acquiredAt), TRACE,
                    java.sql.Timestamp.from(acquiredAt),
                    java.sql.Timestamp.from(
                            acquiredAt.plus(Duration.ofDays(30))));
            jdbc.update("""
                    insert into identity_access.ia_identity_sync_attempt (
                      job_id, attempt_no, fencing_token, status,
                      input_watermark, started_at, trace_id,
                      retention_effective_at, expires_at)
                    values (?, 1, 1, 'running', 0, ?, ?, ?, ?)
                    """,
                    jobId, java.sql.Timestamp.from(acquiredAt), TRACE,
                    java.sql.Timestamp.from(acquiredAt),
                    java.sql.Timestamp.from(
                            acquiredAt.plus(Duration.ofDays(30))));
            jdbc.update("""
                    insert into identity_access.ia_identity_sync_lease (
                      source_id, feed_id, partition_id,
                      consumer_projection, job_id, attempt_no,
                      fencing_token, lease_owner, acquired_at,
                      lease_expires_at, retention_effective_at, expires_at)
                    values (?, ?, ?, 'responsibility', ?, 1, 1,
                            'pg-v2-worker', ?, ?, ?, ?)
                    """,
                    KEY.sourceId(), KEY.feedId(), KEY.partitionId(), jobId,
                    java.sql.Timestamp.from(acquiredAt),
                    java.sql.Timestamp.from(leaseExpiresAt),
                    java.sql.Timestamp.from(acquiredAt),
                    java.sql.Timestamp.from(
                            acquiredAt.plus(Duration.ofDays(30))));
            return null;
        });
        return new IdentityLease(
                KEY,
                jobId,
                1,
                1,
                "pg-v2-worker",
                acquiredAt,
                leaseExpiresAt);
    }

    private void applyV2AsWorker(
            JdbcResponsibilitySyncRepository repository,
            NormalizedResponsibilityBatch batch,
            IdentityLease lease,
            Instant appliedAt,
            AuthoritativeResponsibilityRelation relation) {
        new TransactionTemplate(manager).execute(status -> {
            jdbc.execute(
                    "set local role scholarsense_identity_sync_worker");
            repository.applyV2Shadow(
                    batch,
                    lease,
                    appliedAt,
                    List.of(v2Scope(relation)));
            return null;
        });
    }

    private <T> T inCutoverTransaction(Supplier<T> work) {
        return new TransactionTemplate(manager).execute(status -> {
            jdbc.execute("set local role "
                    + "scholarsense_identity_responsibility_v2_cutover");
            return work.get();
        });
    }

    private AuthoritativeResponsibilityRelation v2Relation(
            UUID eventId,
            UUID supersedesId,
            long sourceVersion,
            long sourceWatermark,
            long recordVersion,
            long lineageVersion,
            String payloadDigest,
            AccessInvalidationChangeKind changeKind,
            AccessInvalidationReason reason,
            ResponsibilityStatus status) {
        return new AuthoritativeResponsibilityRelation(
                eventId,
                KEY.sourceId(),
                RELATION,
                new ResponsibilityStudentSourceReference(
                        "RESPONSIBILITY-STUDENT-REF",
                        "resp-student-v2",
                        STUDENT,
                        DIGEST_B,
                        STUDENT_EQUIVALENCE),
                DIGEST_C,
                DIGEST_D,
                ResponsibilityType.PRIMARY,
                status,
                new EffectiveInterval(now.minus(Duration.ofDays(1)), null),
                sourceVersion,
                sourceWatermark,
                recordVersion,
                lineageVersion,
                payloadDigest,
                changeKind,
                reason,
                now.plusSeconds(sourceVersion - 1),
                new AccessInvalidationLineageId(LINEAGE),
                supersedesId);
    }

    private ResponsibilityScopeProjectionUpdate v2Scope(
            AuthoritativeResponsibilityRelation relation) {
        return new ResponsibilityScopeProjectionUpdate(
                STUDENT_EQUIVALENCE,
                List.of(relation),
                ResponsibilityRecipientDecision.invalid(
                        ResponsibilityRecipientReason.ZERO_RECIPIENT),
                List.of(new ResponsibilityRecipientEvidence(
                        relation,
                        null,
                        null,
                        false,
                        false,
                        false,
                        false)));
    }

    private NormalizedResponsibilityBatch v2Batch(
            long sourceVersion,
            long fromWatermark,
            long toWatermark,
            AuthoritativeResponsibilityRelation relation) {
        return new NormalizedResponsibilityBatch(
                uuidV7(),
                KEY,
                "RESPONSIBILITY-BATCH-1.0.0",
                "RESPONSIBILITY-AUTHORITY-2.0.0",
                sourceVersion,
                fromWatermark,
                toWatermark,
                java.util.Map.of("identity-authority|sandbox-0", 42L),
                now.minusSeconds(1),
                now,
                TRACE,
                DIGEST_A,
                DIGEST_B,
                true,
                new byte[] {1},
                new byte[] {2},
                new byte[] {3},
                "config://test/responsibility-authority-inbox",
                "k1",
                List.of(relation));
    }

    private NormalizedResponsibilityBatch v1Heartbeat(long watermark) {
        return new NormalizedResponsibilityBatch(
                uuidV7(),
                KEY,
                "RESPONSIBILITY-BATCH-1.0.0",
                "RESPONSIBILITY-AUTHORITY-1.0.0",
                watermark,
                watermark,
                watermark,
                java.util.Map.of("identity-authority|sandbox-0", 42L),
                now.minusSeconds(1),
                now,
                TRACE,
                DIGEST_A,
                DIGEST_B,
                true,
                new byte[] {1},
                new byte[] {2},
                new byte[] {3},
                "config://test/responsibility-authority-inbox",
                "k1",
                List.of());
    }

    private ResponsibilityFullSnapshot v2Snapshot(
            JdbcResponsibilitySyncRepository repository,
            AuthoritativeResponsibilityRelation current,
            List<AuthoritativeResponsibilityRelation> lineage,
            long sourceVersion,
            long throughWatermark) {
        List<ResponsibilityV2LineageDigestEvent> digestEvents = lineage.stream()
                .map(relation -> new ResponsibilityV2LineageDigestEvent(
                        relation.aggregateVersion(),
                        relation.relationId(),
                        relation.supersedesId(),
                        relation.sourceVersion(),
                        relation.sourceWatermark(),
                        relation.recordVersion(),
                        relation.payloadDigest(),
                        relation.changeKind().name().toLowerCase()
                                .replace('_', '-'),
                        relation.changeReason().name(),
                        relation.changeEffectiveAt()))
                .toList();
        ResponsibilityV2LineageManifest manifest =
                new ResponsibilityV2LineageManifest(
                        RELATION,
                        new AccessInvalidationLineageId(LINEAGE),
                        lineage.getFirst().relationId(),
                        lineage.getLast().relationId(),
                        lineage.size(),
                        ResponsibilityV2LineageDigest.digest(digestEvents));
        ResponsibilityV2ProjectionFingerprint fingerprint =
                repository.v2ShadowFingerprint(KEY);
        assertEquals(
                fingerprint.canonicalLineageDigest(),
                ResponsibilityV2LineageManifest.digest(List.of(manifest)));
        return new ResponsibilityFullSnapshot(
                uuidV7(),
                KEY,
                "RESPONSIBILITY-SNAPSHOT-1.0.0",
                "RESPONSIBILITY-AUTHORITY-2.0.0",
                LocalDate.now(),
                now,
                sourceVersion,
                throughWatermark,
                java.util.Map.of("identity-authority|sandbox-0", 42L),
                true,
                true,
                List.of(KEY.partitionId()),
                fingerprint.recordCount(),
                fingerprint.canonicalDigest(),
                DIGEST_B,
                DIGEST_A,
                true,
                List.of(new ResponsibilitySnapshotEntry(
                        RELATION,
                        STUDENT_EQUIVALENCE,
                        current.recordVersion(),
                        current.payloadDigest(),
                        current.status() == ResponsibilityStatus.ACTIVE,
                        false)),
                fingerprint.lineageCount(),
                fingerprint.canonicalLineageDigest(),
                List.of(manifest),
                TRACE);
    }

    private void upsertLiveCheckpoint(
            long sourceVersion,
            long sourceWatermark,
            long aggregateVersion) {
        jdbc.update("""
                insert into identity_access.ia_identity_sync_checkpoint (
                  source_id, feed_id, partition_id, consumer_projection,
                  source_version, source_watermark, aggregate_version,
                  last_successful_at, health, freshness, updated_at,
                  trace_id, retention_effective_at)
                values (?, ?, ?, 'responsibility', ?, ?, ?, ?, 'healthy',
                        'fresh', ?, ?, ?)
                on conflict (source_id, feed_id, partition_id,
                             consumer_projection)
                do update set source_version=excluded.source_version,
                              source_watermark=excluded.source_watermark,
                              aggregate_version=excluded.aggregate_version,
                              last_successful_at=excluded.last_successful_at,
                              health='healthy', freshness='fresh',
                              updated_at=excluded.updated_at,
                              trace_id=excluded.trace_id,
                              retention_effective_at=
                                  excluded.retention_effective_at
                """,
                KEY.sourceId(), KEY.feedId(), KEY.partitionId(),
                sourceVersion, sourceWatermark, aggregateVersion,
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
                TRACE, java.sql.Timestamp.from(now));
    }

    private String livePayload() {
        return jdbc.queryForObject("""
                select payload_digest
                  from identity_access.ia_responsibility_current
                 where source_id=? and relation_ref_token=?
                """, String.class, KEY.sourceId(), RELATION);
    }

    private String shadowPayload() {
        return jdbc.queryForObject("""
                select payload_digest
                  from identity_access.ia_responsibility_v2_shadow_current
                 where source_id=? and relation_ref_token=?
                """, String.class, KEY.sourceId(), RELATION);
    }

    private long liveSourceVersion() {
        return jdbc.queryForObject("""
                select source_version
                  from identity_access.ia_identity_sync_checkpoint
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                """, Long.class, KEY.sourceId(), KEY.feedId(),
                KEY.partitionId());
    }

    private UUID uuidV7() {
        return UUID.fromString(UuidV7.generate(now));
    }

    private JdbcResponsibilityReconciliationAdapter store() {
        return new JdbcResponsibilityReconciliationAdapter(
                jdbc,
                new TransactionTemplate(manager),
                () -> trusted(now),
                8);
    }

    private ResponsibilityReconciliationResult matched(
            RunningResponsibilityReconciliationAttempt attempt) {
        return new ResponsibilityReconciliationResult(
                UUID.fromString(
                        "019c1234-0000-7000-8000-000000000120"),
                attempt.jobId(),
                KEY,
                attempt.businessDate(),
                1,
                1,
                java.util.Map.of("identity-authority|sandbox-0", 0L),
                0,
                0,
                DIGEST_A,
                DIGEST_A,
                0,
                0,
                0,
                0,
                new java.math.BigDecimal("1.000000"),
                0,
                0,
                "succeeded",
                "matched",
                "RESPONSIBILITY_RECONCILIATION_MATCHED",
                attempt.lease().fencingToken(),
                attempt.startedAt(),
                Instant.now(),
                TRACE,
                List.of());
    }

    private ResponsibilityReconciliationResult differences(
            RunningResponsibilityReconciliationAttempt attempt) {
        return new ResponsibilityReconciliationResult(
                UUID.fromString(
                        "019c1234-0000-7000-8000-000000000150"),
                attempt.jobId(),
                KEY,
                attempt.businessDate(),
                1,
                1,
                java.util.Map.of(
                        "identity-authority|sandbox-0",
                        0L),
                1,
                0,
                DIGEST_A,
                DIGEST_B,
                0,
                1,
                0,
                0,
                new java.math.BigDecimal("0.000000"),
                0,
                1,
                "succeeded",
                "threshold-failed",
                "RESPONSIBILITY_RECONCILIATION_THRESHOLD_FAILED",
                attempt.lease().fencingToken(),
                attempt.startedAt(),
                now.plusSeconds(1),
                TRACE,
                List.of(new ResponsibilityReconciliationDifference(
                        "missing",
                        RELATION,
                        DIGEST_A,
                        1L,
                        null,
                        DIGEST_A,
                        null,
                        "RESPONSIBILITY_RECONCILIATION_DIFFERENCES")));
    }

    private void insertInbox(String batchId, long watermark) {
        jdbc.update("""
                insert into identity_access.ia_responsibility_source_inbox (
                  batch_id, source_id, feed_id, partition_id,
                  consumer_projection, schema_version, contract_version,
                  source_version, from_watermark, to_watermark,
                  supporting_identity_org_watermarks, envelope_digest,
                  signature_digest, encrypted_payload, encrypted_data_key,
                  encryption_nonce, encryption_key_ref,
                  encryption_key_version, source_visible_at, observed_at,
                  consumer_watermark, trace_id, retention_effective_at,
                  expires_at)
                values (
                  ?,'SRC-P0-RESPONSIBILITY-001',
                  'responsibility-authority','sandbox-0','responsibility',
                  'RESPONSIBILITY-BATCH-1.0.0',
                  'RESPONSIBILITY-AUTHORITY-1.0.0',
                  1,0,?,cast(? as jsonb),?,?,decode('aa','hex'),
                  decode('bb','hex'),decode('cc','hex'),
                  'config://test/responsibility-authority-inbox','k1',
                  ?,?,?,?, ?,?)
                """,
                UUID.fromString(batchId),
                watermark,
                "{\"identity-authority|sandbox-0\":42}",
                DIGEST_A,
                DIGEST_B,
                java.sql.Timestamp.from(now.minusSeconds(60)),
                java.sql.Timestamp.from(now),
                watermark,
                TRACE,
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now.plus(Duration.ofDays(30))));
    }

    private void insertArchive() {
        jdbc.update("""
                insert into identity_access.ia_responsibility_source_archive (
                  batch_id, source_id, feed_id, partition_id,
                  consumer_projection, schema_version, contract_version,
                  source_version, from_watermark, to_watermark,
                  supporting_identity_org_watermarks, envelope_digest,
                  signature_digest, encrypted_payload, encrypted_data_key,
                  encryption_nonce, encryption_key_ref,
                  encryption_key_version, source_visible_at, observed_at,
                  applied_at, trace_id, consumer_watermark,
                  retention_effective_at, expires_at)
                values (
                  '019c1234-0000-7000-8000-000000000110',
                  'SRC-P0-RESPONSIBILITY-001',
                  'responsibility-authority','sandbox-0','responsibility',
                  'RESPONSIBILITY-BATCH-1.0.0',
                  'RESPONSIBILITY-AUTHORITY-1.0.0',
                  2,0,2,
                  cast('{"identity-authority|sandbox-0":42}' as jsonb),
                  ?,?,decode('aa','hex'),decode('bb','hex'),
                  decode('cc','hex'),
                  'config://test/responsibility-authority-inbox','k1',
                  ?,?,?,?,2,?,?)
                """,
                DIGEST_A,
                DIGEST_B,
                java.sql.Timestamp.from(now.minusSeconds(60)),
                java.sql.Timestamp.from(now.minusSeconds(30)),
                java.sql.Timestamp.from(now),
                TRACE,
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now.plus(Duration.ofDays(365))));
    }

    private void insertFact(
            String factId,
            String eventId,
            long watermark,
            String payloadDigest) {
        jdbc.update("""
                insert into identity_access.ia_responsibility_source_fact (
                  fact_id,batch_id,event_id,source_id,feed_id,partition_id,
                  consumer_projection,relation_ref_token,
                  student_ref_purpose,student_ref_key_version,
                  student_ref_token,student_ref_digest,
                  student_equivalence_digest,counselor_account_ref_digest,
                  college_organization_ref_digest,responsibility_type,
                  relation_status,effective_from,source_version,
                  source_watermark,record_version,aggregate_version,
                  payload_digest,supporting_identity_org_watermarks,
                  recipient_validity,recipient_reason_code,
                  recipient_mapped,applied_at,trace_id,consumer_watermark,
                  retention_effective_at,expires_at)
                values (
                  ?,'019c1234-0000-7000-8000-000000000110',?,
                  'SRC-P0-RESPONSIBILITY-001',
                  'responsibility-authority','sandbox-0','responsibility',?,
                  'RESPONSIBILITY-STUDENT-REF','resp-student-v1',?,
                  ?,?,?,?,'primary','active',?,2,?,?,?,
                  ?,cast(? as jsonb),'valid','RESPONSIBILITY_VALID',true,
                  ?,?,?, ?,?)
                """,
                UUID.fromString(factId),
                UUID.fromString(eventId),
                RELATION,
                STUDENT,
                DIGEST_A,
                DIGEST_A,
                DIGEST_A,
                DIGEST_A,
                java.sql.Timestamp.from(now.minusSeconds(120)),
                watermark,
                watermark,
                watermark,
                payloadDigest,
                "{\"identity-authority|sandbox-0\":42}",
                java.sql.Timestamp.from(now),
                TRACE,
                watermark,
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now.plus(Duration.ofDays(365))));
    }

    private void insertCurrent(
            String relationId,
            String relationToken,
            String partitionId,
            String studentDigest,
            String collegeDigest) {
        jdbc.update("""
                insert into identity_access.ia_responsibility_current (
                  relation_id,source_id,feed_id,partition_id,
                  consumer_projection,relation_ref_token,
                  student_ref_purpose,student_ref_key_version,
                  student_ref_token,student_ref_digest,
                  student_equivalence_digest,
                  counselor_account_ref_digest,
                  college_organization_ref_digest,responsibility_type,
                  relation_status,recipient_validity,
                  recipient_reason_code,quality_gate_status,
                  effective_from,source_version,source_watermark,
                  record_version,aggregate_version,payload_digest,
                  applied_at,trace_id,
                  retention_effective_at)
                values (
                  ?,'SRC-P0-RESPONSIBILITY-001',
                  'responsibility-authority',?,'responsibility',?,
                  'RESPONSIBILITY-STUDENT-REF','resp-student-v1',?,
                  ?,?,?,?,'primary','active','invalid',
                  'RESPONSIBILITY_RECIPIENT_ACCOUNT_NOT_ACTIVE',
                  'blocked',?,1,1,1,1,?,?,?,?)
                """,
                UUID.fromString(relationId),
                partitionId,
                relationToken,
                "stok_" + studentDigest.substring(0, 32),
                studentDigest,
                studentDigest,
                DIGEST_B,
                collegeDigest,
                java.sql.Timestamp.from(now.minusSeconds(120)),
                DIGEST_A,
                java.sql.Timestamp.from(now),
                TRACE,
                java.sql.Timestamp.from(now));
    }

    private void insertException(
            UUID exceptionId,
            String businessKey,
            String studentDigest,
            String collegeDigest,
            String status,
            long aggregateVersion) {
        Instant lastSeen = now.minusSeconds(30);
        jdbc.update("""
                insert into identity_access
                  .ia_responsibility_exception_current (
                    exception_id,business_key_digest,
                    college_organization_ref_digest,student_ref_digest,
                    reason_code,source_kind,source_version,
                    source_watermark,first_seen_at,last_seen_at,
                    resolved_at,status,aggregate_version,trace_id,
                    retention_effective_at)
                values (?,?,?,?,?,'reconciliation',1,1,?,?,?,?,?,?,?)
                """,
                exceptionId,
                businessKey,
                collegeDigest,
                studentDigest,
                "RESPONSIBILITY_RECONCILIATION_DIFFERENCES",
                java.sql.Timestamp.from(now.minusSeconds(120)),
                java.sql.Timestamp.from(lastSeen),
                "resolved".equals(status)
                        ? java.sql.Timestamp.from(
                                now.minusSeconds(10))
                        : null,
                status,
                aggregateVersion,
                TRACE,
                java.sql.Timestamp.from(now));
    }

    private String exceptionStatus(UUID exceptionId) {
        return jdbc.queryForObject("""
                select status
                  from identity_access.ia_responsibility_exception_current
                 where exception_id=?
                """,
                String.class,
                exceptionId);
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(
                                    StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private long count(String table) {
        return jdbc.queryForObject(
                "select count(*) from identity_access." + table,
                Long.class);
    }

    private void assertAllowed(String role, String sql)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("set role " + role);
            statement.execute(sql);
        }
    }

    private void assertDenied(String role, String sql) {
        SQLException denied = assertThrows(SQLException.class, () -> {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("set role " + role);
                statement.execute(sql);
            }
        });
        assertEquals("42501", denied.getSQLState());
    }

    private static TrustedTime trusted(Instant instant) {
        return new TrustedTime(
                instant,
                new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        instant.minusSeconds(10),
                        instant.plusSeconds(50),
                        "evidence://signed/clock/campus-ntp-a.json"));
    }

    private static DataSource dataSource(String url) {
        return new DriverManagerDataSource(
                url,
                requiredProperty("scholarsense.audit.pg.user"),
                "");
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " is required; use "
                            + "scripts/run_audit_postgresql_tests.sh");
        }
        return value;
    }
}
