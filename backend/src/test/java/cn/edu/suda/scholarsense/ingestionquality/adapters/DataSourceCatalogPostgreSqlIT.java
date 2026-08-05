package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogAuditRelayWork;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenDataCatalogLoader;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenDataCatalogTestReport;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogRetentionCleanup;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogTransactionAdapter;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogActorContext;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditEvent;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuthorizationDecision;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogEvidenceSet;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogFixtures;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogContractViolation;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogPublicationGuard;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogView;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogVersionConflictException;
import cn.edu.suda.scholarsense.ingestionquality.application.DataSourceCatalogService;
import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogBootstrap;
import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogPolicy;
import cn.edu.suda.scholarsense.ingestionquality.application.PublishCatalogCommand;
import cn.edu.suda.scholarsense.ingestionquality.application.ValidateCatalogCommand;
import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyBinding;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyRequirement;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuntimeEvidenceClaim;
import cn.edu.suda.scholarsense.ingestionquality.domain.SourceContract;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizedValue;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationDomain;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationPort;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL 18.4 evidence, launched by scripts/run_audit_postgresql_tests.sh. */
class DataSourceCatalogPostgreSqlIT {
    private static final UUID CATALOG_ID = UUID.fromString("019fc6b8-9400-7000-8000-000000000111");
    private static final UUID RELEASE_ID = UUID.fromString("019fc6b8-9400-7000-8000-000000000112");
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final String TRACE = "00112233445566778899aabbccddeeff";
    private static final String ONLINE_LOGIN = "scholarsense_iq_online_test_login";
    private static final String RELAY_LOGIN = "scholarsense_iq_relay_test_login";
    private static final CatalogActorContext ACTOR =
            new CatalogActorContext("session-r6", "owner-r6", "192.0.2.10");
    private static final TimeSourceProfile TIME_PROFILE = new TimeSourceProfile(
            "campus-ntp-catalog", "AUDIT-CLOCK-BINDING-1.0.0", 37,
            NOW.minusSeconds(10), NOW.plusSeconds(300),
            "evidence://signed/clock/catalog-nonzero-offset.json");
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private JdbcCatalogStore store;

