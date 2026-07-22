package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityAccessStore;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityAuditAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcSessionTransactionAdapter;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL 18.4 evidence. This class is run only by scripts/run_audit_postgresql_tests.sh. */
class IdentityAuditPostgreSqlIT {
    private static final String REQUEST_DIGEST = "a".repeat(64);

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcIdentityAccessStore store;
    private JdbcIdentityAuditAdapter audit;
    private JdbcSessionTransactionAdapter transactions;
    private Instant now;

    @BeforeEach
    void setUp() {
        dataSource = dataSource(requiredProperty("scholarsense.audit.pg.url"));
        jdbc = new JdbcTemplate(dataSource);
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var manager = new DataSourceTransactionManager(dataSource);
        transactions = new JdbcSessionTransactionAdapter(new TransactionTemplate(manager));
        store = new JdbcIdentityAccessStore(jdbc);
        audit = new JdbcIdentityAuditAdapter(jdbc, new TransactionTemplate(manager), new ObjectMapper());
        removeFailureInjection();
        jdbc.execute("truncate table identity_access.ia_local_audit_outbox, "
                + "identity_access.ia_idempotency_result, identity_access.ia_remote_logout_outbox, "
                + "identity_access.ia_local_audit_fact, identity_access.ia_refresh_secret, "
                + "identity_access.ia_identity_session cascade");
    }

    @AfterEach
    void tearDown() {
        removeFailureInjection();
    }

