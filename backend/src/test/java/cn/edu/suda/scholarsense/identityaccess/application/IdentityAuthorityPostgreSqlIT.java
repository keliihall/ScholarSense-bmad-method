package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HmacIdentityAuditTokenAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.IdentitySyncAuditAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAuthoritativeIdentityContextAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityAuditAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityReplayAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityProjectionRebuildAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentitySyncJobAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentitySyncRepository;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentitySyncTransactionAdapter;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeAccount;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.identityaccess.domain.EmploymentRoleBinding;
import cn.edu.suda.scholarsense.identityaccess.domain.OrganizationNode;
import cn.edu.suda.scholarsense.identityaccess.domain.OrganizationType;
import cn.edu.suda.scholarsense.identityaccess.domain.TargetRole;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL 18.4 identity authority evidence, run by run_audit_postgresql_tests.sh. */
class IdentityAuthorityPostgreSqlIT {
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "identity-authority",
            "sandbox-0",
            "identity-org");
    private static final String TRACE = "0123456789abcdef0123456789abcdef";

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private Instant now;

    @BeforeEach
    void setUp() {
        dataSource = dataSource(requiredProperty("scholarsense.audit.pg.url"));
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.execute("""
                truncate table
                  identity_access.ia_identity_slo_compensation,
                  identity_access.ia_identity_slo_evidence,
                  identity_access.ia_identity_reconciliation_sample,
                  identity_access.ia_identity_replay_request,
                  identity_access.ia_identity_rejected_record,
                  identity_access.ia_authoritative_role_current,
                  identity_access.ia_authoritative_organization_current,
                  identity_access.ia_authoritative_subject_binding_history,
                  identity_access.ia_authoritative_account_current,
                  identity_access.ia_identity_source_fact,
                  identity_access.ia_identity_source_archive,
                  identity_access.ia_identity_source_inbox,
                  identity_access.ia_identity_sync_lease,
                  identity_access.ia_identity_sync_attempt,
                  identity_access.ia_identity_sync_failure_resolution,
                  identity_access.ia_identity_sync_job,
                  identity_access.ia_identity_sync_checkpoint,
                  identity_access.ia_local_audit_outbox,
                  identity_access.ia_local_audit_fact
                cascade
                """);
    }

    @Test
    void cleanAndUpgradePathsContainV6EncryptionFencingAndReplayState() {
        assertEquals("180004", jdbc.queryForObject(
                "select current_setting('server_version_num')", String.class));
        for (String table : List.of(
                "ia_identity_source_inbox",
                "ia_identity_source_archive",
                "ia_identity_sync_checkpoint",
                "ia_identity_sync_failure_resolution",
                "ia_identity_replay_request",
                "ia_identity_slo_evidence",
                "ia_identity_slo_compensation",
                "ia_authoritative_subject_binding_history")) {
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from information_schema.tables "
                            + "where table_schema='identity_access' and table_name=?",
                    Integer.class,
                    table));
        }
        for (String column : List.of("encrypted_data_key", "encryption_nonce")) {
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from information_schema.columns "
                            + "where table_schema='identity_access' "
                            + "and table_name='ia_identity_source_inbox' and column_name=?",
                    Integer.class,
                    column));
        }

        JdbcTemplate upgraded = new JdbcTemplate(dataSource(
                requiredProperty("scholarsense.audit.pg.upgrade-url")));
        assertEquals(1, upgraded.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema='identity_access' "
                        + "and table_name='ia_identity_sync_checkpoint'",
                Integer.class));
    }

    @Test
    void twoWorkersHaveOneLeaseWinnerThenExpiredTransferFencesOldWriter() throws Exception {
        IdentitySyncJob first = job("501");
        IdentitySyncJob second = job("502");
        List<Callable<Boolean>> enqueues = List.of(
                () -> jobs(dataSource).enqueueIfEligible(first),
                () -> jobs(dataSource).enqueueIfEligible(second));
        List<Boolean> enqueueResults = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (var future : executor.invokeAll(enqueues)) {
                enqueueResults.add(future.get());
            }
        }
        assertEquals(1, enqueueResults.stream().filter(Boolean::booleanValue).count());
        UUID activeJobId = jdbc.queryForObject(
                "select job_id from identity_access.ia_identity_sync_job "
                        + "where status='queued'",
                UUID.class);

        List<Callable<Optional<RunningIdentitySyncAttempt>>> claims = List.of(
                () -> jobs(dataSource).start(activeJobId, "worker-a", now),
                () -> jobs(dataSource).start(activeJobId, "worker-b", now));
        List<Optional<RunningIdentitySyncAttempt>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (var future : executor.invokeAll(claims)) {
                results.add(future.get());
            }
        }
        assertEquals(1, results.stream().filter(Optional::isPresent).count());
        RunningIdentitySyncAttempt winner =
                results.stream().flatMap(Optional::stream).findFirst().orElseThrow();

        Instant transferAt = now.plus(Duration.ofMinutes(3));
        RunningIdentitySyncAttempt transferred =
                jobs(dataSource).start(activeJobId, "worker-c", transferAt).orElseThrow();

        assertTrue(transferred.lease().fencingToken() > winner.lease().fencingToken());
        IdentitySyncJob staleCompletion = winner.job().transitionTo(
                IdentitySyncJobStatus.SUCCEEDED,
                IdentitySourceHealth.HEALTHY,
                IdentityProjectionFreshness.FRESH,
                transferAt,
                null,
                null,
                1);
        IdentitySyncException fenced = assertThrows(
                IdentitySyncException.class,
                () -> jobs(dataSource).save(winner, staleCompletion, transferAt));
        assertEquals("IDENTITY_SYNC_FENCING_STALE", fenced.code());
    }

    @Test
    void expiredLeaseCannotCreateAnAttemptBeyondTheRetryBudget() {
        var jobs = jobs(dataSource);
        IdentitySyncJob exhausted = new IdentitySyncJob(
                uuid("505"),
                KEY,
                IdentitySyncJobStatus.QUEUED,
                IdentitySourceHealth.DEGRADED,
                IdentityProjectionFreshness.STALE,
                now,
                null,
                0,
                now,
                1,
                0,
                null,
                TRACE);
        jobs.enqueue(exhausted);
        jobs.start(exhausted.jobId(), "worker-timeout", now).orElseThrow();

        assertTrue(jobs.start(
                exhausted.jobId(),
                "worker-recovery",
                now.plus(Duration.ofMinutes(3))).isEmpty());
        assertEquals("failed", jdbc.queryForObject(
                "select status from identity_access.ia_identity_sync_job where job_id=?",
                String.class,
                exhausted.jobId()));
        assertEquals("IDENTITY_SYNC_RETRY_BUDGET_EXHAUSTED", jdbc.queryForObject(
                "select reason_code from identity_access.ia_identity_sync_job where job_id=?",
                String.class,
                exhausted.jobId()));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from identity_access.ia_identity_sync_attempt where job_id=?",
                Long.class,
                exhausted.jobId()));
        assertTrue(jobs.nextDue(now.plus(Duration.ofMinutes(4))).isEmpty());
    }

    @Test
    void signedHeartbeatRefreshesCheckpointWithoutAdvancingWatermark() {
        var jobs = jobs(dataSource);
        IdentitySyncJob heartbeat = job("506");
        jobs.enqueue(heartbeat);
        RunningIdentitySyncAttempt attempt =
                jobs.start(heartbeat.jobId(), "worker-heartbeat", now)
                        .orElseThrow();
        Instant completedAt = now.plusSeconds(1);
        IdentitySyncJob completed = attempt.job().transitionTo(
                IdentitySyncJobStatus.SUCCEEDED,
                IdentitySourceHealth.HEALTHY,
                IdentityProjectionFreshness.FRESH,
                completedAt,
                null,
                null,
                attempt.inputWatermark());

        jobs.save(attempt, completed, completedAt);

        assertEquals(0L, jdbc.queryForObject(
                "select source_watermark "
                        + "from identity_access.ia_identity_sync_checkpoint",
                Long.class));
        assertEquals("healthy", jdbc.queryForObject(
                "select health from identity_access.ia_identity_sync_checkpoint",
                String.class));
        assertEquals("fresh", jdbc.queryForObject(
                "select freshness from identity_access.ia_identity_sync_checkpoint",
                String.class));
        assertEquals(completedAt, jdbc.queryForObject(
                        "select last_successful_at "
                                + "from identity_access.ia_identity_sync_checkpoint",
                        Timestamp.class)
                .toInstant());
    }

    @Test
    void terminalFailureNeedsDurableOperatorResolutionBeforeReplacement() {
        var jobs = jobs(dataSource);
        IdentitySyncJob failed = job("507");
        jobs.enqueue(failed);
        RunningIdentitySyncAttempt attempt =
                jobs.start(failed.jobId(), "worker-terminal", now).orElseThrow();
        IdentitySyncJob terminal = attempt.job().transitionTo(
                IdentitySyncJobStatus.FAILED,
                IdentitySourceHealth.DEGRADED,
                IdentityProjectionFreshness.STALE,
                now.plusSeconds(1),
                null,
                "IDENTITY_SOURCE_PAYLOAD_INVALID",
                0);
        jobs.save(attempt, terminal, now.plusSeconds(1));
        assertFalse(jobs.enqueueIfEligible(job("508")));

        IdentitySyncJob replacement = new IdentitySyncJob(
                uuid("509"),
                KEY,
                IdentitySyncJobStatus.QUEUED,
                IdentitySourceHealth.DEGRADED,
                IdentityProjectionFreshness.STALE,
                now.plusSeconds(2),
                null,
                0,
                now.plusSeconds(2),
                3,
                0,
                null,
                TRACE);
        boolean resolved = jobs.resolveFailureAndEnqueue(
                new IdentitySyncFailureResolution(
                        uuid("510"),
                        KEY,
                        "operator:hei",
                        "UPSTREAM_DATA_REPAIRED",
                        TRACE,
                        now.plusSeconds(2)),
                replacement);

        assertTrue(resolved);
        assertEquals(1L, count("ia_identity_sync_failure_resolution"));
        assertEquals("queued", jdbc.queryForObject(
                "select status from identity_access.ia_identity_sync_job "
                        + "where job_id=?",
                String.class,
                replacement.jobId()));
    }

    @Test
    void factProjectionCheckpointAndAuditRollbackTogetherThenReadBackRecordsSlo() {
        var jobs = jobs(dataSource);
        IdentitySyncJob job = job("503");
        jobs.enqueue(job);
        RunningIdentitySyncAttempt attempt =
                jobs.start(job.jobId(), "worker-atomic", now).orElseThrow();
        NormalizedIdentityBatch baseBatch = batch(0, 1, 1);
        AuthoritativeAccount originalAccount = baseBatch.accounts().getFirst();
        String historicalBinding =
                "actor_v1_k1_" + digest("historical-binding");
        String currentBinding =
                "actor_v1_k2_" + digest("current-binding");
        NormalizedIdentityBatch batch = baseBatch.withAccounts(List.of(new AuthoritativeAccount(
                originalAccount.accountId(),
                originalAccount.sourceId(),
                originalAccount.externalRefDigest(),
                currentBinding,
                List.of(currentBinding, historicalBinding),
                originalAccount.status(),
                originalAccount.effectiveInterval(),
                originalAccount.sourceVersion(),
                originalAccount.aggregateVersion())));
        JdbcIdentitySyncRepository repository = new JdbcIdentitySyncRepository(jdbc);
        var transactions = new JdbcIdentitySyncTransactionAdapter(
                new TransactionTemplate(manager));
        TrustedTimeSource trusted = () -> trusted(now);
        var replay = new JdbcIdentityReplayAdapter(jdbc, trusted);
        var failing = new IdentitySyncService(
                repository,
                replay,
                transactions,
                ignored -> {
                    throw new IllegalStateException("injected audit failure");
                },
                ignored -> {},
                ignored -> Optional.empty(),
                repository,
                trusted);

        IdentitySyncException rolledBack = assertThrows(
                IdentitySyncException.class,
                () -> failing.process(batch, attempt.lease()));
        assertEquals("IDENTITY_SYNC_PERSISTENCE_UNAVAILABLE", rolledBack.code());
        assertEquals(0L, count("ia_identity_source_inbox"));
        assertEquals(0L, count("ia_authoritative_account_current"));
        assertEquals(0L, count("ia_local_audit_fact"));
        assertEquals(0L, jdbc.queryForObject(
                "select source_watermark from identity_access.ia_identity_sync_checkpoint "
                        + "where source_id=? and feed_id=? and partition_id=? "
                        + "and consumer_projection=?",
                Long.class,
                KEY.sourceId(), KEY.feedId(), KEY.partitionId(), KEY.consumerProjection()));

        var localAudit = new JdbcIdentityAuditAdapter(
                jdbc, new TransactionTemplate(manager), new ObjectMapper());
        var facts = new IdentityAuditFactFactory(
                trusted,
                new HmacIdentityAuditTokenAdapter(
                        new SecretKeySpec(new byte[32], "HmacSHA256"), "k1"));
        var context = new JdbcAuthoritativeIdentityContextAdapter(
                jdbc, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(15));
        AuthorizationEffectivenessProbePort probe = subject ->
                context.findCurrent(subject).map(value -> new AuthorizationEffectiveContext(
                        value.accountId(),
                        value.sourceVersion(),
                        value.sourceWatermark(),
                        AuthorizationFreshness.FRESH));
        var service = new IdentitySyncService(
                repository,
                replay,
                transactions,
                new IdentitySyncAuditAdapter(facts, localAudit),
                ignored -> {},
                probe,
                repository,
                trusted);

        IdentitySyncResult result = service.process(batch, attempt.lease());

        assertEquals(IdentitySyncOutcome.APPLIED, result.outcome());
        assertEquals(1L, count("ia_identity_source_inbox"));
        assertEquals(1L, count("ia_identity_source_archive"));
        assertEquals(3L, count("ia_identity_source_fact"));
        assertEquals(1L, count("ia_authoritative_account_current"));
        assertEquals("苏州大学", jdbc.queryForObject(
                "select display_name "
                        + "from identity_access.ia_authoritative_organization_current",
                String.class));
        assertEquals(1L, count("ia_local_audit_fact"));
        assertEquals(1L, count("ia_local_audit_outbox"));
        assertEquals(3L, count("ia_identity_slo_evidence"));
        assertEquals(3L, jdbc.queryForObject(
                "select count(*) from identity_access.ia_identity_slo_evidence "
                        + "where within_fifteen_minutes",
                Long.class));
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from identity_access.ia_identity_slo_evidence "
                        + "where late_reason_code='IDENTITY_AUTHORIZATION_READBACK_EMPTY'",
                Long.class));
        assertFalse(jdbc.queryForObject(
                "select encode(encrypted_data_key, 'hex') "
                        + "from identity_access.ia_identity_source_inbox",
                String.class).isBlank());
        assertTrue(new JdbcAuthoritativeIdentityContextAdapter(
                        jdbc,
                        Clock.fixed(
                                now.plus(Duration.ofHours(23)),
                                ZoneOffset.UTC),
                        Duration.ofMinutes(15))
                .findCurrent(historicalBinding)
                .isPresent());
        assertTrue(new JdbcAuthoritativeIdentityContextAdapter(
                        jdbc,
                        Clock.fixed(
                                now.plus(Duration.ofHours(25)),
                                ZoneOffset.UTC),
                        Duration.ofMinutes(15))
                .findCurrent(historicalBinding)
                .isEmpty());
        assertTrue(new JdbcAuthoritativeIdentityContextAdapter(
                        jdbc,
                        Clock.fixed(
                                now.plus(Duration.ofHours(25)),
                                ZoneOffset.UTC),
                        Duration.ofMinutes(15))
                .findCurrent(currentBinding)
                .isPresent());

        IdentitySyncJob completedJob = attempt.job().transitionTo(
                IdentitySyncJobStatus.SUCCEEDED,
                IdentitySourceHealth.HEALTHY,
                IdentityProjectionFreshness.FRESH,
                now,
                null,
                null,
                batch.toWatermark());
        jobs.save(attempt, completedJob, now);
        var rebuildStore = new JdbcIdentityProjectionRebuildAdapter(
                jdbc, repository, jobs);
        var rebuild = new IdentityProjectionRebuildService(
                rebuildStore,
                (archive, plaintext, references) -> batch,
                (encrypted, purpose) -> "{}".toCharArray(),
                transactions,
                () -> trusted(now.plus(Duration.ofMinutes(3))));
        IdentityProjectionRebuildResult rebuilt =
                rebuild.rebuild(KEY, TRACE);
        assertEquals(1, rebuilt.batchCount());
        assertEquals(3, rebuilt.factCount());
        assertTrue(context.findCurrent(currentBinding).isPresent());

        repository.reject(new IdentitySyncRejection(
                uuid("531"),
                batch.batchId(),
                KEY,
                1,
                1,
                digest("retention-rejection"),
                "IDENTITY_SOURCE_PAYLOAD_INVALID",
                false,
                job.jobId(),
                attempt.attemptNo(),
                TRACE,
                now));
        assertEquals(30.0, retentionDays("ia_identity_source_inbox"));
        assertEquals(365.0, retentionDays("ia_identity_source_archive"));
        assertEquals(365.0, retentionDays("ia_identity_source_fact"));
        assertEquals(180.0, retentionDays("ia_identity_rejected_record"));
        assertEquals(90.0, retentionDays("ia_identity_sync_job"));
        assertEquals(1095.0, retentionDays("ia_identity_slo_evidence"));
        assertEquals("RS-1.0.0", jdbc.queryForObject(
                "select retention_schedule_version "
                        + "from identity_access.ia_identity_source_inbox",
                String.class));
        assertEquals("identity-access", jdbc.queryForObject(
                "select retention_owner "
                        + "from identity_access.ia_identity_source_inbox",
                String.class));

        Instant inboxCandidateAt = now.plus(Duration.ofDays(31));
        assertEquals(0L, inboxCandidates(inboxCandidateAt, 0));
        assertEquals(1L, inboxCandidates(inboxCandidateAt, 1));
        jdbc.update("""
                update identity_access.ia_identity_source_inbox
                   set legal_hold=true
                """);
        assertEquals(0L, inboxCandidates(inboxCandidateAt, 1));

        jdbc.update("""
                update identity_access.ia_authoritative_organization_current
                   set status='inactive'
                """);
        assertTrue(context.findCurrent(
                currentBinding).isEmpty());
    }

    @Test
    void databaseRolesEnforceWorkerReaderAndSessionTokenSeparation() throws Exception {
        assertAllowed("scholarsense_identity_current_reader",
                "select count(*) from identity_access.ia_authoritative_account_current");
        assertDenied("scholarsense_identity_current_reader",
                "insert into identity_access.ia_identity_sync_checkpoint "
                        + "(source_id,feed_id,partition_id,consumer_projection,"
                        + "updated_at,trace_id) values "
                        + "('SRC-P0-RESPONSIBILITY-001','identity-authority','x',"
                        + "'identity-org',now(),'" + TRACE + "')");
        assertDenied("scholarsense_identity_current_reader",
                "select count(*) from identity_access.ia_refresh_secret");
        assertDenied("scholarsense_identity_sync_worker",
                "select count(*) from identity_access.ia_refresh_secret");
        assertDenied("scholarsense_identity_sync_worker",
                "update identity_access.ia_identity_session set session_version=session_version");
        assertAllowed("scholarsense_identity_sync_worker",
                "select count(*) from identity_access.ia_identity_sync_job");
        assertAllowed("scholarsense_identity_online",
                "select count(*) from identity_access.ia_authoritative_account_current");
        assertDenied("scholarsense_identity_online",
                "update identity_access.ia_authoritative_account_current "
                        + "set source_version=source_version");
    }

    private JdbcIdentitySyncJobAdapter jobs(DataSource source) {
        return jobs(source, new DataSourceTransactionManager(source));
    }

    private static JdbcIdentitySyncJobAdapter jobs(
            DataSource source, DataSourceTransactionManager transactionManager) {
        return new JdbcIdentitySyncJobAdapter(
                new JdbcTemplate(source), new TransactionTemplate(transactionManager));
    }

    private IdentitySyncJob job(String suffix) {
        return new IdentitySyncJob(
                uuid(suffix),
                KEY,
                IdentitySyncJobStatus.QUEUED,
                IdentitySourceHealth.DEGRADED,
                IdentityProjectionFreshness.STALE,
                now,
                null,
                0,
                now,
                5,
                0,
                null,
                TRACE);
    }

    private NormalizedIdentityBatch batch(
            long fromWatermark, long toWatermark, long sourceVersion) {
        EffectiveInterval interval =
                new EffectiveInterval(now.minus(Duration.ofMinutes(5)), null);
        UUID accountId = uuid("511");
        UUID organizationId = uuid("512");
        var account = new AuthoritativeAccount(
                accountId,
                KEY.sourceId(),
                digest("account"),
                "actor_v1_k1_" + digest("issuer\0subject"),
                AuthoritativeStatus.ACTIVE,
                interval,
                sourceVersion,
                1);
        var organization = new OrganizationNode(
                organizationId,
                KEY.sourceId(),
                digest("organization"),
                null,
                "苏州大学",
                OrganizationType.SCHOOL,
                AuthoritativeStatus.ACTIVE,
                interval,
                sourceVersion,
                1);
        var role = new EmploymentRoleBinding(
                uuid("513"),
                accountId,
                organizationId,
                digest("role"),
                "SANDBOX_STUDENT_AFFAIRS",
                TargetRole.R3_STUDENT_AFFAIRS,
                "IDENTITY-ROLE-MAPPING-1.0.0",
                AuthoritativeStatus.ACTIVE,
                interval,
                sourceVersion,
                1);
        return new NormalizedIdentityBatch(
                uuid("514"),
                KEY,
                "IDENTITY-AUTHORITY-BATCH-1.0.0",
                sourceVersion,
                fromWatermark,
                toWatermark,
                now.minus(Duration.ofMinutes(5)),
                now.minus(Duration.ofMinutes(4)),
                TRACE,
                "IDENTITY-ROLE-MAPPING-1.0.0",
                digest("mapping"),
                digest("envelope"),
                digest("signature"),
                true,
                "encrypted".getBytes(StandardCharsets.UTF_8),
                "wrapped".getBytes(StandardCharsets.UTF_8),
                "nonce".getBytes(StandardCharsets.UTF_8),
                "config://test/identity-authority-inbox",
                "k1",
                List.of(account),
                List.of(organization),
                List.of(role),
                List.of(
                        fact("521", IdentityRecordKind.ACCOUNT, account.externalRefDigest()),
                        fact("522", IdentityRecordKind.ORGANIZATION,
                                organization.externalRefDigest()),
                        fact("523", IdentityRecordKind.EMPLOYMENT_ROLE,
                                role.externalRefDigest())));
    }

    private IdentitySourceFact fact(
            String suffix, IdentityRecordKind kind, String externalDigest) {
        return new IdentitySourceFact(
                uuid(suffix),
                kind,
                externalDigest,
                1,
                new EffectiveInterval(now.minus(Duration.ofMinutes(5)), null),
                digest("payload-" + suffix),
                1);
    }

    private long count(String table) {
        return jdbc.queryForObject(
                "select count(*) from identity_access." + table, Long.class);
    }

    private double retentionDays(String table) {
        return jdbc.queryForObject(
                "select extract(epoch from (expires_at - retention_effective_at))"
                        + " / 86400.0 from identity_access." + table + " limit 1",
                Double.class);
    }

    private long inboxCandidates(Instant candidateAt, long safeConsumerWatermark) {
        return jdbc.queryForObject("""
                select count(*)
                  from identity_access.ia_identity_source_inbox
                 where expires_at<=? and not legal_hold and consumer_watermark<=?
                """,
                Long.class,
                Timestamp.from(candidateAt),
                safeConsumerWatermark);
    }

    private void assertAllowed(String role, String sql) throws Exception {
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

    private static TrustedTime trusted(Instant now) {
        return new TrustedTime(
                now,
                new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        now.minusSeconds(10),
                        now.plusSeconds(50),
                        "evidence://signed/clock/campus-ntp-a.json"));
    }

    private static UUID uuid(String suffix) {
        return UUID.fromString("019c1234-0000-7000-8000-000000000" + suffix);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static DataSource dataSource(String url) {
        return new DriverManagerDataSource(
                url, requiredProperty("scholarsense.audit.pg.user"), "");
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " is required; use scripts/run_audit_postgresql_tests.sh");
        }
        return value;
    }
}