    @TempDir
    Path temporary;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(required("scholarsense.audit.pg.url"));
        source.setUsername(required("scholarsense.audit.pg.user"));
        source.setPassword(System.getProperty("scholarsense.audit.pg.password", ""));
        dataSource = source;
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        store = new JdbcCatalogStore(
                jdbc,
                new ObjectMapper(),
                tokenization(),
                () -> new TrustedTime(NOW.plusSeconds(3), TIME_PROFILE));
        jdbc.execute("""
                truncate table ingestion_quality.iq_local_audit_outbox,
                  ingestion_quality.iq_local_audit_fact,
                  ingestion_quality.iq_catalog_idempotency,
                  ingestion_quality.iq_catalog_current,
                  ingestion_quality.iq_catalog_evidence,
                  ingestion_quality.iq_catalog_validation_attempt,
                  ingestion_quality.iq_dependency_binding,
                  ingestion_quality.iq_source_contract,
                  ingestion_quality.iq_dependency_id_reservation,
                  ingestion_quality.iq_source_id_reservation,
                  ingestion_quality.iq_data_source_catalog cascade
                """);
    }

    @Test
    void exactServerMigrationAndLeastPrivilegeRolesExist() {
        assertEquals("180004", jdbc.queryForObject("select current_setting('server_version_num')", String.class));
        assertEquals(11, jdbc.queryForObject("""
                select count(*) from information_schema.tables
                 where table_schema='ingestion_quality' and table_type='BASE TABLE'
                """, Integer.class));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "select has_schema_privilege('scholarsense_ingestion_quality_online','ingestion_quality','USAGE')",
                Boolean.class)));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                "select has_table_privilege('scholarsense_ingestion_quality_relay',"
                        + "'ingestion_quality.iq_data_source_catalog','INSERT')", Boolean.class)));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                "select has_table_privilege('scholarsense_ingestion_quality_online',"
                        + "'ingestion_quality.iq_data_source_catalog','UPDATE')", Boolean.class)));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                "select has_table_privilege('scholarsense_ingestion_quality_online',"
                        + "'ingestion_quality.iq_local_audit_fact','SELECT')", Boolean.class)));
        for (String table : List.of(
                "iq_source_id_reservation", "iq_dependency_id_reservation",
                "iq_source_contract", "iq_dependency_binding")) {
            assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                    "select has_table_privilege('scholarsense_ingestion_quality_online',?, 'INSERT')",
                    Boolean.class, "ingestion_quality." + table)), table);
        }
        assertTrue(functionPrivilege(
                "scholarsense_ingestion_quality_online", "iq_add_catalog_source"));
        assertTrue(functionPrivilege(
                "scholarsense_ingestion_quality_online", "iq_add_catalog_dependency"));
        assertTrue(functionPrivilege(
                "scholarsense_ingestion_quality_online", "iq_record_catalog_validation"));
        assertTrue(functionPrivilege(
                "scholarsense_ingestion_quality_online", "iq_publish_catalog"));
        for (String table : List.of(
                "iq_catalog_validation_attempt", "iq_catalog_evidence", "iq_catalog_current")) {
            assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                    "select has_table_privilege('scholarsense_ingestion_quality_online',?, 'INSERT')",
                    Boolean.class, "ingestion_quality." + table)), table);
        }
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                select has_column_privilege(
                  'scholarsense_ingestion_quality_online',
                  'ingestion_quality.iq_local_audit_fact','audit_id','INSERT')
                """, Boolean.class)));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject("""
                select has_column_privilege(
                  'scholarsense_ingestion_quality_online',
                  'ingestion_quality.iq_data_source_catalog','legal_hold','INSERT')
                """, Boolean.class)));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                select has_column_privilege(
                  'scholarsense_ingestion_quality_online',
                  'ingestion_quality.iq_data_source_catalog','expires_at','INSERT')
                """, Boolean.class)));
        assertFalse(retentionFunctionPrivilege("scholarsense_ingestion_quality_online"));
        assertTrue(retentionFunctionPrivilege("scholarsense_ingestion_quality_relay"));
        assertEquals(Set.of(),
                updateColumns("iq_data_source_catalog", "scholarsense_ingestion_quality_online"));
        assertEquals(Set.of(),
                updateColumns("iq_catalog_current", "scholarsense_ingestion_quality_online"));
        assertEquals(4, jdbc.queryForObject("""
                select count(*)
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality'
                   and procedure.proname in (
                     'iq_add_catalog_source','iq_add_catalog_dependency',
                     'iq_record_catalog_validation','iq_publish_catalog')
                   and procedure.prosecdef
                   and procedure.proconfig @> array['search_path=pg_catalog']
                """, Integer.class));
    }

    @Test
    void startupGateUsesDistinctRealLoginRolesWithExclusiveInheritedMembership() {
        ensureWorkloadLogins();

        PostgreSqlConnectionProfile online = PostgreSqlDataSourceStartupGate.verifyOnline(
                workloadDataSource(ONLINE_LOGIN), "test", ONLINE_LOGIN);
        PostgreSqlConnectionProfile relay = PostgreSqlDataSourceStartupGate.verifyRelay(
                workloadDataSource(RELAY_LOGIN), "test", RELAY_LOGIN);

        assertEquals(ONLINE_LOGIN, online.expectedWorkloadIdentity());
        assertEquals(RELAY_LOGIN, relay.expectedWorkloadIdentity());
        for (String login : List.of(ONLINE_LOGIN, RELAY_LOGIN)) {
            JdbcTemplate workload = new JdbcTemplate(workloadDataSource(login));
            assertTrue(Boolean.TRUE.equals(workload.queryForObject(
                    "select session_user=current_user", Boolean.class)));
            assertTrue(Boolean.TRUE.equals(workload.queryForObject("""
                    select rolcanlogin and rolinherit
                           and not (rolsuper or rolcreaterole or rolcreatedb
                                   or rolreplication or rolbypassrls)
                      from pg_catalog.pg_roles where rolname=current_user
                    """, Boolean.class)));
        }
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject("""
                select bool_or(rolcanlogin or rolsuper or rolcreaterole or rolcreatedb
                               or rolreplication or rolbypassrls)
                  from pg_catalog.pg_roles
                 where rolname in (
                   'scholarsense_ingestion_quality_online',
                   'scholarsense_ingestion_quality_relay')
                """, Boolean.class)));
    }

    @Test
    void relayRoleCanUpdateOnlyDeliveryColumnsAndCannotMutatePayloadOrFact() {
        ensureWorkloadLogins();
        UUID eventId = appendPendingAudit();
        Set<String> updateColumns = Set.copyOf(jdbc.queryForList("""
                select column_name
                  from information_schema.column_privileges
                 where table_schema='ingestion_quality'
                   and table_name='iq_local_audit_outbox'
                   and grantee='scholarsense_ingestion_quality_relay'
                   and privilege_type='UPDATE'
                """, String.class));
        assertEquals(Set.of(
                "status", "attempts", "available_at", "claimed_until",
                "delivered_at", "last_error_code"), updateColumns);
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject("""
                select has_table_privilege(
                  'scholarsense_ingestion_quality_relay',
                  'ingestion_quality.iq_local_audit_fact','UPDATE')
                """, Boolean.class)));
        String original = jdbc.queryForObject("""
                select payload::text from ingestion_quality.iq_local_audit_outbox
                 where event_id=?
                """, String.class, eventId);

        JdbcTemplate relayJdbc = new JdbcTemplate(workloadDataSource(RELAY_LOGIN));
        JdbcTemplate onlineJdbc = new JdbcTemplate(workloadDataSource(ONLINE_LOGIN));
        UUID onlineFact = UUID.fromString("019fc6b8-9400-7000-8000-000000000119");
        assertEquals(1, onlineJdbc.update("""
                insert into ingestion_quality.iq_local_audit_fact
                  (audit_id,actor_search_token,action,result,catalog_id,aggregate_version,
                   trace_id,occurred_at,authorization_context,expires_at)
                values (?,?,?,?,?,?,?,?,?::jsonb,?)
                """, onlineFact, "ast_v1_k1_" + "a".repeat(64),
                "data-source-catalog.online-insert-proof", "accepted", CATALOG_ID, 2L,
                TRACE, Timestamp.from(NOW), "{}",
                Timestamp.from(NOW.atZone(ZoneOffset.UTC).plusYears(3).toInstant())));
        assertThrows(DataAccessException.class, () -> onlineJdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_local_audit_fact
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_local_audit_fact
                 where audit_id=?
                """, Integer.class, onlineFact));
        assertThrows(DataAccessException.class, () -> relayJdbc.update("""
                    update ingestion_quality.iq_local_audit_outbox
                       set payload=jsonb_set(
                         payload,'{fact,action}',
                         '"data-source-catalog.forged"'::jsonb)
                     where event_id=?
                    """, eventId));
        assertEquals(original, jdbc.queryForObject("""
                select payload::text from ingestion_quality.iq_local_audit_outbox
                 where event_id=?
                """, String.class, eventId));
    }

    @Test
    void tamperedCanonicalPayloadIsPermanentlyFailedAndNeverClaimedForLedger() {
        UUID eventId = appendPendingAudit();
        jdbc.update("""
                update ingestion_quality.iq_local_audit_outbox
                   set payload=jsonb_set(
                     payload,'{fact,action}',
                     '"data-source-catalog.forged"'::jsonb)
                 where event_id=?
                """, eventId);
        var relay = new JdbcCatalogAuditRelayWork(jdbc, transactions, new ObjectMapper());

        assertTrue(relay.claimDue(100, NOW.plusSeconds(3), Duration.ofSeconds(60)).isEmpty());
        assertEquals("failed", jdbc.queryForObject("""
                select status from ingestion_quality.iq_local_audit_outbox
                 where event_id=?
                """, String.class, eventId));
        assertEquals(1L, jdbc.queryForObject("""
                select attempts from ingestion_quality.iq_local_audit_outbox
                 where event_id=?
                """, Long.class, eventId));
        assertEquals("AUDIT_PAYLOAD_INTEGRITY_INVALID", jdbc.queryForObject("""
                select last_error_code from ingestion_quality.iq_local_audit_outbox
                 where event_id=?
                """, String.class, eventId));
        assertTrue(relay.claimDue(100, NOW.plusSeconds(4), Duration.ofSeconds(60)).isEmpty());
    }

    @Test
    void publishCommitsCatalogIdempotencyCurrentAndLocalAuditAtomically() {
        ensureWorkloadLogins();
        DataSource onlineDataSource = workloadDataSource(ONLINE_LOGIN);
        JdbcTemplate onlineJdbc = new JdbcTemplate(onlineDataSource);
        TransactionTemplate onlineTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(onlineDataSource));
        JdbcCatalogStore onlineStore = new JdbcCatalogStore(
                onlineJdbc,
                new ObjectMapper(),
                tokenization(),
                () -> new TrustedTime(NOW.plusSeconds(3), TIME_PROFILE));
        onlineTransactions.executeWithoutResult(status -> onlineStore.save(draft(), 0));
        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                update ingestion_quality.iq_data_source_catalog
                   set content_digest=? where catalog_id=?
                """, "sha256:" + "f".repeat(64), CATALOG_ID));
        DataSourceCatalogService service = service(
                onlineStore, onlineTransactions, onlineStore::append, trace -> {});
        CatalogView publishable = service.validate(new ValidateCatalogCommand(
                CATALOG_ID, 1, ACTOR, TRACE));
        CatalogView published = service.publish(new PublishCatalogCommand(
                CATALOG_ID, publishable.aggregateVersion(), 0, RELEASE_ID, "idem-pg-001",
                "sha256:" + "c".repeat(64), ACTOR, TRACE));

        assertEquals(CatalogStatus.PUBLISHED, published.status());
        assertEquals(CATALOG_ID, store.current().orElseThrow().catalogId());
        assertEquals(1, count("iq_catalog_idempotency"));
        assertEquals(17, count("iq_source_id_reservation"));
        assertEquals(11, count("iq_dependency_id_reservation"));
        assertEquals(17, count("iq_catalog_evidence"));
        assertEquals(1, store.currentPointer().orElseThrow().pointerVersion());
        assertEquals(2, count("iq_local_audit_fact"));
        assertEquals(2, count("iq_local_audit_outbox"));
        assertEquals(TRACE, jdbc.queryForObject("""
                select trace_id from ingestion_quality.iq_catalog_validation_attempt
                 where catalog_id=? and aggregate_version=2
                """, String.class, CATALOG_ID));
        assertEquals("campus-ntp-catalog", jdbc.queryForObject("""
                select payload #>> '{fact,timeSourceProfile,sourceId}'
                  from ingestion_quality.iq_local_audit_outbox
                 order by created_at desc limit 1
                """, String.class));
        assertEquals("37", jdbc.queryForObject("""
                select payload #>> '{fact,timeSourceProfile,offsetMs}'
                  from ingestion_quality.iq_local_audit_outbox
                 order by created_at desc limit 1
                """, String.class));
        assertEquals("evidence://signed/clock/catalog-nonzero-offset.json",
                jdbc.queryForObject("""
                        select payload #>> '{fact,timeSourceProfile,evidenceRef}'
                          from ingestion_quality.iq_local_audit_outbox
                         order by created_at desc limit 1
                        """, String.class));
        assertEquals(NOW.plusSeconds(2).toString(), jdbc.queryForObject("""
                select payload #>> '{fact,occurredAt}'
                  from ingestion_quality.iq_local_audit_outbox
                 where payload #>> '{fact,action}'='data-source-catalog.publish'
                """, String.class));
        assertEquals(NOW.plusSeconds(3).toString(), jdbc.queryForObject("""
                select payload #>> '{fact,recordedAt}'
                  from ingestion_quality.iq_local_audit_outbox
                 where payload #>> '{fact,action}'='data-source-catalog.publish'
                """, String.class));
        assertEquals(NOW.plusSeconds(3), jdbc.queryForObject("""
                select created_at from ingestion_quality.iq_local_audit_outbox
                 where payload #>> '{fact,action}'='data-source-catalog.publish'
                """, Timestamp.class).toInstant());
        assertEquals(NOW.plusSeconds(3), jdbc.queryForObject("""
                select available_at from ingestion_quality.iq_local_audit_outbox
                 where payload #>> '{fact,action}'='data-source-catalog.publish'
                """, Timestamp.class).toInstant());
        assertTrue(jdbc.queryForObject("""
                select payload #>> '{fact,sourceIpSearchToken}'
                  from ingestion_quality.iq_local_audit_outbox
                 where payload #>> '{fact,action}'='data-source-catalog.publish'
                """, String.class).startsWith("ipt_v1_k1_"));
        assertTrue(jdbc.queryForObject("""
                select actor_search_token from ingestion_quality.iq_local_audit_fact
                 where action='data-source-catalog.publish'
                """, String.class).startsWith("ast_v1_k1_"));
        assertEquals("AUDIT-TOKENIZATION-1.0.0", jdbc.queryForObject("""
                select payload #>> '{fact,tokenizationProfileVersion}'
                  from ingestion_quality.iq_local_audit_outbox
                 where payload #>> '{fact,action}'='data-source-catalog.publish'
                """, String.class));
        assertEquals("k1", jdbc.queryForObject("""
                select payload #>> '{fact,keyVersion}'
                  from ingestion_quality.iq_local_audit_outbox
                 where payload #>> '{fact,action}'='data-source-catalog.publish'
                """, String.class));
        assertEquals(sha256("idem-pg-001"), jdbc.queryForObject("""
                select payload #>> '{fact,idempotencyKeyDigest}'
                  from ingestion_quality.iq_local_audit_outbox
                 where payload #>> '{fact,action}'='data-source-catalog.publish'
                """, String.class));
        assertEquals("pending", jdbc.queryForObject("""
                select status from ingestion_quality.iq_local_audit_outbox
                 where event_type='ingestion-quality.local-audit-fact.recorded.v1'
                 order by created_at desc limit 1
                """, String.class));
        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                update ingestion_quality.iq_data_source_catalog
                   set updated_at=updated_at where catalog_id=?
                """, CATALOG_ID));

        var relay = new JdbcCatalogAuditRelayWork(jdbc, transactions, new ObjectMapper());
        assertEquals(2, relay.claimDue(100, NOW.plusSeconds(3), Duration.ofSeconds(60)).size());
    }

    @Test
    void frozenLoaderRoundTripsThroughJdbcRestartValidationAndPublication() throws Exception {
        FrozenDataCatalogTestReport.Fixture fixture =
                FrozenDataCatalogTestReport.create(temporary);
        ObjectMapper json = new ObjectMapper();
        FrozenDataCatalogLoader loader = new FrozenDataCatalogLoader(
                json, fixture.contractRoot(), fixture.reportPath(), fixture.reportUri(),
                fixture.signingKey(), fixture.subject());
        Instant reportTime = loader.authorizationProbe().createdAt();
        TrustedTimeSource runtimeTime = () -> new TrustedTime(
                reportTime.plusSeconds(3), new TimeSourceProfile(
                        "campus-ntp-loader-pg", "AUDIT-CLOCK-BINDING-1.0.0", 10,
                        reportTime.minusSeconds(10), reportTime.plusSeconds(30),
                        "evidence://signed/clock/loader-pg.json"));
        JdbcCatalogStore localStore = new JdbcCatalogStore(
                jdbc, json, tokenization(), runtimeTime);
        JdbcCatalogTransactionAdapter transactionAdapter =
                new JdbcCatalogTransactionAdapter(transactions);
        FrozenDataCatalogPolicy policy = new FrozenDataCatalogPolicy();
        FrozenDataCatalogBootstrap firstProcess = new FrozenDataCatalogBootstrap(
                loader, localStore, policy, transactionAdapter);

        CatalogView first = firstProcess.run();
        CatalogView restarted = new FrozenDataCatalogBootstrap(
                loader, localStore, policy, transactionAdapter).run();

        assertEquals(first, restarted);
        assertEquals(loader.load().sources(), localStore.find(first.catalogId()).orElseThrow().sources());
        assertEquals(loader.load().dependencies(),
                localStore.find(first.catalogId()).orElseThrow().dependencies());

        DataSourceCatalogService service = new DataSourceCatalogService(
                localStore, localStore, policy,
                (actor, sourceAction, dependencyAction, catalog, trace) ->
                        CatalogAuthorizationDecision.ALLOW,
                transactionAdapter, localStore, trace -> {}, loader, loader, runtimeTime);
        CatalogView validated = service.validate(new ValidateCatalogCommand(
                first.catalogId(), first.aggregateVersion(), ACTOR, TRACE));
        CatalogView published = service.publish(new PublishCatalogCommand(
                first.catalogId(), validated.aggregateVersion(), 0, RELEASE_ID,
                "idem-loader-pg", "sha256:" + "6".repeat(64), ACTOR, TRACE));

        assertEquals(CatalogStatus.PUBLISHED, published.status());
        assertEquals(17, count("iq_catalog_evidence"));
        assertEquals(7L, jdbc.queryForObject("""
                select min(handoff_revision) from ingestion_quality.iq_catalog_evidence
                """, Long.class));
        assertEquals("sha256:" + "e".repeat(64), jdbc.queryForObject("""
                select min(handoff_digest) from ingestion_quality.iq_catalog_evidence
                """, String.class));
        assertEquals(reportTime.atZone(ZoneOffset.UTC).plusYears(3).toInstant(),
                jdbc.queryForObject("""
                        select expires_at from ingestion_quality.iq_data_source_catalog
                         where catalog_id=?
                        """, Timestamp.class, first.catalogId()).toInstant());
        assertEquals(reportTime.atZone(ZoneOffset.UTC).plusYears(3).toInstant(),
                jdbc.queryForObject("""
                        select min(expires_at) from ingestion_quality.iq_catalog_evidence
                         where catalog_id=?
                        """, Timestamp.class, first.catalogId()).toInstant());
        String expectedActor = tokenization().tokenize(
                AuditTokenizationDomain.ACTOR, ACTOR.auditActorRef()).value();
        String sessionAsActor = tokenization().tokenize(
                AuditTokenizationDomain.ACTOR, ACTOR.authorizationSessionRef()).value();
        assertEquals(expectedActor, jdbc.queryForObject("""
                select actor_search_token from ingestion_quality.iq_local_audit_fact
                 where action='data-source-catalog.publish'
                """, String.class));
        assertFalse(expectedActor.equals(sessionAsActor));
    }

    @Test
    void auditFailureRollsBackPublishedStatePointerAndIdempotency() {
        transactions.executeWithoutResult(status -> store.save(draft(), 0));
        DataSourceCatalogService normal = service(store::append);
        normal.validate(new ValidateCatalogCommand(CATALOG_ID, 1, ACTOR, TRACE));
        DataSourceCatalogService failing = service(event -> { throw new IllegalStateException("audit unavailable"); });

        assertThrows(IllegalStateException.class, () -> failing.publish(new PublishCatalogCommand(
                CATALOG_ID, 2, 0, RELEASE_ID, "idem-pg-rollback", "sha256:" + "c".repeat(64),
                ACTOR, TRACE)));
        assertEquals(CatalogStatus.PUBLISHABLE, store.find(CATALOG_ID).orElseThrow().status());
        assertTrue(store.current().isEmpty());
        assertEquals(0, count("iq_catalog_idempotency"));
        assertEquals(1, count("iq_local_audit_fact"));
    }

    @Test
    void backwardSecondTrustedTimeSampleFailsAsStableUnavailableAndRollsBack() {
        transactions.executeWithoutResult(status -> store.save(draft(), 0));
        AtomicInteger samples = new AtomicInteger();
        TrustedTimeSource backward = () -> new TrustedTime(
                samples.getAndIncrement() == 0 ? NOW.plusSeconds(2) : NOW.plusSeconds(1),
                TIME_PROFILE);
        JdbcCatalogStore localStore = new JdbcCatalogStore(
                jdbc, new ObjectMapper(), tokenization(), backward);
        DataSourceCatalogService service = new DataSourceCatalogService(
                localStore, localStore, catalog -> List.of(),
                (actor, sourceAction, dependencyAction, catalog, trace) ->
                        CatalogAuthorizationDecision.ALLOW,
                new JdbcCatalogTransactionAdapter(transactions), localStore, trace -> {},
                catalog -> CatalogFixtures.evidence(catalog, NOW.plusSeconds(2)),
                () -> draft(), backward);

        IngestionQualityApplicationException failure = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.validate(new ValidateCatalogCommand(
                        CATALOG_ID, 1, ACTOR, TRACE)));

        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", failure.code());
        assertEquals(CatalogStatus.DRAFT, localStore.find(CATALOG_ID).orElseThrow().status());
        assertEquals(1, localStore.find(CATALOG_ID).orElseThrow().aggregateVersion());
        assertEquals(0, count("iq_catalog_validation_attempt"));
        assertEquals(0, count("iq_local_audit_fact"));
        assertEquals(2, samples.get());
    }

    @Test
    void concurrentSameIdempotencyKeyAtomicallyClaimsAndReplaysOnePublication()
            throws Exception {
        transactions.executeWithoutResult(status -> store.save(draft(), 0));
        DataSourceCatalogService normal = service(store::append);
        normal.validate(new ValidateCatalogCommand(CATALOG_ID, 1, ACTOR, TRACE));
        CountDownLatch ready = new CountDownLatch(2);
        CatalogPublicationGuard simultaneous = ignored -> awaitBoth(ready);
        DataSourceCatalogService concurrent = service(store::append, simultaneous);
        PublishCatalogCommand command = new PublishCatalogCommand(
                CATALOG_ID, 2, 0, RELEASE_ID, "idem-pg-concurrent-replay",
                "sha256:" + "7".repeat(64), ACTOR, TRACE);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<CatalogView> left = executor.submit(() -> concurrent.publish(command));
            Future<CatalogView> right = executor.submit(() -> concurrent.publish(command));
            assertEquals(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
        }

        assertEquals(1, count("iq_catalog_idempotency"));
        assertEquals(17, count("iq_catalog_evidence"));
        assertEquals(2, count("iq_local_audit_fact"));
        assertEquals(1, store.currentPointer().orElseThrow().pointerVersion());
    }

    @Test
    void concurrentDifferentCatalogsWithSameExpectedPointerAllowOnlyOneCasWinner()
            throws Exception {
        UUID secondId = UUID.fromString("019fc6b8-9400-7000-8000-000000000113");
        transactions.executeWithoutResult(status -> {
            store.save(draft(), 0);
            store.save(CatalogFixtures.draft(secondId, NOW), 0);
        });
        DataSourceCatalogService normal = service(store::append);
        normal.validate(new ValidateCatalogCommand(CATALOG_ID, 1, ACTOR, TRACE));
        normal.validate(new ValidateCatalogCommand(secondId, 1, ACTOR, TRACE));
        CountDownLatch ready = new CountDownLatch(2);
        DataSourceCatalogService concurrent = service(store::append, ignored -> awaitBoth(ready));
        PublishCatalogCommand first = new PublishCatalogCommand(
                CATALOG_ID, 2, 0, RELEASE_ID, "idem-pg-cas-left",
                "sha256:" + "8".repeat(64), ACTOR, TRACE);
        PublishCatalogCommand second = new PublishCatalogCommand(
                secondId, 2, 0,
                UUID.fromString("019fc6b8-9400-7000-8000-000000000114"),
                "idem-pg-cas-right", "sha256:" + "9".repeat(64), ACTOR, TRACE);

        int success = 0;
        int conflicts = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<CatalogView>> results = List.of(
                    executor.submit(() -> concurrent.publish(first)),
                    executor.submit(() -> concurrent.publish(second)));
            for (Future<CatalogView> result : results) {
                try {
                    result.get(20, TimeUnit.SECONDS);
                    success++;
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof IngestionQualityApplicationException conflict
                            && "INGESTION_QUALITY_VERSION_CONFLICT".equals(conflict.code())) {
                        conflicts++;
                    } else {
                        throw failure;
                    }
                }
            }
        }

        assertEquals(1, success);
        assertEquals(1, conflicts);
        assertEquals(1, count("iq_catalog_idempotency"));
        assertEquals(17, count("iq_catalog_evidence"));
        assertEquals(1, store.currentPointer().orElseThrow().pointerVersion());
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_data_source_catalog
                 where status='published'
                """, Integer.class));
    }

    @Test
    void catalogReleaseIdReuseAcrossCatalogsIsAnExactStableConflict() {
        UUID secondId = UUID.fromString("019fc6b8-9400-7000-8000-000000000115");
        transactions.executeWithoutResult(status -> {
            store.save(draft(), 0);
            store.save(CatalogFixtures.draft(secondId, NOW), 0);
        });
        DataSourceCatalogService service = service(store::append);
        service.validate(new ValidateCatalogCommand(CATALOG_ID, 1, ACTOR, TRACE));
        service.validate(new ValidateCatalogCommand(secondId, 1, ACTOR, TRACE));
        service.publish(new PublishCatalogCommand(
                CATALOG_ID, 2, 0, RELEASE_ID, "idem-release-owner",
                "sha256:" + "4".repeat(64), ACTOR, TRACE));

        IngestionQualityApplicationException conflict = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.publish(new PublishCatalogCommand(
                        secondId, 2, 1, RELEASE_ID, "idem-release-reuse",
                        "sha256:" + "5".repeat(64), ACTOR, TRACE)));

        assertEquals("INGESTION_QUALITY_CATALOG_RELEASE_CONFLICT", conflict.code());
        assertEquals(CATALOG_ID, store.currentPointer().orElseThrow().catalogId());
        assertEquals(1, store.currentPointer().orElseThrow().pointerVersion());
        assertEquals(CatalogStatus.PUBLISHABLE, store.find(secondId).orElseThrow().status());
        assertEquals(1, count("iq_catalog_idempotency"));
    }

    @Test
    void safeIntegerVersionMaximumPersistsButNeitherAggregateNorPointerCanAdvancePastIt() {
        UUID maximumId = UUID.fromString("019fc6b8-9400-7000-8000-000000000116");
        UUID candidateId = UUID.fromString("019fc6b8-9400-7000-8000-000000000117");
        UUID overflowId = UUID.fromString("019fc6b8-9400-7000-8000-000000000119");
        publishDirect(
                maximumId,
                UUID.fromString("019fc6b8-9400-7000-8000-0000000001a0"),
                NOW, 0);
        transactions.executeWithoutResult(status -> {
            jdbc.execute("set local session_replication_role='replica'");
            jdbc.update("""
                    update ingestion_quality.iq_data_source_catalog
                       set aggregate_version=? where catalog_id=?
                    """, DataSourceCatalog.MAX_VERSION, maximumId);
            jdbc.update("""
                    update ingestion_quality.iq_catalog_current
                       set aggregate_version=?,pointer_version=? where singleton=true
                    """, DataSourceCatalog.MAX_VERSION, DataSourceCatalog.MAX_VERSION);
        });

        assertEquals(DataSourceCatalog.MAX_VERSION,
                store.currentPointer().orElseThrow().catalogAggregateVersion());
        assertEquals(DataSourceCatalog.MAX_VERSION,
                store.currentPointer().orElseThrow().pointerVersion());
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                insert into ingestion_quality.iq_data_source_catalog
                  (catalog_id,contract_version,status,aggregate_version,content_digest,
                   validation_errors,created_at,updated_at,expires_at)
                values (?,'DCC-1.0.0','draft',?,?,'[]'::jsonb,?,?,?)
                """, overflowId, DataSourceCatalog.MAX_VERSION + 1,
                "sha256:" + "f".repeat(64), Timestamp.from(NOW), Timestamp.from(NOW),
                Timestamp.from(NOW.atZone(ZoneOffset.UTC).plusYears(3).toInstant())));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                update ingestion_quality.iq_catalog_current set pointer_version=?
                 where singleton=true
                """, DataSourceCatalog.MAX_VERSION + 1));

        DataSourceCatalog candidate = CatalogFixtures.draft(candidateId, NOW.plusSeconds(1));
        DataSourceCatalog publishable = candidate.validated(List.of(), NOW.plusSeconds(2));
        CatalogEvidenceSet evidence = CatalogFixtures.evidence(
                publishable, NOW.plusSeconds(3));
        DataSourceCatalog published = publishable.publish(
                UUID.fromString("019fc6b8-9400-7000-8000-000000000118"),
                evidence.digest(), NOW.plusSeconds(3));
        transactions.executeWithoutResult(status -> {
            store.save(candidate, 0);
            store.saveValidation(publishable, 1, TRACE);
        });

        CatalogVersionConflictException conflict = assertThrows(
                CatalogVersionConflictException.class,
                () -> transactions.executeWithoutResult(status -> store.publish(
                        published, 2, DataSourceCatalog.MAX_VERSION, evidence)));

        assertEquals(DataSourceCatalog.MAX_VERSION, conflict.currentVersion());
        assertEquals(CatalogStatus.PUBLISHABLE, store.find(candidateId).orElseThrow().status());
        assertEquals(DataSourceCatalog.MAX_VERSION,
                store.currentPointer().orElseThrow().pointerVersion());
    }

    @Test
    void onlineRoleCannotBypassPublishedStructureOrCurrentPointerIntegrity() {
        ensureWorkloadLogins();
        UUID olderCatalogId = UUID.fromString("019fc6b8-9400-7000-8000-000000000140");
        Instant olderCreatedAt = NOW.minusSeconds(60);
        publishDirect(
                olderCatalogId,
                UUID.fromString("019fc6b8-9400-7000-8000-000000000147"),
                olderCreatedAt, 0);
        publishDirect(CATALOG_ID, RELEASE_ID, NOW, 1);
        JdbcTemplate onlineJdbc = new JdbcTemplate(workloadDataSource(ONLINE_LOGIN));
        Instant draftCreatedAt = NOW.plusSeconds(10);
        UUID initiallyPublishedId = UUID.fromString(
                "019fc6b8-9400-7000-8000-000000000141");
        UUID incompleteId = UUID.fromString(
                "019fc6b8-9400-7000-8000-000000000142");

        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                insert into ingestion_quality.iq_data_source_catalog
                  (catalog_id,catalog_release_id,contract_version,status,aggregate_version,
                   content_digest,evidence_set_digest,validation_errors,created_at,updated_at,
                   published_at,expires_at)
                values (?,?,'DCC-1.0.0','published',3,?,?,'[]'::jsonb,?,?,?,?)
                """, initiallyPublishedId,
                UUID.fromString("019fc6b8-9400-7000-8000-000000000143"),
                "sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64),
                Timestamp.from(draftCreatedAt), Timestamp.from(draftCreatedAt),
                Timestamp.from(draftCreatedAt), Timestamp.from(
                        draftCreatedAt.atZone(ZoneOffset.UTC).plusYears(3).toInstant())));

        transactions.executeWithoutResult(status -> store.save(
                CatalogFixtures.draft(incompleteId, draftCreatedAt), 0));
        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                update ingestion_quality.iq_data_source_catalog
                   set status='publishable',aggregate_version=2,
                       validation_errors='[]'::jsonb,updated_at=?
                 where catalog_id=? and status='draft' and aggregate_version=1
                """, Timestamp.from(draftCreatedAt.plusSeconds(1)), incompleteId));
        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                insert into ingestion_quality.iq_catalog_validation_attempt
                  (attempt_id,catalog_id,aggregate_version,result,errors,validated_at,trace_id)
                values (?,?,2,'publishable','[]'::jsonb,?,?)
                """, UUID.fromString("019fc6b8-9400-7000-8000-000000000146"),
                incompleteId, Timestamp.from(draftCreatedAt.plusSeconds(1)), TRACE));
        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                insert into ingestion_quality.iq_catalog_evidence
                  (evidence_id,catalog_id,source_id,evidence_uri,evidence_digest,environment,
                   authority,candidate_commit,candidate_tree,handoff_revision,handoff_digest,
                   result,occurred_at,contract_version,schema_version,quality_gate_version,
                   input_digest,scenarios,signature_digest,cleanup_result,
                   runtime_evidence_claim,expires_at)
                select md5((?::uuid)::text || evidence.source_id)::uuid,
                       ?,evidence.source_id,evidence.evidence_uri,evidence.evidence_digest,
                       evidence.environment,evidence.authority,evidence.candidate_commit,
                       evidence.candidate_tree,evidence.handoff_revision,evidence.handoff_digest,
                       evidence.result,evidence.occurred_at,evidence.contract_version,
                       evidence.schema_version,evidence.quality_gate_version,
                       evidence.input_digest,evidence.scenarios,evidence.signature_digest,
                       evidence.cleanup_result,evidence.runtime_evidence_claim,evidence.expires_at
                  from ingestion_quality.iq_catalog_evidence evidence
                 where evidence.catalog_id=?
                """, incompleteId, incompleteId, CATALOG_ID));
        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                update ingestion_quality.iq_data_source_catalog
                   set catalog_release_id=?,status='published',aggregate_version=2,
                       evidence_set_digest=?,validation_errors='[]'::jsonb,
                       updated_at=?,published_at=?
                 where catalog_id=?
                """, UUID.fromString("019fc6b8-9400-7000-8000-000000000144"),
                "sha256:" + "4".repeat(64), Timestamp.from(draftCreatedAt.plusSeconds(1)),
                Timestamp.from(draftCreatedAt.plusSeconds(1)), incompleteId));
        assertEquals(CatalogStatus.DRAFT, store.find(incompleteId).orElseThrow().status());
        assertEquals(1, store.find(incompleteId).orElseThrow().aggregateVersion());
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_catalog_evidence where catalog_id=?
                """, Integer.class, incompleteId));

        assertEquals(17, jdbc.update("""
                insert into ingestion_quality.iq_catalog_evidence
                  (evidence_id,catalog_id,source_id,evidence_uri,evidence_digest,environment,
                   authority,candidate_commit,candidate_tree,handoff_revision,handoff_digest,
                   result,occurred_at,contract_version,schema_version,quality_gate_version,
                   input_digest,scenarios,signature_digest,cleanup_result,
                   runtime_evidence_claim,expires_at)
                select md5((?::uuid)::text || evidence.source_id)::uuid,
                       ?,evidence.source_id,evidence.evidence_uri,evidence.evidence_digest,
                       evidence.environment,evidence.authority,evidence.candidate_commit,
                       evidence.candidate_tree,evidence.handoff_revision,evidence.handoff_digest,
                       evidence.result,evidence.occurred_at,evidence.contract_version,
                       evidence.schema_version,evidence.quality_gate_version,
                       evidence.input_digest,evidence.scenarios,evidence.signature_digest,
                       evidence.cleanup_result,evidence.runtime_evidence_claim,evidence.expires_at
                  from ingestion_quality.iq_catalog_evidence evidence
                 where evidence.catalog_id=?
                """, incompleteId, incompleteId, CATALOG_ID));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                update ingestion_quality.iq_data_source_catalog
                   set catalog_release_id=?,status='published',aggregate_version=2,
                       evidence_set_digest=?,validation_errors='[]'::jsonb,
                       updated_at=?,published_at=?
                 where catalog_id=?
                """, UUID.fromString("019fc6b8-9400-7000-8000-000000000144"),
                "sha256:" + "4".repeat(64), Timestamp.from(draftCreatedAt.plusSeconds(1)),
                Timestamp.from(draftCreatedAt.plusSeconds(1)), incompleteId));
        assertEquals(1, jdbc.update("""
                update ingestion_quality.iq_data_source_catalog
                   set status='publishable',aggregate_version=2,
                       validation_errors='[]'::jsonb,updated_at=?
                 where catalog_id=? and status='draft' and aggregate_version=1
                """, Timestamp.from(draftCreatedAt.plusSeconds(1)), incompleteId));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                update ingestion_quality.iq_data_source_catalog
                   set catalog_release_id=?,status='published',aggregate_version=3,
                       evidence_set_digest=?,validation_errors='[]'::jsonb,
                       updated_at=?,published_at=?
                 where catalog_id=?
                """, UUID.fromString("019fc6b8-9400-7000-8000-000000000144"),
                "sha256:" + "4".repeat(64), Timestamp.from(draftCreatedAt.plusSeconds(2)),
                Timestamp.from(draftCreatedAt.plusSeconds(2)), incompleteId));
        assertEquals(1, jdbc.update("""
                insert into ingestion_quality.iq_catalog_validation_attempt
                  (attempt_id,catalog_id,aggregate_version,result,errors,validated_at,trace_id)
                values (?,?,2,'publishable','[]'::jsonb,?,?)
                """, UUID.fromString("019fc6b8-9400-7000-8000-000000000146"),
                incompleteId, Timestamp.from(draftCreatedAt.plusSeconds(1)), TRACE));
        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                update ingestion_quality.iq_data_source_catalog
                   set catalog_release_id=?,status='published',aggregate_version=3,
                       evidence_set_digest=?,validation_errors='[]'::jsonb,
                       updated_at=?,published_at=?
                 where catalog_id=?
                """, UUID.fromString("019fc6b8-9400-7000-8000-000000000144"),
                "sha256:" + "4".repeat(64), Timestamp.from(draftCreatedAt.plusSeconds(2)),
                Timestamp.from(draftCreatedAt.plusSeconds(2)), incompleteId));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                update ingestion_quality.iq_data_source_catalog
                   set catalog_release_id=?,status='published',aggregate_version=4,
                       evidence_set_digest=?,validation_errors='[]'::jsonb,
                       updated_at=?,published_at=?
                 where catalog_id=?
                """, UUID.fromString("019fc6b8-9400-7000-8000-000000000144"),
                "sha256:" + "4".repeat(64), Timestamp.from(draftCreatedAt.plusSeconds(2)),
                Timestamp.from(draftCreatedAt.plusSeconds(2)), incompleteId));

        String appendedSourceId = "SRC-P1-APPEND-999";
        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                insert into ingestion_quality.iq_source_id_reservation
                  (source_id,first_catalog_id,first_purpose,reserved_at)
                values (?,?,?,?)
                """, appendedSourceId, CATALOG_ID, "forbidden-published-append",
                Timestamp.from(NOW.plusSeconds(20))));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_source_id_reservation
                 where source_id=?
                """, Integer.class, appendedSourceId));
        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                insert into ingestion_quality.iq_source_contract
                  (catalog_id,source_id,purpose,schema_version,quality_gate_version,
                   evidence_uri,runtime_evidence_claim,descriptor)
                values (?,?,?,'APPEND-1.0.0','QG-1.0.0',?,'target-verified','{}'::jsonb)
                """, CATALOG_ID, appendedSourceId, "forbidden-published-append",
                "evidence+sha256://" + "5".repeat(64)));

        String unboundSourceId = jdbc.queryForObject("""
                select source_contract.source_id
                  from ingestion_quality.iq_source_contract source_contract
                 where source_contract.catalog_id=?
                   and not exists (
                     select 1
                       from ingestion_quality.iq_dependency_id_reservation reservation
                      where reservation.source_id=source_contract.source_id)
                 order by source_contract.source_id
                 limit 1
                """, String.class, CATALOG_ID);
        String appendedDependencyId = "DEP-P1-APPEND-999";
        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                insert into ingestion_quality.iq_dependency_id_reservation
                  (dependency_id,source_id,first_catalog_id,reserved_at)
                values (?,?,?,?)
                """, appendedDependencyId, unboundSourceId, CATALOG_ID,
                Timestamp.from(NOW.plusSeconds(21))));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_dependency_id_reservation
                 where dependency_id=?
                """, Integer.class, appendedDependencyId));
        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                insert into ingestion_quality.iq_dependency_binding
                  (catalog_id,source_id,dependency_id,requirement,combination_operator)
                values (?,?,?,'required','all-of')
                """, CATALOG_ID, unboundSourceId, appendedDependencyId));

        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                insert into ingestion_quality.iq_catalog_evidence
                  (evidence_id,catalog_id,source_id,evidence_uri,evidence_digest,environment,
                   authority,candidate_commit,candidate_tree,handoff_revision,handoff_digest,
                   result,occurred_at,contract_version,schema_version,quality_gate_version,
                   input_digest,scenarios,signature_digest,cleanup_result,
                   runtime_evidence_claim,expires_at)
                select ?,catalog_id,source_id,evidence_uri,?,environment,
                       authority,candidate_commit,candidate_tree,handoff_revision,handoff_digest,
                       result,occurred_at,contract_version,schema_version,quality_gate_version,
                       input_digest,scenarios,signature_digest,cleanup_result,
                       runtime_evidence_claim,expires_at
                  from ingestion_quality.iq_catalog_evidence
                 where catalog_id=?
                 order by source_id
                 limit 1
                """, UUID.fromString("019fc6b8-9400-7000-8000-000000000145"),
                "sha256:" + "6".repeat(64), CATALOG_ID));

        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                update ingestion_quality.iq_catalog_current
                   set catalog_id=?,aggregate_version=2,pointer_version=pointer_version+1,
                       switched_at=?
                 where singleton=true
                """, incompleteId, Timestamp.from(draftCreatedAt)));
        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                update ingestion_quality.iq_catalog_current
                   set pointer_version=pointer_version+1
                 where singleton=true
                """));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                update ingestion_quality.iq_catalog_current
                   set pointer_version=pointer_version+1
                 where singleton=true
                """));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                update ingestion_quality.iq_catalog_current
                   set catalog_id=?,aggregate_version=3,
                       pointer_version=pointer_version+1,switched_at=?
                 where singleton=true
                """, olderCatalogId, Timestamp.from(olderCreatedAt.plusSeconds(2))));
        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                update ingestion_quality.iq_catalog_current
                   set aggregate_version=aggregate_version+1,
                       pointer_version=pointer_version+1
                 where singleton=true
                """));
        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                update ingestion_quality.iq_catalog_current
                   set pointer_version=pointer_version+1,
                       switched_at=switched_at+interval '1 second'
                 where singleton=true
                """));

        assertEquals(CATALOG_ID, store.currentPointer().orElseThrow().catalogId());
        assertEquals(2, store.currentPointer().orElseThrow().pointerVersion());
        assertEquals(CatalogStatus.PUBLISHABLE, store.find(incompleteId).orElseThrow().status());
        assertEquals(2, store.find(incompleteId).orElseThrow().aggregateVersion());
        assertEquals(17, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_source_contract where catalog_id=?
                """, Integer.class, CATALOG_ID));
        assertEquals(11, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_dependency_binding where catalog_id=?
                """, Integer.class, CATALOG_ID));
        assertEquals(17, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_catalog_evidence where catalog_id=?
                """, Integer.class, CATALOG_ID));
    }

    @Test
    void stableSourceIdAllowsACompatibleCatalogRevisionButRejectsPurposeReuse() {
        transactions.executeWithoutResult(status -> store.save(draft(), 0));
        UUID nextCatalogId = UUID.fromString("019fc6b8-9400-7000-8000-000000000121");
        DataSourceCatalog compatible = DataSourceCatalog.draft(
                nextCatalogId, "DCC-1.0.0", List.of(new SourceContract(
                        "SRC-P0-CALENDAR-001", "business-calendar-control", "BC-1.1.0", "QG-1.0.0",
                        "evidence+sha256://" + "b".repeat(64), RuntimeEvidenceClaim.TARGET_VERIFIED)),
                List.of(new DependencyBinding("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001",
                        DependencyRequirement.REQUIRED, DependencyOperator.ALL_OF)),
                "sha256:" + "b".repeat(64), NOW.plusSeconds(1));
        transactions.executeWithoutResult(status -> store.save(compatible, 0));
        assertEquals(2, count("iq_data_source_catalog"));
        assertEquals(17, count("iq_source_id_reservation"));

        UUID reusedCatalogId = UUID.fromString("019fc6b8-9400-7000-8000-000000000122");
        DataSourceCatalog reused = DataSourceCatalog.draft(
                reusedCatalogId, "DCC-1.0.0", List.of(new SourceContract(
                        "SRC-P0-CALENDAR-001", "unrelated-purpose", "BC-2.0.0", "QG-1.0.0",
                        "evidence+sha256://" + "c".repeat(64), RuntimeEvidenceClaim.TARGET_VERIFIED)),
                List.of(), "sha256:" + "c".repeat(64), NOW.plusSeconds(2));
        assertThrows(IllegalStateException.class,
                () -> transactions.executeWithoutResult(status -> store.save(reused, 0)));
        assertEquals(2, count("iq_data_source_catalog"));
    }

    @Test
    void retentionCleanupIsRelayOnlyHonorsLegalHoldsAndPreservesPermanentIdReservations() {
        ensureWorkloadLogins();
        Instant cutoff = jdbc.queryForObject(
                "select clock_timestamp()", Timestamp.class).toInstant();
        Instant expiredAt = cutoff.atZone(ZoneOffset.UTC).minusYears(4).toInstant();
        Instant activeAt = cutoff.atZone(ZoneOffset.UTC).minusYears(1).toInstant();
        TimeSourceProfile retentionProfile = new TimeSourceProfile(
                "campus-ntp-retention", "AUDIT-CLOCK-BINDING-1.0.0", 4,
                cutoff.minusSeconds(30), cutoff.plusSeconds(600),
                "evidence://signed/clock/catalog-retention.json");
        UUID expiredId = UUID.fromString("019fc6b8-9400-7000-8000-000000000131");
        UUID activeId = UUID.fromString("019fc6b8-9400-7000-8000-000000000132");
        UUID heldId = UUID.fromString("019fc6b8-9400-7000-8000-000000000133");

        publishDirect(
                expiredId,
                UUID.fromString("019fc6b8-9400-7000-8000-000000000134"),
                expiredAt, 0);
        publishDirect(
                activeId,
                UUID.fromString("019fc6b8-9400-7000-8000-000000000135"),
                activeAt, 1);
        DataSourceCatalog held = retentionHeldDraft(heldId, expiredAt.plusSeconds(10));
        transactions.executeWithoutResult(status -> store.save(held, 0));

        JdbcCatalogStore retentionStore = new JdbcCatalogStore(
                jdbc, new ObjectMapper(), tokenization(),
                () -> new TrustedTime(cutoff, retentionProfile));
        transactions.executeWithoutResult(status -> {
            retentionStore.append(new CatalogAuditEvent(
                    "data-source-catalog.retention-fixture", "accepted", expiredId, 3L,
                    ACTOR.auditActorRef(), ACTOR.sourceIp(), TRACE,
                    expiredAt.plusSeconds(2), retentionProfile, null));
            store.claim("retention-expired", "sha256:" + "1".repeat(64),
                    expiredId, expiredAt.plusSeconds(3));
            store.claim("retention-active", "sha256:" + "2".repeat(64),
                    activeId, cutoff);
            store.claim("retention-held", "sha256:" + "3".repeat(64),
                    heldId, expiredAt.plusSeconds(3));
        });
        JdbcTemplate onlineJdbc = new JdbcTemplate(workloadDataSource(ONLINE_LOGIN));
        JdbcTemplate relayJdbc = new JdbcTemplate(workloadDataSource(RELAY_LOGIN));
        assertThrows(DataAccessException.class, () -> onlineJdbc.update("""
                update ingestion_quality.iq_data_source_catalog set legal_hold=true
                 where catalog_id=?
                """, expiredId));
        assertEquals(1, jdbc.update("""
                update ingestion_quality.iq_data_source_catalog set legal_hold=true
                 where catalog_id=? and status='published'
                """, expiredId));
        assertEquals(1, jdbc.update("""
                update ingestion_quality.iq_data_source_catalog set legal_hold=true
                 where catalog_id=?
                """, heldId));
        assertThrows(DataAccessException.class, () -> onlineJdbc.queryForObject(
                "select ingestion_quality.iq_cleanup_expired(?)", Long.class,
                Timestamp.from(cutoff)));
        assertThrows(DataAccessException.class, () -> relayJdbc.queryForObject(
                "select ingestion_quality.iq_cleanup_expired(?)", Long.class,
                Timestamp.from(cutoff.plusSeconds(86_400))));
        JdbcCatalogRetentionCleanup cleanup = new JdbcCatalogRetentionCleanup(
                relayJdbc, () -> new TrustedTime(cutoff, retentionProfile));

        assertEquals(0, cleanup.cleanupExpired());
        assertEquals(17, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_catalog_evidence
                 where catalog_id=?
                """, Integer.class, expiredId));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_local_audit_fact
                 where catalog_id=?
                """, Integer.class, expiredId));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_local_audit_outbox outbox
                  join ingestion_quality.iq_local_audit_fact fact
                    on fact.audit_id=outbox.audit_id
                 where fact.catalog_id=?
                """, Integer.class, expiredId));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_catalog_idempotency
                 where catalog_id=?
                """, Integer.class, expiredId));
        assertTrue(store.find(expiredId).isPresent());

        assertEquals(1, jdbc.update("""
                update ingestion_quality.iq_data_source_catalog set legal_hold=false
                 where catalog_id=? and status='published'
                """, expiredId));
        String heldEvidenceSource = CatalogFixtures.sources().getFirst().sourceId();
        jdbc.update("""
                update ingestion_quality.iq_catalog_evidence set legal_hold=true
                 where catalog_id=? and source_id=?
                """, expiredId, heldEvidenceSource);
        jdbc.update("""
                update ingestion_quality.iq_local_audit_fact set legal_hold=true
                 where catalog_id=?
                """, expiredId);
        assertTrue(cleanup.cleanupExpired() >= 17);
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_catalog_evidence
                 where catalog_id=?
                """, Integer.class, expiredId));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_local_audit_fact
                 where catalog_id=?
                """, Integer.class, expiredId));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_local_audit_outbox outbox
                  join ingestion_quality.iq_local_audit_fact fact
                    on fact.audit_id=outbox.audit_id
                 where fact.catalog_id=?
                """, Integer.class, expiredId));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_catalog_idempotency
                 where catalog_id=?
                """, Integer.class, expiredId));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_catalog_idempotency
                 where catalog_id=?
                """, Integer.class, heldId));
        assertTrue(store.find(expiredId).isPresent());
        assertTrue(store.find(activeId).isPresent());
        assertTrue(store.find(heldId).isPresent());

        jdbc.update("""
                update ingestion_quality.iq_catalog_evidence set legal_hold=false
                 where catalog_id=?
                """, expiredId);
        assertTrue(cleanup.cleanupExpired() >= 1);
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_catalog_evidence
                 where catalog_id=?
                """, Integer.class, expiredId));
        assertTrue(store.find(expiredId).isPresent(), "held audit still fences root deletion");

        jdbc.update("""
                update ingestion_quality.iq_local_audit_fact set legal_hold=false
                 where catalog_id=?
                """, expiredId);
        assertTrue(cleanup.cleanupExpired() >= 3);
        assertTrue(store.find(expiredId).isEmpty());
        assertEquals(activeId, store.currentPointer().orElseThrow().catalogId());
        assertEquals(17, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_source_id_reservation
                 where first_catalog_id=?
                """, Integer.class, activeId));
        assertEquals(11, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_dependency_id_reservation
                 where first_catalog_id=?
                """, Integer.class, activeId));

        jdbc.update("""
                update ingestion_quality.iq_data_source_catalog set legal_hold=false
                 where catalog_id=?
                """, heldId);
        assertTrue(cleanup.cleanupExpired() >= 2);
        assertTrue(store.find(heldId).isEmpty());
        assertEquals(18, count("iq_source_id_reservation"));
        assertEquals(12, count("iq_dependency_id_reservation"));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_source_id_reservation
                 where source_id='SRC-P1-HOLD-901' and first_catalog_id is null
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_dependency_id_reservation
                 where dependency_id='DEP-P1-HOLD-901' and first_catalog_id is null
                """, Integer.class));

        DataSourceCatalog changedPurpose = DataSourceCatalog.draft(
                UUID.fromString("019fc6b8-9400-7000-8000-000000000136"),
                "DCC-1.0.0", List.of(new SourceContract(
                        "SRC-P1-HOLD-901", "repurposed-after-retention", "HOLD-SLICE-2.0.0",
                        "QG-1.0.0", "evidence+sha256://" + "8".repeat(64),
                        RuntimeEvidenceClaim.TARGET_VERIFIED)),
                List.of(), "sha256:" + "8".repeat(64), cutoff.plusSeconds(1));
        IllegalStateException sourceReuse = assertThrows(
                IllegalStateException.class,
                () -> transactions.executeWithoutResult(
                        status -> store.save(changedPurpose, 0)));
        assertEquals("INGESTION_QUALITY_SOURCE_ID_REUSE", sourceReuse.getMessage());

        SourceContract student = CatalogFixtures.sources().stream()
                .filter(source -> source.sourceId().equals("SRC-P0-STUDENT-001"))
                .findFirst().orElseThrow();
        DataSourceCatalog remappedDependency = DataSourceCatalog.draft(
                UUID.fromString("019fc6b8-9400-7000-8000-000000000137"),
                "DCC-1.0.0", List.of(student), List.of(new DependencyBinding(
                        student.sourceId(), "DEP-P1-HOLD-901",
                        DependencyRequirement.REQUIRED, DependencyOperator.ALL_OF)),
                "sha256:" + "9".repeat(64), cutoff.plusSeconds(2));
        IllegalStateException dependencyReuse = assertThrows(
                IllegalStateException.class,
                () -> transactions.executeWithoutResult(
                        status -> store.save(remappedDependency, 0)));
        assertEquals("INGESTION_QUALITY_DEPENDENCY_ID_REUSE", dependencyReuse.getMessage());
        assertEquals(1, count("iq_data_source_catalog"));
    }

    private DataSourceCatalogService service(
            cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditPort audit) {
        return service(audit, trace -> {});
    }

    private DataSourceCatalogService service(
            cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditPort audit,
            CatalogPublicationGuard guard) {
        return service(store, transactions, audit, guard);
    }

    private DataSourceCatalogService service(
            JdbcCatalogStore catalogStore,
            TransactionTemplate catalogTransactions,
            cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditPort audit,
            CatalogPublicationGuard guard) {
        return new DataSourceCatalogService(catalogStore, catalogStore,
                catalog -> List.<CatalogContractViolation>of(),
                (actor, sourceAction, dependencyAction, catalog, trace) ->
                        CatalogAuthorizationDecision.ALLOW,
                new JdbcCatalogTransactionAdapter(catalogTransactions), audit, guard,
                catalog -> CatalogFixtures.evidence(catalog, NOW.plusSeconds(2)),
                () -> CatalogFixtures.draft(CATALOG_ID, NOW),
                () -> new TrustedTime(NOW.plusSeconds(2), TIME_PROFILE));
    }

    private static void awaitBoth(CountDownLatch ready) {
        ready.countDown();
        try {
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent publication did not rendezvous");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent publication interrupted", interrupted);
        }
    }

    private static DataSourceCatalog draft() {
        return CatalogFixtures.draft(CATALOG_ID, NOW);
    }

    private DataSourceCatalog publishDirect(
            UUID catalogId, UUID releaseId, Instant createdAt, long expectedCurrentVersion) {
        DataSourceCatalog draft = CatalogFixtures.draft(catalogId, createdAt);
        DataSourceCatalog publishable = draft.validated(List.of(), createdAt.plusSeconds(1));
        CatalogEvidenceSet evidence = CatalogFixtures.evidence(
                publishable, createdAt.plusSeconds(2));
        DataSourceCatalog published = publishable.publish(
                releaseId, evidence.digest(), createdAt.plusSeconds(2));
        transactions.executeWithoutResult(status -> {
            store.save(draft, 0);
            store.saveValidation(publishable, 1, TRACE);
            store.publish(published, 2, expectedCurrentVersion, evidence);
        });
        return published;
    }

    private static DataSourceCatalog retentionHeldDraft(UUID catalogId, Instant createdAt) {
        return DataSourceCatalog.draft(
                catalogId, "DCC-1.0.0", List.of(new SourceContract(
                        "SRC-P1-HOLD-901", "retention-hold-anchor", "HOLD-SLICE-1.0.0",
                        "QG-1.0.0", "evidence+sha256://" + "7".repeat(64),
                        RuntimeEvidenceClaim.TARGET_VERIFIED)),
                List.of(new DependencyBinding(
                        "SRC-P1-HOLD-901", "DEP-P1-HOLD-901",
                        DependencyRequirement.REQUIRED, DependencyOperator.ALL_OF)),
                "sha256:" + "7".repeat(64), createdAt);
    }

    private UUID appendPendingAudit() {
        transactions.executeWithoutResult(status -> store.save(draft(), 0));
        service(store::append).validate(new ValidateCatalogCommand(
                CATALOG_ID, 1, ACTOR, TRACE));
        return jdbc.queryForObject("""
                select event_id from ingestion_quality.iq_local_audit_outbox
                 order by created_at,event_id limit 1
                """, UUID.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from ingestion_quality." + table, Integer.class);
    }

    private Set<String> updateColumns(String table, String grantee) {
        return Set.copyOf(jdbc.queryForList("""
                select column_name
                  from information_schema.column_privileges
                 where table_schema='ingestion_quality'
                   and table_name=? and grantee=? and privilege_type='UPDATE'
                """, String.class, table, grantee));
    }

    private boolean retentionFunctionPrivilege(String role) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select has_function_privilege(?, procedure.oid, 'EXECUTE')
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality'
                   and procedure.proname='iq_cleanup_expired'
                   and procedure.pronargs=1
                """, Boolean.class, role));
    }

    private boolean functionPrivilege(String role, String function) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select count(*)=1 and bool_and(
                         has_function_privilege(?, procedure.oid, 'EXECUTE'))
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality'
                   and procedure.proname=?
                """, Boolean.class, role, function));
    }

    private void ensureWorkloadLogins() {
        jdbc.execute("""
                do $role_test$
                begin
                    if not exists (
                        select 1 from pg_catalog.pg_roles
                         where rolname='scholarsense_iq_online_test_login') then
                        create role scholarsense_iq_online_test_login login inherit;
                    end if;
                    if not exists (
                        select 1 from pg_catalog.pg_roles
                         where rolname='scholarsense_iq_relay_test_login') then
                        create role scholarsense_iq_relay_test_login login inherit;
                    end if;
                end
                $role_test$;
                alter role scholarsense_iq_online_test_login
                    login inherit nosuperuser nocreatedb nocreaterole
                    noreplication nobypassrls;
                alter role scholarsense_iq_relay_test_login
                    login inherit nosuperuser nocreatedb nocreaterole
                    noreplication nobypassrls;
                revoke scholarsense_ingestion_quality_online,
                       scholarsense_ingestion_quality_relay
                    from scholarsense_iq_online_test_login,
                         scholarsense_iq_relay_test_login;
                grant scholarsense_ingestion_quality_online
                    to scholarsense_iq_online_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_relay
                    to scholarsense_iq_relay_test_login
                    with inherit true, set false;
                """);
    }

    private DataSource workloadDataSource(String login) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(required("scholarsense.audit.pg.url"));
        source.setUsername(login);
        source.setPassword("");
        return source;
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required");
        return value;
    }

    private static AuditTokenizationPort tokenization() {
        return (domain, value) -> new AuditTokenizedValue(
                domain.prefix() + "_v1_k1_" + sha256(domain.name() + "\0" + value),
                "AUDIT-TOKENIZATION-1.0.0",
                "k1");
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
