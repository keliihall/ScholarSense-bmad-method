package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityEligibilityEventTransactionAdapter;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityEligibilityQueryStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityEligibilityReadAudit;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityRecoveryTaskQueryStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityRecoveryTaskReadAudit;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityEligibilitySnapshotLookupStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityTaskRelayWork;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizedValue;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotActorContext;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryTaskQueryCriteria;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityMutation;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityCursor;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityCursorStage;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityProcessingOutcome;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityQuarantine;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilitySnapshotEvidence;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseEligibilityEvidence;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseMemberEvidence;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseTaskPlan;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseWorkloadAuthorizationGuard;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchWorkloadAuthorizationEvidence;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchWorkloadAuthorizationPort;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchWorkloadAuthorizationRequest;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchWorkloadAuthorizationResult;
import cn.edu.suda.scholarsense.ingestionquality.application.RuleEligibilityDecision;
import cn.edu.suda.scholarsense.ingestionquality.application.UpstreamQualityEvent;
import cn.edu.suda.scholarsense.ingestionquality.application.UpstreamQualityEventKind;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchObservationWindow;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyQualityState;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyRequirement;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityDecision;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityReason;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityFuseEpisodeAction;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityFuseTaskAction;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityFuseTransition;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityOverallResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyMember;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleVersionIdentity;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL 18.4 atomicity, immutable-history and actual-login evidence for Story 2.4. */
class QualityEligibilityPersistencePostgreSqlIT {
    private static final String CONSUMER_ROLE =
            "scholarsense_ingestion_quality_eligibility_consumer";
    private static final String CONSUMER_LOGIN = "scholarsense_iq_eligibility_consumer_test_login";
    private static final String ONLINE_ROLE = "scholarsense_ingestion_quality_online";
    private static final String ONLINE_LOGIN = "scholarsense_iq_eligibility_online_test_login";
    private static final String TASK_RELAY_ROLE =
            "scholarsense_ingestion_quality_task_relay";
    private static final String TASK_RELAY_LOGIN =
            "scholarsense_iq_quality_task_relay_test_login";
    private static final String RETENTION_ROLE =
            "scholarsense_ingestion_quality_retention_executor";
    private static final String RETENTION_LOGIN =
            "scholarsense_iq_quality_fuse_retention_test_login";

