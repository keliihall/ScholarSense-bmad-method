package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationConsumer;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.AccessInvalidationEventJsonCodec;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.AccessInvalidationConsumerDatabase;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.AccessInvalidationDatabaseRoleVerifier;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationBackfillProcessor;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationDeliveryRepository;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationAppliedFactObserver;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationFenceQueryAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationImpactResolver;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationJobRepository;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationReconciler;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationRepository;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityCascadeScopeReadBackAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcResponsibilityRecipientEvidenceAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcResponsibilitySyncRepository;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.LocalAccessInvalidationTransportAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.ResponsibilityScopeQueryAdapter;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAggregateType;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationSnapshot;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationState;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationChangeKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationCause;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationConsumerRoute;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationDeliveryDecision;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationDependencyWatermark;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReason;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationRetention;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSourceVector;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSubjectSnapshot;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeAccount;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientValidity;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL 18.4 invalidation atomicity, fencing, and privilege evidence. */
class AccessInvalidationPostgreSqlIT {
    private static final AccessInvalidationLineageId LINEAGE =
            new AccessInvalidationLineageId(
                    "lin_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    private static final UUID ROOT_EVENT =
            UUID.fromString("019c0000-0000-7000-8000-000000000201");
    private static final UUID NEXT_EVENT =
            UUID.fromString("019c0000-0000-7000-8000-000000000202");
    private static final String TRACE =
            "0123456789abcdef0123456789abcdef";
    private static final Instant NOW =
            Instant.parse("2026-07-31T12:00:00Z");
    private static final String PRODUCER_LOGIN =
            "scholarsense_access_producer_test_login";
    private static final String CONSUMER_LOGIN =
            "scholarsense_access_consumer_test_login";
    private static final String CUTOVER_LOGIN =
            "scholarsense_responsibility_cutover_test_login";
    private static final String ROLE_TEST_PASSWORD =
            "access-role-test-password";

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = dataSource(
                requiredProperty("scholarsense.audit.pg.url"));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                truncate table
                  identity_access.ia_responsibility_v2_cutover_command,
                  identity_access.ia_access_invalidation_reconciliation,
                  identity_access.ia_access_invalidation_job,
                  identity_access.ia_access_invalidation_backfill_request,
                  identity_access.ia_access_invalidation_consumer_watermark,
                  identity_access.ia_access_invalidation_consumer_applied_outbox,
                  identity_access.ia_access_invalidation_local_apply,
                  identity_access.ia_access_invalidation_local_fence,
                  identity_access.ia_access_invalidation_local_inbox,
                  identity_access.ia_access_invalidation_propagation,
                  identity_access.ia_access_invalidation_observed_ack,
                  identity_access.ia_access_invalidation_delivery_attempt,
                  identity_access.ia_access_invalidation_outbox,
                  identity_access.ia_access_invalidation_lineage_head,
                  identity_access.ia_access_invalidation_fact
                cascade
                """);
    }

    @Test
    void cleanAndUpgradeContainV8TablesAndControlledConsumers() {
        assertEquals("180004", jdbc.queryForObject(
                "select current_setting('server_version_num')",
                String.class));
        assertEquals(15, jdbc.queryForObject("""
                select count(*)
                  from information_schema.tables
                 where table_schema='identity_access'
                   and table_name like 'ia_access_invalidation_%'
                """, Integer.class));
        assertEquals(5, jdbc.queryForObject("""
                select count(*)
                  from identity_access
                    .ia_access_invalidation_consumer_registry
                """, Integer.class));
        assertEquals(4, jdbc.queryForObject("""
                select count(*)
                  from identity_access
                    .ia_access_invalidation_consumer_registry
                 where lifecycle='planned/not-installed'
                   and not required
                   and runtime_evidence_claim='none'
                """, Integer.class));
        JdbcTemplate upgraded = new JdbcTemplate(dataSource(
                requiredProperty(
                        "scholarsense.audit.pg.upgrade-url")));
        assertEquals(15, upgraded.queryForObject("""
                select count(*)
                  from information_schema.tables
                 where table_schema='identity_access'
                   and table_name like 'ia_access_invalidation_%'
                """, Integer.class));
    }

    @Test
    void factHeadOutboxAndPropagationCommitAtomicallyWithoutVersionGaps() {
        JdbcAccessInvalidationRepository repository = repository();
        AccessInvalidationFact root = fact(
                ROOT_EVENT, null, 1, "a".repeat(64));
        repository.append(command(
                root,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000211"),
                0,
                0));
        assertEquals(1L, count("ia_access_invalidation_fact"));
        assertEquals(1L, count("ia_access_invalidation_outbox"));
        assertEquals(1L, count("ia_access_invalidation_propagation"));
        assertEquals(1, repository.head(LINEAGE.value())
                .orElseThrow()
                .aggregateVersion());

        AccessInvalidationFact successor = fact(
                NEXT_EVENT, ROOT_EVENT, 2, "b".repeat(64));
        repository.append(command(
                successor,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000212"),
                1,
                1));
        assertEquals(2, repository.head(LINEAGE.value())
                .orElseThrow()
                .aggregateVersion());
        assertEquals(2L, count("ia_access_invalidation_fact"));

        repository.append(command(
                successor,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000213"),
                1,
                1));
        assertEquals(2L, count("ia_access_invalidation_fact"));

        AccessInvalidationFact conflictingReplay = fact(
                NEXT_EVENT, ROOT_EVENT, 2, "c".repeat(64));
        assertThrows(IllegalStateException.class, () ->
                repository.append(command(
                        conflictingReplay,
                        UUID.fromString(
                                "019c0000-0000-7000-8000-000000000214"),
                        1,
                        1)));
        assertEquals(2L, count("ia_access_invalidation_fact"));
    }

    @Test
    void propagationAndWatermarkCannotDriftFromTheirImmutableFact() {
        AccessInvalidationFact root =
                fact(ROOT_EVENT, null, 1, "a".repeat(64));
        AccessInvalidationAppendCommand append = command(
                root,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000215"),
                0,
                0);
        repository().append(append);

        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        update identity_access
                          .ia_access_invalidation_propagation
                           set target_version=2
                         where event_id=?
                        """, ROOT_EVENT));
        assertEquals(1L, jdbc.queryForObject("""
                select target_version
                  from identity_access.ia_access_invalidation_propagation
                 where event_id=?
                """, Long.class, ROOT_EVENT));

        AtomicInteger sequence = new AtomicInteger(216);
        var consumer = new JdbcAccessInvalidationConsumer(
                jdbc,
                transactions(),
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000"
                                + sequence.incrementAndGet()));
        var route = new AccessInvalidationConsumerRoute(
                "authorization-current-scope",
                "identity-access",
                AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE,
                LINEAGE);
        assertEquals(
                AccessInvalidationDeliveryDecision.APPLIED,
                consumer.apply(
                        route,
                        root,
                        append.eventPayload(),
                        eventDigest(root),
                        NOW.plusSeconds(2)));

        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        update identity_access
                          .ia_access_invalidation_consumer_watermark
                           set current_watermark=2
                         where consumer_id=? and aggregate_type=?
                           and lineage_id=?
                        """,
                        route.consumerId(),
                        "responsibility-scope",
                        LINEAGE.value()));
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        update identity_access
                          .ia_access_invalidation_consumer_watermark
                           set last_payload_digest=?
                         where consumer_id=? and aggregate_type=?
                           and lineage_id=?
                        """,
                        "0".repeat(64),
                        route.consumerId(),
                        "responsibility-scope",
                        LINEAGE.value()));
        assertEquals(1L, jdbc.queryForObject("""
                select current_watermark
                  from identity_access
                    .ia_access_invalidation_consumer_watermark
                 where consumer_id=? and aggregate_type=?
                   and lineage_id=?
                """,
                Long.class,
                route.consumerId(),
                "responsibility-scope",
                LINEAGE.value()));
        assertEquals(3, jdbc.queryForObject("""
                select count(*)
                  from pg_constraint
                 where conname in (
                   'ia_access_invalidation_propagation_lineage_version_uk',
                   'ia_access_invalidation_propagation_fact_fk',
                   'ia_access_invalidation_consumer_watermark_fact_fk')
                """, Integer.class));
    }

    @Test
    void producerRejectsIncompleteWirePayloadBeforeAnyAtomicMemberCommits() {
        AccessInvalidationFact root =
                fact(ROOT_EVENT, null, 1, "a".repeat(64));
        var incomplete = new AccessInvalidationAppendCommand(
                root,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000215"),
                0,
                0,
                "{\"id\":\"" + root.eventId()
                        + "\",\"data\":{\"eventId\":\""
                        + root.eventId() + "\"}}",
                NOW);

        assertThrows(
                IllegalArgumentException.class,
                () -> repository().append(incomplete));
        assertEquals(0L, count("ia_access_invalidation_fact"));
        assertEquals(0L, count("ia_access_invalidation_outbox"));
    }

    @Test
    void losingRootAndConcurrentSuccessorRollBackAllAtomicMembers()
            throws Exception {
        JdbcAccessInvalidationRepository repository = repository();
        repository.append(command(
                fact(ROOT_EVENT, null, 1, "a".repeat(64)),
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000221"),
                0,
                0));

        AccessInvalidationFact competingRoot = fact(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000222"),
                null,
                1,
                "d".repeat(64));
        assertThrows(RuntimeException.class, () ->
                repository.append(command(
                        competingRoot,
                        UUID.fromString(
                                "019c0000-0000-7000-8000-000000000223"),
                        0,
                        0)));
        assertEquals(1L, count("ia_access_invalidation_fact"));
        assertEquals(1L, count("ia_access_invalidation_outbox"));

        List<Callable<Boolean>> workers = List.of(
                () -> appendCandidate(
                        UUID.fromString(
                                "019c0000-0000-7000-8000-000000000224"),
                        UUID.fromString(
                                "019c0000-0000-7000-8000-000000000225")),
                () -> appendCandidate(
                        UUID.fromString(
                                "019c0000-0000-7000-8000-000000000226"),
                        UUID.fromString(
                                "019c0000-0000-7000-8000-000000000227")));
        List<Boolean> outcomes;
        try (var executor = Executors.newFixedThreadPool(2)) {
            outcomes = executor.invokeAll(workers).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    })
                    .toList();
        }
        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        assertEquals(2L, count("ia_access_invalidation_fact"));
        assertEquals(2, repository.head(LINEAGE.value())
                .orElseThrow()
                .aggregateVersion());
    }

    @Test
    void writerCannotUpdateDeleteOrTruncateImmutableFacts() {
        assertDenied(
                "update identity_access.ia_access_invalidation_fact "
                        + "set payload_digest=payload_digest");
        assertDenied(
                "delete from "
                        + "identity_access.ia_access_invalidation_fact");
        assertDenied(
                "truncate table "
                        + "identity_access.ia_access_invalidation_fact");
    }

    @Test
    void producerAndConsumerDatabaseRolesCannotForgeEachOthersEvidence() {
        String producer = "scholarsense_identity_sync_worker";
        String consumer =
                "scholarsense_identity_invalidation_consumer";
        assertTrue(hasPrivilege(
                producer, "ia_access_invalidation_fact", "INSERT"));
        assertFalse(hasPrivilege(
                producer,
                "ia_access_invalidation_consumer_watermark",
                "INSERT"));
        assertFalse(hasPrivilege(
                producer,
                "ia_access_invalidation_local_apply",
                "INSERT"));
        assertTrue(hasPrivilege(
                consumer,
                "ia_access_invalidation_consumer_watermark",
                "INSERT"));
        assertTrue(hasPrivilege(
                consumer,
                "ia_access_invalidation_local_apply",
                "INSERT"));
        assertFalse(hasPrivilege(
                consumer, "ia_access_invalidation_fact", "INSERT"));
        assertFalse(hasPrivilege(
                consumer,
                "ia_access_invalidation_observed_ack",
                "INSERT"));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                "select pg_has_role(?, ?, 'member')",
                Boolean.class,
                producer,
                consumer)));

        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        insert into identity_access
                          .ia_access_invalidation_consumer_watermark (
                            consumer_id, producer, aggregate_type, lineage_id,
                            current_watermark, fencing_token, trace_id)
                        values (
                          'public-task', 'identity-access',
                          'responsibility-scope', ?, 0, 0, ?)
                        """, LINEAGE.value(), TRACE));
    }

    @Test
    void responsibilityV2CutoverAclIsExactOnCleanAndUpgrade() {
        assertResponsibilityV2CutoverAcl(jdbc);
        assertResponsibilityV2CutoverAcl(new JdbcTemplate(dataSource(
                requiredProperty(
                        "scholarsense.audit.pg.upgrade-url"))));
    }

    @Test
    void startupVerifierProvesDistinctProducerAndConsumerPrincipals()
            throws Exception {
        verifyRoleIsolation();
        verifyCutoverRoleIsolation();
    }

    @Test
    void startupVerifierRejectsRoutineGateOrLiveDeletePrivilege()
            throws Exception {
        jdbc.execute("""
                grant update (reconciliation_status) on identity_access
                  .ia_responsibility_v2_shadow_checkpoint
                  to scholarsense_identity_sync_worker
                """);
        try {
            assertThrows(
                    IllegalStateException.class,
                    this::verifyRoleIsolation);
        } finally {
            jdbc.execute("""
                    revoke update (reconciliation_status) on identity_access
                      .ia_responsibility_v2_shadow_checkpoint
                      from scholarsense_identity_sync_worker
                    """);
        }

        jdbc.execute("""
                grant delete on identity_access.ia_responsibility_current
                  to scholarsense_identity_sync_worker
                """);
        try {
            assertThrows(
                    IllegalStateException.class,
                    this::verifyRoleIsolation);
        } finally {
            jdbc.execute("""
                    revoke delete on identity_access.ia_responsibility_current
                      from scholarsense_identity_sync_worker
                    """);
        }
    }

    @Test
    void startupVerifierRejectsCutoverRoutineOrImmutableCommandUpdate()
            throws Exception {
        jdbc.execute("""
                grant update (source_version) on identity_access
                  .ia_responsibility_v2_shadow_checkpoint
                  to scholarsense_identity_responsibility_v2_cutover
                """);
        try {
            assertThrows(
                    IllegalStateException.class,
                    this::verifyCutoverRoleIsolation);
        } finally {
            jdbc.execute("""
                    revoke update (source_version) on identity_access
                      .ia_responsibility_v2_shadow_checkpoint
                      from scholarsense_identity_responsibility_v2_cutover
                    """);
        }

        jdbc.execute("""
                grant update (trace_id) on identity_access
                  .ia_responsibility_v2_cutover_command
                  to scholarsense_identity_responsibility_v2_cutover
                """);
        try {
            assertThrows(
                    IllegalStateException.class,
                    this::verifyCutoverRoleIsolation);
        } finally {
            jdbc.execute("""
                    revoke update (trace_id) on identity_access
                      .ia_responsibility_v2_cutover_command
                      from scholarsense_identity_responsibility_v2_cutover
                    """);
        }
    }

    @Test
    void startupVerifierRejectsColumnLevelProducerEvidenceMutation()
            throws Exception {
        jdbc.execute("""
                grant update (current_state) on identity_access
                  .ia_access_invalidation_local_fence
                  to scholarsense_identity_sync_worker
                """);
        try {
            assertThrows(
                    IllegalStateException.class,
                    this::verifyRoleIsolation);
        } finally {
            jdbc.execute("""
                    revoke update (current_state) on identity_access
                      .ia_access_invalidation_local_fence
                      from scholarsense_identity_sync_worker
                    """);
        }
    }

    @Test
    void startupVerifierRejectsConsumerReadBeyondItsBoundary()
            throws Exception {
        jdbc.execute("""
                grant select on identity_access
                  .ia_access_invalidation_outbox
                  to scholarsense_identity_invalidation_consumer
                """);
        try {
            assertThrows(
                    IllegalStateException.class,
                    this::verifyRoleIsolation);
        } finally {
            jdbc.execute("""
                    revoke select on identity_access
                      .ia_access_invalidation_outbox
                      from scholarsense_identity_invalidation_consumer
                    """);
        }
    }

    @Test
    void startupVerifierRejectsConsumerMembershipOutsideItsBoundary()
            throws Exception {
        jdbc.execute("""
                grant scholarsense_identity_current_reader
                  to scholarsense_identity_invalidation_consumer
                """);
        try {
            assertThrows(
                    IllegalStateException.class,
                    this::verifyRoleIsolation);
        } finally {
            jdbc.execute("""
                    revoke scholarsense_identity_current_reader
                      from scholarsense_identity_invalidation_consumer
                    """);
        }
    }

    @Test
    void startupVerifierRejectsSetRoleFromTheSameSessionPrincipal()
            throws Exception {
        try (Connection producerConnection = dataSource.getConnection();
                Connection consumerConnection = dataSource.getConnection()) {
            try (Statement producerStatement =
                            producerConnection.createStatement();
                    Statement consumerStatement =
                            consumerConnection.createStatement()) {
                producerStatement.execute(
                        "set role scholarsense_identity_sync_worker");
                consumerStatement.execute(
                        "set role scholarsense_identity_invalidation_consumer");
            }
            var producerSource = new SingleConnectionDataSource(
                    producerConnection, true);
            var consumerSource = new SingleConnectionDataSource(
                    consumerConnection, true);
            assertThrows(
                    IllegalStateException.class,
                    () -> AccessInvalidationDatabaseRoleVerifier.verify(
                            new JdbcTemplate(producerSource),
                            new TransactionTemplate(
                                    new DataSourceTransactionManager(
                                            producerSource)),
                            new AccessInvalidationConsumerDatabase(
                                    new JdbcTemplate(consumerSource),
                                    new TransactionTemplate(
                                            new DataSourceTransactionManager(
                                                    consumerSource)))));
        }
    }

    private void verifyRoleIsolation() throws Exception {
        ensureRoleIsolationTestLogins();
        var producerSource = new DriverManagerDataSource(
                requiredProperty("scholarsense.audit.pg.url"),
                PRODUCER_LOGIN,
                ROLE_TEST_PASSWORD);
        AccessInvalidationDatabaseRoleVerifier.verify(
                new JdbcTemplate(producerSource),
                new TransactionTemplate(
                        new DataSourceTransactionManager(producerSource)),
                AccessInvalidationConsumerDatabase.connect(
                        requiredProperty("scholarsense.audit.pg.url"),
                        CONSUMER_LOGIN,
                        ROLE_TEST_PASSWORD));
    }

    private void verifyCutoverRoleIsolation() {
        ensureRoleIsolationTestLogins();
        var cutoverSource = new DriverManagerDataSource(
                requiredProperty("scholarsense.audit.pg.url"),
                CUTOVER_LOGIN,
                ROLE_TEST_PASSWORD);
        AccessInvalidationDatabaseRoleVerifier
                .verifyResponsibilityV2Cutover(
                        new JdbcTemplate(cutoverSource),
                        new TransactionTemplate(
                                new DataSourceTransactionManager(
                                        cutoverSource)));
    }

    private void ensureRoleIsolationTestLogins() {
        jdbc.execute("""
                do $role_test$
                begin
                    if not exists (
                        select 1 from pg_catalog.pg_roles
                         where rolname=
                           'scholarsense_access_producer_test_login'
                    ) then
                        create role scholarsense_access_producer_test_login
                            login password 'access-role-test-password';
                    end if;
                    if not exists (
                        select 1 from pg_catalog.pg_roles
                         where rolname=
                           'scholarsense_access_consumer_test_login'
                    ) then
                        create role scholarsense_access_consumer_test_login
                            login password 'access-role-test-password';
                    end if;
                    if not exists (
                        select 1 from pg_catalog.pg_roles
                         where rolname=
                           'scholarsense_responsibility_cutover_test_login'
                    ) then
                        create role
                          scholarsense_responsibility_cutover_test_login
                            login password 'access-role-test-password';
                    end if;
                end
                $role_test$;
                alter role scholarsense_access_producer_test_login
                    login nosuperuser nocreatedb nocreaterole
                    noreplication nobypassrls
                    password 'access-role-test-password';
                alter role scholarsense_access_consumer_test_login
                    login nosuperuser nocreatedb nocreaterole
                    noreplication nobypassrls
                    password 'access-role-test-password';
                alter role scholarsense_responsibility_cutover_test_login
                    login nosuperuser nocreatedb nocreaterole
                    noreplication nobypassrls
                    password 'access-role-test-password';
                revoke scholarsense_identity_invalidation_consumer,
                       scholarsense_identity_current_reader,
                       scholarsense_identity_responsibility_v2_cutover
                    from scholarsense_access_producer_test_login;
                revoke scholarsense_identity_sync_worker,
                       scholarsense_identity_current_reader,
                       scholarsense_identity_responsibility_v2_cutover
                    from scholarsense_access_consumer_test_login;
                revoke scholarsense_identity_sync_worker,
                       scholarsense_identity_invalidation_consumer,
                       scholarsense_identity_current_reader
                    from scholarsense_responsibility_cutover_test_login;
                grant scholarsense_identity_sync_worker
                    to scholarsense_access_producer_test_login;
                grant scholarsense_identity_invalidation_consumer
                    to scholarsense_access_consumer_test_login;
                grant scholarsense_identity_responsibility_v2_cutover
                    to scholarsense_responsibility_cutover_test_login;
                """);
    }

    @Test
    void relayAcknowledgementDoesNotAdvanceConsumerWatermark() {
        repository().append(command(
                fact(ROOT_EVENT, null, 1, "a".repeat(64)),
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000231"),
                0,
                0));
        var delivery = new JdbcAccessInvalidationDeliveryRepository(
                jdbc, transactions());
        var claims = delivery.claim(
                "relay-a", 10, NOW.plusSeconds(30), NOW.plusSeconds(90));

        assertEquals(1, claims.size());
        delivery.published(
                claims.getFirst(),
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000232"),
                NOW.plusSeconds(31));

        assertEquals("published", jdbc.queryForObject("""
                select status
                  from identity_access.ia_access_invalidation_outbox
                """, String.class));
        assertEquals(
                0L,
                count("ia_access_invalidation_consumer_watermark"));
    }

    @Test
    void consumerAppliesOnlyContinuousRouteWatermarkAtomically() {
        AccessInvalidationFact root =
                fact(ROOT_EVENT, null, 1, "a".repeat(64));
        AccessInvalidationFact successor =
                fact(NEXT_EVENT, ROOT_EVENT, 2, "b".repeat(64));
        repository().append(command(
                root,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000241"),
                0,
                0));
        repository().append(command(
                successor,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000242"),
                1,
                1));
        AtomicInteger sequence = new AtomicInteger(250);
        var consumer = new JdbcAccessInvalidationConsumer(
                jdbc,
                transactions(),
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000"
                                + sequence.incrementAndGet()));
        var route = new AccessInvalidationConsumerRoute(
                "authorization-current-scope",
                "identity-access",
                AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE,
                LINEAGE);

        assertThrows(
                IllegalArgumentException.class,
                () -> consumer.apply(
                        route,
                        successor,
                        "{\"id\":\"" + successor.eventId()
                                + "\",\"data\":{\"eventId\":\""
                                + successor.eventId() + "\"}}",
                        eventDigest(successor),
                        NOW.plusSeconds(9)));
        assertEquals(0L, count("ia_access_invalidation_local_inbox"));

        assertEquals(
                AccessInvalidationDeliveryDecision
                        .GAP_BACKFILL_REQUIRED,
                consumer.apply(
                        route,
                        successor,
                        command(
                                        successor,
                                        UUID.fromString(
                                                "019c0000-0000-7000-8000-000000000243"),
                                        1,
                                        1)
                                .eventPayload(),
                        eventDigest(successor),
                        NOW.plusSeconds(10)));
        assertFalse(consumer.find(route).isPresent());
        assertEquals(
                1L,
                count("ia_access_invalidation_backfill_request"));
        assertEquals(1L, jdbc.queryForObject("""
                select from_version
                  from identity_access
                    .ia_access_invalidation_backfill_request
                """, Long.class));
        assertEquals(1L, jdbc.queryForObject("""
                select through_version
                  from identity_access
                    .ia_access_invalidation_backfill_request
                """, Long.class));

        assertEquals(
                AccessInvalidationDeliveryDecision.APPLIED,
                consumer.apply(
                        route,
                        root,
                        command(
                                        root,
                                        UUID.fromString(
                                                "019c0000-0000-7000-8000-000000000244"),
                                        0,
                                        0)
                                .eventPayload(),
                        eventDigest(root),
                        NOW.plusSeconds(11)));
        assertEquals("invalidated", jdbc.queryForObject("""
                select current_state
                  from identity_access
                    .ia_access_invalidation_local_fence
                 where lineage_id=?
                """, String.class, LINEAGE.value()));
        assertEquals(
                AccessInvalidationDeliveryDecision.APPLIED,
                consumer.apply(
                        route,
                        successor,
                        command(
                                        successor,
                                        UUID.fromString(
                                                "019c0000-0000-7000-8000-000000000245"),
                                        1,
                                        1)
                                .eventPayload(),
                        eventDigest(successor),
                        NOW.plusSeconds(12)));
        assertEquals(
                AccessInvalidationDeliveryDecision.DUPLICATE,
                consumer.apply(
                        route,
                        successor,
                        command(
                                        successor,
                                        UUID.fromString(
                                                "019c0000-0000-7000-8000-000000000246"),
                                        1,
                                        1)
                                .eventPayload(),
                        eventDigest(successor),
                        NOW.plusSeconds(13)));
        AccessInvalidationFact conflicting =
                fact(
                        NEXT_EVENT,
                        ROOT_EVENT,
                        2,
                        "c".repeat(64),
                        AccessInvalidationReason.ACCOUNT_DISABLED);
        assertEquals(
                AccessInvalidationDeliveryDecision.CONFLICT,
                consumer.apply(
                        route,
                        conflicting,
                        command(
                                        conflicting,
                                        UUID.fromString(
                                                "019c0000-0000-7000-8000-000000000247"),
                                        1,
                                        1)
                                .eventPayload(),
                        eventDigest(conflicting),
                        NOW.plusSeconds(14)));

        assertEquals(
                2,
                consumer.find(route).orElseThrow().currentWatermark());
        assertEquals(2L, count("ia_access_invalidation_local_inbox"));
        assertEquals(2L, count("ia_access_invalidation_local_apply"));
        assertEquals(
                2L,
                count("ia_access_invalidation_consumer_applied_outbox"));

        var observer = new JdbcAccessInvalidationAppliedFactObserver(
                jdbc, transactions());
        assertEquals(2, observer.observe(10, NOW.plusSeconds(15)));
        var reconciler = new JdbcAccessInvalidationReconciler(
                jdbc,
                transactions(),
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000271"),
                new ObjectMapper());
        assertTrue(reconciler.reconcile(
                        LINEAGE,
                        2,
                        NOW.plusSeconds(16),
                        TRACE)
                .healthy());
        assertEquals("complete", jdbc.queryForObject("""
                select propagation_status
                  from identity_access
                    .ia_access_invalidation_propagation
                 where event_id=?
                """, String.class, NEXT_EVENT));
    }

    @Test
    void durableBackfillClaimsAndReplaysAContinuousBoundedRange() {
        AccessInvalidationFact root =
                fact(ROOT_EVENT, null, 1, "a".repeat(64));
        AccessInvalidationFact successor =
                fact(NEXT_EVENT, ROOT_EVENT, 2, "b".repeat(64));
        JdbcAccessInvalidationRepository repository = repository();
        repository.append(command(
                root,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000401"),
                0,
                0));
        repository.append(command(
                successor,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000402"),
                1,
                1));
        AtomicInteger sequence = new AtomicInteger(410);
        var consumer = new JdbcAccessInvalidationConsumer(
                jdbc,
                transactions(),
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000"
                                + sequence.incrementAndGet()));
        var route = new AccessInvalidationConsumerRoute(
                "authorization-current-scope",
                "identity-access",
                AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE,
                LINEAGE);
        assertEquals(
                AccessInvalidationDeliveryDecision
                        .GAP_BACKFILL_REQUIRED,
                consumer.apply(
                        route,
                        successor,
                        command(
                                        successor,
                                        UUID.fromString(
                                                "019c0000-0000-7000-8000-000000000403"),
                                        1,
                                        1)
                                .eventPayload(),
                        eventDigest(successor),
                        NOW.plusSeconds(10)));

        var backfill = new JdbcAccessInvalidationBackfillProcessor(
                jdbc,
                transactions(),
                repository,
                consumer,
                new AccessInvalidationEventJsonCodec(
                        new ObjectMapper()));
        assertEquals(
                1,
                backfill.process(
                        "backfill-a", 10, 1, NOW.plusSeconds(11)));
        assertEquals(
                1,
                consumer.find(route).orElseThrow().currentWatermark());
        assertEquals("pending", jdbc.queryForObject("""
                select status
                  from identity_access
                    .ia_access_invalidation_backfill_request
                """, String.class));

        assertEquals(
                1,
                backfill.process(
                        "backfill-b", 10, 1, NOW.plusSeconds(12)));
        assertEquals(
                2,
                consumer.find(route).orElseThrow().currentWatermark());
        assertEquals("completed", jdbc.queryForObject("""
                select status
                  from identity_access
                    .ia_access_invalidation_backfill_request
                """, String.class));
        assertEquals(2L, count("ia_access_invalidation_local_apply"));
    }

    @Test
    void durableBackfillQuarantinesAStaleWatermarkFence() {
        AccessInvalidationFact root =
                fact(ROOT_EVENT, null, 1, "a".repeat(64));
        AccessInvalidationFact successor =
                fact(NEXT_EVENT, ROOT_EVENT, 2, "b".repeat(64));
        JdbcAccessInvalidationRepository repository = repository();
        repository.append(command(
                root,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000421"),
                0,
                0));
        repository.append(command(
                successor,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000422"),
                1,
                1));
        AtomicInteger sequence = new AtomicInteger(430);
        var consumer = new JdbcAccessInvalidationConsumer(
                jdbc,
                transactions(),
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000"
                                + sequence.incrementAndGet()));
        var route = new AccessInvalidationConsumerRoute(
                "authorization-current-scope",
                "identity-access",
                AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE,
                LINEAGE);
        assertEquals(
                AccessInvalidationDeliveryDecision
                        .GAP_BACKFILL_REQUIRED,
                consumer.apply(
                        route,
                        successor,
                        command(
                                        successor,
                                        UUID.fromString(
                                                "019c0000-0000-7000-8000-000000000423"),
                                        1,
                                        1)
                                .eventPayload(),
                        eventDigest(successor),
                        NOW.plusSeconds(10)));
        jdbc.update("""
                update identity_access
                  .ia_access_invalidation_backfill_request
                   set watermark_fencing_token=1
                """);

        var backfill = new JdbcAccessInvalidationBackfillProcessor(
                jdbc,
                transactions(),
                repository,
                consumer,
                new AccessInvalidationEventJsonCodec(
                        new ObjectMapper()));
        assertEquals(
                1,
                backfill.process(
                        "backfill-stale", 10, 10,
                        NOW.plusSeconds(11)));
        assertEquals("quarantined", jdbc.queryForObject("""
                select status
                  from identity_access
                    .ia_access_invalidation_backfill_request
                """, String.class));
        assertEquals(
                "ACCESS_INVALIDATION_BACKFILL_WATERMARK_FENCED",
                jdbc.queryForObject("""
                        select last_error_code
                          from identity_access
                            .ia_access_invalidation_backfill_request
                        """, String.class));
        assertFalse(consumer.find(route).isPresent());
        assertEquals(0L, count("ia_access_invalidation_local_apply"));
    }

    @Test
    void forgedAppliedVersionIsQuarantinedAndCannotCompletePropagation() {
        AccessInvalidationFact root =
                fact(ROOT_EVENT, null, 1, "a".repeat(64));
        repository().append(command(
                root,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000441"),
                0,
                0));
        AtomicInteger sequence = new AtomicInteger(450);
        var consumer = new JdbcAccessInvalidationConsumer(
                jdbc,
                transactions(),
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000"
                                + sequence.incrementAndGet()));
        var route = new AccessInvalidationConsumerRoute(
                "authorization-current-scope",
                "identity-access",
                AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE,
                LINEAGE);
        assertEquals(
                AccessInvalidationDeliveryDecision.APPLIED,
                consumer.apply(
                        route,
                        root,
                        command(
                                        root,
                                        UUID.fromString(
                                                "019c0000-0000-7000-8000-000000000442"),
                                        0,
                                        0)
                                .eventPayload(),
                        eventDigest(root),
                        NOW.plusSeconds(10)));

        transactions().executeWithoutResult(status -> {
            jdbc.execute("set local session_replication_role='replica'");
            jdbc.update("""
                    update identity_access
                      .ia_access_invalidation_consumer_applied_outbox
                       set applied_version=2
                    """);
        });
        assertEquals(
                1,
                new JdbcAccessInvalidationAppliedFactObserver(
                                jdbc, transactions())
                        .observe(10, NOW.plusSeconds(11)));
        assertEquals(0L, count("ia_access_invalidation_observed_ack"));
        assertEquals("quarantined", jdbc.queryForObject("""
                select status
                  from identity_access
                    .ia_access_invalidation_consumer_applied_outbox
                """, String.class));

        var reconciliation = new JdbcAccessInvalidationReconciler(
                        jdbc,
                        transactions(),
                        ignored -> UUID.fromString(
                                "019c0000-0000-7000-8000-000000000461"),
                        new ObjectMapper())
                .reconcile(
                        LINEAGE,
                        1,
                        NOW.plusSeconds(12),
                        TRACE);
        assertTrue(reconciliation.gapConsumerIds()
                .contains("authorization-current-scope"));
        assertEquals("lagging", propagation(root.eventId()));
    }

    @Test
    void causeWaitsForImpactCompletionAndRequiredDenominatorReopensSafely() {
        AccessInvalidationFact cause = causeFact();
        JdbcAccessInvalidationRepository repository = repository();
        repository.append(command(
                cause,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000291"),
                0,
                0));
        repository.enqueue(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000292"),
                new AccessInvalidationCause(
                        cause.eventId(),
                        cause.lineageId(),
                        cause.reasonCode(),
                        cause.sourceVector(),
                        cause.effectiveAt(),
                        cause.traceId()),
                cause.retention().retainUntil());
        AtomicInteger ids = new AtomicInteger(300);
        var consumer = new JdbcAccessInvalidationConsumer(
                jdbc,
                transactions(),
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000"
                                + ids.incrementAndGet()));
        var route = new AccessInvalidationConsumerRoute(
                "authorization-current-scope",
                "identity-access",
                AccessInvalidationAggregateType.IDENTITY_CAUSE,
                cause.lineageId());
        assertEquals(
                AccessInvalidationDeliveryDecision.APPLIED,
                consumer.apply(
                        route,
                        cause,
                        command(
                                        cause,
                                        UUID.fromString(
                                                "019c0000-0000-7000-8000-000000000293"),
                                        0,
                                        0)
                                .eventPayload(),
                        eventDigest(cause),
                        NOW.plusSeconds(20)));
        new JdbcAccessInvalidationAppliedFactObserver(
                        jdbc, transactions())
                .observe(10, NOW.plusSeconds(21));
        var reconciler = new JdbcAccessInvalidationReconciler(
                jdbc,
                transactions(),
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000"
                                + ids.incrementAndGet()),
                new ObjectMapper());

        reconciler.reconcile(
                cause.lineageId(), 1, NOW.plusSeconds(22), TRACE);
        assertEquals("pending", propagation(cause.eventId()));

        jdbc.update("""
                update identity_access.ia_access_invalidation_job
                   set status='completed'
                 where cause_event_id=?
                """, cause.eventId());
        reconciler.reconcile(
                cause.lineageId(), 1, NOW.plusSeconds(23), TRACE);
        assertEquals("complete", propagation(cause.eventId()));

        try {
            jdbc.update("""
                    update identity_access
                      .ia_access_invalidation_consumer_registry
                       set lifecycle='active', required=true,
                           activation_at=?, initial_watermark=0,
                           runtime_evidence_claim='current-runtime',
                           updated_at=?
                     where consumer_id='reporting-export'
                    """, Timestamp.from(NOW), Timestamp.from(NOW));
            assertEquals(
                    1,
                    reconciler.reconcileIncremental(
                            NOW.plusSeconds(24), 10));
            assertEquals("lagging", propagation(cause.eventId()));
            assertEquals(2, jdbc.queryForObject("""
                    select required_consumer_count
                      from identity_access
                        .ia_access_invalidation_propagation
                     where event_id=?
                    """, Integer.class, cause.eventId()));
        } finally {
            jdbc.update("""
                    update identity_access
                      .ia_access_invalidation_consumer_registry
                       set lifecycle='planned/not-installed', required=false,
                           activation_at=null, initial_watermark=null,
                           runtime_evidence_claim='none'
                     where consumer_id='reporting-export'
                    """);
        }
    }

    @Test
    void durableImpactJobsUseLeaseFencingAndRecoverableKeysetCursor() {
        AccessInvalidationFact cause = causeFact();
        JdbcAccessInvalidationRepository store = repository();
        store.append(command(
                cause,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000280"),
                0,
                0));
        UUID jobId = UUID.fromString(
                "019c0000-0000-7000-8000-000000000281");
        var impactCause = new AccessInvalidationCause(
                cause.eventId(),
                cause.lineageId(),
                cause.reasonCode(),
                cause.sourceVector(),
                cause.effectiveAt(),
                cause.traceId());
        store.enqueue(
                jobId,
                impactCause,
                cause.retention().retainUntil());
        store.enqueue(
                jobId,
                impactCause,
                cause.retention().retainUntil());
        var jobs = new JdbcAccessInvalidationJobRepository(
                jdbc, transactions());

        var first = jobs.claim(
                cn.edu.suda.scholarsense.identityaccess.domain
                        .AccessInvalidationJobKind.IMPACT,
                "job-a",
                NOW.plusSeconds(2),
                10);
        assertEquals(1, first.size());
        assertEquals(1, first.getFirst().job().fence());
        assertTrue(jobs.isCurrentImpact(first.getFirst()));
        assertTrue(jobs.claim(
                cn.edu.suda.scholarsense.identityaccess.domain
                                .AccessInvalidationJobKind.IMPACT,
                        "job-b",
                        NOW.plusSeconds(2),
                        10)
                .isEmpty());

        jobs.checkpoint(
                first.getFirst(),
                7,
                LINEAGE.value(),
                false,
                NOW.plusSeconds(3));
        var second = jobs.claim(
                cn.edu.suda.scholarsense.identityaccess.domain
                        .AccessInvalidationJobKind.IMPACT,
                "job-b",
                NOW.plusSeconds(3),
                10);
        assertEquals(2, second.getFirst().job().fence());
        assertEquals(7, second.getFirst().job().cursor());
        assertEquals(LINEAGE.value(), second.getFirst().cursorKey());
        assertThrows(
                IllegalStateException.class,
                () -> jobs.checkpoint(
                        first.getFirst(),
                        8,
                        true,
                        NOW.plusSeconds(4)));
        jobs.checkpoint(
                second.getFirst(), 8, true, NOW.plusSeconds(4));
        assertEquals("completed", jdbc.queryForObject("""
                select status
                  from identity_access.ia_access_invalidation_job
                """, String.class));
    }

    @Test
    void supersededImpactCauseCannotAppendAnOlderFanOutPage() {
        AccessInvalidationFact firstCause = causeFact();
        JdbcAccessInvalidationRepository store = repository();
        store.append(command(
                firstCause,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000282"),
                0,
                0));
        store.enqueue(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000283"),
                new AccessInvalidationCause(
                        firstCause.eventId(),
                        firstCause.lineageId(),
                        firstCause.reasonCode(),
                        firstCause.sourceVector(),
                        firstCause.effectiveAt(),
                        firstCause.traceId()),
                firstCause.retention().retainUntil());
        var jobs = new JdbcAccessInvalidationJobRepository(
                jdbc, transactions());
        var staleLease = jobs.claim(
                        cn.edu.suda.scholarsense.identityaccess.domain
                                .AccessInvalidationJobKind.IMPACT,
                        "impact-old-cause",
                        NOW.plusSeconds(1),
                        1)
                .getFirst();

        AccessInvalidationFact newerCause = successorCause(firstCause);
        store.append(command(
                newerCause,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000285"),
                1,
                1));
        AccessInvalidationCause newerBinding = new AccessInvalidationCause(
                newerCause.eventId(),
                newerCause.lineageId(),
                newerCause.reasonCode(),
                newerCause.sourceVector(),
                newerCause.effectiveAt(),
                newerCause.traceId());
        var conflict = assertThrows(
                IllegalStateException.class,
                () -> store.enqueue(
                        staleLease.job().jobId(),
                        newerBinding,
                        newerCause.retention().retainUntil()));
        assertEquals(
                "ACCESS_INVALIDATION_IMPACT_IDEMPOTENCY_CONFLICT",
                conflict.getMessage());

        assertFalse(jobs.isCurrentImpact(staleLease));
        jobs.checkpoint(
                staleLease,
                staleLease.job().cursor(),
                staleLease.cursorKey(),
                true,
                NOW.plusSeconds(3));
        assertEquals("completed", jdbc.queryForObject("""
                select status
                  from identity_access.ia_access_invalidation_job
                 where job_id=?
                """, String.class, staleLease.job().jobId()));
    }

    @Test
    void expiryJobIdempotencyRejectsAConflictingEventBinding() {
        Instant dueAt = NOW.plusSeconds(5);
        seedExpiringScope(dueAt);
        AccessInvalidationFact scheduled = fact(
                ROOT_EVENT, null, 1, "a".repeat(64));
        repository().append(command(
                scheduled,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000383"),
                0,
                0));
        var jobs = new JdbcAccessInvalidationJobRepository(
                jdbc, transactions());
        UUID jobId = UUID.fromString(
                "019c0000-0000-7000-8000-000000000384");
        var first = jobs.enqueue(
                jobId,
                LINEAGE,
                scheduled.eventId(),
                scheduled.aggregateVersion(),
                dueAt,
                AccessInvalidationReason.RELATION_EXPIRED,
                TRACE,
                NOW.plusSeconds(1));
        var duplicate = jobs.enqueue(
                jobId,
                LINEAGE,
                scheduled.eventId(),
                scheduled.aggregateVersion(),
                dueAt,
                AccessInvalidationReason.RELATION_EXPIRED,
                TRACE,
                NOW.plusSeconds(1));
        assertEquals(first, duplicate);

        var conflict = assertThrows(
                IllegalStateException.class,
                () -> jobs.enqueue(
                        jobId,
                        LINEAGE,
                        scheduled.eventId(),
                        scheduled.aggregateVersion() + 1,
                        dueAt,
                        AccessInvalidationReason.RELATION_EXPIRED,
                        TRACE,
                        NOW.plusSeconds(1)));
        assertEquals(
                "ACCESS_INVALIDATION_EXPIRY_IDEMPOTENCY_CONFLICT",
                conflict.getMessage());
    }

    @Test
    void overdueExpiryRunsAsCatchUpAgainstItsBoundEventAndVersion() {
        Instant dueAt = NOW.plusSeconds(5);
        seedExpiringScope(dueAt);
        AccessInvalidationFact scheduled = fact(
                ROOT_EVENT, null, 1, "a".repeat(64));
        JdbcAccessInvalidationRepository store = repository();
        store.append(command(
                scheduled,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000381"),
                0,
                0));
        var jobs = new JdbcAccessInvalidationJobRepository(
                jdbc, transactions());
        jobs.enqueue(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000382"),
                LINEAGE,
                scheduled.eventId(),
                scheduled.aggregateVersion(),
                dueAt,
                AccessInvalidationReason.RELATION_EXPIRED,
                TRACE,
                NOW.plusSeconds(1));
        AtomicInteger ids = new AtomicInteger(390);
        var worker = new AccessInvalidationExpiryWorker(
                jobs,
                store,
                new AccessInvalidationEventJsonCodec(new ObjectMapper()),
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000"
                                + ids.incrementAndGet()),
                transactionPort());

        assertEquals(
                1,
                worker.run(
                        "expiry-catch-up",
                        10,
                        NOW.plus(1, ChronoUnit.HOURS)));

        AccessInvalidationFact expired = store.latest(
                        LINEAGE.value())
                .orElseThrow();
        assertEquals(AccessInvalidationChangeKind.EXPIRED,
                expired.changeKind());
        assertEquals(AccessInvalidationReason.RELATION_EXPIRED,
                expired.reasonCode());
        assertEquals(dueAt, expired.effectiveAt());
        assertEquals(scheduled.eventId(), expired.supersedesId());
        assertEquals(2, expired.aggregateVersion());
        assertEquals("completed", jdbc.queryForObject("""
                select status
                  from identity_access.ia_access_invalidation_job
                 where job_kind='expiry'
                """, String.class));
    }

    @Test
    void expiryBoundToSupersededEventCompletesWithoutAppending() {
        Instant dueAt = NOW.plusSeconds(5);
        seedExpiringScope(dueAt);
        JdbcAccessInvalidationRepository store = repository();
        AccessInvalidationFact scheduled = fact(
                ROOT_EVENT, null, 1, "a".repeat(64));
        store.append(command(
                scheduled,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000371"),
                0,
                0));
        var jobs = new JdbcAccessInvalidationJobRepository(
                jdbc, transactions());
        jobs.enqueue(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000372"),
                LINEAGE,
                scheduled.eventId(),
                scheduled.aggregateVersion(),
                dueAt,
                AccessInvalidationReason.RELATION_EXPIRED,
                TRACE,
                NOW.plusSeconds(1));
        AccessInvalidationFact successor = fact(
                NEXT_EVENT,
                ROOT_EVENT,
                2,
                "b".repeat(64));
        store.append(command(
                successor,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000373"),
                1,
                1));
        var worker = new AccessInvalidationExpiryWorker(
                jobs,
                store,
                new AccessInvalidationEventJsonCodec(new ObjectMapper()),
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000374"),
                transactionPort());

        assertEquals(1, worker.run(
                "expiry-stale", 10, NOW.plusSeconds(60)));

        assertEquals(2L, count("ia_access_invalidation_fact"));
        assertEquals(successor.eventId(), store.latest(
                        LINEAGE.value())
                .orElseThrow()
                .eventId());
        assertEquals("completed", jdbc.queryForObject("""
                select status
                  from identity_access.ia_access_invalidation_job
                 where job_kind='expiry'
                """, String.class));
    }

    @Test
    void sourceCorrectionUsesKeysetAndRequiresRoleCollegeMatch() {
        String accountDigest = "1".repeat(64);
        String collegeDigest = "2".repeat(64);
        String otherCollegeDigest = "3".repeat(64);
        String roleDigest = "4".repeat(64);
        String replacementAccountDigest = "a".repeat(64);
        seedImpactIdentity(
                accountDigest,
                collegeDigest,
                otherCollegeDigest,
                roleDigest);
        insertImpactAccount(
                "019c0000-0000-7000-8000-000000000405",
                replacementAccountDigest,
                "actor_v1_k1_" + "5".repeat(64));
        insertImpactArchive();
        AccessInvalidationLineageId laterLineage =
                new AccessInvalidationLineageId(
                        "lin_CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC");
        insertImpactScope(
                "019c0000-0000-7000-8000-000000000411",
                "019c0000-0000-7000-8000-000000000421",
                "019c0000-0000-7000-8000-000000000431",
                "rtok_" + "a".repeat(40),
                "5".repeat(64),
                accountDigest,
                otherCollegeDigest,
                LINEAGE,
                "7".repeat(64));
        insertImpactScope(
                "019c0000-0000-7000-8000-000000000412",
                "019c0000-0000-7000-8000-000000000422",
                "019c0000-0000-7000-8000-000000000432",
                "rtok_" + "b".repeat(40),
                "6".repeat(64),
                replacementAccountDigest,
                collegeDigest,
                laterLineage,
                "8".repeat(64));
        jdbc.update("""
                update identity_access.ia_authoritative_role_current
                   set account_id=(
                     select account_id
                       from identity_access
                         .ia_authoritative_account_current
                      where external_ref_digest=?),
                       organization_id=(
                     select organization_id
                       from identity_access
                         .ia_authoritative_organization_current
                      where external_ref_digest=?),
                       source_version=10
                 where external_ref_digest=?
                """, replacementAccountDigest, collegeDigest, roleDigest);
        snapshotCurrentImpactRole(10);
        assertEquals(2L, count(
                "ia_authoritative_role_binding_history"));
        jdbc.update("""
                delete from identity_access.ia_responsibility_source_fact
                """);
        assertEquals(0L, count("ia_responsibility_source_fact"));

        AccessInvalidationFact cause = identityCorrectionCause(roleDigest);
        JdbcAccessInvalidationRepository repository = repository();
        repository.append(command(
                cause,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000451"),
                0,
                0));
        AtomicInteger sequence = new AtomicInteger(460);
        var resolver = new JdbcAccessInvalidationImpactResolver(
                jdbc,
                repository,
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000"
                                + sequence.incrementAndGet()));
        AccessInvalidationCause correction = new AccessInvalidationCause(
                cause.eventId(),
                cause.lineageId(),
                cause.reasonCode(),
                cause.sourceVector(),
                cause.effectiveAt(),
                cause.traceId());

        List<AccessInvalidationFact> first = resolver.resolve(
                correction, 1, 0, null);
        assertEquals(1, first.size());
        assertEquals(LINEAGE, first.getFirst().lineageId());
        assertEquals(
                AccessInvalidationChangeKind.CORRECTED,
                first.getFirst().changeKind());
        assertFalse(first.getFirst().authorizationSnapshot()
                .r1EmploymentValid());
        assertTrue(first.getFirst().authorizationSnapshot()
                .relationEffective());

        jdbc.update("""
                delete from identity_access.ia_responsibility_current
                 where access_lineage_id=?
                """, LINEAGE.value());
        List<AccessInvalidationFact> second = resolver.resolve(
                correction,
                1,
                1,
                first.getFirst().lineageId().value());
        assertEquals(1, second.size());
        assertEquals(laterLineage, second.getFirst().lineageId());
        assertEquals(
                AccessInvalidationChangeKind.REVALIDATED,
                second.getFirst().changeKind());
        assertTrue(second.getFirst().authorizationSnapshot()
                .r1EmploymentValid());
        assertTrue(second.getFirst().authorizationSnapshot()
                .relationEffective());
    }

    @Test
    void roleInvalidationMatchesHistoricalAccountAndCollegeExactly() {
        String accountDigest = "1".repeat(64);
        String replacementAccountDigest = "a".repeat(64);
        String collegeDigest = "2".repeat(64);
        String otherCollegeDigest = "3".repeat(64);
        String roleDigest = "4".repeat(64);
        seedImpactIdentity(
                accountDigest,
                collegeDigest,
                otherCollegeDigest,
                roleDigest);
        insertImpactAccount(
                "019c0000-0000-7000-8000-000000000405",
                replacementAccountDigest,
                "actor_v1_k1_" + "5".repeat(64));
        insertImpactArchive();
        insertImpactScope(
                "019c0000-0000-7000-8000-000000000471",
                "019c0000-0000-7000-8000-000000000472",
                "019c0000-0000-7000-8000-000000000473",
                "rtok_" + "f".repeat(40),
                "5".repeat(64),
                accountDigest,
                otherCollegeDigest,
                LINEAGE,
                "7".repeat(64));
        insertImpactScope(
                "019c0000-0000-7000-8000-000000000474",
                "019c0000-0000-7000-8000-000000000475",
                "019c0000-0000-7000-8000-000000000476",
                "rtok_" + "g".repeat(40),
                "6".repeat(64),
                accountDigest,
                collegeDigest,
                new AccessInvalidationLineageId(
                        "lin_CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"),
                "8".repeat(64));
        insertImpactScope(
                "019c0000-0000-7000-8000-000000000477",
                "019c0000-0000-7000-8000-000000000478",
                "019c0000-0000-7000-8000-000000000479",
                "rtok_" + "h".repeat(40),
                "9".repeat(64),
                replacementAccountDigest,
                otherCollegeDigest,
                new AccessInvalidationLineageId(
                        "lin_EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE"),
                "a".repeat(64));

        AccessInvalidationFact cause = identityRoleInvalidationCause(
                roleDigest);
        JdbcAccessInvalidationRepository store = repository();
        store.append(command(
                cause,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000481"),
                0,
                0));
        var resolver = new JdbcAccessInvalidationImpactResolver(
                jdbc,
                store,
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000482"));

        List<AccessInvalidationFact> affected = resolver.resolve(
                new AccessInvalidationCause(
                        cause.eventId(),
                        cause.lineageId(),
                        cause.reasonCode(),
                        cause.sourceVector(),
                        cause.effectiveAt(),
                        cause.traceId()),
                10,
                0,
                null);

        assertEquals(1, affected.size());
        assertEquals(LINEAGE, affected.getFirst().lineageId());
        assertEquals(AccessInvalidationChangeKind.INVALIDATED,
                affected.getFirst().changeKind());
    }

    @Test
    void cascadeReadBackEnumeratesEveryExactTrustedActiveLineage() {
        String accountDigest = "1".repeat(64);
        String collegeDigest = "2".repeat(64);
        String roleCollegeDigest = "3".repeat(64);
        seedImpactIdentity(
                accountDigest,
                collegeDigest,
                roleCollegeDigest,
                "4".repeat(64));
        insertImpactArchive();
        insertImpactScope(
                "019c0000-0000-7000-8000-000000000511",
                "019c0000-0000-7000-8000-000000000521",
                "019c0000-0000-7000-8000-000000000531",
                "rtok_" + "c".repeat(40),
                "5".repeat(64),
                accountDigest,
                roleCollegeDigest,
                LINEAGE,
                "7".repeat(64));
        AccessInvalidationLineageId secondActiveLineage =
                new AccessInvalidationLineageId(
                        "lin_FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
        insertImpactScope(
                "019c0000-0000-7000-8000-000000000514",
                "019c0000-0000-7000-8000-000000000524",
                "019c0000-0000-7000-8000-000000000534",
                "rtok_" + "f".repeat(40),
                "5".repeat(64),
                accountDigest,
                roleCollegeDigest,
                secondActiveLineage,
                "b".repeat(64));
        AccessInvalidationLineageId inactiveLineage =
                new AccessInvalidationLineageId(
                        "lin_DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD");
        insertImpactScope(
                "019c0000-0000-7000-8000-000000000512",
                "019c0000-0000-7000-8000-000000000522",
                "019c0000-0000-7000-8000-000000000532",
                "rtok_" + "d".repeat(40),
                "6".repeat(64),
                accountDigest,
                roleCollegeDigest,
                inactiveLineage,
                "8".repeat(64));
        AccessInvalidationLineageId expiredLineage =
                new AccessInvalidationLineageId(
                        "lin_EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE");
        insertImpactScope(
                "019c0000-0000-7000-8000-000000000513",
                "019c0000-0000-7000-8000-000000000523",
                "019c0000-0000-7000-8000-000000000533",
                "rtok_" + "e".repeat(40),
                "9".repeat(64),
                accountDigest,
                roleCollegeDigest,
                expiredLineage,
                "a".repeat(64));
        AccessInvalidationLineageId blockedLineage =
                new AccessInvalidationLineageId(
                        "lin_GGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGG");
        insertImpactScope(
                "019c0000-0000-7000-8000-000000000515",
                "019c0000-0000-7000-8000-000000000525",
                "019c0000-0000-7000-8000-000000000535",
                "rtok_" + "g".repeat(40),
                "7".repeat(64),
                accountDigest,
                roleCollegeDigest,
                blockedLineage,
                "c".repeat(64));
        jdbc.update("""
                update identity_access.ia_responsibility_current
                   set relation_status='inactive'
                 where access_lineage_id=?
                """, inactiveLineage.value());
        jdbc.update("""
                update identity_access.ia_responsibility_current
                   set effective_to=?
                 where access_lineage_id=?
                """, Timestamp.from(NOW.minusSeconds(1)),
                expiredLineage.value());
        jdbc.update("""
                update identity_access.ia_responsibility_current
                   set quality_gate_status='blocked'
                 where access_lineage_id=?
                """, blockedLineage.value());
        jdbc.update("""
                update identity_access.ia_responsibility_current
                   set counselor_account_id=null,
                       college_organization_id=null,
                       recipient_validity='invalid',
                       recipient_reason_code=
                           'RESPONSIBILITY_INACTIVE_RECIPIENT'
                 where access_lineage_id=?
                """, secondActiveLineage.value());
        seedFreshResponsibilityCheckpoint();
        var checkpointKey = new CheckpointKey(
                "SRC-P0-RESPONSIBILITY-001",
                "responsibility-authority",
                "sandbox-0",
                "responsibility");
        var syncRepository = new JdbcResponsibilitySyncRepository(jdbc);
        var recipientEvidence =
                new JdbcResponsibilityRecipientEvidenceAdapter(jdbc);
        UUID expectedAccountId = UUID.fromString(
                "019c0000-0000-7000-8000-000000000401");
        for (AccessInvalidationLineageId lineage :
                List.of(LINEAGE, secondActiveLineage)) {
            var exactRelation = syncRepository.currentCascadeScope(
                            checkpointKey,
                            lineage,
                            expectedAccountId,
                            "5".repeat(64),
                            NOW)
                    .orElseThrow();
            assertEquals(
                    expectedAccountId,
                    recipientEvidence.resolve(
                                    List.of(exactRelation), NOW)
                            .getFirst()
                            .counselorAccountId());
        }
        var exactReadBack = new ResponsibilityScopeQueryAdapter(
                syncRepository,
                recipientEvidence,
                () -> trustedTime(NOW),
                checkpointKey);

        var adapter = new JdbcIdentityCascadeScopeReadBackAdapter(
                jdbc, exactReadBack);

        var results = adapter.readBack(
                IdentityRecordKind.ACCOUNT,
                accountDigest,
                9,
                9,
                3,
                NOW);

        assertEquals(2, results.size());
        assertEquals(
                java.util.Set.of(LINEAGE, secondActiveLineage),
                results.stream()
                        .map(IdentityCascadeScopeReadBack::accessLineageId)
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(results.stream().allMatch(result ->
                result.studentEquivalenceDigest().equals("5".repeat(64))
                        && result.expectedValidity()
                                == ResponsibilityRecipientValidity.VALID
                        && result.readBack().validity()
                                == ResponsibilityRecipientValidity.VALID
                        && result.readBack().sourceVersion()
                                == result.scopeSourceVersion()
                        && result.readBack().sourceWatermark()
                                == result.scopeSourceWatermark()
                        && result.readBack().aggregateVersion()
                                == result.scopeAggregateVersion()));
    }

    @Test
    void roleCascadeUsesTrustedAssociationHistoryForOldAndNewScopes() {
        String accountDigest = "1".repeat(64);
        String replacementAccountDigest = "a".repeat(64);
        String newCollegeDigest = "2".repeat(64);
        String oldCollegeDigest = "3".repeat(64);
        String roleDigest = "4".repeat(64);
        seedImpactIdentity(
                accountDigest,
                newCollegeDigest,
                oldCollegeDigest,
                roleDigest);
        insertImpactAccount(
                "019c0000-0000-7000-8000-000000000405",
                replacementAccountDigest,
                "actor_v1_k1_" + "5".repeat(64));
        insertImpactArchive();
        AccessInvalidationLineageId oldLineage =
                new AccessInvalidationLineageId(
                        "lin_HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH");
        AccessInvalidationLineageId newLineage =
                new AccessInvalidationLineageId(
                        "lin_IIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIII");
        AccessInvalidationLineageId unrelatedLineage =
                new AccessInvalidationLineageId(
                        "lin_JJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJ");
        insertImpactScope(
                "019c0000-0000-7000-8000-000000000541",
                "019c0000-0000-7000-8000-000000000551",
                "019c0000-0000-7000-8000-000000000561",
                "rtok_" + "h".repeat(40),
                "8".repeat(64),
                accountDigest,
                oldCollegeDigest,
                oldLineage,
                "d".repeat(64));
        insertImpactScope(
                "019c0000-0000-7000-8000-000000000542",
                "019c0000-0000-7000-8000-000000000552",
                "019c0000-0000-7000-8000-000000000562",
                "rtok_" + "i".repeat(40),
                "9".repeat(64),
                accountDigest,
                newCollegeDigest,
                newLineage,
                "e".repeat(64));
        insertImpactScope(
                "019c0000-0000-7000-8000-000000000543",
                "019c0000-0000-7000-8000-000000000553",
                "019c0000-0000-7000-8000-000000000563",
                "rtok_" + "j".repeat(40),
                "b".repeat(64),
                replacementAccountDigest,
                newCollegeDigest,
                unrelatedLineage,
                "f".repeat(64));
        jdbc.update("""
                update identity_access.ia_authoritative_role_current
                   set organization_id=(
                         select organization_id
                           from identity_access
                                .ia_authoritative_organization_current
                          where external_ref_digest=?),
                       source_version=10,
                       source_watermark=10,
                       aggregate_version=4,
                       applied_at=?
                 where external_ref_digest=?
                """,
                newCollegeDigest,
                Timestamp.from(NOW.plusSeconds(1)),
                roleDigest);
        snapshotCurrentImpactRole(10);
        seedFreshResponsibilityCheckpoint();
        var checkpointKey = new CheckpointKey(
                "SRC-P0-RESPONSIBILITY-001",
                "responsibility-authority",
                "sandbox-0",
                "responsibility");
        var syncRepository = new JdbcResponsibilitySyncRepository(jdbc);
        var recipientEvidence =
                new JdbcResponsibilityRecipientEvidenceAdapter(jdbc);
        UUID expectedAccountId = UUID.fromString(
                "019c0000-0000-7000-8000-000000000401");
        for (var target : List.of(
                Map.entry(oldLineage, "8".repeat(64)),
                Map.entry(newLineage, "9".repeat(64)))) {
            var exactRelation = syncRepository.currentCascadeScope(
                            checkpointKey,
                            target.getKey(),
                            expectedAccountId,
                            target.getValue(),
                            NOW.plusSeconds(2))
                    .orElseThrow();
            assertEquals(
                    expectedAccountId,
                    recipientEvidence.resolve(
                                    List.of(exactRelation),
                                    NOW.plusSeconds(2))
                            .getFirst()
                            .counselorAccountId());
        }
        var exactReadBack = new ResponsibilityScopeQueryAdapter(
                syncRepository,
                recipientEvidence,
                () -> trustedTime(NOW.plusSeconds(2)),
                checkpointKey);
        var adapter = new JdbcIdentityCascadeScopeReadBackAdapter(
                jdbc, exactReadBack);

        List<IdentityCascadeScopeReadBack> results = adapter.readBack(
                IdentityRecordKind.EMPLOYMENT_ROLE,
                roleDigest,
                10,
                10,
                4,
                NOW.plusSeconds(2));

        assertEquals(
                java.util.Set.of(oldLineage, newLineage),
                results.stream()
                        .map(IdentityCascadeScopeReadBack::accessLineageId)
                        .collect(java.util.stream.Collectors.toSet()));
        IdentityCascadeScopeReadBack oldScope = results.stream()
                .filter(result -> result.accessLineageId().equals(oldLineage))
                .findFirst()
                .orElseThrow();
        assertEquals(
                ResponsibilityRecipientValidity.INVALID,
                oldScope.expectedValidity());
        assertEquals(
                ResponsibilityRecipientValidity.INVALID,
                oldScope.readBack().validity());
        IdentityCascadeScopeReadBack newScope = results.stream()
                .filter(result -> result.accessLineageId().equals(newLineage))
                .findFirst()
                .orElseThrow();
        assertEquals(
                ResponsibilityRecipientValidity.VALID,
                newScope.expectedValidity());
        assertEquals(
                ResponsibilityRecipientValidity.VALID,
                newScope.readBack().validity());
        assertTrue(results.stream().allMatch(result ->
                result.identitySourceVersion() == 10
                        && result.identitySourceWatermark() == 10
                        && result.identityAggregateVersion() == 4));
    }

    @Test
    void sameTracePostgreSqlEvidenceExecutesReadBackRelayConsumerAndReconciliation()
            throws Exception {
        String studentDigest = "c".repeat(64);
        String accountDigest = "1".repeat(64);
        String collegeDigest = "2".repeat(64);
        String roleDigest = "4".repeat(64);
        seedImpactIdentity(
                accountDigest,
                collegeDigest,
                "3".repeat(64),
                roleDigest);
        jdbc.update("""
                update identity_access.ia_authoritative_role_current
                   set organization_id=(
                     select organization_id
                       from identity_access
                         .ia_authoritative_organization_current
                      where external_ref_digest=?)
                 where external_ref_digest=?
                """, collegeDigest, roleDigest);
        insertImpactArchive();
        insertImpactScope(
                "019c0000-0000-7000-8000-000000000501",
                ROOT_EVENT.toString(),
                "019c0000-0000-7000-8000-000000000502",
                "rtok_" + "e".repeat(40),
                studentDigest,
                accountDigest,
                collegeDigest,
                LINEAGE,
                "7".repeat(64));
        seedFreshResponsibilityCheckpoint();
        var checkpointKey = new CheckpointKey(
                "SRC-P0-RESPONSIBILITY-001",
                "responsibility-authority",
                "sandbox-0",
                "responsibility");
        var syncRepository = new JdbcResponsibilitySyncRepository(jdbc);
        assertTrue(syncRepository.checkpoint(checkpointKey).isPresent());
        var currentRelations = syncRepository.currentByStudentDigest(
                checkpointKey, studentDigest, NOW.plusSeconds(8));
        assertEquals(1, currentRelations.size());
        var recipientEvidence =
                new JdbcResponsibilityRecipientEvidenceAdapter(jdbc);
        assertEquals(
                1,
                recipientEvidence.resolve(
                                currentRelations, NOW.plusSeconds(8))
                        .size());
        var scopeReadBack = new ResponsibilityScopeQueryAdapter(
                syncRepository,
                recipientEvidence,
                () -> trustedTime(NOW.plusSeconds(8)),
                checkpointKey,
                new JdbcAccessInvalidationFenceQueryAdapter(jdbc));
        assertEquals(
                ResponsibilityRecipientValidity.VALID,
                scopeReadBack.readBack(studentDigest, NOW.plusSeconds(8))
                        .validity());

        jdbc.update("""
                update identity_access.ia_authoritative_account_current
                   set status='inactive', source_version=10,
                       applied_at=?, trace_id=?
                 where external_ref_digest=?
                """,
                Timestamp.from(NOW.plusSeconds(1)),
                TRACE,
                accountDigest);
        JdbcAccessInvalidationRepository invalidations = repository();
        var jobs = new JdbcAccessInvalidationJobRepository(
                jdbc, transactions());
        var events = new AccessInvalidationEventJsonCodec(
                new ObjectMapper());
        AtomicInteger workflowIds = new AtomicInteger(530);
        AccessInvalidationIdPort workflowId = ignored -> UUID.fromString(
                "019c0000-0000-7000-8000-000000000"
                        + workflowIds.incrementAndGet());
        var publisher = new AccessInvalidationPublisherService(
                invalidations,
                invalidations,
                jobs,
                events,
                workflowId);
        CommittedIdentityChangeSet committed = inactiveAccountChangeSet(
                accountDigest);
        publisher.publish(committed);
        AccessInvalidationFact cause = invalidations.find(
                        committed.batch().sourceFacts().getFirst().eventId())
                .orElseThrow();
        var impactWorker = new AccessInvalidationImpactWorker(
                jobs,
                invalidations,
                new JdbcAccessInvalidationImpactResolver(
                        jdbc, invalidations, workflowId),
                events,
                workflowId,
                transactionPort());
        assertEquals(1, impactWorker.run(
                "same-trace-impact", 10, 100, NOW.plusSeconds(2)));
        UUID invalidationEventId = jdbc.queryForObject("""
                select event_id
                 from identity_access.ia_access_invalidation_fact
                 where aggregate_type='responsibility-scope'
                   and cause_event_id=?
                """, UUID.class, cause.eventId());
        AccessInvalidationFact correction = invalidations.find(
                        invalidationEventId)
                .orElseThrow();
        long watermarkBeforeRelay = jdbc.queryForObject("""
                select count(*)
                  from identity_access
                    .ia_access_invalidation_consumer_watermark
                 where lineage_id=?
                """, Long.class, LINEAGE.value());
        var delivery = new JdbcAccessInvalidationDeliveryRepository(
                jdbc, transactions());
        AtomicInteger sequence = new AtomicInteger(510);
        var consumer = new JdbcAccessInvalidationConsumer(
                jdbc,
                transactions(),
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000"
                                + sequence.incrementAndGet()));
        var relay = new AccessInvalidationOutboxRelayProcessor(
                delivery,
                new LocalAccessInvalidationTransportAdapter(
                        invalidations,
                        consumer,
                        () -> trustedTime(NOW.plusSeconds(4))),
                workflowId,
                "same-trace-relay");
        assertEquals(2, relay.relay(10, NOW.plusSeconds(4)));
        ResponsibilityScopeReadBack denied = scopeReadBack.readBack(
                studentDigest, NOW.plusSeconds(8));
        assertEquals(
                ResponsibilityRecipientValidity.INVALID,
                denied.validity());
        assertTrue(!denied.evaluatedAt().isBefore(
                        correction.effectiveAt())
                && !denied.evaluatedAt().isAfter(
                        correction.effectiveAt()
                                .plus(15, ChronoUnit.MINUTES)));

        assertEquals(2, new JdbcAccessInvalidationAppliedFactObserver(
                        jdbc, transactions())
                .observe(10, NOW.plusSeconds(9)));
        var reconciliation = new JdbcAccessInvalidationReconciler(
                        jdbc,
                        transactions(),
                        ignored -> UUID.fromString(
                                "019c0000-0000-7000-8000-000000000521"),
                        new ObjectMapper())
                .reconcile(
                        LINEAGE,
                        1,
                        NOW.plusSeconds(10),
                        TRACE);
        assertTrue(reconciliation.healthy());
        assertEquals("complete", propagation(correction.eventId()));
        writeSameTraceEvidenceIfRequested(
                cause,
                correction,
                denied,
                watermarkBeforeRelay);
    }

    private boolean appendCandidate(UUID eventId, UUID outboxId) {
        try {
            repository().append(command(
                    fact(eventId, ROOT_EVENT, 2, "e".repeat(64)),
                    outboxId,
                    1,
                    1));
            return true;
        } catch (RuntimeException lostRace) {
            return false;
        }
    }

    private void seedExpiringScope(Instant dueAt) {
        String accountDigest = "1".repeat(64);
        String collegeDigest = "2".repeat(64);
        seedImpactIdentity(
                accountDigest,
                collegeDigest,
                "3".repeat(64),
                "4".repeat(64));
        insertImpactArchive();
        insertImpactScope(
                "019c0000-0000-7000-8000-000000000361",
                ROOT_EVENT.toString(),
                "019c0000-0000-7000-8000-000000000362",
                "rtok_" + "d".repeat(40),
                "5".repeat(64),
                accountDigest,
                collegeDigest,
                LINEAGE,
                "6".repeat(64));
        jdbc.update("""
                update identity_access.ia_responsibility_current
                   set effective_to=?
                 where access_lineage_id=?
                """, Timestamp.from(dueAt), LINEAGE.value());
    }

    private void seedFreshResponsibilityCheckpoint() {
        jdbc.update("""
                insert into identity_access.ia_identity_sync_checkpoint (
                  source_id, feed_id, partition_id, consumer_projection,
                  source_version, source_watermark, aggregate_version,
                  last_successful_at, health, freshness, updated_at,
                  trace_id, retention_effective_at)
                values ('SRC-P0-RESPONSIBILITY-001',
                  'responsibility-authority', 'sandbox-0', 'responsibility',
                  9, 2, 1, ?, 'healthy', 'fresh', ?, ?, ?)
                on conflict (
                  source_id, feed_id, partition_id, consumer_projection)
                do update set source_version=excluded.source_version,
                  source_watermark=excluded.source_watermark,
                  aggregate_version=excluded.aggregate_version,
                  last_successful_at=excluded.last_successful_at,
                  health=excluded.health, freshness=excluded.freshness,
                  updated_at=excluded.updated_at,
                  trace_id=excluded.trace_id,
                  retention_effective_at=excluded.retention_effective_at
                """,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                TRACE,
                Timestamp.from(NOW));
    }

    private static CommittedIdentityChangeSet inactiveAccountChangeSet(
            String accountDigest) {
        var interval = new EffectiveInterval(
                NOW.minusSeconds(3600), null);
        var account = new AuthoritativeAccount(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000401"),
                "SRC-P0-RESPONSIBILITY-001",
                accountDigest,
                "actor_v1_k1_" + "1".repeat(64),
                AuthoritativeStatus.INACTIVE,
                interval,
                10,
                4);
        var sourceFact = new IdentitySourceFact(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000550"),
                IdentityRecordKind.ACCOUNT,
                accountDigest,
                10,
                interval,
                "6".repeat(64),
                4);
        var batch = new NormalizedIdentityBatch(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000551"),
                new CheckpointKey(
                        "SRC-P0-RESPONSIBILITY-001",
                        "identity-authority",
                        "sandbox-0",
                        "identity-org"),
                "IDENTITY-AUTHORITY-BATCH-1.0.0",
                10,
                9,
                10,
                NOW,
                NOW.plusSeconds(1),
                TRACE,
                "IDENTITY-ROLE-MAPPING-1.0.0",
                "7".repeat(64),
                "8".repeat(64),
                "9".repeat(64),
                true,
                new byte[] {1},
                new byte[] {2},
                new byte[] {3},
                "config://test/identity-inbox",
                "k1",
                List.of(account),
                List.of(),
                List.of(),
                List.of(sourceFact));
        return new CommittedIdentityChangeSet(
                batch,
                new IdentitySyncResult(
                        IdentitySyncOutcome.APPLIED,
                        "SYNC_APPLIED",
                        10,
                        10,
                        4),
                NOW.plusSeconds(1));
    }

    private void writeSameTraceEvidenceIfRequested(
            AccessInvalidationFact cause,
            AccessInvalidationFact fact,
            ResponsibilityScopeReadBack readBack,
            long watermarkBeforeRelay) throws Exception {
        String output = System.getenv(
                "ACCESS_INVALIDATION_EVIDENCE_OUTPUT");
        if (output == null || output.isBlank()) {
            return;
        }
        Instant sourceVisibleAt = jdbc.queryForObject("""
                select effective_at
                  from identity_access.ia_access_invalidation_fact
                 where event_id=?
                """, Timestamp.class, cause.eventId()).toInstant();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("traceId", jdbc.queryForObject("""
                select trace_id
                  from identity_access.ia_access_invalidation_fact
                 where event_id=?
                """, String.class, fact.eventId()));
        evidence.put("eventId", jdbc.queryForObject("""
                select event_id::text
                  from identity_access.ia_access_invalidation_fact
                 where event_id=?
                """, String.class, fact.eventId()));
        evidence.put("aggregateVersion", jdbc.queryForObject("""
                select aggregate_version
                  from identity_access.ia_access_invalidation_fact
                 where event_id=?
                """, Long.class, fact.eventId()));
        evidence.put("sourceVisibleAt", sourceVisibleAt.toString());
        evidence.put("causeEventId", jdbc.queryForObject("""
                select cause_event_id::text
                  from identity_access.ia_access_invalidation_fact
                 where event_id=?
                """, String.class, fact.eventId()));
        evidence.put("causeTraceId", jdbc.queryForObject("""
                select trace_id
                  from identity_access.ia_access_invalidation_fact
                 where event_id=?
                """, String.class, cause.eventId()));
        evidence.put("lineageId", jdbc.queryForObject("""
                select lineage_id
                  from identity_access.ia_access_invalidation_fact
                 where event_id=?
                """, String.class, fact.eventId()));
        evidence.put("impactJobStatus", jdbc.queryForObject("""
                select status
                  from identity_access.ia_access_invalidation_job
                 where job_kind='impact' and cause_event_id=?
                """, String.class, cause.eventId()));
        evidence.put("impactJobCursorLineageId", jdbc.queryForObject("""
                select cursor_lineage_id
                  from identity_access.ia_access_invalidation_job
                 where job_kind='impact' and cause_event_id=?
                """, String.class, cause.eventId()));
        evidence.put("readBackValidity", readBack.validity().name());
        evidence.put("readBackReasonCode", readBack.reasonCode());
        evidence.put("readBackEvaluatedAt",
                readBack.evaluatedAt().toString());
        evidence.put("withinFifteenMinutes",
                !readBack.evaluatedAt().isBefore(sourceVisibleAt)
                        && !readBack.evaluatedAt().isAfter(
                                sourceVisibleAt.plus(
                                        15, ChronoUnit.MINUTES)));
        evidence.put("outboxStatus", jdbc.queryForObject("""
                select status
                  from identity_access.ia_access_invalidation_outbox
                 where event_id=?
                """, String.class, fact.eventId()));
        evidence.put("outboxTraceId", jdbc.queryForObject("""
                select trace_id
                  from identity_access.ia_access_invalidation_outbox
                 where event_id=?
                """, String.class, fact.eventId()));
        evidence.put("deliveryOutcome", jdbc.queryForObject("""
                select outcome
                  from identity_access
                    .ia_access_invalidation_delivery_attempt
                 where outbox_id=(
                   select outbox_id
                     from identity_access.ia_access_invalidation_outbox
                    where event_id=?)
                """, String.class, fact.eventId()));
        evidence.put("watermarkBeforeRelay", watermarkBeforeRelay);
        evidence.put("consumerId", jdbc.queryForObject("""
                select consumer_id
                  from identity_access
                    .ia_access_invalidation_consumer_watermark
                 where lineage_id=?
                """, String.class, fact.lineageId().value()));
        evidence.put("consumerWatermark", jdbc.queryForObject("""
                select current_watermark
                  from identity_access
                    .ia_access_invalidation_consumer_watermark
                 where consumer_id='authorization-current-scope'
                   and lineage_id=?
                """, Long.class, fact.lineageId().value()));
        evidence.put("consumerLastEventId", jdbc.queryForObject("""
                select last_event_id::text
                  from identity_access
                    .ia_access_invalidation_consumer_watermark
                 where consumer_id='authorization-current-scope'
                   and lineage_id=?
                """, String.class, fact.lineageId().value()));
        evidence.put("consumerTraceId", jdbc.queryForObject("""
                select trace_id
                  from identity_access
                    .ia_access_invalidation_consumer_watermark
                 where consumer_id='authorization-current-scope'
                   and lineage_id=?
                """, String.class, fact.lineageId().value()));
        evidence.put("localFenceState", jdbc.queryForObject("""
                select current_state
                  from identity_access.ia_access_invalidation_local_fence
                 where consumer_id='authorization-current-scope'
                   and lineage_id=?
                """, String.class, fact.lineageId().value()));
        evidence.put("localFenceTraceId", jdbc.queryForObject("""
                select trace_id
                  from identity_access.ia_access_invalidation_local_fence
                 where consumer_id='authorization-current-scope'
                   and lineage_id=?
                """, String.class, fact.lineageId().value()));
        evidence.put("reconciliationOutcome", jdbc.queryForObject("""
                select outcome
                  from identity_access
                    .ia_access_invalidation_reconciliation
                 where lineage_id=? and target_version=?
                 order by checked_at desc limit 1
                """, String.class, fact.lineageId().value(),
                fact.aggregateVersion()));
        evidence.put("reconciliationTraceId", jdbc.queryForObject("""
                select trace_id
                  from identity_access
                    .ia_access_invalidation_reconciliation
                 where lineage_id=? and target_version=?
                 order by checked_at desc limit 1
                """, String.class, fact.lineageId().value(),
                fact.aggregateVersion()));
        evidence.put("propagationStatus", propagation(fact.eventId()));
        evidence.put("requiredConsumerCount", jdbc.queryForObject("""
                select required_consumer_count
                  from identity_access
                    .ia_access_invalidation_propagation
                 where event_id=?
                """, Integer.class, fact.eventId()));
        evidence.put("appliedRequiredConsumerCount", jdbc.queryForObject("""
                select applied_required_consumer_count
                  from identity_access
                    .ia_access_invalidation_propagation
                 where event_id=?
                """, Integer.class, fact.eventId()));
        evidence.put("propagationTraceId", jdbc.queryForObject("""
                select trace_id
                  from identity_access
                    .ia_access_invalidation_propagation
                 where event_id=?
                """, String.class, fact.eventId()));
        evidence.put("plannedConsumersAdvanced", jdbc.queryForObject("""
                select count(*)
                  from identity_access
                    .ia_access_invalidation_consumer_registry registry
                  join identity_access
                    .ia_access_invalidation_consumer_watermark watermark
                    on watermark.consumer_id=registry.consumer_id
                 where registry.lifecycle='planned/not-installed'
                   and watermark.current_watermark>0
                """, Long.class));
        evidence.put("databaseVersion", jdbc.queryForObject(
                "select version()", String.class));
        new ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValue(java.nio.file.Path.of(output).toFile(), evidence);
    }

    private static TrustedTime trustedTime(Instant instant) {
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

    private void seedImpactIdentity(
            String accountDigest,
            String collegeDigest,
            String otherCollegeDigest,
            String roleDigest) {
        jdbc.execute("""
                truncate table
                  identity_access.ia_responsibility_current,
                  identity_access.ia_responsibility_source_fact,
                  identity_access.ia_responsibility_source_archive,
                  identity_access.ia_authoritative_role_binding_history,
                  identity_access.ia_authoritative_role_current,
                  identity_access.ia_authoritative_account_current,
                  identity_access.ia_authoritative_organization_current
                cascade
                """);
        insertImpactAccount(
                "019c0000-0000-7000-8000-000000000401",
                accountDigest,
                "actor_v1_k1_" + "1".repeat(64));
        insertImpactOrganization(
                "019c0000-0000-7000-8000-000000000402",
                collegeDigest,
                "目标学院");
        insertImpactOrganization(
                "019c0000-0000-7000-8000-000000000403",
                otherCollegeDigest,
                "其他学院");
        jdbc.update("""
                insert into identity_access.ia_authoritative_role_current (
                  binding_id, source_id, feed_id, partition_id,
                  consumer_projection, account_id, organization_id,
                  external_ref_digest, source_role_code, target_role_id,
                  mapping_version, mapping_digest, status, effective_from,
                  source_version, source_watermark, aggregate_version,
                  applied_at, trace_id, retention_effective_at)
                values (?, 'SRC-P0-RESPONSIBILITY-001',
                  'identity-authority', 'sandbox-0', 'identity-org',
                  ?, ?, ?, 'COUNSELOR', 'R1-COUNSELOR',
                  'IDENTITY-ROLE-MAPPING-1.0.0', ?, 'active', ?,
                  9, 9, 3, ?, ?, ?)
                """,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000404"),
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000401"),
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000403"),
                roleDigest,
                "9".repeat(64),
                Timestamp.from(NOW.minusSeconds(3600)),
                Timestamp.from(NOW),
                TRACE,
                Timestamp.from(NOW));
        snapshotCurrentImpactRole(9);
    }

    private void insertImpactAccount(
            String accountId,
            String accountDigest,
            String subjectBindingToken) {
        jdbc.update("""
                insert into identity_access.ia_authoritative_account_current (
                  account_id, source_id, feed_id, partition_id,
                  consumer_projection, external_ref_digest,
                  subject_binding_token, status, effective_from,
                  source_version, source_watermark, aggregate_version,
                  mapping_version, applied_at, trace_id,
                  retention_effective_at)
                values (?, 'SRC-P0-RESPONSIBILITY-001',
                  'identity-authority', 'sandbox-0', 'identity-org', ?, ?,
                  'active', ?, 9, 9, 3, 'IDENTITY-ROLE-MAPPING-1.0.0',
                  ?, ?, ?)
                """,
                UUID.fromString(accountId),
                accountDigest,
                subjectBindingToken,
                Timestamp.from(NOW.minusSeconds(3600)),
                Timestamp.from(NOW),
                TRACE,
                Timestamp.from(NOW));
    }

    private void snapshotCurrentImpactRole(long sourceVersion) {
        jdbc.update("""
                insert into identity_access
                  .ia_authoritative_role_binding_history (
                    source_id, role_external_ref_digest, source_version,
                    binding_id, account_id, organization_id,
                    source_role_code, target_role_id, mapping_version,
                    mapping_digest, status, effective_from, effective_to,
                    recorded_at, trace_id, retention_effective_at,
                    expires_at)
                select source_id, external_ref_digest, ?, binding_id,
                       account_id, organization_id, source_role_code,
                       target_role_id, mapping_version, mapping_digest,
                       status, effective_from, effective_to, ?, trace_id, ?, ?
                  from identity_access.ia_authoritative_role_current
                """,
                sourceVersion,
                Timestamp.from(NOW.plusSeconds(sourceVersion)),
                Timestamp.from(NOW.plusSeconds(sourceVersion)),
                Timestamp.from(NOW.plus(365, ChronoUnit.DAYS)));
    }

    private void insertImpactOrganization(
            String organizationId,
            String externalRefDigest,
            String displayName) {
        jdbc.update("""
                insert into identity_access
                  .ia_authoritative_organization_current (
                    organization_id, source_id, feed_id, partition_id,
                    consumer_projection, external_ref_digest, display_name,
                    organization_type, status, effective_from,
                    source_version, source_watermark, aggregate_version,
                    applied_at, trace_id, retention_effective_at)
                values (?, 'SRC-P0-RESPONSIBILITY-001',
                  'identity-authority', 'sandbox-0', 'identity-org', ?, ?,
                  'college', 'active', ?, 9, 9, 3, ?, ?, ?)
                """,
                UUID.fromString(organizationId),
                externalRefDigest,
                displayName,
                Timestamp.from(NOW.minusSeconds(3600)),
                Timestamp.from(NOW),
                TRACE,
                Timestamp.from(NOW));
    }

    private void insertImpactArchive() {
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
                values (?, 'SRC-P0-RESPONSIBILITY-001',
                  'responsibility-authority', 'sandbox-0', 'responsibility',
                  'RESPONSIBILITY-BATCH-1.0.0',
                  'RESPONSIBILITY-AUTHORITY-1.0.0', 9, 0, 2,
                  cast('{"identity-authority|sandbox-0":9}' as jsonb),
                  ?, ?, decode('aa','hex'), decode('bb','hex'),
                  decode('cc','hex'),
                  'config://test/responsibility-authority-inbox', 'k1',
                  ?, ?, ?, ?, 2, ?, ?)
                """,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000410"),
                "a".repeat(64),
                "b".repeat(64),
                Timestamp.from(NOW.minusSeconds(120)),
                Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW),
                TRACE,
                Timestamp.from(NOW),
                Timestamp.from(NOW.plus(365, ChronoUnit.DAYS)));
    }

    private void insertImpactScope(
            String factId,
            String eventId,
            String relationId,
            String relationToken,
            String studentDigest,
            String accountDigest,
            String collegeDigest,
            AccessInvalidationLineageId lineage,
            String payloadDigest) {
        UUID accountId = jdbc.queryForObject("""
                select account_id
                  from identity_access.ia_authoritative_account_current
                 where external_ref_digest=?
                """, UUID.class, accountDigest);
        UUID collegeId = jdbc.queryForObject("""
                select organization_id
                  from identity_access.ia_authoritative_organization_current
                 where external_ref_digest=?
                """, UUID.class, collegeDigest);
        jdbc.update("""
                insert into identity_access.ia_responsibility_source_fact (
                  fact_id, batch_id, event_id, source_id, feed_id,
                  partition_id, consumer_projection, relation_ref_token,
                  student_ref_purpose, student_ref_key_version,
                  student_ref_token, student_ref_digest,
                  student_equivalence_digest, counselor_account_ref_digest,
                  college_organization_ref_digest, responsibility_type,
                  relation_status, effective_from, source_version,
                  source_watermark, record_version, aggregate_version,
                  payload_digest, supporting_identity_org_watermarks,
                  recipient_validity, recipient_reason_code,
                  recipient_mapped, applied_at, trace_id, consumer_watermark,
                  retention_effective_at, expires_at)
                values (?, ?, ?, 'SRC-P0-RESPONSIBILITY-001',
                  'responsibility-authority', 'sandbox-0', 'responsibility', ?,
                  'RESPONSIBILITY-STUDENT-REF', 'resp-student-v1', ?, ?, ?,
                  ?, ?, 'primary', 'active', ?, 9, 2, 1, 1, ?,
                  cast('{"identity-authority|sandbox-0":9}' as jsonb),
                  'valid', 'RESPONSIBILITY_VALID', true, ?, ?, 2, ?, ?)
                """,
                UUID.fromString(factId),
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000410"),
                UUID.fromString(eventId),
                relationToken,
                "stok_" + studentDigest,
                studentDigest,
                studentDigest,
                accountDigest,
                collegeDigest,
                Timestamp.from(NOW.minusSeconds(3600)),
                payloadDigest,
                Timestamp.from(NOW),
                TRACE,
                Timestamp.from(NOW),
                Timestamp.from(NOW.plus(365, ChronoUnit.DAYS)));
        jdbc.update("""
                insert into identity_access.ia_responsibility_current (
                  relation_id, source_id, feed_id, partition_id,
                  consumer_projection, relation_ref_token,
                  student_ref_purpose, student_ref_key_version,
                  student_ref_token, student_ref_digest,
                  student_equivalence_digest, counselor_account_ref_digest,
                  college_organization_ref_digest, counselor_account_id,
                  college_organization_id, responsibility_type,
                  relation_status, recipient_validity,
                  recipient_reason_code, quality_gate_status,
                  effective_from, source_version, source_watermark,
                  record_version, aggregate_version, payload_digest,
                  applied_at, trace_id,
                  retention_effective_at, access_lineage_id,
                  access_event_id, access_change_kind, access_reason_code,
                  access_effective_at)
                values (?, 'SRC-P0-RESPONSIBILITY-001',
                  'responsibility-authority', 'sandbox-0', 'responsibility', ?,
                  'RESPONSIBILITY-STUDENT-REF', 'resp-student-v1', ?, ?, ?,
                  ?, ?, ?, ?, 'primary', 'active', 'valid',
                  'RESPONSIBILITY_VALID', 'trusted', ?, 9, 2, 1, 1, ?, ?, ?, ?,
                  ?, ?, 'corrected', 'SOURCE_CORRECTION', ?)
                """,
                UUID.fromString(relationId),
                relationToken,
                "stok_" + studentDigest,
                studentDigest,
                studentDigest,
                accountDigest,
                collegeDigest,
                accountId,
                collegeId,
                Timestamp.from(NOW.minusSeconds(3600)),
                payloadDigest,
                Timestamp.from(NOW),
                TRACE,
                Timestamp.from(NOW),
                lineage.value(),
                UUID.fromString(eventId),
                Timestamp.from(NOW));
    }

    private static AccessInvalidationFact identityCorrectionCause(
            String objectDigest) {
        AccessInvalidationLineageId causeLineage =
                new AccessInvalidationLineageId(
                        "lin_DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD");
        return new AccessInvalidationFact(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000450"),
                TRACE,
                AccessInvalidationChangeKind.CORRECTED,
                AccessInvalidationReason.SOURCE_CORRECTION,
                causeLineage,
                null,
                null,
                AccessInvalidationAggregateType.IDENTITY_CAUSE,
                "cause_" + objectDigest,
                1,
                1,
                NOW,
                new AccessInvalidationSourceVector(
                        "SRC-P0-RESPONSIBILITY-001",
                        9,
                        9,
                        List.of(new AccessInvalidationDependencyWatermark(
                                "identity-authority",
                                "sandbox-0",
                                9))),
                new AccessInvalidationSubjectSnapshot(
                        "subtok_" + objectDigest,
                        "scptok_" + objectDigest,
                        objectDigest,
                        "ACCESS-INVALIDATION-TOKENIZATION-1.0.0"),
                new AccessInvalidationAuthorizationSnapshot(
                        AccessInvalidationAuthorizationState.INVALIDATED,
                        true,
                        true,
                        true,
                        true,
                        "RFP-1.0.0"),
                new AccessInvalidationRetention(
                        "restricted",
                        "RS-1.0.0",
                        NOW.plus(365, ChronoUnit.DAYS),
                        false),
                "f".repeat(64));
    }

    private static AccessInvalidationFact identityRoleInvalidationCause(
            String objectDigest) {
        return new AccessInvalidationFact(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000480"),
                TRACE,
                AccessInvalidationChangeKind.INVALIDATED,
                AccessInvalidationReason.R1_EMPLOYMENT_INVALID,
                new AccessInvalidationLineageId(
                        "lin_FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"),
                null,
                null,
                AccessInvalidationAggregateType.IDENTITY_CAUSE,
                "cause_" + objectDigest,
                1,
                1,
                NOW,
                new AccessInvalidationSourceVector(
                        "SRC-P0-RESPONSIBILITY-001",
                        9,
                        9,
                        List.of(new AccessInvalidationDependencyWatermark(
                                "identity-authority",
                                "sandbox-0",
                                9))),
                new AccessInvalidationSubjectSnapshot(
                        "subtok_" + objectDigest,
                        "scptok_" + objectDigest,
                        objectDigest,
                        "ACCESS-INVALIDATION-TOKENIZATION-1.0.0"),
                new AccessInvalidationAuthorizationSnapshot(
                        AccessInvalidationAuthorizationState.INVALIDATED,
                        true,
                        false,
                        true,
                        false,
                        "RFP-1.0.0"),
                new AccessInvalidationRetention(
                        "restricted",
                        "RS-1.0.0",
                        NOW.plus(365, ChronoUnit.DAYS),
                        false),
                "e".repeat(64));
    }

    private JdbcAccessInvalidationRepository repository() {
        return new JdbcAccessInvalidationRepository(
                new JdbcTemplate(dataSource),
                transactions(),
                new ObjectMapper());
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
    }

    private IdentitySyncTransactionPort transactionPort() {
        return new IdentitySyncTransactionPort() {
            @Override
            public <T> T execute(
                    java.util.function.Supplier<T> work) {
                return transactions().execute(status -> work.get());
            }
        };
    }

    private static AccessInvalidationAppendCommand command(
            AccessInvalidationFact fact,
            UUID outboxId,
            long expectedVersion,
            long expectedFence) {
        String payload = new AccessInvalidationEventJsonCodec(
                        new ObjectMapper())
                .encode(fact);
        return new AccessInvalidationAppendCommand(
                fact,
                outboxId,
                expectedVersion,
                expectedFence,
                payload,
                NOW.plusSeconds(fact.aggregateVersion()));
    }

    private static AccessInvalidationFact fact(
            UUID eventId,
            UUID supersedesId,
            long version,
            String payloadDigest) {
        return fact(
                eventId,
                supersedesId,
                version,
                payloadDigest,
                version == 1
                        ? AccessInvalidationReason.SOURCE_CORRECTION
                        : AccessInvalidationReason
                                .DIRECT_RESPONSIBILITY_CHANGE);
    }

    private static AccessInvalidationFact fact(
            UUID eventId,
            UUID supersedesId,
            long version,
            String payloadDigest,
            AccessInvalidationReason reason) {
        return new AccessInvalidationFact(
                eventId,
                TRACE,
                version == 1
                        ? AccessInvalidationChangeKind.CORRECTED
                        : AccessInvalidationChangeKind.REVOKED,
                reason,
                LINEAGE,
                supersedesId,
                null,
                AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE,
                LINEAGE.value(),
                version,
                version,
                NOW.plusSeconds(version),
                new AccessInvalidationSourceVector(
                        "SRC-P0-RESPONSIBILITY-001",
                        8 + version,
                        8 + version,
                        List.of(
                                new AccessInvalidationDependencyWatermark(
                                        "identity-authority",
                                        "sandbox-0",
                                        42),
                                new AccessInvalidationDependencyWatermark(
                                        "responsibility-authority",
                                        "sandbox-0",
                                        8 + version))),
                new AccessInvalidationSubjectSnapshot(
                        "subtok_" + "a".repeat(40),
                        "scptok_" + "b".repeat(40),
                        "c".repeat(64),
                        "ACCESS-INVALIDATION-TOKENIZATION-1.0.0"),
                new AccessInvalidationAuthorizationSnapshot(
                        AccessInvalidationAuthorizationState.INVALIDATED,
                        true,
                        true,
                        true,
                        false,
                        "RFP-1.0.0"),
                new AccessInvalidationRetention(
                        "restricted",
                        "RS-1.0.0",
                        NOW.plus(365, ChronoUnit.DAYS),
                        false),
                payloadDigest);
    }

    private static AccessInvalidationFact causeFact() {
        AccessInvalidationLineageId lineage =
                new AccessInvalidationLineageId(
                        "lin_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        return new AccessInvalidationFact(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000290"),
                TRACE,
                AccessInvalidationChangeKind.INVALIDATED,
                AccessInvalidationReason.ACCOUNT_DISABLED,
                lineage,
                null,
                null,
                AccessInvalidationAggregateType.IDENTITY_CAUSE,
                "cause_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                1,
                1,
                NOW,
                new AccessInvalidationSourceVector(
                        "SRC-P0-RESPONSIBILITY-001",
                        9,
                        9,
                        List.of(new AccessInvalidationDependencyWatermark(
                                "identity-authority",
                                "sandbox-0",
                                9))),
                new AccessInvalidationSubjectSnapshot(
                        "subtok_" + "a".repeat(40),
                        "scptok_" + "b".repeat(40),
                        "c".repeat(64),
                        "ACCESS-INVALIDATION-TOKENIZATION-1.0.0"),
                new AccessInvalidationAuthorizationSnapshot(
                        AccessInvalidationAuthorizationState.INVALIDATED,
                        false,
                        true,
                        true,
                        false,
                        "RFP-1.0.0"),
                new AccessInvalidationRetention(
                        "restricted",
                        "RS-1.0.0",
                        NOW.plus(365, ChronoUnit.DAYS),
                        false),
                "d".repeat(64));
    }

    private static AccessInvalidationFact successorCause(
            AccessInvalidationFact previous) {
        return new AccessInvalidationFact(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000291"),
                previous.traceId(),
                AccessInvalidationChangeKind.CORRECTED,
                AccessInvalidationReason.SOURCE_CORRECTION,
                previous.lineageId(),
                previous.eventId(),
                null,
                AccessInvalidationAggregateType.IDENTITY_CAUSE,
                previous.aggregateId(),
                2,
                2,
                NOW.plusSeconds(2),
                previous.sourceVector(),
                previous.subjectSnapshot(),
                new AccessInvalidationAuthorizationSnapshot(
                        AccessInvalidationAuthorizationState.INVALIDATED,
                        true,
                        false,
                        false,
                        false,
                        "RFP-1.0.0"),
                previous.retention(),
                "e".repeat(64));
    }

    private static String eventDigest(AccessInvalidationFact fact) {
        var codec = new AccessInvalidationEventJsonCodec(
                new ObjectMapper());
        String payload = codec.encode(fact);
        return codec.validate(fact, payload).payloadDigest();
    }

    private static void assertResponsibilityV2CutoverAcl(
            JdbcTemplate database) {
        String producer = "scholarsense_identity_sync_worker";
        String reader = "scholarsense_identity_current_reader";
        String consumer =
                "scholarsense_identity_invalidation_consumer";
        String cutover = AccessInvalidationDatabaseRoleVerifier
                .RESPONSIBILITY_V2_CUTOVER_ROLE;

        assertTrue(hasPrivilege(
                database, producer, "ia_responsibility_current", "SELECT"));
        assertTrue(hasPrivilege(
                database, producer, "ia_responsibility_current", "INSERT"));
        assertTrue(hasPrivilege(
                database, producer, "ia_responsibility_current", "UPDATE"));
        assertFalse(hasPrivilege(
                database, producer, "ia_responsibility_current", "DELETE"));
        assertTrue(hasPrivilege(
                database, producer, "ia_identity_sync_checkpoint", "UPDATE"));
        assertFalse(hasPrivilege(
                database,
                producer,
                "ia_responsibility_v2_shadow_checkpoint",
                "UPDATE"));
        assertTrue(hasColumnPrivilege(
                database,
                producer,
                "ia_responsibility_v2_shadow_checkpoint",
                "source_version",
                "UPDATE"));
        assertTrue(hasColumnPrivilege(
                database,
                producer,
                "ia_responsibility_v2_shadow_checkpoint",
                "source_id",
                "INSERT"));
        assertFalse(hasColumnPrivilege(
                database,
                producer,
                "ia_responsibility_v2_shadow_checkpoint",
                "active",
                "INSERT"));
        for (String column : List.of(
                "reconciliation_status",
                "reconciliation_snapshot_id",
                "invalidation_materialized",
                "invalidation_materialized_count",
                "active",
                "reconciled_at",
                "activated_at")) {
            assertFalse(hasColumnPrivilege(
                    database,
                    producer,
                    "ia_responsibility_v2_shadow_checkpoint",
                    column,
                    "UPDATE"), column);
        }
        assertFalse(hasAnyColumnPrivilege(
                database,
                producer,
                "ia_responsibility_v2_reconciliation_snapshot",
                "SELECT"));
        assertFalse(hasAnyColumnPrivilege(
                database,
                producer,
                "ia_responsibility_v2_reconciliation_snapshot",
                "INSERT"));

        assertTrue(hasPrivilege(
                database, cutover, "ia_responsibility_current", "INSERT"));
        assertTrue(hasPrivilege(
                database, cutover, "ia_responsibility_current", "DELETE"));
        for (String column : List.of(
                "source_id",
                "feed_id",
                "partition_id",
                "consumer_projection")) {
            assertTrue(hasColumnPrivilege(
                    database,
                    cutover,
                    "ia_responsibility_current",
                    column,
                    "SELECT"), column);
        }
        for (String column : List.of(
                "relation_id",
                "relation_ref_token",
                "student_ref_token",
                "payload_digest")) {
            assertFalse(hasColumnPrivilege(
                    database,
                    cutover,
                    "ia_responsibility_current",
                    column,
                    "SELECT"), column);
        }
        assertFalse(hasAnyColumnPrivilege(
                database, cutover, "ia_responsibility_current", "UPDATE"));
        assertTrue(hasPrivilege(
                database,
                cutover,
                "ia_responsibility_v2_reconciliation_snapshot",
                "SELECT"));
        assertTrue(hasPrivilege(
                database,
                cutover,
                "ia_responsibility_v2_reconciliation_snapshot",
                "INSERT"));
        assertFalse(hasAnyColumnPrivilege(
                database,
                cutover,
                "ia_responsibility_v2_reconciliation_snapshot",
                "UPDATE"));
        assertTrue(hasPrivilege(
                database,
                cutover,
                "ia_responsibility_v2_source_fact",
                "SELECT"));
        assertFalse(hasAnyColumnPrivilege(
                database,
                cutover,
                "ia_responsibility_v2_source_fact",
                "INSERT"));
        assertTrue(hasColumnPrivilege(
                database,
                cutover,
                "ia_responsibility_v2_shadow_checkpoint",
                "reconciliation_status",
                "UPDATE"));
        assertTrue(hasColumnPrivilege(
                database,
                cutover,
                "ia_responsibility_v2_shadow_checkpoint",
                "active",
                "UPDATE"));
        for (String column : List.of(
                "source_version",
                "source_watermark",
                "aggregate_version",
                "last_successful_at",
                "replay_started_at_zero",
                "updated_at")) {
            assertFalse(hasColumnPrivilege(
                    database,
                    cutover,
                    "ia_responsibility_v2_shadow_checkpoint",
                    column,
                    "UPDATE"), column);
        }
        assertFalse(hasAnyColumnPrivilege(
                database,
                cutover,
                "ia_responsibility_v2_shadow_checkpoint",
                "INSERT"));

        assertTrue(hasPrivilege(
                database,
                cutover,
                "ia_responsibility_v2_cutover_command",
                "SELECT"));
        assertTrue(hasPrivilege(
                database,
                cutover,
                "ia_responsibility_v2_cutover_command",
                "INSERT"));
        for (String column : List.of(
                "status",
                "reason_code",
                "snapshot_id",
                "updated_at",
                "completed_at")) {
            assertTrue(hasColumnPrivilege(
                    database,
                    cutover,
                    "ia_responsibility_v2_cutover_command",
                    column,
                    "UPDATE"), column);
        }
        for (String column : List.of(
                "command_id",
                "source_id",
                "feed_id",
                "partition_id",
                "consumer_projection",
                "business_date",
                "operator_ref",
                "approval_ref",
                "profile_digest",
                "signature_digest",
                "requested_at",
                "trace_id")) {
            assertFalse(hasColumnPrivilege(
                    database,
                    cutover,
                    "ia_responsibility_v2_cutover_command",
                    column,
                    "UPDATE"), column);
        }
        assertFalse(hasPrivilege(
                database,
                cutover,
                "ia_responsibility_v2_cutover_command",
                "DELETE"));

        for (String outsider : List.of(producer, reader, consumer)) {
            for (String privilege : List.of(
                    "SELECT", "INSERT", "UPDATE")) {
                assertFalse(hasAnyColumnPrivilege(
                        database,
                        outsider,
                        "ia_responsibility_v2_cutover_command",
                        privilege), outsider + ":" + privilege);
            }
            assertFalse(hasPrivilege(
                    database,
                    outsider,
                    "ia_responsibility_v2_cutover_command",
                    "DELETE"), outsider);
        }
        assertFalse(hasAnyColumnPrivilege(
                database,
                reader,
                "ia_responsibility_v2_shadow_checkpoint",
                "SELECT"));
        assertFalse(hasAnyColumnPrivilege(
                database,
                reader,
                "ia_responsibility_v2_reconciliation_snapshot",
                "SELECT"));
        for (String table : List.of(
                "ia_responsibility_v2_cutover_command",
                "ia_responsibility_v2_shadow_checkpoint",
                "ia_responsibility_v2_reconciliation_snapshot")) {
            for (String privilege : List.of(
                    "SELECT",
                    "INSERT",
                    "UPDATE",
                    "DELETE",
                    "TRUNCATE",
                    "REFERENCES",
                    "TRIGGER",
                    "MAINTAIN")) {
                assertFalse(hasPublicPrivilege(
                        database, table, privilege),
                        table + ":PUBLIC:" + privilege);
            }
        }
        assertFalse(hasPublicPrivilege(
                database,
                "ia_responsibility_current",
                "DELETE"));
    }

    private long count(String table) {
        return jdbc.queryForObject(
                "select count(*) from identity_access." + table,
                Long.class);
    }

    private boolean hasPrivilege(
            String role, String table, String privilege) {
        return hasPrivilege(jdbc, role, table, privilege);
    }

    private static boolean hasPrivilege(
            JdbcTemplate database,
            String role,
            String table,
            String privilege) {
        return Boolean.TRUE.equals(database.queryForObject(
                "select has_table_privilege(?, ?, ?)",
                Boolean.class,
                role,
                "identity_access." + table,
                privilege));
    }

    private static boolean hasAnyColumnPrivilege(
            JdbcTemplate database,
            String role,
            String table,
            String privilege) {
        return Boolean.TRUE.equals(database.queryForObject(
                "select has_any_column_privilege(?, ?, ?)",
                Boolean.class,
                role,
                "identity_access." + table,
                privilege));
    }

    private static boolean hasColumnPrivilege(
            JdbcTemplate database,
            String role,
            String table,
            String column,
            String privilege) {
        return Boolean.TRUE.equals(database.queryForObject(
                "select has_column_privilege(?, ?, ?, ?)",
                Boolean.class,
                role,
                "identity_access." + table,
                column,
                privilege));
    }

    private static boolean hasPublicPrivilege(
            JdbcTemplate database,
            String table,
            String privilege) {
        return Boolean.TRUE.equals(database.queryForObject(
                """
                select exists (
                    select 1
                      from pg_catalog.pg_class relation
                      join pg_catalog.pg_namespace namespace
                        on namespace.oid=relation.relnamespace
                      cross join lateral pg_catalog.aclexplode(
                        coalesce(
                          relation.relacl,
                          pg_catalog.acldefault(
                            'r', relation.relowner))) access
                     where namespace.nspname='identity_access'
                       and relation.relname=?
                       and access.grantee=0
                       and access.privilege_type=?
                    union all
                    select 1
                      from pg_catalog.pg_class relation
                      join pg_catalog.pg_namespace namespace
                        on namespace.oid=relation.relnamespace
                      join pg_catalog.pg_attribute attribute
                        on attribute.attrelid=relation.oid
                       and attribute.attnum>0
                       and not attribute.attisdropped
                      cross join lateral pg_catalog.aclexplode(
                        attribute.attacl) access
                     where namespace.nspname='identity_access'
                       and relation.relname=?
                       and access.grantee=0
                       and access.privilege_type=?)
                """,
                Boolean.class,
                table,
                privilege,
                table,
                privilege));
    }

    private String propagation(UUID eventId) {
        return jdbc.queryForObject("""
                select propagation_status
                  from identity_access.ia_access_invalidation_propagation
                 where event_id=?
                """, String.class, eventId);
    }

    private void assertDenied(String sql) {
        SQLException denied = assertThrows(SQLException.class, () -> {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(
                        "set role scholarsense_identity_sync_worker");
                statement.execute(sql);
            }
        });
        assertTrue(
                List.of("42501", "2BP01").contains(denied.getSQLState()),
                denied::getMessage);
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
