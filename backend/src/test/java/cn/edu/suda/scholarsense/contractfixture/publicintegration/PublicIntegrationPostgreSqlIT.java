package cn.edu.suda.scholarsense.contractfixture.publicintegration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.shared.outbox.DeliveryRecordKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL 18.4 test-scope PIC atomicity and fencing evidence. */
class PublicIntegrationPostgreSqlIT {

    private static final String SCHEMA = "public_integration_test";
    private static final Instant NOW = Instant.parse("2026-08-03T08:00:00Z");
    private static final DeliveryRecordKey KEY = new DeliveryRecordKey(
            "Candidate", "candidate-01", "pic.public-task.v1", "PIC-1.0.0");
    private static final DeliveryRecordKey MESSAGE_KEY = new DeliveryRecordKey(
            "Candidate", "candidate-01", "pic.public-message.v1", "PIC-1.0.0");
    private static final DeliveryRecordKey OTHER_KEY = new DeliveryRecordKey(
            "Candidate", "candidate-02", "pic.public-task.v1", "PIC-1.0.0");
    private static final String WORK_ITEM_TOKEN = "wk1." + "a".repeat(43);

    private JdbcTemplate jdbc;
    private PostgresPublicIntegrationReferenceAdapter adapter;

    @BeforeEach
    void setUp() {
        DataSource dataSource = dataSource(requiredProperty("scholarsense.audit.pg.url"));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop schema if exists " + SCHEMA + " cascade");
        jdbc.execute("create schema " + SCHEMA);
        for (String statement : schemaSql().split(";\\s*(?:\\R|$)")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement.replace("${schema}", SCHEMA));
            }
        }
        adapter = new PostgresPublicIntegrationReferenceAdapter(
                SCHEMA,
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) {
            jdbc.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test
    void schemaIsTemporaryAndDoesNotPolluteProductionOwners() {
        assertEquals("180004", jdbc.queryForObject(
                "select current_setting('server_version_num')", String.class));
        assertEquals(15, jdbc.queryForObject("""
                select count(*) from information_schema.tables
                 where table_schema=? and table_type='BASE TABLE'
                """, Integer.class, SCHEMA));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from information_schema.tables
                 where table_schema in ('identity_access','audit_operations')
                   and (table_name like '%public_integration%'
                     or table_name like '%delivery_record%')
                """, Integer.class));
    }

    @Test
    void callbackNonceAndInboxReplayGuardIsDurableAndAtomicAcrossRestart() {
        var first = adapter.recordAuthenticatedCallback(
                "provider.synthetic", "callback-key-01", "a".repeat(64),
                "PIC-1.0.0", "urn:synthetic:provider", "event-01",
                "b".repeat(64), "accepted".getBytes(StandardCharsets.UTF_8), NOW);
        assertEquals(PublicIntegrationCallbackSecurityVerifier.Decision.ACCEPT, first);

        var restarted = new PostgresPublicIntegrationReferenceAdapter(
                SCHEMA, jdbc, adapter.transactionTemplateForTest());
        assertEquals(PublicIntegrationCallbackSecurityVerifier.Decision.REPLAY,
                restarted.recordAuthenticatedCallback(
                        "provider.synthetic", "callback-key-01", "a".repeat(64),
                        "PIC-1.0.0", "urn:synthetic:provider", "event-01",
                        "b".repeat(64), "ignored".getBytes(StandardCharsets.UTF_8),
                        NOW.plusSeconds(1)));
        assertEquals(PublicIntegrationCallbackSecurityVerifier.Decision.ALREADY_APPLIED,
                restarted.recordAuthenticatedCallback(
                        "provider.synthetic", "callback-key-01", "c".repeat(64),
                        "PIC-1.0.0", "urn:synthetic:provider", "event-01",
                        "b".repeat(64), "ignored".getBytes(StandardCharsets.UTF_8),
                        NOW.plusSeconds(2)));
        assertEquals(PublicIntegrationCallbackSecurityVerifier.Decision.PAYLOAD_CONFLICT,
                restarted.recordAuthenticatedCallback(
                        "provider.synthetic", "callback-key-01", "d".repeat(64),
                        "PIC-1.0.0", "urn:synthetic:provider", "event-01",
                        "e".repeat(64), "ignored".getBytes(StandardCharsets.UTF_8),
                        NOW.plusSeconds(3)));
        assertArrayEquals("accepted".getBytes(StandardCharsets.UTF_8),
                restarted.callbackTechnicalResult("urn:synthetic:provider", "event-01"));
    }

    @Test
    void streamAdmissionIsAtomicAndCreatesOneCurrentOutboxOnlyWhenLaneIdle() {
        assertThrows(IllegalStateException.class,
                () -> adapter.admitStream(admission("g1." + "a".repeat(43), 1, 4), true));
        assertEquals(0, adapter.count("queued_delivery"));
        assertEquals(0, adapter.count("current_delivery"));
        assertEquals(0, adapter.count("outbox"));

        assertTrue(adapter.admitStream(admission("g1." + "a".repeat(43), 1, 4), false));
        assertFalse(adapter.admitStream(admission("g1." + "b".repeat(43), 2, 9), false));

        assertEquals(2, adapter.count("queued_delivery"));
        assertEquals(1, adapter.count("current_delivery"));
        assertEquals(1, adapter.count("outbox"));
        assertEquals(2, adapter.routeWatermark());
    }

    @Test
    void concurrentAdmissionsStillProduceOneActiveGeneration() throws Exception {
        var sequence = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Callable<Boolean>> commands = List.of(
                    () -> adapter.admitIntent(intentAdmission(
                            "g1." + "c".repeat(43), "di1." + "c".repeat(43),
                            NOW.plusMillis(sequence.incrementAndGet())), false),
                    () -> adapter.admitIntent(intentAdmission(
                            "g1." + "d".repeat(43), "di1." + "d".repeat(43),
                            NOW.plusMillis(sequence.incrementAndGet())), false));
            List<Boolean> results = workers.invokeAll(commands)
                    .stream().map(future -> {
                        try {
                            return future.get();
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    }).toList();
            assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        }
        assertEquals(2, adapter.count("queued_delivery"));
        assertEquals(1, adapter.count("current_delivery"));
        assertEquals(1, adapter.count("outbox"));
    }

    @Test
    void claimSkipsLockedOutboxAndClaimsNextAvailableLane() throws Exception {
        String lockedGeneration = "g1." + "u".repeat(43);
        String availableGeneration = "g1." + "v".repeat(43);
        adapter.admitStream(admissionFor(
                KEY, WORK_ITEM_TOKEN, lockedGeneration, 1, 4, "create"), false);
        adapter.admitStream(admissionFor(
                OTHER_KEY, "wk1." + "b".repeat(43), availableGeneration,
                1, 4, "create"), false);

        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var lockHolder = workers.submit(() ->
                    adapter.transactionTemplateForTest().executeWithoutResult(status -> {
                        jdbc.queryForList("""
                                select generation_key
                                  from public_integration_test.outbox
                                 where aggregate_type=? and aggregate_id=?
                                   and channel_id=? and contract_version=?
                                   and generation_key=?
                                 for update
                                """, KEY.aggregateType(), KEY.aggregateId(),
                                KEY.channelId(), KEY.contractVersion(), lockedGeneration);
                        locked.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("lock release timed out");
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                        }
                    }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));

            var claimant = workers.submit(() -> adapter.claim(
                    "worker-skip-locked", NOW.plusSeconds(10),
                    Duration.ofSeconds(30)).orElseThrow());
            try {
                assertEquals(availableGeneration,
                        claimant.get(2, TimeUnit.SECONDS).generationKey());
            } finally {
                release.countDown();
            }
            lockHolder.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void claimFenceAndConfirmPromoteAreAtomicAcrossCrashBoundaries() {
        String first = "g1." + "e".repeat(43);
        String second = "g1." + "f".repeat(43);
        adapter.admitStream(admission(first, 1, 4), false);
        adapter.admitStream(admission(second, 2, 9), false);
        var claim = adapter.claim("worker-a", NOW.plusSeconds(10), Duration.ofSeconds(30))
                .orElseThrow();

        assertThrows(IllegalStateException.class,
                () -> adapter.confirmAndPromote(
                        claim,
                        "external-task-01",
                        "sha256:" + "1".repeat(64),
                        NOW.plusSeconds(11),
                        true));
        assertEquals(first, adapter.currentGenerationKey());
        assertEquals("pending", adapter.currentStatus());
        assertEquals(1, adapter.count("outbox"));

        assertTrue(adapter.confirmAndPromote(
                claim,
                "external-task-01",
                "sha256:" + "1".repeat(64),
                NOW.plusSeconds(11),
                false));
        assertEquals(NOW.plusSeconds(11), adapter.confirmedAt(KEY, first));
        assertEquals(second, adapter.currentGenerationKey());
        assertEquals(2, adapter.currentDeliverySequence());
        assertEquals(2, adapter.count("outbox"));
        assertFalse(adapter.confirm(claim));
    }

    @Test
    void relayRestartPromoterRecoversConfirmedCurrentAndQueuedCandidate() {
        String first = "g1." + "l".repeat(43);
        String second = "g1." + "m".repeat(43);
        adapter.admitStream(admission(first, 1, 4), false);
        adapter.admitStream(admission(second, 2, 9), false);
        var claim = adapter.claim("worker-restart", NOW.plusSeconds(10),
                Duration.ofSeconds(30)).orElseThrow();
        assertTrue(adapter.confirm(claim));

        assertThrows(IllegalStateException.class, () -> adapter.recoverPromoter(
                KEY, false, NOW.plusSeconds(11), true));
        assertEquals(first, adapter.currentGenerationKey());
        assertTrue(adapter.recoverPromoter(KEY, false, NOW.plusSeconds(11), false));
        assertEquals(second, adapter.currentGenerationKey());
        assertEquals(2, adapter.currentDeliverySequence());
    }

    @Test
    void expiredWorkerCannotConfirmNewFenceAndRetryNeedsAuthorization() {
        String generation = "g1." + "g".repeat(43);
        adapter.admitStream(admission(generation, 1, 4), false);
        var old = adapter.claim("worker-a", NOW.plusSeconds(10), Duration.ofSeconds(1))
                .orElseThrow();
        var current = adapter.claim("worker-b", NOW.plusSeconds(12), Duration.ofSeconds(30))
                .orElseThrow();

        assertFalse(adapter.confirm(old));
        adapter.fail(current, true);
        assertThrows(IllegalStateException.class,
                () -> adapter.retryCurrent(
                        KEY, generation, adapter.currentFence(), NOW.plusSeconds(13),
                        false, false));
        long failedFence = adapter.currentFence();
        assertThrows(IllegalStateException.class, () -> adapter.retryCurrent(
                KEY, generation, failedFence, NOW.plusSeconds(13), true, true));
        assertEquals("failed", adapter.currentStatus());
        assertEquals(failedFence, adapter.currentFence());
        adapter.retryCurrent(
                KEY, generation, failedFence, NOW.plusSeconds(13), true, false);
        assertEquals("retrying", adapter.currentStatus());
    }

    @Test
    void mappingLedgerPurgesRawMaterialAfterDay90ButKeepsTerminalTombstone() {
        String generation = "g1." + "h".repeat(43);
        adapter.admitStream(admission(generation, 1, 4), false);
        adapter.bindProviderLineage(
                KEY, generation,
                WORK_ITEM_TOKEN,
                "external-task-sensitive-ref",
                NOW);
        var claim = adapter.claim("worker", NOW.plusSeconds(10), Duration.ofSeconds(30))
                .orElseThrow();
        adapter.confirmAndPromote(
                claim,
                "external-task-sensitive-ref",
                "sha256:" + "2".repeat(64),
                NOW.plusSeconds(11),
                false);
        jdbc.update("""
                update public_integration_test.current_delivery
                   set current_external_task_ref=null,
                       current_provider_receipt_digest=null
                """);
        assertTrue(adapter.rebuildCurrentCache(KEY));
        assertEquals("external-task-sensitive-ref", adapter.currentExternalTaskRef());

        adapter.sealLineageTerminal(
                WORK_ITEM_TOKEN, generation, NOW, NOW.plus(Duration.ofDays(30)));

        assertEquals(0, adapter.purgeExpiredMappings(NOW.plus(Duration.ofDays(89))));
        assertEquals(0, adapter.purgeExpiredMappings(NOW.plus(Duration.ofDays(90))));
        assertEquals("external-task-sensitive-ref", adapter.externalTaskRef(generation));
        assertEquals(1, adapter.purgeExpiredMappings(
                NOW.plus(Duration.ofDays(90)).plusNanos(1_000)));
        assertEquals(null, adapter.externalTaskRef(generation));
        assertTrue(adapter.hasTerminalTombstone(generation));
        assertTrue(adapter.hasProviderLineageTombstone(WORK_ITEM_TOKEN));
        assertThrows(IllegalStateException.class, () -> adapter.bindProviderLineage(
                KEY, generation, WORK_ITEM_TOKEN,
                "external-task-recreated", NOW.plus(Duration.ofDays(91))));
    }

    @Test
    void providerLineageBindsOnlyRequestedLaneAndRebindsNewGenerationIdempotently() {
        String first = "g1." + "n".repeat(43);
        String second = "g1." + "o".repeat(43);
        adapter.admitStream(admission(first, 1, 4), false);
        adapter.admitStream(admission(second, 2, 9), false);
        assertThrows(IllegalStateException.class, () -> adapter.bindProviderLineage(
                KEY, first, "wk1." + "z".repeat(43), "external-task-02", NOW));
        adapter.bindProviderLineage(
                KEY, first, WORK_ITEM_TOKEN, "external-task-02", NOW);
        var claim = adapter.claim("worker-lineage", NOW.plusSeconds(10),
                Duration.ofSeconds(30)).orElseThrow();
        assertTrue(adapter.confirmAndPromote(
                claim, "external-task-02", "sha256:" + "6".repeat(64),
                NOW.plusSeconds(11), false));

        adapter.bindProviderLineage(
                KEY, second, WORK_ITEM_TOKEN, "external-task-02", NOW.plusSeconds(12));
        adapter.bindProviderLineage(
                KEY, second, WORK_ITEM_TOKEN, "external-task-02", NOW.plusSeconds(13));
        assertEquals("external-task-02", adapter.externalTaskRef(first));
        assertEquals("external-task-02", adapter.externalTaskRef(second));
        assertEquals("external-task-02", adapter.currentExternalTaskRef());
    }

    @Test
    void terminalPreemptionAndOutboxAreOneTransaction() {
        String ordinary = "g1." + "i".repeat(43);
        String waiting = "g1." + "j".repeat(43);
        String terminal = "g1." + "k".repeat(43);
        adapter.admitStream(admission(ordinary, 1, 4), false);
        adapter.admitStream(admission(waiting, 2, 9), false);

        assertThrows(IllegalStateException.class, () -> adapter.preemptWithTerminal(
                admission(terminal, 3, 10, "revoke"),
                WORK_ITEM_TOKEN, true));
        assertEquals(ordinary, adapter.currentGenerationKey());
        assertEquals(1, adapter.count("outbox"));
        assertEquals(0, adapter.countTerminalCancelledQueue());

        assertTrue(adapter.preemptWithTerminal(
                admission(terminal, 3, 10, "revoke"),
                WORK_ITEM_TOKEN, false));
        assertEquals(terminal, adapter.currentGenerationKey());
        assertEquals(2, adapter.currentDeliverySequence());
        assertEquals(2, adapter.count("outbox"));
        assertEquals(2, adapter.countTerminalCancelledQueue());
        assertEquals(10, adapter.appliedTerminalVersion(WORK_ITEM_TOKEN));
        long terminalFence = adapter.terminalFenceEpoch(WORK_ITEM_TOKEN);
        assertFalse(adapter.preemptWithTerminal(
                admission(terminal, 3, 10, "revoke"),
                WORK_ITEM_TOKEN, false));
        assertEquals(terminalFence,
                adapter.terminalFenceEpoch(WORK_ITEM_TOKEN));
        assertEquals(10, adapter.appliedTerminalVersion(WORK_ITEM_TOKEN));
        assertThrows(IllegalStateException.class, () -> adapter.preemptWithTerminal(
                admission("g1." + "t".repeat(43), 4, 10, "revoke"),
                WORK_ITEM_TOKEN, false));
    }

    @Test
    void sourceTerminalFenceBlocksCrossChannelAdmissionClaimPreSendAndLateConfirm() {
        String task = "g1." + "p".repeat(43);
        String message = "g1." + "q".repeat(43);
        String terminal = "g1." + "r".repeat(43);
        adapter.admitStream(admissionFor(
                KEY, WORK_ITEM_TOKEN, task, 1, 4, "create"), false);
        adapter.admitStream(admissionFor(
                MESSAGE_KEY, WORK_ITEM_TOKEN, message, 1, 4, "create"), false);
        var messageClaim = adapter.claim(
                "worker-message", NOW.plusSeconds(10), Duration.ofSeconds(30),
                MESSAGE_KEY).orElseThrow();

        assertTrue(adapter.preemptWithTerminal(
                admissionFor(KEY, WORK_ITEM_TOKEN, terminal, 2, 5, "revoke"),
                WORK_ITEM_TOKEN, false));
        assertFalse(adapter.preSendAllowed(messageClaim));
        assertFalse(adapter.confirm(messageClaim));
        assertTrue(jdbc.queryForObject("""
                select sealed from public_integration_test.generation_ledger
                 where aggregate_type=? and aggregate_id=?
                   and channel_id=? and contract_version=? and generation_key=?
                """, Boolean.class, MESSAGE_KEY.aggregateType(),
                MESSAGE_KEY.aggregateId(), MESSAGE_KEY.channelId(),
                MESSAGE_KEY.contractVersion(), message));
        assertTrue(jdbc.queryForObject("""
                select delivered_at is not null
                  from public_integration_test.outbox
                 where aggregate_type=? and aggregate_id=?
                   and channel_id=? and contract_version=? and generation_key=?
                """, Boolean.class, MESSAGE_KEY.aggregateType(),
                MESSAGE_KEY.aggregateId(), MESSAGE_KEY.channelId(),
                MESSAGE_KEY.contractVersion(), message));
        assertEquals("terminal-cancelled", jdbc.queryForObject("""
                select disposition from public_integration_test.queued_delivery
                 where aggregate_type=? and aggregate_id=?
                   and channel_id=? and contract_version=? and generation_key=?
                """, String.class, MESSAGE_KEY.aggregateType(),
                MESSAGE_KEY.aggregateId(), MESSAGE_KEY.channelId(),
                MESSAGE_KEY.contractVersion(), message));
        assertThrows(IllegalStateException.class, () -> adapter.retryCurrent(
                MESSAGE_KEY, message, messageClaim.fencingToken() + 1,
                NOW.plusSeconds(12), true, false));
        assertThrows(IllegalStateException.class, () -> adapter.admitStream(
                admissionFor(MESSAGE_KEY, WORK_ITEM_TOKEN,
                        "g1." + "s".repeat(43), 2, 4, "create"), false));
    }

    @Test
    void idempotencyReceiptIsByteStableAndMismatchOrRollbackCreatesNoEffect() {
        byte[] receipt = "{\"resultCode\":\"ACCEPTED\"}".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class,
                () -> adapter.reserveCommand(
                        "tenant|workload|create", "idem-01", "sha256:" + "3".repeat(64),
                        receipt, NOW, true));
        assertEquals(0, adapter.count("idempotency_result"));

        assertArrayEquals(receipt, adapter.reserveCommand(
                "tenant|workload|create", "idem-01", "sha256:" + "3".repeat(64),
                receipt, NOW, false));
        assertArrayEquals(receipt, adapter.reserveCommand(
                "tenant|workload|create", "idem-01", "sha256:" + "3".repeat(64),
                "different".getBytes(StandardCharsets.UTF_8), NOW.plusSeconds(1), false));
        assertThrows(IllegalStateException.class,
                () -> adapter.reserveCommand(
                        "tenant|workload|create", "idem-01", "sha256:" + "4".repeat(64),
                        receipt, NOW, false));
        assertEquals(1, adapter.count("idempotency_result"));
    }

    private PostgresPublicIntegrationReferenceAdapter.StreamAdmission admission(
            String generationKey,
            long routeSequence,
            long sourceVersion) {
        return admission(generationKey, routeSequence, sourceVersion, "create");
    }

    private PostgresPublicIntegrationReferenceAdapter.StreamAdmission admission(
            String generationKey,
            long routeSequence,
            long sourceVersion,
            String operation) {
        return admissionFor(
                KEY, WORK_ITEM_TOKEN, generationKey, routeSequence,
                sourceVersion, operation);
    }

    private PostgresPublicIntegrationReferenceAdapter.StreamAdmission admissionFor(
            DeliveryRecordKey key,
            String workItemToken,
            String generationKey,
            long routeSequence,
            long sourceVersion,
            String operation) {
        return new PostgresPublicIntegrationReferenceAdapter.StreamAdmission(
                key,
                generationKey,
                operation,
                NOW.plusSeconds(routeSequence),
                "018f0f9a-7b0d-7abc-8def-0123456789a" + routeSequence,
                "018f0f9a-7b0d-7abc-8def-0123456789b" + routeSequence,
                "sha256:" + "a".repeat(64),
                "sha256:" + String.valueOf(routeSequence).repeat(64),
                routeSequence,
                sourceVersion,
                workItemToken);
    }

    private PostgresPublicIntegrationReferenceAdapter.IntentAdmission intentAdmission(
            String generationKey,
            String deliveryIntentId,
            Instant acceptedAt) {
        return new PostgresPublicIntegrationReferenceAdapter.IntentAdmission(
                KEY,
                generationKey,
                "create",
                acceptedAt,
                deliveryIntentId,
                "sha256:" + "5".repeat(64),
                4,
                WORK_ITEM_TOKEN);
    }

    private static String schemaSql() {
        try {
            return new ClassPathResource("public-integration/reference-schema.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static DataSource dataSource(String url) {
        var source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(url);
        source.setUsername(System.getProperty("scholarsense.audit.pg.user", System.getProperty("user.name")));
        return source;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required property: " + name);
        }
        return value;
    }
}