    @Test
    void exactServerAndBothCleanAndLegacyUpgradePathsAreProven() {
        assertEquals("180004", jdbc.queryForObject(
                "select current_setting('server_version_num')", String.class));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema='identity_access' and table_name='ia_local_audit_outbox'",
                Integer.class));

        JdbcTemplate upgraded = new JdbcTemplate(dataSource(
                requiredProperty("scholarsense.audit.pg.upgrade-url")));
        assertEquals("180004", upgraded.queryForObject(
                "select current_setting('server_version_num')", String.class));
        assertEquals("SESSION-AUDIT-LEGACY-1.2.0", upgraded.queryForObject(
                "select schema_version from identity_access.ia_local_audit_fact "
                        + "where trace_id='trace-legacy-upgrade'",
                String.class));
        assertEquals(1, upgraded.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema='identity_access' and table_name='ia_local_audit_outbox'",
                Integer.class));
    }

    @Test
    void commandCommitsStateIdempotencyBusinessOutboxFactAndAuditOutboxExactlyOnce() {
        seedSession("session-atomic");
        SessionCommandService service = service(store);
        SessionCommand command = command("session-atomic", "logout-key-atomic", "trace-atomic-01");

        SessionCommandResult first = service.execute(command);
        SessionCommandResult replay = service.execute(command);

        assertEquals(first, replay);
        assertEquals(2L, scalarLong(
                "select session_version from identity_access.ia_identity_session where session_id='session-atomic'"));
        assertEquals("REVOKED", jdbc.queryForObject(
                "select status from identity_access.ia_identity_session where session_id='session-atomic'",
                String.class));
        assertAtomicCounts(1, 1, 1, 1);
        assertEquals(1L, scalarLong(
                "select count(*) from identity_access.ia_idempotency_result "
                        + "where expires_at > clock_timestamp()"));
        String factText = jdbc.queryForObject(
                "select row_to_json(f)::text from identity_access.ia_local_audit_fact f", String.class);
        assertNotNull(factText);
        assertTrue(!factText.contains("192.0.2.10") && !factText.contains("actor-raw-account"));
    }

    @Test
    void factConstraintFailureRollsBackTheEntireCommand() {
        assertRollbackForInsertTrigger("ia_local_audit_fact", "audit_fact_failure");
    }

    @Test
    void auditOutboxConstraintFailureRollsBackTheEntireCommand() {
        assertRollbackForInsertTrigger("ia_local_audit_outbox", "audit_outbox_failure");
    }

    @Test
    void businessOutboxConstraintFailureAlsoRollsBackPreviouslyAppendedAudit() {
        assertRollbackForInsertTrigger("ia_remote_logout_outbox", "business_outbox_failure");
    }

    @Test
    void deferredCommitFailureRollsBackEveryLocalWriteAndReturnsNoSuccess() {
        seedSession("session-commit");
        jdbc.execute("""
                create or replace function identity_access.audit_commit_failure() returns trigger
                language plpgsql as $body$
                begin
                  raise exception 'injected commit failure' using errcode = '23514';
                end
                $body$
                """);
        jdbc.execute("""
                create constraint trigger audit_commit_failure
                after insert on identity_access.ia_idempotency_result
                deferrable initially deferred for each row
                execute function identity_access.audit_commit_failure()
                """);

        assertThrows(RuntimeException.class, () -> service(store).execute(
                command("session-commit", "logout-key-commit", "trace-commit-01")));

        assertSessionUnchanged("session-commit");
        assertAtomicCounts(0, 0, 0, 0);
    }

    @Test
    void concurrentCommandsHaveOneWinnerAndOneCommittedVersionRejectionFact() throws Exception {
        seedSession("session-concurrent");
        CyclicBarrier bothReadVersionOne = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        IdentitySessionRepository synchronizedReads = new IdentitySessionRepository() {
            @Override
            public Optional<IdentitySession> findById(String sessionId) {
                Optional<IdentitySession> result = store.findById(sessionId);
                if (reads.incrementAndGet() <= 2) {
                    try {
                        bothReadVersionOne.await();
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }
                return result;
            }

            @Override
            public void save(IdentitySession session) {
                store.save(session);
            }
        };
        SessionCommandService service = service(synchronizedReads);
        List<Callable<Object>> commands = List.of(
                () -> executeOrFailure(service, command(
                        "session-concurrent", "logout-key-winner-a", "trace-winner-01")),
                () -> executeOrFailure(service, command(
                        "session-concurrent", "logout-key-winner-b", "trace-winner-02")));

        List<Object> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (var future : executor.invokeAll(commands)) {
                results.add(future.get());
            }
        }

        assertEquals(1, results.stream().filter(SessionCommandResult.class::isInstance).count());
        assertEquals(1, results.stream()
                .filter(IdentityAccessException.class::isInstance)
                .map(IdentityAccessException.class::cast)
                .filter(error -> "IDENTITY_SESSION_VERSION_CONFLICT".equals(error.code()))
                .count());
        assertAtomicCounts(1, 1, 2, 2);
        assertEquals(1L, scalarLong(
                "select count(*) from identity_access.ia_local_audit_fact where outcome='accepted'"));
        assertEquals(1L, scalarLong(
                "select count(*) from identity_access.ia_local_audit_fact "
                        + "where outcome='rejected' and reason_code='IDENTITY_SESSION_VERSION_CONFLICT'"));
    }

    @Test
    void identifiersAreUniqueUuidV7AndOnlineWriterCannotMutateFacts() throws Exception {
        IdentityAuditRecord record = AuditTestSupport.factory().create(auditRequest("trace-privilege-01"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("set role scholarsense_identity_online");
            var roleDataSource = new SingleConnectionDataSource(connection, true);
            var roleManager = new DataSourceTransactionManager(roleDataSource);
            new JdbcIdentityAuditAdapter(
                    new JdbcTemplate(roleDataSource), new TransactionTemplate(roleManager), new ObjectMapper())
                    .append(record);
            statement.execute("reset role");
        }

        assertEquals(7, record.fact().auditId().version());
        assertEquals(7, record.outbox().eventId().version());
        IdentityAccessException duplicate = assertThrows(IdentityAccessException.class, () -> audit.append(record));
        assertEquals("IDENTITY_AUDIT_UNAVAILABLE", duplicate.code());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "update identity_access.ia_local_audit_fact set schema_version='UNKNOWN-1.0.0' "
                        + "where audit_id=?", record.fact().auditId()));
        assertRoleDenied("update identity_access.ia_local_audit_fact set reason_code=reason_code");
        assertRoleDenied("delete from identity_access.ia_local_audit_fact");
        assertRoleDenied("truncate table identity_access.ia_local_audit_fact");
        assertRoleDenied("insert into identity_access.ia_local_audit_fact "
                + "(audit_id, actor_pseudonym, session_pseudonym, action, result, occurred_at, "
                + "source_ip_pseudonym, trace_id, profile_version, schema_version, producer_module, "
                + "actor_type, role_ids, authorization_context, outcome, reason_code, recorded_at, "
                + "time_source_profile, tokenization_profile_version, key_version, policy_versions, "
                + "retention_schedule_version) values "
                + "('019bf18e-6c00-7000-8000-000000000099', 'a', 's', 'identity.session.view', "
                + "'accepted', now(), 'ip', '0123456789abcdef0123456789abcdef', 'ISP-1.0.0', "
                + "'SESSION-AUDIT-LEGACY-1.2.0', 'identity-access', 'service', '[]'::jsonb, "
                + "'{}'::jsonb, 'accepted', 'AUTHORIZATION_ALLOWED', now(), '{}'::jsonb, "
                + "'AUDIT-TOKENIZATION-1.0.0', 'k1', '{}'::jsonb, 'RS-1.0.0')");
    }

    private void assertRollbackForInsertTrigger(String table, String functionName) {
        String sessionId = "session-" + functionName;
        seedSession(sessionId);
        jdbc.execute("create or replace function identity_access." + functionName + "() returns trigger "
                + "language plpgsql as $body$ begin raise exception 'injected constraint failure' "
                + "using errcode = '23514'; end $body$");
        jdbc.execute("create trigger " + functionName + " before insert on identity_access." + table
                + " for each row execute function identity_access." + functionName + "()");

        assertThrows(RuntimeException.class, () -> service(store).execute(
                command(sessionId, "logout-key-" + functionName, "trace-rollback-01")));

        assertSessionUnchanged(sessionId);
        assertAtomicCounts(0, 0, 0, 0);
    }

    private SessionCommandService service(IdentitySessionRepository sessions) {
        return new SessionCommandService(
                sessions, store, AuditTestSupport.factory(), audit, store, transactions,
                Clock.fixed(now.plusSeconds(30), ZoneOffset.UTC));
    }

    private void seedSession(String sessionId) {
        store.save(IdentitySession.authenticate(
                sessionId, "sp_01234567890123456789", "actor-raw-account", "browser-binding",
                "https://app.test.invalid", "refresh-family", "refresh-digest", now));
    }

    private static SessionCommand command(String sessionId, String key, String trace) {
        return new SessionCommand(
                SessionCommandType.LOGOUT, sessionId, 1,
                "idem_" + digestSeed(key).substring(0, 43), REQUEST_DIGEST,
                "192.0.2.10", digestSeed(trace).substring(0, 32));
    }

    private static IdentityAuditRequest auditRequest(String trace) {
        return new IdentityAuditRequest(
                cn.edu.suda.scholarsense.shared.outbox.ActorType.SERVICE,
                "audit-test-service", List.of(),
                new cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditAuthorizationContext(
                        "not-applicable", null, List.of(), List.of(), "SERVICE_OPERATION"),
                IdentityAuditAction.SESSION_VIEW, "accepted", "AUTHORIZATION_ALLOWED",
                null, null, "SESSION_CONTINUITY", null, "192.0.2.20",
                digestSeed(trace).substring(0, 32),
                null, null, null, null, java.util.Map.of("identitySessionPolicy", "ISP-1.0.0"));
    }

    private static String digestSeed(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Object executeOrFailure(SessionCommandService service, SessionCommand command) {
        try {
            return service.execute(command);
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private void assertSessionUnchanged(String sessionId) {
        assertEquals(1L, scalarLong(
                "select session_version from identity_access.ia_identity_session where session_id='"
                        + sessionId + "'"));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "select status from identity_access.ia_identity_session where session_id=?",
                String.class, sessionId));
    }

    private void assertAtomicCounts(long idempotency, long businessOutbox, long facts, long auditOutbox) {
        assertEquals(idempotency, scalarLong("select count(*) from identity_access.ia_idempotency_result"));
        assertEquals(businessOutbox, scalarLong("select count(*) from identity_access.ia_remote_logout_outbox"));
        assertEquals(facts, scalarLong("select count(*) from identity_access.ia_local_audit_fact"));
        assertEquals(auditOutbox, scalarLong("select count(*) from identity_access.ia_local_audit_outbox"));
    }

    private long scalarLong(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? -1 : value;
    }

    private void assertRoleDenied(String sql) throws Exception {
        SQLException error = assertThrows(SQLException.class, () -> {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("set role scholarsense_identity_online");
                statement.execute(sql);
            }
        });
        assertEquals("42501", error.getSQLState());
    }

    private void removeFailureInjection() {
        for (String name : List.of(
                "audit_fact_failure", "audit_outbox_failure", "business_outbox_failure")) {
            for (String table : List.of(
                    "ia_local_audit_fact", "ia_local_audit_outbox", "ia_remote_logout_outbox")) {
                jdbc.execute("drop trigger if exists " + name + " on identity_access." + table);
            }
            jdbc.execute("drop function if exists identity_access." + name + "()");
        }
        jdbc.execute("drop trigger if exists audit_commit_failure "
                + "on identity_access.ia_idempotency_result");
        jdbc.execute("drop function if exists identity_access.audit_commit_failure()");
    }

    private static DataSource dataSource(String url) {
        return new DriverManagerDataSource(
                url, requiredProperty("scholarsense.audit.pg.user"), "");
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required; use scripts/run_audit_postgresql_tests.sh");
        }
        return value;
    }
}