    @Test
    void frozenRegistryAndOwnerObjectsAreCompleteAndIndexed() {
        JdbcTemplate jdbc = admin();
        assertEquals(5, jdbc.queryForObject(
                "select count(*) from ingestion_quality.iq_rule_dependency_rule", Integer.class));
        assertEquals(11, jdbc.queryForObject("""
                select count(distinct dependency_id)
                  from ingestion_quality.iq_rule_dependency_member
                """, Integer.class));
        assertEquals(24, jdbc.queryForObject(
                "select count(*) from ingestion_quality.iq_rule_dependency_member", Integer.class));
        for (String index : List.of(
                "iq_rule_dependency_member_source_idx",
                "iq_dependency_quality_lineage_idx",
                "iq_quality_eligibility_current_page_idx",
                "iq_quality_eligibility_current_status_page_idx",
                "iq_quality_eligibility_member_owner_idx",
                "iq_quality_eligibility_outbox_due_idx")) {
            assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from pg_catalog.pg_indexes
                     where schemaname='ingestion_quality' and indexname=?)
                    """, Boolean.class, index)), index);
        }
        String mixedTransitions = """
                [{"ruleId":"AAA-BOOTSTRAP","ruleVersion":"1.0.0",
                  "reasonCode":"BOOTSTRAP_FUSED",
                  "evaluatedReasonCode":"REQUIRED_MEMBER_FUSED"},
                 {"ruleId":"ZZZ-EXISTING","ruleVersion":"1.0.0",
                  "reasonCode":"THRESHOLD_UNSATISFIED",
                  "evaluatedReasonCode":"THRESHOLD_UNSATISFIED"}]
                """;
        assertEquals("THRESHOLD_UNSATISFIED", jdbc.queryForObject("""
                select ingestion_quality.iq_quality_trigger_reason(?::jsonb)
                """, String.class, mixedTransitions));
        assertThrows(RuntimeException.class, () -> jdbc.queryForObject("""
                select ingestion_quality.iq_quality_trigger_reason(?::jsonb)
                """, String.class, """
                [{"ruleId":"AAA-BOOTSTRAP","ruleVersion":"1.0.0",
                  "reasonCode":"BOOTSTRAP_FUSED",
                  "evaluatedReasonCode":"REQUIRED_MEMBER_FUSED"}]
                """));
    }

    @Test
    void overlappingSourcesSerializeOnAffectedRuleBeforeStateIsRead() throws Exception {
        JdbcTemplate admin = admin();
        createLogin(admin);
        CountDownLatch firstLoaded = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                DriverManagerDataSource source = login(CONSUMER_LOGIN);
                return new TransactionTemplate(
                        new DataSourceTransactionManager(source)).execute(status -> {
                            String state = new JdbcTemplate(source).queryForObject("""
                                    select ingestion_quality.iq_load_quality_eligibility_processing_state(
                                      ?::uuid,?)::text
                                    """, String.class,
                                    "019fe8a0-0000-7000-8000-000000000111",
                                    "SRC-P0-CAMPUS-ACCESS-001");
                            firstLoaded.countDown();
                            try {
                                if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException(
                                            "first rule lock was not released");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                            return state;
                        });
            });
            assertTrue(firstLoaded.await(5, TimeUnit.SECONDS));

            var overlapping = executor.submit(() -> {
                DriverManagerDataSource source = login(CONSUMER_LOGIN);
                return new TransactionTemplate(
                        new DataSourceTransactionManager(source)).execute(status ->
                                new JdbcTemplate(source).queryForObject("""
                                        select ingestion_quality.iq_load_quality_eligibility_processing_state(
                                          ?::uuid,?)::text
                                        """, String.class,
                                        "019fe8a0-0000-7000-8000-000000000112",
                                        "SRC-P0-DORM-ACCESS-001"));
            });
            assertThrows(TimeoutException.class,
                    () -> overlapping.get(250, TimeUnit.MILLISECONDS),
                    "the second source must wait on their shared ACC-SAFE-001 RuleVersion");

            releaseFirst.countDown();
            assertNotNull(first.get(5, TimeUnit.SECONDS));
            assertNotNull(overlapping.get(5, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            dropLogin(admin);
        }
    }

    @Test
    void consumerActualLoginCanOnlyUseClosedFunctionsAndPoisonIsAtomic() {
        JdbcTemplate admin = admin();
        createLogin(admin);
        try {
            DriverManagerDataSource source = login(CONSUMER_LOGIN);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            assertFalse(Boolean.TRUE.equals(admin.queryForObject("""
                    select has_table_privilege(?,
                      'ingestion_quality.iq_quality_event_inbox','INSERT')
                    """, Boolean.class, CONSUMER_LOGIN)));
            assertTrue(Boolean.TRUE.equals(admin.queryForObject("""
                    select has_function_privilege(?, procedure.oid, 'EXECUTE')
                      from pg_catalog.pg_proc procedure
                      join pg_catalog.pg_namespace namespace
                        on namespace.oid=procedure.pronamespace
                     where namespace.nspname='ingestion_quality'
                       and procedure.proname='iq_accept_quality_eligibility_event'
                    """, Boolean.class, CONSUMER_LOGIN)));

            UpstreamQualityEvent event = event(
                    "019fe8a0-0000-7000-8000-000000000101");
            JdbcQualityEligibilitySnapshotLookupStore lookup =
                    new JdbcQualityEligibilitySnapshotLookupStore(jdbc);
            assertTrue(lookup.findExact(
                    event.batchId(), event.snapshotId(), event.snapshotImmutableHash()).isEmpty());
            var adapter = eligibilityAdapter(jdbc, source);
            QualityEligibilityMutation poisoned = adapter.transact(event, ignored ->
                    new QualityEligibilityMutation(
                            QualityEligibilityProcessingOutcome.POISONED,
                            null, null, null, List.of(), null,
                            new QualityEligibilityQuarantine(
                                    event.eventId(), event.sourceId(),
                                    "INGESTION_QUALITY_UPSTREAM_BINDING_INVALID",
                                    event.payloadDigest()),
                            null));
            assertEquals(QualityEligibilityProcessingOutcome.POISONED, poisoned.outcome());
            adapter.transact(event, state -> QualityEligibilityMutation.noChange(
                    QualityEligibilityProcessingOutcome.DUPLICATE, state));

            assertEquals(1, admin.queryForObject("""
                    select count(*) from ingestion_quality.iq_quality_event_inbox
                     where event_id=?
                    """, Integer.class, event.eventId()));
            assertEquals(1, admin.queryForObject("""
                    select count(*) from ingestion_quality.iq_quality_event_quarantine
                     where event_id=?
                    """, Integer.class, event.eventId()));

            UpstreamQualityEvent invalid = event(
                    "019fe8a0-0000-7000-8000-000000000102");
            assertThrows(RuntimeException.class, () -> adapter.transact(invalid, ignored ->
                    new QualityEligibilityMutation(
                            QualityEligibilityProcessingOutcome.APPLIED,
                            null, null, null, List.of(), null, null, null)));
            assertEquals(0, admin.queryForObject("""
                    select count(*) from ingestion_quality.iq_quality_event_inbox
                     where event_id=?
                    """, Integer.class, invalid.eventId()),
                    "a late mutation failure must roll back the inbox claim");
        } finally {
            dropLogin(admin);
        }
    }

    @Test
    void historyRejectsRawUpdateAndDeleteEvenForMigrationPrincipal() {
        JdbcTemplate jdbc = admin();
        UUID eligibilityId = UUID.fromString("019fe8a0-0000-7000-8000-000000000301");
        jdbc.update("""
                insert into ingestion_quality.iq_quality_eligibility_history(
                  eligibility_id,rule_id,rule_version,registry_version,registry_digest,
                  catalog_version,catalog_digest,rule_catalog_version,rule_catalog_digest,
                  aggregate_version,status,reason_code,composition_operator,threshold,
                  effective_at,occurred_at,trace_id,producer,retention_due_at,legal_hold)
                values (?,'ACADEMIC-001','1.0.0','RULE-DEPENDENCY-REGISTRY-1.0.0',?,
                  'DCC-1.1.0',?,'RC-1.0.0',?,1,'fused','REQUIRED_MEMBER_FUSED',
                  'all-of',null,?::timestamptz,?::timestamptz,?,
                  'ingestion-quality',?::timestamptz,false)
                """, eligibilityId, "sha256:" + "1".repeat(64),
                "sha256:aeb19962071e2144a85bb2e07fd124eff0d69edf93f7202e821204616a60f219",
                "sha256:d4f2587e0bde7a37964bab7ce990560327e2e5960f68fba5b770f09b041fd21a",
                "2026-08-10T00:00:00Z", "2026-08-10T00:01:00Z",
                "11111111111111111111111111111111", "2028-08-10T00:01:00Z");
        String update = "update ingestion_quality.iq_quality_eligibility_history "
                + "set status='eligible' where eligibility_id=?";
        String delete = "delete from ingestion_quality.iq_quality_eligibility_history "
                + "where eligibility_id=?";
        assertThrows(RuntimeException.class, () -> jdbc.update(update, eligibilityId));
        assertThrows(RuntimeException.class, () -> jdbc.update(delete, eligibilityId));
    }

    @Test
    void appliedEventAtomicallyCreatesDependencyEligibilityAuditAndOutbox() {
        JdbcTemplate admin = admin();
        UpstreamQualityEvent event = event(
                "019fe8a0-0000-7000-8000-000000000103")
                .withBatchId(UUID.fromString(
                        "019fe8a0-0000-7000-8000-000000000204"));
        seedFailedSnapshot(admin, event);
        createLogin(admin);
        createOnlineLogin(admin);
        try {
            DriverManagerDataSource source = login(CONSUMER_LOGIN);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            var lookup = new JdbcQualityEligibilitySnapshotLookupStore(jdbc);
            var snapshot = lookup.findExact(
                    event.batchId(), event.snapshotId(), event.snapshotImmutableHash()).orElseThrow();
            var adapter = eligibilityAdapter(jdbc, source);
            RuleDependencyDefinition rule = new RuleDependencyDefinition(
                    new RuleVersionIdentity("ACC-SAFE-001", "1.0.0"),
                    DependencyOperator.ALL_OF, null,
                    List.of(new RuleDependencyMember(
                            event.sourceId(), event.sourceSchemaVersion(),
                            "DEP-P0-CAMPUS-ACCESS-001", "1.0.0",
                            DependencyRequirement.REQUIRED, "primary")));
            QualityEligibilityMutation result = adapter.transact(event, ignored ->
                    new QualityEligibilityMutation(
                            QualityEligibilityProcessingOutcome.APPLIED,
                            new QualityEligibilityCursor(
                                    event.sourceId(), "DEP-P0-CAMPUS-ACCESS-001", 1,
                                    event.lineageId(), 0, event.batchId(),
                                    QualityEligibilityCursorStage.TERMINAL,
                                    false, 1),
                            null,
                            new DependencyQualityState(
                                    event.sourceId(), 1, event.lineageId(), 0,
                                    "DEP-P0-CAMPUS-ACCESS-001", 1,
                                    QualityEligibilityStatus.FUSED, true, event.watermark()),
                            List.of(new RuleEligibilityDecision(
                                    rule, new QualityEligibilityDecision(
                                            QualityEligibilityStatus.FUSED,
                                            QualityEligibilityReason.REQUIRED_MEMBER_FUSED,
                                            List.of("DEP-P0-CAMPUS-ACCESS-001")))),
                            null, null, snapshot));

            assertEquals(QualityEligibilityProcessingOutcome.APPLIED, result.outcome());
            assertEquals(1, count(admin, "iq_dependency_quality_current"));
            assertEquals(1, count(admin, "iq_quality_eligibility_current"));
            assertEquals(1, count(admin, "iq_quality_eligibility_history"));
            assertEquals(7, count(admin, "iq_quality_eligibility_member_history"));
            assertEquals(1, count(admin, "iq_quality_eligibility_outbox"));
            assertEquals(1, admin.queryForObject("""
                    select count(*) from ingestion_quality.iq_quality_eligibility_audit
                     where action='quality-eligibility-derived'
                    """, Integer.class));

            JdbcTemplate online = new JdbcTemplate(login(ONLINE_LOGIN));
            UUID eligibilityId = admin.queryForObject("""
                    select eligibility_id
                      from ingestion_quality.iq_quality_eligibility_current
                     where rule_id='ACC-SAFE-001'
                    """, UUID.class);
            var queried = new JdbcQualityEligibilityQueryStore(online)
                    .findCurrentById(eligibilityId).orElseThrow();
            assertEquals(7, queried.members().size());
            assertEquals(event.traceId(), queried.traceId());

            Instant auditAt = Instant.parse("2026-08-10T00:02:00Z");
            var profile = new TimeSourceProfile(
                    "test-clock", "AUDIT-CLOCK-BINDING-1.0.0", 0,
                    auditAt.minusSeconds(1), auditAt.plusSeconds(60),
                    "evidence://signed/story-2.4-read-audit");
            var audit = new JdbcQualityEligibilityReadAudit(
                    online, new ObjectMapper(), () -> new TrustedTime(auditAt, profile),
                    new TransactionTemplate(new DataSourceTransactionManager(
                            online.getDataSource())),
                    (domain, ignored) -> new AuditTokenizedValue(
                            domain.prefix() + "_v1_k7_" + "a".repeat(64),
                            "AUDIT-TOKENIZATION-1.0.0", "k7"));
            audit.record(List.of(queried),
                    new QualitySnapshotActorContext("session-ref", "actor-ref", "127.0.0.1"),
                    "quality-eligibility-detail-read", event.traceId());
            assertEquals(1, admin.queryForObject("""
                    select count(*) from ingestion_quality.iq_quality_eligibility_audit
                     where eligibility_id=? and action='quality-eligibility-detail-read'
                       and actor_search_token like 'ast_v1_k7_%'
                       and source_ip_search_token like 'ipt_v1_k7_%'
                    """, Integer.class, eligibilityId));
        } finally {
            dropLogin(admin);
            dropOnlineLogin(admin);
        }
    }

    @Test
    void verifiedFailureAtomicallyCreatesOneFuseTaskAndLateDeliveryConflictRollsBack() {
        JdbcTemplate admin = admin();
        resetFuseState(admin);
        UpstreamQualityEvent firstEvent = event(
                "019fe8a0-0000-7000-8000-000000000121");
        seedFailedSnapshot(admin, firstEvent);
        seedEligibleCurrent(admin);
        createLogin(admin);
        try {
            DriverManagerDataSource source = login(CONSUMER_LOGIN);
            JdbcTemplate consumerJdbc = new JdbcTemplate(source);
            var adapter = eligibilityAdapter(consumerJdbc, source);
            QualityEligibilitySnapshotEvidence snapshot =
                    new JdbcQualityEligibilitySnapshotLookupStore(consumerJdbc)
                            .findExact(firstEvent.batchId(), firstEvent.snapshotId(),
                                    firstEvent.snapshotImmutableHash())
                            .orElseThrow();
            RuleDependencyDefinition rule = fuseRule(firstEvent);
            QualityEligibilityMutation first = fuseMutation(
                    firstEvent, snapshot, rule, QualityFuseTaskAction.CREATE,
                    QualityEligibilityStatus.ELIGIBLE,
                    UUID.fromString("019fe8a0-0000-7000-8000-000000000401"),
                    UUID.fromString("019fe8a0-0000-7000-8000-000000000402"),
                    0, "fuse:campus-access:g1");

            QualityEligibilityMutation persisted = adapter.transact(
                    firstEvent, ignored -> first);
            QualityEligibilityMutation replay = adapter.transact(
                    firstEvent, state -> QualityEligibilityMutation.noChange(
                            QualityEligibilityProcessingOutcome.DUPLICATE, state));

            assertEquals(first.fuseTaskPlan().episodeId(),
                    persisted.fuseTaskPlan().episodeId());
            assertEquals(first.fuseTaskPlan().recoveryTaskId(),
                    replay.fuseTaskPlan().recoveryTaskId());
            assertEquals(1, count(admin, "iq_quality_fuse_episode_history"));
            assertEquals(1, count(admin, "iq_quality_recovery_task_history"));
            assertEquals(1, count(admin, "iq_quality_fuse_audit"));
            assertEquals(1, count(admin, "iq_quality_task_outbox"));
            assertEquals("pending", admin.queryForObject("""
                    select status from ingestion_quality.iq_quality_task_delivery
                     where task_id=?
                    """, String.class, first.fuseTaskPlan().recoveryTaskId()));
            assertEquals("fused", admin.queryForObject("""
                    select status from ingestion_quality.iq_quality_eligibility_current
                     where rule_id='ACC-SAFE-001'
                    """, String.class));

            admin.update("""
                    update ingestion_quality.iq_quality_task_delivery
                       set route_sequence=9 where task_id=?
                    """, first.fuseTaskPlan().recoveryTaskId());
            UpstreamQualityEvent secondEvent = event(
                    "019fe8a0-0000-7000-8000-000000000122", 2);
            QualityEligibilityMutation update = fuseMutation(
                    secondEvent, snapshot, rule, QualityFuseTaskAction.UPDATE,
                    QualityEligibilityStatus.FUSED,
                    first.fuseTaskPlan().episodeId(), first.fuseTaskPlan().recoveryTaskId(),
                    1, "fuse:campus-access:g1:update");

            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> adapter.transact(secondEvent, ignored -> update));
            assertTrue(failure.getMessage().contains(
                    "INGESTION_QUALITY_TASK_ROUTE_SEQUENCE_CONFLICT"),
                    failure.getMessage());
            assertEquals(1, count(admin, "iq_quality_fuse_episode_history"));
            assertEquals(1, count(admin, "iq_quality_recovery_task_history"));
            assertEquals(0, admin.queryForObject("""
                    select count(*) from ingestion_quality.iq_quality_event_inbox
                     where event_id=?
                    """, Integer.class, secondEvent.eventId()));
            assertEquals(1L, admin.queryForObject("""
                    select source_version
                      from ingestion_quality.iq_quality_dependency_cursor
                     where source_id=?
                    """, Long.class, firstEvent.sourceId()));
        } finally {
            dropLogin(admin);
            resetFuseState(admin);
        }
    }

    @Test
    void recoveringPassPreservesTheActiveEpisodeWithoutCreatingAnotherTaskVersion() {
        JdbcTemplate admin = admin();
        resetFuseState(admin);
        UpstreamQualityEvent firstEvent = event(
                "019fe8a0-0000-7000-8000-000000000123");
        seedFailedSnapshot(admin, firstEvent);
        seedEligibleCurrent(admin);
        createLogin(admin);
        try {
            DriverManagerDataSource source = login(CONSUMER_LOGIN);
            JdbcTemplate consumerJdbc = new JdbcTemplate(source);
            var adapter = eligibilityAdapter(consumerJdbc, source);
            QualityEligibilitySnapshotEvidence snapshot =
                    new JdbcQualityEligibilitySnapshotLookupStore(consumerJdbc)
                            .findExact(firstEvent.batchId(), firstEvent.snapshotId(),
                                    firstEvent.snapshotImmutableHash())
                            .orElseThrow();
            RuleDependencyDefinition rule = fuseRule(firstEvent);
            QualityEligibilityMutation created = fuseMutation(
                    firstEvent, snapshot, rule, QualityFuseTaskAction.CREATE,
                    QualityEligibilityStatus.ELIGIBLE,
                    UUID.fromString("019fe8a0-0000-7000-8000-000000000403"),
                    UUID.fromString("019fe8a0-0000-7000-8000-000000000404"),
                    0, "fuse:campus-access:recovering:g1");
            adapter.transact(firstEvent, ignored -> created);

            UpstreamQualityEvent recoveryEvent = event(
                    "019fe8a0-0000-7000-8000-000000000124", 2);
            adapter.transact(recoveryEvent,
                    ignored -> recoveringMutation(recoveryEvent, snapshot, rule));
            UpstreamQualityEvent passEvent = event(
                    "019fe8a0-0000-7000-8000-000000000125", 3);
            QualityEligibilityMutation preserved = recoveringPassMutation(
                    passEvent, snapshot, rule, created.fuseTaskPlan().episodeId(),
                    created.fuseTaskPlan().recoveryTaskId());

            QualityEligibilityMutation accepted = adapter.transact(
                    passEvent, ignored -> preserved);

            assertEquals(QualityFuseTaskAction.PRESERVE,
                    accepted.fuseTaskPlan().action());
            assertEquals("recovering", admin.queryForObject("""
                    select status from ingestion_quality.iq_quality_eligibility_current
                     where rule_id='ACC-SAFE-001'
                    """, String.class));
            assertEquals(1, count(admin, "iq_quality_fuse_episode_history"));
            assertEquals(1, count(admin, "iq_quality_recovery_task_history"));
            assertEquals(1, count(admin, "iq_quality_task_outbox"));
        } finally {
            dropLogin(admin);
            resetFuseState(admin);
        }
    }

    @Test
    void taskRelayReclaimsCrashFencesLateReceiptAndKeepsBusinessStateOrthogonal() {
        JdbcTemplate admin = admin();
        resetFuseState(admin);
        UpstreamQualityEvent event = event(
                "019fe8a0-0000-7000-8000-000000000131");
        seedFailedSnapshot(admin, event);
        seedEligibleCurrent(admin);
        createLogin(admin);
        createTaskRelayLogin(admin);
        try {
            DriverManagerDataSource consumerSource = login(CONSUMER_LOGIN);
            JdbcTemplate consumerJdbc = new JdbcTemplate(consumerSource);
            QualityEligibilitySnapshotEvidence snapshot =
                    new JdbcQualityEligibilitySnapshotLookupStore(consumerJdbc)
                            .findExact(event.batchId(), event.snapshotId(),
                                    event.snapshotImmutableHash())
                            .orElseThrow();
            QualityEligibilityMutation mutation = fuseMutation(
                    event, snapshot, fuseRule(event), QualityFuseTaskAction.CREATE,
                    QualityEligibilityStatus.ELIGIBLE,
                    UUID.fromString("019fe8a0-0000-7000-8000-000000000411"),
                    UUID.fromString("019fe8a0-0000-7000-8000-000000000412"),
                    0, "fuse:campus-access:relay:g1");
            eligibilityAdapter(consumerJdbc, consumerSource)
                    .transact(event, ignored -> mutation);

            JdbcQualityTaskRelayWork relay = new JdbcQualityTaskRelayWork(
                    new JdbcTemplate(login(TASK_RELAY_LOGIN)), new ObjectMapper());
            var crashed = relay.claimNext();
            assertEquals(1, crashed.attempt());
            admin.update("""
                    update ingestion_quality.iq_quality_task_outbox
                       set claimed_at=statement_timestamp()-interval '31 seconds'
                     where event_id=?
                    """, crashed.eventId());
            var reclaimed = relay.claimNext();
            assertEquals(2, reclaimed.attempt());
            try (var stalePermit = relay.acquireSendPermit(crashed)) {
                assertFalse(stalePermit.authorized());
            }
            try (var retryPermit = relay.acquireSendPermit(reclaimed)) {
                assertTrue(retryPermit.authorized());
                assertTrue(retryPermit.retry("QUALITY_TASK_TARGET_TIMEOUT"));
                assertTrue(retryPermit.retry("QUALITY_TASK_TARGET_TIMEOUT"));
            }
            assertEquals(null, relay.claimNext());

            admin.update("""
                    update ingestion_quality.iq_quality_task_outbox
                       set available_at=statement_timestamp() where event_id=?
                    """, reclaimed.eventId());
            var finalClaim = relay.claimNext();
            assertEquals(3, finalClaim.attempt());
            try (var finalPermit = relay.acquireSendPermit(finalClaim)) {
                assertTrue(finalPermit.authorized());
                assertTrue(finalPermit.confirm("receipt-final"));
                assertTrue(finalPermit.confirm("receipt-final"));
                assertFalse(finalPermit.fail("QUALITY_TASK_TARGET_REQUEST_REJECTED"));
            }

            assertEquals("confirmed", admin.queryForObject("""
                    select status from ingestion_quality.iq_quality_task_delivery
                     where task_id=?
                    """, String.class, mutation.fuseTaskPlan().recoveryTaskId()));
            assertEquals("open", admin.queryForObject("""
                    select status from ingestion_quality.iq_quality_recovery_task_current
                     where task_id=?
                    """, String.class, mutation.fuseTaskPlan().recoveryTaskId()));
            assertEquals("fused", admin.queryForObject("""
                    select status from ingestion_quality.iq_quality_eligibility_current
                     where rule_id='ACC-SAFE-001'
                    """, String.class));

            admin.update("""
                    update ingestion_quality.iq_quality_task_outbox
                       set payload_utf8=convert_to(repeat('a',65536),'UTF8'),
                           payload_digest=encode(sha256(
                             convert_to(repeat('a',65536),'UTF8')),'hex')
                     where event_id=?
                    """, finalClaim.eventId());
            assertEquals(65_536, admin.queryForObject("""
                    select octet_length(payload_utf8)
                      from ingestion_quality.iq_quality_task_outbox where event_id=?
                    """, Integer.class, finalClaim.eventId()));
            assertThrows(RuntimeException.class, () -> admin.update("""
                    update ingestion_quality.iq_quality_task_outbox
                       set payload_utf8=convert_to(repeat('a',65537),'UTF8')
                     where event_id=?
                    """, finalClaim.eventId()));

            admin.update("""
                    update ingestion_quality.iq_quality_task_outbox
                       set status='pending',attempts=8,available_at=statement_timestamp(),
                           claimed_at=null,last_error_code=null where event_id=?;
                    update ingestion_quality.iq_quality_task_delivery
                       set status='retrying',receipt_id=null,next_attempt_at=statement_timestamp(),
                           lease_owner=null,lease_expires_at=null where task_id=?
                    """, finalClaim.eventId(), finalClaim.taskId());
            assertEquals(null, relay.claimNext());
            assertEquals("failed", admin.queryForObject("""
                    select status from ingestion_quality.iq_quality_task_delivery
                     where task_id=?
                    """, String.class, finalClaim.taskId()));
            assertEquals("QUALITY_TASK_RELAY_ATTEMPTS_EXHAUSTED",
                    admin.queryForObject("""
                            select last_error_code
                              from ingestion_quality.iq_quality_task_delivery
                             where task_id=?
                            """, String.class, finalClaim.taskId()));
            assertEquals("open", admin.queryForObject("""
                    select status from ingestion_quality.iq_quality_recovery_task_current
                     where task_id=?
                    """, String.class, finalClaim.taskId()));
        } finally {
            dropTaskRelayLogin(admin);
            dropLogin(admin);
            resetFuseState(admin);
        }
    }

    @Test
    void newerRouteCannotCommitBetweenSendAuthorizationAndFinalization() throws Exception {
        JdbcTemplate admin = admin();
        resetFuseState(admin);
        UpstreamQualityEvent firstEvent = event(
                "019fe8a0-0000-7000-8000-000000000132");
        seedFailedSnapshot(admin, firstEvent);
        seedEligibleCurrent(admin);
        createLogin(admin);
        createTaskRelayLogin(admin);
        try {
            DriverManagerDataSource consumerSource = login(CONSUMER_LOGIN);
            JdbcTemplate consumerJdbc = new JdbcTemplate(consumerSource);
            var adapter = eligibilityAdapter(consumerJdbc, consumerSource);
            QualityEligibilitySnapshotEvidence snapshot =
                    new JdbcQualityEligibilitySnapshotLookupStore(consumerJdbc)
                            .findExact(firstEvent.batchId(), firstEvent.snapshotId(),
                                    firstEvent.snapshotImmutableHash())
                            .orElseThrow();
            RuleDependencyDefinition rule = fuseRule(firstEvent);
            QualityEligibilityMutation first = fuseMutation(
                    firstEvent, snapshot, rule, QualityFuseTaskAction.CREATE,
                    QualityEligibilityStatus.ELIGIBLE,
                    UUID.fromString("019fe8a0-0000-7000-8000-000000000413"),
                    UUID.fromString("019fe8a0-0000-7000-8000-000000000414"),
                    0, "fuse:campus-access:route:g1");
            adapter.transact(firstEvent, ignored -> first);

            JdbcQualityTaskRelayWork relay = new JdbcQualityTaskRelayWork(
                    new JdbcTemplate(login(TASK_RELAY_LOGIN)), new ObjectMapper());
            var oldClaim = relay.claimNext();
            assertEquals(1L, oldClaim.routeSequence());
            UpstreamQualityEvent updateEvent = event(
                    "019fe8a0-0000-7000-8000-000000000133", 2);
            QualityEligibilityMutation update = fuseMutation(
                    updateEvent, snapshot, rule, QualityFuseTaskAction.UPDATE,
                    QualityEligibilityStatus.FUSED, first.fuseTaskPlan().episodeId(),
                    first.fuseTaskPlan().recoveryTaskId(), 1,
                    "fuse:campus-access:route:g1:update");
            try (var executor = Executors.newSingleThreadExecutor();
                    var oldPermit = relay.acquireSendPermit(oldClaim)) {
                assertTrue(oldPermit.authorized());
                var updateFuture = executor.submit(
                        () -> adapter.transact(updateEvent, ignored -> update));
                assertThrows(TimeoutException.class,
                        () -> updateFuture.get(250, TimeUnit.MILLISECONDS),
                        "a new route must wait until the old external send is finalized");
                assertTrue(oldPermit.retry("QUALITY_TASK_TARGET_TIMEOUT"));
                oldPermit.close();
                assertNotNull(updateFuture.get(5, TimeUnit.SECONDS));
            }

            try (var stalePermit = relay.acquireSendPermit(oldClaim)) {
                assertFalse(stalePermit.authorized());
            }
            admin.update("""
                    update ingestion_quality.iq_quality_task_outbox
                       set available_at=statement_timestamp()-interval '1 day'
                     where event_id=?
                    """, oldClaim.eventId());
            var currentClaim = relay.claimNext();
            assertEquals(2L, currentClaim.routeSequence());
            assertEquals("QUALITY_TASK_ROUTE_SUPERSEDED", admin.queryForObject("""
                    select last_error_code from ingestion_quality.iq_quality_task_outbox
                     where event_id=?
                    """, String.class, oldClaim.eventId()));
            try (var currentPermit = relay.acquireSendPermit(currentClaim)) {
                assertTrue(currentPermit.authorized());
            }
        } finally {
            dropTaskRelayLogin(admin);
            dropLogin(admin);
            resetFuseState(admin);
        }
    }

    @Test
    void onlineTaskQueryHydratesOwnedProjectionAndCommitsAppendOnlyReadAudit() {
        JdbcTemplate admin = admin();
        resetFuseState(admin);
        UpstreamQualityEvent event = event(
                "019fe8a0-0000-7000-8000-000000000141");
        seedFailedSnapshot(admin, event);
        seedEligibleCurrent(admin);
        createLogin(admin);
        createOnlineLogin(admin);
        try {
            DriverManagerDataSource consumerSource = login(CONSUMER_LOGIN);
            JdbcTemplate consumerJdbc = new JdbcTemplate(consumerSource);
            QualityEligibilitySnapshotEvidence snapshot =
                    new JdbcQualityEligibilitySnapshotLookupStore(consumerJdbc)
                            .findExact(event.batchId(), event.snapshotId(),
                                    event.snapshotImmutableHash())
                            .orElseThrow();
            QualityEligibilityMutation mutation = fuseMutation(
                    event, snapshot, fuseRule(event), QualityFuseTaskAction.CREATE,
                    QualityEligibilityStatus.ELIGIBLE,
                    UUID.fromString("019fe8a0-0000-7000-8000-000000000421"),
                    UUID.fromString("019fe8a0-0000-7000-8000-000000000422"),
                    0, "fuse:campus-access:query:g1");
            eligibilityAdapter(consumerJdbc, consumerSource)
                    .transact(event, ignored -> mutation);

            DriverManagerDataSource onlineSource = login(ONLINE_LOGIN);
            JdbcTemplate online = new JdbcTemplate(onlineSource);
            assertFalse(Boolean.TRUE.equals(admin.queryForObject("""
                    select has_table_privilege(?,
                      'ingestion_quality.iq_quality_recovery_task_current','SELECT')
                    """, Boolean.class, ONLINE_LOGIN)));
            var store = new JdbcQualityRecoveryTaskQueryStore(online, new ObjectMapper());
            var listed = store.findCurrent(new QualityRecoveryTaskQueryCriteria(
                    event.sourceId(), "open", null, null, 21));
            assertEquals(1, listed.size());
            var task = listed.getFirst();
            assertEquals(mutation.fuseTaskPlan().recoveryTaskId(), task.taskId());
            assertEquals(List.of(new RuleVersionIdentity("ACC-SAFE-001", "1.0.0")),
                    task.affectedRules());
            assertEquals("pending", task.taskDelivery().status());
            assertEquals(1L, task.taskDelivery().routeSequence());
            assertEquals(event.traceId(), task.traceId());

            Instant auditAt = Instant.parse("2026-08-10T00:02:00Z");
            var profile = new TimeSourceProfile(
                    "test-clock", "AUDIT-CLOCK-BINDING-1.0.0", 0,
                    auditAt.minusSeconds(1), auditAt.plusSeconds(60),
                    "evidence://signed/story-2.5a-task-read-audit");
            var audit = new JdbcQualityRecoveryTaskReadAudit(
                    online, new ObjectMapper(), () -> new TrustedTime(auditAt, profile),
                    new TransactionTemplate(new DataSourceTransactionManager(onlineSource)),
                    (domain, ignored) -> new AuditTokenizedValue(
                            domain.prefix() + "_v1_k7_" + "a".repeat(64),
                            "AUDIT-TOKENIZATION-1.0.0", "k7"));
            audit.record(List.of(task),
                    new QualitySnapshotActorContext("session-ref", "actor-ref", "127.0.0.1"),
                    "quality-recovery-task-detail-read", event.traceId());
            assertEquals(1, admin.queryForObject("""
                    select count(*)
                      from ingestion_quality.iq_quality_recovery_task_read_audit
                     where task_id=? and aggregate_version=1
                       and action='quality-recovery-task-detail-read'
                       and actor_search_token like 'ast_v1_k7_%'
                       and source_ip_search_token like 'ipt_v1_k7_%'
                    """, Integer.class, task.taskId()));
            assertThrows(RuntimeException.class, () -> admin.update("""
                    update ingestion_quality.iq_quality_recovery_task_read_audit
                       set outcome='accepted' where task_id=?
                    """, task.taskId()));
        } finally {
            dropOnlineLogin(admin);
            dropLogin(admin);
            resetFuseState(admin);
        }
    }

    @Test
    void fuseRetentionHonorsP90LegalHoldAndKeepsActiveBusinessFacts() {
        JdbcTemplate admin = admin();
        resetFuseState(admin);
        UpstreamQualityEvent event = event(
                "019fe8a0-0000-7000-8000-000000000151");
        seedFailedSnapshot(admin, event);
        seedEligibleCurrent(admin);
        createLogin(admin);
        createRetentionLogin(admin);
        try {
            DriverManagerDataSource consumerSource = login(CONSUMER_LOGIN);
            JdbcTemplate consumerJdbc = new JdbcTemplate(consumerSource);
            QualityEligibilitySnapshotEvidence snapshot =
                    new JdbcQualityEligibilitySnapshotLookupStore(consumerJdbc)
                            .findExact(event.batchId(), event.snapshotId(),
                                    event.snapshotImmutableHash())
                            .orElseThrow();
            QualityEligibilityMutation mutation = fuseMutation(
                    event, snapshot, fuseRule(event), QualityFuseTaskAction.CREATE,
                    QualityEligibilityStatus.ELIGIBLE,
                    UUID.fromString("019fe8a0-0000-7000-8000-000000000431"),
                    UUID.fromString("019fe8a0-0000-7000-8000-000000000432"),
                    0, "fuse:campus-access:retention:g1");
            eligibilityAdapter(consumerJdbc, consumerSource)
                    .transact(event, ignored -> mutation);

            admin.update("""
                    update ingestion_quality.iq_quality_fuse_idempotency
                       set created_at=statement_timestamp()-interval '91 days',
                           expires_at=statement_timestamp()-interval '1 day',legal_hold=true
                    """);
            admin.update("""
                    update ingestion_quality.iq_quality_task_outbox
                       set status='delivered',claimed_at=null,
                           created_at=statement_timestamp()-interval '91 days',legal_hold=true
                    """);
            admin.update("""
                    update ingestion_quality.iq_quality_task_delivery
                       set status='confirmed',receipt_id='retention-receipt',
                           next_attempt_at=null,lease_owner=null,lease_expires_at=null,
                           updated_at=statement_timestamp()-interval '91 days'
                    """);
            JdbcTemplate retention = new JdbcTemplate(login(RETENTION_LOGIN));

            retention.queryForObject(
                    "select ingestion_quality.iq_cleanup_quality_fuse_expired(statement_timestamp())",
                    Long.class);
            assertEquals(1, count(admin, "iq_quality_fuse_idempotency"));
            assertEquals(1, count(admin, "iq_quality_task_outbox"));
            assertEquals(1, count(admin, "iq_quality_task_delivery"));

            admin.update("update ingestion_quality.iq_quality_fuse_idempotency set legal_hold=false");
            admin.update("update ingestion_quality.iq_quality_task_outbox set legal_hold=false");
            retention.queryForObject(
                    "select ingestion_quality.iq_cleanup_quality_fuse_expired(statement_timestamp())",
                    Long.class);
            assertEquals(0, count(admin, "iq_quality_fuse_idempotency"));
            assertEquals(0, count(admin, "iq_quality_task_outbox"));
            assertEquals(0, count(admin, "iq_quality_task_delivery"));
            assertEquals(1, count(admin, "iq_quality_fuse_episode_current"));
            assertEquals(1, count(admin, "iq_quality_recovery_task_current"));
        } finally {
            dropRetentionLogin(admin);
            dropLogin(admin);
            resetFuseState(admin);
        }
    }

    private static QualityEligibilityMutation fuseMutation(
            UpstreamQualityEvent event,
            QualityEligibilitySnapshotEvidence snapshot,
            RuleDependencyDefinition rule,
            QualityFuseTaskAction action,
            QualityEligibilityStatus prior,
            UUID episodeId,
            UUID taskId,
            long expectedEpisodeVersion,
            String businessKey) {
        QualityEligibilityDecision fused = new QualityEligibilityDecision(
                QualityEligibilityStatus.FUSED,
                QualityEligibilityReason.REQUIRED_MEMBER_FUSED,
                List.of("DEP-P0-CAMPUS-ACCESS-001"));
        QualityFuseTransition transition = new QualityFuseTransition(
                prior, fused, fused,
                action == QualityFuseTaskAction.CREATE
                        ? QualityFuseEpisodeAction.START
                        : QualityFuseEpisodeAction.UPDATE,
                action, true);
        RuleEligibilityDecision decision = RuleEligibilityDecision.applied(rule, transition);
        DependencyQualityState triggeringState = new DependencyQualityState(
                event.sourceId(), event.sourceVersion(), event.lineageId(), 0,
                "DEP-P0-CAMPUS-ACCESS-001", event.sourceVersion(),
                QualityEligibilityStatus.FUSED, true, event.watermark());
        List<QualityFuseMemberEvidence> members = rule.members().stream()
                .map(member -> QualityFuseMemberEvidence.from(
                        member,
                        member.dependencyId().equals("DEP-P0-CAMPUS-ACCESS-001")
                                ? triggeringState : null,
                        member.dependencyId().equals("DEP-P0-CAMPUS-ACCESS-001")))
                .toList();
        QualityFuseEligibilityEvidence eligibilityEvidence =
                new QualityFuseEligibilityEvidence(
                        UUID.fromString("019fe8a0-0000-7000-8000-000000000399"),
                        rule.ruleVersion().businessKey("RULE-DEPENDENCY-REGISTRY-1.0.0"),
                        prior == QualityEligibilityStatus.FUSED ? 3 : 2,
                        rule.ruleVersion(), QualityFuseEligibilityEvidence.digestMembers(members),
                        "RULE-DEPENDENCY-REGISTRY-1.0.0",
                        "sha256:cd1915107c0c2657a430c2d5ba0abd420d9bd8d06f6cdcaed2a570c71ce6655a",
                        "DCC-1.1.0",
                        "sha256:aeb19962071e2144a85bb2e07fd124eff0d69edf93f7202e821204616a60f219",
                        "RC-1.0.0",
                        "sha256:d4f2587e0bde7a37964bab7ce990560327e2e5960f68fba5b770f09b041fd21a",
                        rule.operator(), rule.threshold(), prior,
                        QualityEligibilityStatus.FUSED, QualityEligibilityStatus.FUSED,
                        QualityEligibilityReason.REQUIRED_MEMBER_FUSED, members);
        QualityFuseTaskPlan plan = new QualityFuseTaskPlan(
                action, episodeId, taskId, "qf:" + "a".repeat(64), "k7", 1,
                expectedEpisodeVersion, event.sourceId(), event.sourceVersion(),
                "DEP-P0-CAMPUS-ACCESS-001",
                event.sourceVersion(), event.batchId(), event.snapshotId(),
                event.snapshotImmutableHash(), event.qualityGateVersion(),
                event.qualityGateDigest(), event.qmdpVersion(), event.qmdpDigest(),
                snapshot.qshmVersion(), snapshot.qshmDigest(), event.lineageId(),
                event.watermark(), List.of(rule.ruleVersion()),
                List.of(eligibilityEvidence), snapshot.formulaEvidence(),
                businessKey, event.payloadDigest(), event.effectiveAt(),
                event.occurredAt());
        return new QualityEligibilityMutation(
                QualityEligibilityProcessingOutcome.APPLIED,
                new QualityEligibilityCursor(
                        event.sourceId(), "DEP-P0-CAMPUS-ACCESS-001",
                        event.sourceVersion(), event.lineageId(), 0, event.batchId(),
                        QualityEligibilityCursorStage.TERMINAL, false,
                        event.sourceVersion()),
                null,
                new DependencyQualityState(
                        event.sourceId(), event.sourceVersion(), event.lineageId(), 0,
                        "DEP-P0-CAMPUS-ACCESS-001", event.sourceVersion(),
                        QualityEligibilityStatus.FUSED, true, event.watermark()),
                List.of(decision), null, null, snapshot, plan);
    }

    private static QualityEligibilityMutation recoveringPassMutation(
            UpstreamQualityEvent event,
            QualityEligibilitySnapshotEvidence snapshot,
            RuleDependencyDefinition rule,
            UUID episodeId,
            UUID taskId) {
        QualityEligibilityDecision evaluated = new QualityEligibilityDecision(
                QualityEligibilityStatus.ELIGIBLE,
                QualityEligibilityReason.ALL_REQUIRED_ELIGIBLE, List.of());
        QualityEligibilityDecision applied = new QualityEligibilityDecision(
                QualityEligibilityStatus.RECOVERING,
                QualityEligibilityReason.RECOVERY_LATCHED, List.of());
        QualityFuseTransition transition = new QualityFuseTransition(
                QualityEligibilityStatus.RECOVERING, evaluated, applied,
                QualityFuseEpisodeAction.PRESERVE, QualityFuseTaskAction.PRESERVE, false);
        RuleEligibilityDecision decision = RuleEligibilityDecision.applied(rule, transition);
        DependencyQualityState triggeringState = new DependencyQualityState(
                event.sourceId(), event.sourceVersion(), event.lineageId(), 0,
                "DEP-P0-CAMPUS-ACCESS-001", event.sourceVersion(),
                QualityEligibilityStatus.ELIGIBLE, true, event.watermark());
        List<QualityFuseMemberEvidence> members = rule.members().stream()
                .map(member -> QualityFuseMemberEvidence.from(
                        member,
                        member.dependencyId().equals("DEP-P0-CAMPUS-ACCESS-001")
                                ? triggeringState : null,
                        false))
                .toList();
        QualityFuseEligibilityEvidence evidence = new QualityFuseEligibilityEvidence(
                UUID.fromString("019fe8a0-0000-7000-8000-000000000399"),
                rule.ruleVersion().businessKey("RULE-DEPENDENCY-REGISTRY-1.0.0"),
                4, rule.ruleVersion(), QualityFuseEligibilityEvidence.digestMembers(members),
                "RULE-DEPENDENCY-REGISTRY-1.0.0",
                "sha256:cd1915107c0c2657a430c2d5ba0abd420d9bd8d06f6cdcaed2a570c71ce6655a",
                "DCC-1.1.0",
                "sha256:aeb19962071e2144a85bb2e07fd124eff0d69edf93f7202e821204616a60f219",
                "RC-1.0.0",
                "sha256:d4f2587e0bde7a37964bab7ce990560327e2e5960f68fba5b770f09b041fd21a",
                rule.operator(), rule.threshold(), QualityEligibilityStatus.RECOVERING,
                QualityEligibilityStatus.ELIGIBLE, QualityEligibilityStatus.RECOVERING,
                QualityEligibilityReason.RECOVERY_LATCHED, members);
        QualityFuseTaskPlan plan = new QualityFuseTaskPlan(
                QualityFuseTaskAction.PRESERVE, episodeId, taskId,
                "qf:" + "a".repeat(64), "k7", 1, 1,
                event.sourceId(), event.sourceVersion(), "DEP-P0-CAMPUS-ACCESS-001",
                event.sourceVersion(), event.batchId(), event.snapshotId(),
                event.snapshotImmutableHash(), event.qualityGateVersion(),
                event.qualityGateDigest(), event.qmdpVersion(), event.qmdpDigest(),
                snapshot.qshmVersion(), snapshot.qshmDigest(), event.lineageId(),
                event.watermark(), List.of(rule.ruleVersion()), List.of(evidence),
                snapshot.formulaEvidence(), "fuse:campus-access:recovering:g1:pass",
                event.payloadDigest(), event.effectiveAt(), event.occurredAt());
        return new QualityEligibilityMutation(
                QualityEligibilityProcessingOutcome.APPLIED,
                new QualityEligibilityCursor(
                        event.sourceId(), "DEP-P0-CAMPUS-ACCESS-001",
                        event.sourceVersion(), event.lineageId(), 0, event.batchId(),
                        QualityEligibilityCursorStage.TERMINAL, false,
                        event.sourceVersion()),
                null, triggeringState, List.of(decision), null, null, snapshot, plan);
    }

    private static QualityEligibilityMutation recoveringMutation(
            UpstreamQualityEvent event,
            QualityEligibilitySnapshotEvidence snapshot,
            RuleDependencyDefinition rule) {
        QualityEligibilityDecision recovering = new QualityEligibilityDecision(
                QualityEligibilityStatus.RECOVERING,
                QualityEligibilityReason.RECOVERY_LATCHED, List.of());
        QualityFuseTransition transition = new QualityFuseTransition(
                QualityEligibilityStatus.FUSED, recovering, recovering,
                QualityFuseEpisodeAction.PRESERVE, QualityFuseTaskAction.PRESERVE, false);
        RuleEligibilityDecision decision = RuleEligibilityDecision.applied(rule, transition);
        DependencyQualityState triggeringState = new DependencyQualityState(
                event.sourceId(), event.sourceVersion(), event.lineageId(), 0,
                "DEP-P0-CAMPUS-ACCESS-001", event.sourceVersion(),
                QualityEligibilityStatus.RECOVERING, true, event.watermark());
        return new QualityEligibilityMutation(
                QualityEligibilityProcessingOutcome.APPLIED,
                new QualityEligibilityCursor(
                        event.sourceId(), "DEP-P0-CAMPUS-ACCESS-001",
                        event.sourceVersion(), event.lineageId(), 0, event.batchId(),
                        QualityEligibilityCursorStage.TERMINAL, false,
                        event.sourceVersion()),
                null, triggeringState, List.of(decision), null, null, snapshot, null);
    }

    private static RuleDependencyDefinition fuseRule(UpstreamQualityEvent event) {
        return new RuleDependencyDefinition(
                new RuleVersionIdentity("ACC-SAFE-001", "1.0.0"),
                DependencyOperator.ALL_OF, null,
                List.of(
                        member("SRC-P0-ACCOMMODATION-001", "ACCOMMODATION-SLICE-1.0.0",
                                "DEP-P0-ACCOMMODATION-001"),
                        member("SRC-P0-CALENDAR-001", "BC-1.0.0",
                                "DEP-P0-CALENDAR-001"),
                        member(event.sourceId(), event.sourceSchemaVersion(),
                                "DEP-P0-CAMPUS-ACCESS-001"),
                        member("SRC-P0-DEVICE-001", "DEVICE-SLICE-1.0.0",
                                "DEP-P0-DEVICE-001"),
                        member("SRC-P0-DORM-ACCESS-001", "DORM-ACCESS-SLICE-1.0.0",
                                "DEP-P0-DORM-ACCESS-001"),
                        member("SRC-P0-LEAVE-001", "LEAVE-SLICE-1.0.0",
                                "DEP-P0-LEAVE-001"),
                        member("SRC-P0-TIMETABLE-001", "TIMETABLE-SLICE-1.0.0",
                                "DEP-P0-TIMETABLE-001")));
    }

    private static RuleDependencyMember member(
            String sourceId, String sourceContractVersion, String dependencyId) {
        return new RuleDependencyMember(
                sourceId, sourceContractVersion, dependencyId, "1.0.0",
                DependencyRequirement.REQUIRED, "primary");
    }

    private static UpstreamQualityEvent event(String eventId) {
        return event(eventId, 1);
    }

    private static UpstreamQualityEvent event(String eventId, long sourceVersion) {
        return new UpstreamQualityEvent(
                UUID.fromString(eventId), "urn:scholarsense:ingestion-quality",
                UpstreamQualityEventKind.ASSESSED_FAILED.eventType(),
                UpstreamQualityEventKind.ASSESSED_FAILED.schemaVersion(),
                UUID.fromString("019fe8a0-0000-7000-8000-000000000201"),
                "SRC-P0-CAMPUS-ACCESS-001", sourceVersion,
                UUID.fromString("019fe8a0-0000-7000-8000-000000000202"),
                null, 3, DataBatchStatus.QUALITY_FAILED,
                UUID.fromString("019fe8a0-0000-7000-8000-000000000203"),
                "sha256:" + "1".repeat(64), 3, QualityOverallResult.QUALITY_FAILED,
                "sha256:" + "2".repeat(64), "opaque-watermark",
                new BatchObservationWindow(
                        Instant.parse("2026-08-09T00:00:00Z"),
                        Instant.parse("2026-08-10T00:00:00Z")),
                Instant.parse("2026-08-10T00:00:00Z"),
                "CAMPUS-ACCESS-SLICE-1.0.0", "sha256:" + "3".repeat(64),
                "QMDP-1.0.0", "sha256:" + "4".repeat(64),
                "QG-1.0.0", "sha256:" + "5".repeat(64),
                Instant.parse("2026-08-10T00:00:00Z"),
                Instant.parse("2026-08-10T00:01:00Z"),
                "11111111111111111111111111111111", "sha256:" + "6".repeat(64));
    }

    private static void seedFailedSnapshot(JdbcTemplate jdbc, UpstreamQualityEvent event) {
        byte[] businessKey = "story-2.4-applied".getBytes(StandardCharsets.UTF_8);
        jdbc.update("""
                insert into ingestion_quality.iq_data_batch(
                  batch_id,source_id,business_key_utf8,business_key_digest,source_version,
                  lineage_id,effective_at,declared_manifest_digest,status,aggregate_version,
                  received_at,trace_id)
                values (?,?,?,encode(sha256(?),'hex'),1,?,?::timestamptz,?,
                  'receiving',1,?::timestamptz,?)
                """, event.batchId(), event.sourceId(), businessKey, businessKey,
                event.lineageId(), event.effectiveAt().toString(), event.manifestDigest(),
                event.effectiveAt().toString(), event.traceId());
        jdbc.update("""
                update ingestion_quality.iq_data_batch set
                  status='sealed',aggregate_version=2,record_count=0,valid_record_count=0,
                  rejected_record_count=0,observation_start_at=?::timestamptz,
                  observation_end_at=?::timestamptz,cutoff_at=?::timestamptz,
                  business_timezone='Asia/Shanghai',watermark_utf8=?,
                  source_schema_version=?,source_schema_digest=?,
                  data_catalog_version='DCC-1.1.0',data_catalog_digest=?,
                  quality_gate_version=?,quality_gate_digest=?,qmdp_version=?,qmdp_digest=?,
                  source_occurred_at=?::timestamptz,scheduled_due_at=?::timestamptz,
                  lane_id='story-2.4',sealed_contract_evidence='{"story":"2.4"}'::jsonb,
                  sealed_at=?::timestamptz where batch_id=?
                """, event.observationWindow().startAt().toString(),
                event.observationWindow().endAt().toString(), event.cutoffAt().toString(),
                event.watermark().getBytes(StandardCharsets.UTF_8),
                event.sourceSchemaVersion(), event.sourceSchemaDigest(),
                "sha256:" + "9".repeat(64), event.qualityGateVersion(),
                event.qualityGateDigest(), event.qmdpVersion(), event.qmdpDigest(),
                event.effectiveAt().toString(), event.occurredAt().toString(),
                event.occurredAt().minusSeconds(30).toString(), event.batchId());
        jdbc.update("""
                insert into ingestion_quality.iq_quality_snapshot(
                  snapshot_id,batch_id,domain_tag,hash_profile_version,hash_profile_digest,
                  source_id,assessed_batch_status,overall_result,observation_start_at,
                  observation_end_at,cutoff_at,watermark_utf8,source_owner_ref,approval_ref,
                  effective_at,retention_schedule_version,qmdp_version,qmdp_digest,
                  quality_gate_version,quality_gate_digest,canonicalization_profile,
                  manifest_digest,source_schema_version,source_schema_digest,lineage_id,
                  evaluated_at,trace_id,aggregate_version,immutable_hash,retention_due_at,
                  legal_hold,retention_scope_digest)
                values (?,?,
                  'scholarsense.ingestion-quality.quality-snapshot.immutable-hash.v1',
                  'QSHM-1.0.0',?,?, 'quality-failed','quality-failed',
                  ?::timestamptz,?::timestamptz,?::timestamptz,?,?,
                  'AUTH-2026-08-08-001',?::timestamptz,'RS-1.0.0',?,?,?,?,
                  'SCHOLARSENSE-CANONICAL-JSON-1.0.0',?,?,?,?,?::timestamptz,?,3,?,
                  (?::timestamptz + interval '2 years'),false,?)
                """, event.snapshotId(), event.batchId(), "sha256:" + "b".repeat(64),
                event.sourceId(), event.observationWindow().startAt().toString(),
                event.observationWindow().endAt().toString(), event.cutoffAt().toString(),
                event.watermark().getBytes(StandardCharsets.UTF_8), event.sourceId(),
                event.effectiveAt().toString(), event.qmdpVersion(), event.qmdpDigest(),
                event.qualityGateVersion(), event.qualityGateDigest(), event.manifestDigest(),
                event.sourceSchemaVersion(), event.sourceSchemaDigest(), event.lineageId(),
                event.occurredAt().toString(), event.traceId(), event.snapshotImmutableHash(),
                event.occurredAt().toString(), "sha256:" + "c".repeat(64));
        jdbc.update("""
                insert into ingestion_quality.iq_quality_snapshot_metric(
                  snapshot_id,metric_ordinal,metric_id,formula_id,formula_version,result,
                  applicable,numerator,denominator,value_basis_points,unit,operator,
                  threshold_numerator,threshold_denominator,boundary,reason_code)
                values (?,0,'completeness',
                  'QMDP-1.0.0/SRC-P0-CAMPUS-ACCESS-001/completeness','1.0.0',
                  'failed',true,97,100,9700,'basis-point','>=',99,100,'inclusive',null)
                """, event.snapshotId());
        jdbc.update("""
                update ingestion_quality.iq_data_batch
                   set status='quality-failed',aggregate_version=3,evaluated_at=?::timestamptz
                 where batch_id=? and status='sealed' and aggregate_version=2
                """, event.occurredAt().toString(), event.batchId());
    }

    private static void seedEligibleCurrent(JdbcTemplate jdbc) {
        UUID eligibilityId = UUID.fromString(
                "019fe8a0-0000-7000-8000-000000000399");
        jdbc.update("""
                insert into ingestion_quality.iq_quality_eligibility_history(
                  eligibility_id,rule_id,rule_version,registry_version,registry_digest,
                  catalog_version,catalog_digest,rule_catalog_version,rule_catalog_digest,
                  aggregate_version,status,reason_code,composition_operator,threshold,
                  effective_at,occurred_at,trace_id,producer,retention_due_at,legal_hold)
                select ?,'ACC-SAFE-001','1.0.0',registry_version,registry_digest,
                  catalog_version,catalog_digest,rule_catalog_version,rule_catalog_digest,
                  1,'eligible','ALL_REQUIRED_ELIGIBLE','all-of',null,
                  '2026-08-09T00:00:00Z'::timestamptz,
                  '2026-08-09T00:01:00Z'::timestamptz,
                  '11111111111111111111111111111111','ingestion-quality',
                  '2028-08-09T00:01:00Z'::timestamptz,false
                  from ingestion_quality.iq_rule_dependency_registry
                 where registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
                """, eligibilityId);
        jdbc.update("""
                insert into ingestion_quality.iq_quality_eligibility_current(
                  rule_id,rule_version,registry_version,eligibility_id,aggregate_version,
                  status,reason_code,composition_operator,threshold,effective_at,occurred_at)
                values ('ACC-SAFE-001','1.0.0','RULE-DEPENDENCY-REGISTRY-1.0.0',?,1,
                  'eligible','ALL_REQUIRED_ELIGIBLE','all-of',null,
                  '2026-08-09T00:00:00Z'::timestamptz,
                  '2026-08-09T00:01:00Z'::timestamptz)
                """, eligibilityId);
    }

    private static JdbcQualityEligibilityEventTransactionAdapter eligibilityAdapter(
            JdbcTemplate jdbc,
            DriverManagerDataSource source) {
        Instant now = Instant.parse("2026-08-11T08:00:00Z");
        DataBatchWorkloadAuthorizationEvidence evidence =
                new DataBatchWorkloadAuthorizationEvidence(
                        "test", "workload:eligibility-consumer",
                        "spiffe://scholarsense/ingestion-quality/eligibility-consumer",
                        QualityFuseWorkloadAuthorizationGuard.AUDIENCE,
                        Set.of(QualityFuseWorkloadAuthorizationGuard.CAPABILITY), 7,
                        QualityFuseWorkloadAuthorizationGuard.POLICY_VERSION,
                        "sha256:" + "d".repeat(64), now.minusSeconds(60),
                        now.plusSeconds(300), null);
        DataBatchWorkloadAuthorizationPort port = new DataBatchWorkloadAuthorizationPort() {
            @Override
            public DataBatchWorkloadAuthorizationResult capture(
                    DataBatchWorkloadAuthorizationRequest request) {
                return DataBatchWorkloadAuthorizationResult.allow(evidence, 7);
            }

            @Override
            public DataBatchWorkloadAuthorizationResult revalidate(
                    DataBatchWorkloadAuthorizationEvidence captured,
                    DataBatchWorkloadAuthorizationRequest request) {
                return DataBatchWorkloadAuthorizationResult.allow(captured, 7);
            }
        };
        TimeSourceProfile profile = new TimeSourceProfile(
                "postgres-test", "AUDIT-CLOCK-BINDING-1.0.0", 0,
                now.minusSeconds(60), now.plusSeconds(600),
                "evidence://signed/quality-fuse-test-clock");
        return new JdbcQualityEligibilityEventTransactionAdapter(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)),
                new ObjectMapper(), new QualityFuseWorkloadAuthorizationGuard(port, "test"),
                () -> new TrustedTime(now, profile));
    }

    private static void resetFuseState(JdbcTemplate jdbc) {
        jdbc.execute("""
                truncate table
                  ingestion_quality.iq_quality_recovery_task_read_audit,
                  ingestion_quality.iq_quality_fuse_rejection_audit,
                  ingestion_quality.iq_quality_fuse_idempotency,
                  ingestion_quality.iq_quality_fuse_audit,
                  ingestion_quality.iq_quality_task_outbox,
                  ingestion_quality.iq_quality_task_delivery_history,
                  ingestion_quality.iq_quality_task_delivery,
                  ingestion_quality.iq_quality_recovery_task_affected_rule,
                  ingestion_quality.iq_quality_recovery_task_current,
                  ingestion_quality.iq_quality_recovery_task_history,
                  ingestion_quality.iq_quality_fuse_episode_current,
                  ingestion_quality.iq_quality_fuse_episode_history,
                  ingestion_quality.iq_quality_eligibility_outbox,
                  ingestion_quality.iq_quality_eligibility_audit,
                  ingestion_quality.iq_quality_eligibility_idempotency,
                  ingestion_quality.iq_quality_eligibility_member_history,
                  ingestion_quality.iq_quality_eligibility_current,
                  ingestion_quality.iq_quality_eligibility_history,
                  ingestion_quality.iq_dependency_quality_current,
                  ingestion_quality.iq_quality_backfill_request,
                  ingestion_quality.iq_quality_event_quarantine,
                  ingestion_quality.iq_quality_event_inbox,
                  ingestion_quality.iq_quality_dependency_cursor,
                  ingestion_quality.iq_quality_pending_pair,
                  ingestion_quality.iq_quality_snapshot,
                  ingestion_quality.iq_data_batch cascade
                """);
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject(
                "select count(*) from ingestion_quality." + table, Integer.class);
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(login(required("scholarsense.audit.pg.user")));
    }

    private static DriverManagerDataSource login(String user) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl(required("scholarsense.audit.pg.url"));
        source.setUsername(user);
        return source;
    }

    private static void createLogin(JdbcTemplate admin) {
        dropLogin(admin);
        admin.execute("create role " + CONSUMER_LOGIN
                + " login inherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls");
        admin.execute("grant " + CONSUMER_ROLE + " to " + CONSUMER_LOGIN
                + " with inherit true, set false, admin false");
    }

    private static void createOnlineLogin(JdbcTemplate admin) {
        dropOnlineLogin(admin);
        admin.execute("create role " + ONLINE_LOGIN
                + " login inherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls");
        admin.execute("grant " + ONLINE_ROLE + " to " + ONLINE_LOGIN
                + " with inherit true, set false, admin false");
    }

    private static void createTaskRelayLogin(JdbcTemplate admin) {
        dropTaskRelayLogin(admin);
        admin.execute("create role " + TASK_RELAY_LOGIN
                + " login inherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls");
        admin.execute("grant " + TASK_RELAY_ROLE + " to " + TASK_RELAY_LOGIN
                + " with inherit true, set false, admin false");
    }

    private static void createRetentionLogin(JdbcTemplate admin) {
        dropRetentionLogin(admin);
        admin.execute("create role " + RETENTION_LOGIN
                + " login inherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls");
        admin.execute("grant " + RETENTION_ROLE + " to " + RETENTION_LOGIN
                + " with inherit true, set false, admin false");
    }

    private static void dropLogin(JdbcTemplate admin) {
        admin.execute("drop role if exists " + CONSUMER_LOGIN);
    }

    private static void dropOnlineLogin(JdbcTemplate admin) {
        admin.execute("drop role if exists " + ONLINE_LOGIN);
    }

    private static void dropTaskRelayLogin(JdbcTemplate admin) {
        admin.execute("drop role if exists " + TASK_RELAY_LOGIN);
    }

    private static void dropRetentionLogin(JdbcTemplate admin) {
        admin.execute("drop role if exists " + RETENTION_LOGIN);
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalStateException(property + " required");
        return value;
    }
}
