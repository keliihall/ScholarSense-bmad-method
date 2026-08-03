package cn.edu.suda.scholarsense.auditoperations.application;

import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.NOW;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.fact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditEvidenceRepository;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditLedgerRepository;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditSearchQueryRepository;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditVerificationRunRepository;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditSearchProjectionWriter;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcSearchAuditRepository;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.SpringAuditTransactionAdapter;
import cn.edu.suda.scholarsense.auditoperations.domain.FindingCode;
import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchCriteria;
import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchView;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.CurrentFieldProjectionService;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcSensitiveReadTransactionAdapter;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecisionToken;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionPort;
import cn.edu.suda.scholarsense.identityaccess.api.FieldVisibility;
import cn.edu.suda.scholarsense.identityaccess.application.FieldCiphertextEnvelope;
import cn.edu.suda.scholarsense.identityaccess.application.SensitiveFieldCryptoContext;
import cn.edu.suda.scholarsense.identityaccess.application.SensitiveFieldCryptoException;
import cn.edu.suda.scholarsense.identityaccess.application.SensitiveFieldCryptoPort;
import cn.edu.suda.scholarsense.identityaccess.application.SensitiveFieldCryptoService;
import cn.edu.suda.scholarsense.identityaccess.application.WipeablePlaintext;
import cn.edu.suda.scholarsense.identityaccess.domain.FieldProjectionCatalog;
import cn.edu.suda.scholarsense.identityaccess.domain.FieldProjectionEvaluator;
import cn.edu.suda.scholarsense.identityaccess.domain.RoleFieldPolicyCatalog;
import cn.edu.suda.scholarsense.identityaccess.domain.RolePackage;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditFact;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.runtime.JdbcAuditSearchCsrfProofAdapter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Exact PostgreSQL 18.4 ledger evidence, launched only by run_audit_postgresql_tests.sh. */
class AuditLedgerPostgreSqlIT {
    private static final Set<String> R3_FIELDS = Set.of(
            "recordId", "ledgerSequence", "occurredAt", "outcome", "factSchemaVersion",
            "policyVersion", "retentionScheduleVersion", "actorDisplayRef", "objectDisplayRef",
            "businessActionCategory", "businessObjectCategory", "rolePackageSummary",
            "projectionScope", "producerModule", "eventType", "reasonCode", "traceId",
            "integrityStatus", "archiveStatus", "projectionStatus", "sourceNetworkRecorded");
    private static final Set<String> R7_FIELDS = Set.of(
            "recordId", "ledgerSequence", "occurredAt", "outcome", "factSchemaVersion",
            "policyVersion", "retentionScheduleVersion", "businessActionCategory",
            "businessObjectCategory", "rolePackageSummary", "projectionScope", "producerModule",
            "eventType", "reasonCode", "traceId", "integrityStatus", "archiveStatus",
            "projectionStatus", "sourceNetworkRecorded");

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private AtomicLong identifiers;
    private AtomicLong clockTicks;

    @BeforeEach
    void setUp() {
        dataSource = dataSource(requiredProperty("scholarsense.audit.pg.url"));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                truncate table audit_operations.ao_availability_observation,
                  audit_operations.ao_audit_search_csrf_proof,
                  audit_operations.ao_retention_execution_step,
                  audit_operations.ao_retention_execution,
                  audit_operations.ao_legal_hold,
                  audit_operations.ao_archive_manifest,
                  audit_operations.ao_local_audit_outbox,
                  audit_operations.ao_local_audit_fact,
                  audit_operations.ao_audit_search_projection,
                  audit_operations.ao_alert_outbox,
                  audit_operations.ao_finding_disposition,
                  audit_operations.ao_integrity_finding,
                  audit_operations.ao_verification_run,
                  audit_operations.ao_ingestion_receipt,
                  audit_operations.ao_audit_ledger,
                  audit_operations.ao_audit_ledger_head cascade
                """);
        jdbc.update("""
                update audit_operations.ao_audit_search_projection_watermark
                set ledger_sequence=0, projected_at='1970-01-01T00:00:00Z'
                where singleton_id=1
                """);
        jdbc.update("""
                insert into audit_operations.ao_audit_ledger_head
                  (singleton_id, ledger_sequence, entry_hash, updated_at)
                values (1, 0, repeat('0', 64), '1970-01-01T00:00:00Z')
                """);
        jdbc.execute("drop trigger if exists audit_ledger_insert_failure "
                + "on audit_operations.ao_audit_ledger");
        jdbc.execute("drop function if exists audit_operations.audit_ledger_insert_failure()");
        jdbc.execute("drop trigger if exists audit_search_outbox_insert_failure "
                + "on audit_operations.ao_local_audit_outbox");
        jdbc.execute("drop function if exists audit_operations.audit_search_outbox_insert_failure()");
        identifiers = new AtomicLong(10_000);
        clockTicks = new AtomicLong();
    }

    @Test
    void exactServerAndCleanAndUpgradeSchemasContainTheImmutableLedger() {
        assertEquals("180004", jdbc.queryForObject(
                "select current_setting('server_version_num')", String.class));
        assertEquals(1, tableCount(jdbc, "ao_audit_ledger"));
        assertEquals(1, tableCount(jdbc, "ao_audit_search_projection"));
        assertEquals(1, tableCount(jdbc, "ao_retention_execution"));
        JdbcTemplate upgraded = new JdbcTemplate(dataSource(
                requiredProperty("scholarsense.audit.pg.upgrade-url")));
        assertEquals("180004", upgraded.queryForObject(
                "select current_setting('server_version_num')", String.class));
        assertEquals(1, tableCount(upgraded, "ao_audit_ledger"));
        assertEquals(1, tableCount(upgraded, "ia_local_audit_outbox"));
        assertEquals(1, tableCount(upgraded, "ao_audit_search_projection"));
    }

    @Test
    void concurrentWorkersAppendOneHundredTwentyRowsWithNoGap() throws Exception {
        AuditLedgerAppendService service = appendService(dataSource);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<AuditAppendResult>>();
            for (int index = 1; index <= 120; index++) {
                int current = index;
                tasks.add(() -> service.append(source(current)));
            }
            for (var future : executor.invokeAll(tasks)) {
                assertEquals(AuditAppendOutcome.APPENDED, future.get().outcome());
            }
        }

        assertEquals(120L, scalar("select count(*) from audit_operations.ao_audit_ledger"));
        assertEquals(120L, scalar("select count(*) from audit_operations.ao_ingestion_receipt"));
        assertEquals(120L, scalar("select max(ledger_sequence) from audit_operations.ao_audit_ledger"));
        assertEquals(120L, scalar("select ledger_sequence from audit_operations.ao_audit_ledger_head"));
        assertEquals(120L, scalar("select count(*) from audit_operations.ao_audit_search_projection"));
        assertEquals(120L, scalar("select ledger_sequence from audit_operations.ao_audit_search_projection_watermark"));
        assertEquals(0L, scalar("""
                select count(*) from (
                  select ledger_sequence, row_number() over (order by ledger_sequence) expected
                  from audit_operations.ao_audit_ledger) rows
                where ledger_sequence<>expected
                """));
    }

    @Test
    void searchProjectionUsesStableSnapshotPaginationAndApprovedSortIndex() throws Exception {
        AuditLedgerAppendService service = appendService(dataSource);
        for (int index = 1; index <= 30; index++) service.append(source(index));
        long asOf = scalar("select ledger_sequence from audit_operations.ao_audit_search_projection_watermark");
        List<Long> first = jdbc.queryForList("""
                select ledger_sequence from audit_operations.ao_audit_search_projection
                where ledger_sequence <= ? order by occurred_at desc, ledger_sequence desc limit 10 offset 0
                """, Long.class, asOf);
        service.append(source(31));
        List<Long> second = jdbc.queryForList("""
                select ledger_sequence from audit_operations.ao_audit_search_projection
                where ledger_sequence <= ? order by occurred_at desc, ledger_sequence desc limit 10 offset 10
                """, Long.class, asOf);

        assertEquals(10, first.size());
        assertEquals(10, second.size());
        assertTrue(first.stream().noneMatch(second::contains));
        assertTrue(java.util.stream.Stream.concat(first.stream(), second.stream())
                .allMatch(sequence -> sequence <= asOf));

        try (Connection connection = dataSource.getConnection()) {
            JdbcTemplate planner = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            planner.execute("set enable_seqscan=off");
            planner.execute("set enable_bitmapscan=off");
            String plan = String.join("\n", planner.queryForList("""
                    explain (costs off)
                    select ledger_sequence from audit_operations.ao_audit_search_projection
                    order by occurred_at desc, ledger_sequence desc limit 10
                    """, String.class));
            assertTrue(plan.contains("ao_audit_search_projection_sort_idx"), plan);
        }
    }

    @Test
    void realR3R7SearchUsesPublicProjectionParameterizedFiltersAndFrozenSnapshot() {
        AuditLedgerAppendService append = appendService(dataSource);
        for (int index = 1; index <= 30; index++) append.append(searchSource(index));
        AuditSearchService searches = searchService(dataSource);

        AuditSearchPage first = searches.search(searchCriteria(
                "actor-r3", AuditSearchView.BUSINESS, 0, 10, null, null, 2_001));
        assertEquals(30L, first.asOfSequence());
        assertEquals(30L, first.total());
        assertEquals(10, first.items().size());
        assertEquals(R3_FIELDS, first.items().getFirst().fields().keySet());
        assertEquals("[MASKED-IDENTITY]", first.items().getFirst().fields().get("actorDisplayRef"));
        assertEquals("[MASKED-TECHNICAL]", first.items().getFirst().fields().get("eventType"));
        assertFalse(first.toString().contains("ast_v1_"));
        assertFalse(first.toString().contains("ost_v1_"));

        append.append(searchSource(31));
        AuditSearchPage second = searches.search(searchCriteria(
                "actor-r3", AuditSearchView.BUSINESS, 1, 10, first.asOfSequence(), null, 2_002));
        Set<Long> firstSequences = sequences(first);
        Set<Long> secondSequences = sequences(second);
        assertEquals(30L, second.total());
        assertTrue(firstSequences.stream().noneMatch(secondSequences::contains));
        assertTrue(secondSequences.stream().allMatch(sequence -> sequence <= first.asOfSequence()));
        assertFalse(secondSequences.contains(31L));

        AuditSearchPage technical = searches.search(searchCriteria(
                "actor-r7", AuditSearchView.TECHNICAL, 0, 10, first.asOfSequence(), null, 2_003));
        Map<String, Object> technicalFields = technical.items().getFirst().fields();
        assertEquals(R7_FIELDS, technicalFields.keySet());
        assertFalse(technicalFields.containsKey("actorDisplayRef"));
        assertFalse(technicalFields.containsKey("objectDisplayRef"));
        assertEquals("[MASKED-CATEGORY]", technicalFields.get("businessActionCategory"));
        assertEquals("identity-access", technicalFields.get("producerModule"));

        AuditSearchPage malicious = searches.search(searchCriteria(
                "actor-r3", AuditSearchView.BUSINESS, 0, 100, first.asOfSequence(),
                "identity-session' OR '1'='1", 2_004));
        assertEquals(0L, malicious.total());
        assertTrue(malicious.items().isEmpty());
        assertEquals(31L, scalar("select count(*) from audit_operations.ao_audit_search_projection"));
        assertEquals(0L, scalar("""
                select count(*) from audit_operations.ao_local_audit_fact
                where fact ?| array['actorRef','objectRef','actorSearchToken','objectSearchToken']
                """));
        Number maliciousAuditLeak = jdbc.queryForObject("""
                select count(*) from audit_operations.ao_local_audit_fact f
                join audit_operations.ao_local_audit_outbox o using (audit_id)
                where f.fact::text like ? or o.envelope::text like ?
                """, Number.class, "%identity-session' OR '1'='1%", "%identity-session' OR '1'='1%");
        assertEquals(0L, maliciousAuditLeak == null ? -1L : maliciousAuditLeak.longValue());
    }

    @Test
    void concurrentR3R7SearchesKeepRoleSchemasAndCommitEveryAuditAtomically() throws Exception {
        AuditLedgerAppendService append = appendService(dataSource);
        for (int index = 1; index <= 12; index++) append.append(searchSource(index));
        AuditSearchService searches = searchService(dataSource);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<AuditSearchPage>>();
            for (int index = 0; index < 8; index++) {
                int current = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent search start timeout");
                    }
                    boolean business = current % 2 == 0;
                    return searches.search(searchCriteria(
                            business ? "actor-r3" : "actor-r7",
                            business ? AuditSearchView.BUSINESS : AuditSearchView.TECHNICAL,
                            0, 4, 12L, null, 3_000 + current));
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (int index = 0; index < futures.size(); index++) {
                AuditSearchPage page = futures.get(index).get();
                assertEquals(index % 2 == 0 ? R3_FIELDS : R7_FIELDS,
                        page.items().getFirst().fields().keySet());
            }
        }

        assertEquals(40L, scalar("select count(*) from audit_operations.ao_local_audit_fact"));
        assertEquals(40L, scalar("select count(*) from audit_operations.ao_local_audit_outbox"));
    }

    @Test
    void projectionAuditFailureRollsBackAndReturnsNoSearchContent() {
        appendService(dataSource).append(searchSource(1));
        jdbc.execute("""
                create function audit_operations.audit_search_outbox_insert_failure() returns trigger
                language plpgsql as $body$
                begin raise exception 'injected projection audit rollback' using errcode='23514'; end
                $body$
                """);
        jdbc.execute("""
                create trigger audit_search_outbox_insert_failure before insert
                on audit_operations.ao_local_audit_outbox for each row
                execute function audit_operations.audit_search_outbox_insert_failure()
                """);
        try {
            AuditSearchException failure = assertThrows(AuditSearchException.class, () ->
                    searchService(dataSource).search(searchCriteria(
                            "actor-r3", AuditSearchView.BUSINESS, 0, 10, 1L, null, 4_001)));
            assertEquals("AUDIT_SEARCH_AUDIT_COMMIT_FAILED", failure.code());
            assertEquals(0L, scalar("select count(*) from audit_operations.ao_local_audit_fact"));
            assertEquals(0L, scalar("select count(*) from audit_operations.ao_local_audit_outbox"));
            assertEquals(1L, scalar("select count(*) from audit_operations.ao_audit_search_projection"));
        } finally {
            jdbc.execute("drop trigger audit_search_outbox_insert_failure "
                    + "on audit_operations.ao_local_audit_outbox");
            jdbc.execute("drop function audit_operations.audit_search_outbox_insert_failure()");
        }
    }

    @Test
    void csrfProofConsumptionIsAtomicAcrossConcurrentApplicationNodes() throws Exception {
        var adapter = new JdbcAuditSearchCsrfProofAdapter(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                () -> NOW);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 32)
                    .mapToObj(ignored -> (java.util.concurrent.Callable<Boolean>) () ->
                            adapter.consume("a".repeat(64), "b".repeat(64)))
                    .toList();
            long accepted = 0;
            for (var result : executor.invokeAll(tasks)) {
                if (result.get()) accepted++;
            }
            assertEquals(1, accepted);
        }
        assertEquals(1L, scalar("""
                select count(*) from audit_operations.ao_audit_search_csrf_proof
                where browser_session_digest=repeat('a',64) and proof_digest=repeat('b',64)
                """));
    }

    @Test
    void rollbackDoesNotConsumeALedgerSequenceAndReplayIsContentAware() {
        jdbc.execute("""
                create function audit_operations.audit_ledger_insert_failure() returns trigger
                language plpgsql as $body$
                begin raise exception 'injected ledger rollback' using errcode='23514'; end
                $body$
                """);
        jdbc.execute("""
                create trigger audit_ledger_insert_failure before insert
                on audit_operations.ao_audit_ledger for each row
                execute function audit_operations.audit_ledger_insert_failure()
                """);
        AuditLedgerAppendService service = appendService(dataSource);
        assertThrows(RuntimeException.class, () -> service.append(source(1)));
        assertEquals(0L, scalar("select ledger_sequence from audit_operations.ao_audit_ledger_head"));
        jdbc.execute("drop trigger audit_ledger_insert_failure on audit_operations.ao_audit_ledger");

        AuditAppendResult appended = service.append(source(1));
        AuditAppendResult duplicate = service.append(source(1));
        LocalAuditOutboxRecord original = source(1);
        LocalAuditOutboxRecord collision = LocalAuditOutboxRecord.forFact(
                uuid(90_001), original.fact(), original.createdAt());
        AuditAppendResult rejected = service.append(collision);

        assertEquals(1L, appended.ledgerSequence());
        assertEquals(AuditAppendOutcome.EXACT_DUPLICATE, duplicate.outcome());
        assertEquals(AuditAppendOutcome.COLLISION, rejected.outcome());
        assertEquals(1L, scalar("select count(*) from audit_operations.ao_audit_ledger"));
        assertEquals(1L, scalar("select duplicate_observed_count "
                + "from audit_operations.ao_ingestion_receipt"));
        assertEquals(1L, scalar("select count(*) from audit_operations.ao_integrity_finding"));
    }

    @Test
    void leastPrivilegeRolesCannotMutateLedgerOrCrossTheirCapabilities() throws Exception {
        appendService(dataSource).append(source(1));

        assertRoleDenied("scholarsense_audit_ledger_writer",
                "update audit_operations.ao_audit_ledger set entry_hash=entry_hash");
        assertRoleDenied("scholarsense_audit_ledger_writer",
                "delete from audit_operations.ao_audit_ledger");
        assertRoleDenied("scholarsense_audit_ledger_writer",
                "truncate audit_operations.ao_audit_ledger");
        assertRoleAllowed("scholarsense_audit_verifier",
                "select count(*) from audit_operations.ao_audit_ledger");
        assertRoleDenied("scholarsense_audit_verifier",
                "insert into audit_operations.ao_audit_ledger_head values (2,0,repeat('0',64),now())");
        assertRoleDenied("scholarsense_audit_verifier", """
                insert into audit_operations.ao_finding_disposition (
                  disposition_id, finding_id, disposition, disposition_digest, disposed_at, trace_id)
                select '019bf18e-6c00-7000-8000-000000099999', finding_id, 'resolved',
                  repeat('e',64), now(), '99999999999999999999999999999999'
                from audit_operations.ao_integrity_finding limit 1
                """);
        assertRoleDenied("scholarsense_audit_online",
                "select count(*) from audit_operations.ao_audit_ledger");
        assertRoleAllowed("scholarsense_audit_online", """
                insert into audit_operations.ao_audit_search_csrf_proof (
                  browser_session_digest, proof_digest, consumed_at, expires_at)
                values (repeat('c',64), repeat('d',64), now(), now() + interval '8 hours')
                """);
        assertRoleAllowed("scholarsense_audit_online", """
                delete from audit_operations.ao_audit_search_csrf_proof
                where browser_session_digest=repeat('c',64) and proof_digest=repeat('d',64)
                """);
        assertRoleDenied("scholarsense_audit_alert_delivery",
                "select count(*) from audit_operations.ao_integrity_finding");
        assertRoleAllowed("scholarsense_audit_search",
                "select count(*) from audit_operations.ao_audit_search_projection");
        assertRoleDenied("scholarsense_audit_search",
                "select count(*) from audit_operations.ao_audit_ledger");
        assertRoleAllowed("scholarsense_audit_retention_executor",
                "select count(*) from audit_operations.ao_audit_ledger");
        for (String operation : List.of(
                "update audit_operations.ao_audit_ledger set entry_hash=entry_hash",
                "delete from audit_operations.ao_audit_ledger",
                "truncate audit_operations.ao_audit_ledger")) {
            assertRoleDenied("scholarsense_audit_search", operation);
            assertRoleDenied("scholarsense_audit_retention_executor", operation);
        }
    }

    @Test
    void verifierDetectsPrivilegedPayloadTamper() {
        verifyAfterTamper(
                "update audit_operations.ao_audit_ledger set payload="
                        + "jsonb_set(payload, '{reasonCode}', '\"AUTHORIZATION_DENIED\"') "
                        + "where ledger_sequence=2",
                FindingCode.AUDIT_LEDGER_ENTRY_HASH_MISMATCH);
    }

    @Test
    void verifierPersistsEvidenceWhenStoredPayloadCannotBeDecoded() {
        verifyAfterTamper(
                "update audit_operations.ao_audit_ledger set payload="
                        + "payload || '{\"forged\":true}'::jsonb where ledger_sequence=2",
                FindingCode.AUDIT_LEDGER_ENTRY_HASH_MISMATCH,
                FindingCode.AUDIT_LEDGER_HEAD_MISMATCH);
    }

    @Test
    void replayedContractRejectionUsesOneDurableFindingAndAlert() {
        var manager = new DataSourceTransactionManager(dataSource);
        var transactions = new SpringAuditTransactionAdapter(new TransactionTemplate(manager));
        var evidence = new JdbcAuditEvidenceRepository(
                jdbc, new ObjectMapper(), this::findingId, transactions);
        var rejection = new AuditContractRejectionService(
                evidence, evidence, transactions, this::now, policy(), this::findingId);
        var command = new AuditContractRejectionCommand(
                "identity-access", "a".repeat(64), trace(991), NOW);

        rejection.reject(command);
        rejection.reject(command);

        assertEquals(1L, scalar("select count(*) from audit_operations.ao_integrity_finding"));
        assertEquals(1L, scalar("select count(*) from audit_operations.ao_alert_outbox"));
    }

    @Test
    void verifierDetectsPrivilegedPreviousAndEntryHashTamper() {
        verifyAfterTamper(
                "update audit_operations.ao_audit_ledger set previous_hash=repeat('b',64) "
                        + "where ledger_sequence=2",
                FindingCode.AUDIT_LEDGER_PREVIOUS_HASH_MISMATCH,
                FindingCode.AUDIT_LEDGER_ENTRY_HASH_MISMATCH);
        setUp();
        verifyAfterTamper(
                "update audit_operations.ao_audit_ledger set entry_hash=repeat('c',64) "
                        + "where ledger_sequence=2",
                FindingCode.AUDIT_LEDGER_ENTRY_HASH_MISMATCH,
                FindingCode.AUDIT_LEDGER_PREVIOUS_HASH_MISMATCH);
    }

    @Test
    void verifierDetectsPrivilegedHeadDriftAndMiddleDeletion() {
        verifyAfterTamper(
                "update audit_operations.ao_audit_ledger_head set entry_hash=repeat('d',64)",
                FindingCode.AUDIT_LEDGER_HEAD_MISMATCH);
        setUp();
        verifyAfterTamper(
                "delete from audit_operations.ao_audit_ledger where ledger_sequence=2",
                FindingCode.AUDIT_LEDGER_SEQUENCE_GAP,
                FindingCode.AUDIT_LEDGER_PREVIOUS_HASH_MISMATCH);
    }

    private void verifyAfterTamper(String tamperSql, FindingCode... expected) {
        AuditLedgerAppendService service = appendService(dataSource);
        for (int index = 1; index <= 3; index++) {
            service.append(source(index));
        }
        jdbc.execute(tamperSql);

        LedgerVerificationResult result = verifier().verifyFull(trace(999));

        assertTrue(!result.healthy());
        for (FindingCode code : expected) {
            assertTrue(result.findingCodes().contains(code), () -> result.findingCodes().toString());
        }
        assertEquals(result.findingCodes().size(), scalar(
                "select count(*) from audit_operations.ao_integrity_finding"));
        assertEquals(result.findingCodes().size(), scalar(
                "select count(*) from audit_operations.ao_alert_outbox"));
    }

    private AuditSearchService searchService(DataSource source) {
        JdbcTemplate template = new JdbcTemplate(source);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        var tokenization = (AuditSearchTokenGateway) request -> List.of(
                new AuditTokenQueryValue(
                        "ast_v1_k7_" + "a".repeat(64),
                        "AUDIT-TOKENIZATION-1.0.0",
                        "k7"));
        var trustedTime = new TrustedTime(
                NOW,
                new TimeSourceProfile(
                        "ntp-primary",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        0,
                        NOW.minusSeconds(1),
                        NOW.plusSeconds(60),
                        "evidence://signed/postgresql-it"));
        var searchAudit = new JdbcSearchAuditRepository(
                template,
                transactions,
                new ObjectMapper(),
                tokenization,
                () -> trustedTime);
        FieldProjectionPort projection = fieldProjection(
                searchAudit,
                new JdbcSensitiveReadTransactionAdapter(transactions));
        return new AuditSearchService(
                new JdbcAuditSearchQueryRepository(template),
                request -> auditSearchAuthorization(
                        request.sessionPseudonym(), request.view()),
                tokenization,
                searchAudit,
                () -> NOW,
                requesterKey -> requesterKey,
                projection,
                "audit-search-v1");
    }

    private static FieldProjectionPort fieldProjection(
            JdbcSearchAuditRepository searchAudit,
            JdbcSensitiveReadTransactionAdapter transactions) {
        RoleFieldPolicyCatalog rolePolicy = RoleFieldPolicyCatalog.approved();
        SensitiveFieldCryptoPort unavailableCrypto = new SensitiveFieldCryptoPort() {
            @Override
            public FieldCiphertextEnvelope encrypt(
                    SensitiveFieldCryptoContext context, WipeablePlaintext plaintext) {
                throw new SensitiveFieldCryptoException("FIELD_CRYPTO_BINDING_UNAVAILABLE");
            }

            @Override
            public WipeablePlaintext decrypt(
                    SensitiveFieldCryptoContext context, FieldCiphertextEnvelope envelope) {
                throw new SensitiveFieldCryptoException("FIELD_CRYPTO_BINDING_UNAVAILABLE");
            }
        };
        return new CurrentFieldProjectionService(
                request -> fieldAuthorization(request, rolePolicy),
                request -> new CompositeAuthorizationRecheckDecision(
                        request.priorDecisionToken().objectVersion()
                                        == request.currentRequest().expectedObjectVersion()
                                ? CompositeAuthorizationRecheckOutcome.CURRENT
                                : CompositeAuthorizationRecheckOutcome.STALE,
                        request.priorDecisionToken().objectVersion()
                                        == request.currentRequest().expectedObjectVersion()
                                ? "AUTHORIZATION_CURRENT"
                                : "AUTHORIZATION_STALE"),
                new FieldProjectionEvaluator(),
                FieldProjectionCatalog.approved(),
                rolePolicy,
                reference -> {
                    throw new SensitiveFieldCryptoException("FIELD_CRYPTO_BINDING_UNAVAILABLE");
                },
                new SensitiveFieldCryptoService(unavailableCrypto),
                () -> "audit-search-v1",
                record -> searchAudit.commit(new SearchAuditEvent(
                        record.actorPseudonym(),
                        record.actionId(),
                        "accepted",
                        null,
                        List.of("fieldClassSummary", "projectionCounts", "projectionVersions"),
                        "e".repeat(64),
                        record.objectVersion(),
                        record.traceId(),
                        record.trustedTime())),
                transactions,
                "scholarsense-backend",
                "test");
    }

    private static CompositeAuthorizationDecision fieldAuthorization(
            cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest request,
            RoleFieldPolicyCatalog rolePolicy) {
        boolean business = "actor-r3".equals(request.actorPseudonym())
                && "AGGREGATE_REPORT".equals(request.objectClass())
                && "audit.search-business-metadata".equals(request.actionId());
        boolean technical = "actor-r7".equals(request.actorPseudonym())
                && "TELEMETRY".equals(request.objectClass())
                && "audit.search-technical-metadata".equals(request.actionId());
        RolePackage role = business ? RolePackage.R3 : technical ? RolePackage.R7 : null;
        var token = new CompositeAuthorizationDecisionToken(
                1, 1, 1, 1, 1, request.expectedObjectVersion(), "RFP-1.0.0");
        if (role == null) {
            return new CompositeAuthorizationDecision(
                    CompositeAuthorizationOutcome.DENY,
                    "AUTHORIZATION_DENIED",
                    Set.of(),
                    Set.of(),
                    Map.of(),
                    Set.of(),
                    "RFP-1.0.0",
                    request.expectedObjectVersion(),
                    NOW,
                    token);
        }
        Map<String, FieldVisibility> visibility = rolePolicy.fieldVisibility(role).entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().code(),
                        entry -> FieldVisibility.valueOf(entry.getValue().name())));
        return new CompositeAuthorizationDecision(
                CompositeAuthorizationOutcome.ALLOW,
                "AUTHORIZATION_ALLOWED",
                Set.of(role.authorityId()),
                Set.of(business ? "SCHOOL_GOVERNANCE" : "TECHNICAL_OBJECT"),
                visibility,
                Set.of(),
                "RFP-1.0.0",
                request.expectedObjectVersion(),
                NOW,
                token);
    }

    private static AuthorizedAuditSearchDecision auditSearchAuthorization(
            String session,
            AuditSearchView view) {
        boolean allowed = "actor-r3".equals(session) && view == AuditSearchView.BUSINESS
                || "actor-r7".equals(session) && view == AuditSearchView.TECHNICAL;
        return new AuthorizedAuditSearchDecision(
                allowed,
                "RFP-1.0.0",
                view == AuditSearchView.BUSINESS
                        ? "audit.search-business-metadata"
                        : "audit.search-technical-metadata",
                allowed ? Set.of("audit-domain") : Set.of(),
                Map.of(),
                allowed ? null : "AUDIT_SEARCH_FORBIDDEN");
    }

    private static AuditSearchCriteria searchCriteria(
            String requester,
            AuditSearchView view,
            int page,
            int size,
            Long asOf,
            String objectType,
            long traceSuffix) {
        return new AuditSearchCriteria(
                requester,
                view,
                null,
                objectType,
                null,
                null,
                null,
                null,
                null,
                null,
                page,
                size,
                asOf,
                trace(traceSuffix));
    }

    private static Set<Long> sequences(AuditSearchPage page) {
        return page.items().stream()
                .map(item -> (Long) item.fields().get("ledgerSequence"))
                .collect(Collectors.toUnmodifiableSet());
    }

    private AuditLedgerAppendService appendService(DataSource source) {
        JdbcTemplate template = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        var ledger = new JdbcAuditLedgerRepository(template, new ObjectMapper());
        var evidence = new JdbcAuditEvidenceRepository(template, new ObjectMapper(), this::findingId);
        return new AuditLedgerAppendService(
                ledger, evidence, evidence,
                new SpringAuditTransactionAdapter(new TransactionTemplate(manager)),
                this::now, policy(), this::findingId, new CanonicalAuditHasher(),
                new JdbcAuditSearchProjectionWriter(template));
    }

    private AuditLedgerVerifier verifier() {
        var ledger = new JdbcAuditLedgerRepository(jdbc, new ObjectMapper());
        var evidence = new JdbcAuditEvidenceRepository(jdbc, new ObjectMapper(), this::findingId);
        return new AuditLedgerVerifier(
                ledger, new CanonicalAuditHasher(), evidence, evidence,
                this::findingId, this::now, policy(),
                new JdbcAuditVerificationRunRepository(jdbc));
    }

    private LocalAuditOutboxRecord source(int index) {
        return source(index, fact().roleIds());
    }

    private LocalAuditOutboxRecord searchSource(int index) {
        return source(index, List.of("R3"));
    }

    private LocalAuditOutboxRecord source(int index, List<String> roleIds) {
        LocalAuditFact base = fact();
        UUID auditId = uuid(1_000L + index);
        LocalAuditFact changed = new LocalAuditFact(
                auditId, base.schemaVersion(), base.producerModule(), base.actorType(),
                base.actorSearchToken(), roleIds, base.authorizationContext(), base.action(),
                base.objectType(), base.objectSearchToken(), base.outcome(), base.reasonCode(),
                base.purpose(), base.projectionScope(), base.occurredAt(), base.recordedAt(),
                base.timeSourceProfile(), base.sourceIpSearchToken(), base.tokenizationProfileVersion(),
                base.keyVersion(), trace(index), base.aggregateType(), base.aggregateIdSearchToken(),
                (long) index, base.idempotencyKeyDigest(), Map.copyOf(base.policyVersions()),
                base.retentionScheduleVersion());
        return LocalAuditOutboxRecord.forFact(uuid(50_000L + index), changed, NOW);
    }

    private UUID findingId(Instant ignored) {
        return uuid(identifiers.incrementAndGet());
    }

    private Instant now() {
        return NOW.plusSeconds(clockTicks.incrementAndGet());
    }

    private static AuditPolicyPort policy() {
        return new AuditPolicyPort() {
            @Override public String ingestionPolicyVersion() {
                return "AUDIT-INGESTION-POLICY-1.0.0";
            }
            @Override public String hashProfileVersion() {
                return "AUDIT-LEDGER-HASH-1.0.0";
            }
        };
    }

    private void assertRoleDenied(String role, String sql) throws Exception {
        SQLException failure = assertThrows(SQLException.class, () -> executeAs(role, sql));
        assertEquals("42501", failure.getSQLState());
    }

    private void assertRoleAllowed(String role, String sql) throws Exception {
        executeAs(role, sql);
    }

    private void executeAs(String role, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("set role " + role);
            statement.execute(sql);
        }
    }

    private static int tableCount(JdbcTemplate template, String table) {
        Integer value = template.queryForObject(
                "select count(*) from information_schema.tables where table_name=?", Integer.class, table);
        return value == null ? -1 : value;
    }

    private long scalar(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
        return value == null ? -1 : value.longValue();
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString("019bf18e-6c00-7000-8000-" + String.format("%012x", suffix));
    }

    private static String trace(long value) {
        return String.format("%032x", value);
    }

    private static DataSource dataSource(String url) {
        return new DriverManagerDataSource(url, requiredProperty("scholarsense.audit.pg.user"), "");
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required; use scripts/run_audit_postgresql_tests.sh");
        }
        return value;
    }
}
