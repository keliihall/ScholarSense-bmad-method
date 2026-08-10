package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** PostgreSQL idempotency-time and append-only correction-lineage contract for Story 2.3. */
class DataBatchIdempotencyAndLineagePostgreSqlIT {
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Pattern VERSION = Pattern.compile("^V(\\d{6})__.+\\.sql$");
    private static final String FEATURE_SUFFIX =
            "__ingestion-quality__data_batch_quality_snapshot_v1.sql";

    private static final String QUALITY_WORKER =
            "scholarsense_ingestion_quality_quality_worker";
    private static final String RETENTION =
            "scholarsense_ingestion_quality_retention_executor";
    private static final String WORKER_LOGIN =
            "scholarsense_iq_batch_lineage_worker_login";
    private static final String RETENTION_LOGIN =
            "scholarsense_iq_batch_lineage_retention_login";

    private static final String SOURCE = "SRC-P0-CARD-001";
    private static final String OTHER_SOURCE = "SRC-P0-STUDENT-001";
    private static final Instant BUSINESS_TIME = Instant.parse("2026-08-01T00:00:00Z");

    @AfterEach
    void removeTestOwnedRowsFromTheSharedPostgreSqlFixture() {
        JdbcTemplate jdbc = admin();
        String ownedUuidPrefix = "019fe540-0000-7000-8000-%";
        jdbc.update("""
                delete from ingestion_quality.iq_local_audit_outbox
                 where event_id::text like ?
                """, ownedUuidPrefix);
        jdbc.update("""
                delete from ingestion_quality.iq_local_audit_fact
                 where audit_id::text like ?
                """, ownedUuidPrefix);
        jdbc.update("""
                delete from ingestion_quality.iq_batch_idempotency
                 where batch_id::text like ?
                """, ownedUuidPrefix);
        jdbc.update("""
                delete from ingestion_quality.iq_data_batch
                 where batch_id::text like ? and supersedes_batch_id is not null
                """, ownedUuidPrefix);
        jdbc.update("""
                delete from ingestion_quality.iq_data_batch
                 where batch_id::text like ?
                """, ownedUuidPrefix);
    }

    @Test
    void featureMigrationIsTheDynamicallyDiscoveredGlobalSuccessor() throws Exception {
        List<Migration> inventory = new ArrayList<>();
        try (var paths = Files.walk(MIGRATIONS)) {
            paths.filter(path -> path.getFileName().toString().startsWith("V"))
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .forEach(path -> inventory.add(migration(path)));
        }
        inventory.sort(Comparator.comparingInt(Migration::version));

        List<Migration> feature = inventory.stream()
                .filter(item -> item.path().getFileName().toString().endsWith(FEATURE_SUFFIX))
                .toList();
        assertEquals(1, feature.size());
        Migration candidate = feature.getFirst();
        int predecessor = inventory.stream()
                .filter(item -> item.version() < candidate.version())
                .mapToInt(Migration::version)
                .max()
                .orElseThrow();
        assertEquals(predecessor + 1, candidate.version());
        for (int index = 0; index < inventory.size(); index++) {
            assertEquals(index + 1, inventory.get(index).version());
        }
    }

    @Test
    void claimsUseFreshDatabaseTimeAndExpiredClaimsAreLazilyReclaimed() {
        ensureWorkloadLogins();
        JdbcTemplate jdbc = admin();
        JdbcTemplate worker = workload(WORKER_LOGIN);

        Instant historicalBusinessTime = Instant.parse("2020-01-02T03:04:05Z");
        UUID firstLineage = uuid(0x1003);
        ReceiveSpec first = root(
                0x1000, SOURCE, bytes("fresh-time-business-key"), 1, firstLineage,
                historicalBusinessTime, historicalBusinessTime);
        Instant firstLowerBound = databaseNow(jdbc).minusSeconds(1);
        assertEquals(first.batchId(), receive(worker, first));
        Instant firstUpperBound = databaseNow(jdbc).plusSeconds(1);
        ClaimTimes firstTimes = claimTimes(jdbc, first.scopeDigest());

        assertAll(
                () -> assertEquals(historicalBusinessTime, jdbc.queryForObject("""
                        select received_at from ingestion_quality.iq_data_batch
                         where batch_id=?
                        """, Timestamp.class, first.batchId()).toInstant(),
                        "receivedAt remains business evidence, not the idempotency clock"),
                () -> assertBetween(firstTimes.claimedAt(), firstLowerBound, firstUpperBound),
                () -> assertBetween(firstTimes.completedAt(), firstLowerBound, firstUpperBound),
                () -> assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                        select expires_at = claimed_at + interval '90 days'
                          from ingestion_quality.iq_batch_idempotency
                         where scope_digest=?
                        """, Boolean.class, first.scopeDigest()))));

        Instant replayBusinessTime = BUSINESS_TIME.plusSeconds(20);
        UUID replayLineage = uuid(0x2003);
        ReceiveSpec replayRoot = root(
                0x2000, SOURCE, bytes("expired-reclaim-business-key"), 1,
                replayLineage, replayBusinessTime, replayBusinessTime);
        assertEquals(replayRoot.batchId(), receive(worker, replayRoot));

        String expiredScope = rawDigest("expired-reclaim-scope");
        seedExpiredClaim(
                jdbc, expiredScope, replayRoot.batchId(), requestDigest("stale-body"),
                databaseNow(jdbc).minusSeconds(30));
        ReceiveSpec reclaimed = copy(
                replayRoot, uuid(0x2010), replayRoot.batchId(), expiredScope,
                requestDigest("replacement-body"));
        Instant reclaimLowerBound = databaseNow(jdbc).minusSeconds(1);
        assertEquals(replayRoot.batchId(), receive(worker, reclaimed));
        Instant reclaimUpperBound = databaseNow(jdbc).plusSeconds(1);
        ClaimTimes reclaimedTimes = claimTimes(jdbc, expiredScope);

        assertAll(
                () -> assertBetween(
                        reclaimedTimes.claimedAt(), reclaimLowerBound, reclaimUpperBound),
                () -> assertBetween(
                        reclaimedTimes.completedAt(), reclaimLowerBound, reclaimUpperBound),
                () -> assertEquals(reclaimed.requestDigest(), jdbc.queryForObject("""
                        select request_digest from ingestion_quality.iq_batch_idempotency
                         where scope_digest=?
                        """, String.class, expiredScope).trim()),
                () -> assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                        select expires_at = claimed_at + interval '90 days'
                          from ingestion_quality.iq_batch_idempotency
                         where scope_digest=?
                        """, Boolean.class, expiredScope))),
                () -> assertEquals(1, jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_data_batch
                         where source_id=? and business_key_digest=encode(sha256(?), 'hex')
                        """, Integer.class, replayRoot.sourceId(), replayRoot.businessKeyUtf8())));
    }

    @Test
    void cleanupDeletesExpiredBatchIdempotencyThroughAnExpiryLeadingIndex() {
        ensureWorkloadLogins();
        JdbcTemplate jdbc = admin();
        JdbcTemplate worker = workload(WORKER_LOGIN);
        JdbcTemplate retention = workload(RETENTION_LOGIN);

        ReceiveSpec root = root(
                0x3000, SOURCE, bytes("cleanup-business-key"), 1, uuid(0x3003),
                BUSINESS_TIME.plusSeconds(30), BUSINESS_TIME.plusSeconds(30));
        assertEquals(root.batchId(), receive(worker, root));
        Instant cutoff = databaseNow(jdbc);
        String expiredScope = rawDigest("cleanup-expired-scope");
        seedExpiredClaim(
                jdbc, expiredScope, root.batchId(), requestDigest("cleanup-expired-body"),
                cutoff.minusSeconds(30));

        Long deleted = retention.queryForObject(
                "select ingestion_quality.iq_cleanup_expired(?)",
                Long.class, Timestamp.from(cutoff));

        assertAll(
                () -> assertNotNull(deleted),
                () -> assertEquals(0, countByScope(jdbc, expiredScope),
                        "retention cleanup must include expired batch-command claims"),
                () -> assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists (
                          select 1
                            from pg_catalog.pg_indexes
                           where schemaname='ingestion_quality'
                             and tablename='iq_batch_idempotency'
                             and indexdef ~* '\\(expires_at([ ,]|\\))')
                        """, Boolean.class)),
                        "expiry cleanup must have an expires_at-leading index"));
    }

    @Test
    void cleanupNeverUsesAnAheadTrustedCutoffToExpireAReplayEarly() {
        ensureWorkloadLogins();
        JdbcTemplate jdbc = admin();
        JdbcTemplate worker = workload(WORKER_LOGIN);
        JdbcTemplate retention = workload(RETENTION_LOGIN);

        ReceiveSpec root = root(
                0x3100, SOURCE, bytes("cleanup-future-cutoff-key"), 2, uuid(0x3103),
                BUSINESS_TIME.plusSeconds(31), BUSINESS_TIME.plusSeconds(31));
        assertEquals(root.batchId(), receive(worker, root));
        Instant databaseNow = databaseNow(jdbc);
        Instant stillLiveUntil = databaseNow.plusSeconds(240);
        String liveScope = rawDigest("cleanup-still-live-scope");
        seedExpiredClaim(
                jdbc, liveScope, root.batchId(), requestDigest("cleanup-still-live-body"),
                stillLiveUntil);

        retention.queryForObject(
                "select ingestion_quality.iq_cleanup_expired(?)",
                Long.class, Timestamp.from(databaseNow.plusSeconds(270)));

        assertEquals(1, countByScope(jdbc, liveScope),
                "a tolerated ahead clock must be clamped to database time before deletion");
    }

    @Test
    void changedBodyReuseWinsBeforeReceiveIdentityConflictAndLeavesNoWrites() {
        ensureWorkloadLogins();
        JdbcTemplate jdbc = admin();
        JdbcTemplate worker = workload(WORKER_LOGIN);

        ReceiveSpec original = root(
                0x4000, SOURCE, bytes("mismatch-precedence-key"), 7, uuid(0x4003),
                BUSINESS_TIME.plusSeconds(40), BUSINESS_TIME.plusSeconds(40));
        assertEquals(original.batchId(), receive(worker, original));
        ReceiveSpec changedBody = new ReceiveSpec(
                uuid(0x4010), uuid(0x4011), original.sourceId(),
                original.businessKeyUtf8(), original.sourceVersion(), original.lineageId(),
                original.supersedesBatchId(), original.correctionReason(), original.effectiveAt(),
                digest("changed-manifest"), original.receivedAt(), trace(0x4010),
                original.scopeDigest(), requestDigest("changed-request-body"));
        Footprint before = footprint(jdbc, changedBody);
        Attempt outcome = attempt(worker, changedBody);
        Footprint after = footprint(jdbc, changedBody);

        assertAll(
                () -> assertNotNull(outcome.failure(), "changed-body reuse must fail"),
                () -> assertTrue(outcome.failure().getMostSpecificCause().getMessage()
                                .contains("INGESTION_QUALITY_IDEMPOTENCY_MISMATCH"),
                        "the idempotency mismatch must take precedence over identity lookup"),
                () -> assertEquals(before, after,
                        "mismatch must not add a batch, claim, audit fact, or audit outbox row"),
                () -> assertEquals(original.requestDigest(), jdbc.queryForObject("""
                        select request_digest from ingestion_quality.iq_batch_idempotency
                         where scope_digest=?
                        """, String.class, original.scopeDigest()).trim()));
    }

    @Test
    void staleFreshInspectionWaitsForConcurrentOwnerAndReturnsMismatch() throws Exception {
        ensureWorkloadLogins();
        JdbcTemplate worker = workload(WORKER_LOGIN);
        ReceiveSpec original = root(
                0x4500, SOURCE, bytes("stale-preflight-concurrent-key"), 11, uuid(0x4503),
                BUSINESS_TIME.plusSeconds(45), BUSINESS_TIME.plusSeconds(45));
        String changedRequestDigest = requestDigest("stale-preflight-changed-body");

        Inspection initial = inspect(
                worker, original.scopeDigest(), "receive", changedRequestDigest);
        assertEquals("fresh", initial.disposition());

        CountDownLatch ownerReadyToCommit = new CountDownLatch(1);
        CountDownLatch inspectionStarted = new CountDownLatch(1);
        CountDownLatch allowOwnerCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> owner = executor.submit(() -> receiveWithoutAutoCommit(
                    original, ownerReadyToCommit, allowOwnerCommit));
            assertTrue(ownerReadyToCommit.await(10, TimeUnit.SECONDS),
                    "the owner command must hold its scope lock before postflight inspection");
            Future<Inspection> postflight = executor.submit(() -> {
                inspectionStarted.countDown();
                return inspect(
                        workload(WORKER_LOGIN), original.scopeDigest(), "receive",
                        changedRequestDigest);
            });
            assertTrue(inspectionStarted.await(10, TimeUnit.SECONDS));
            allowOwnerCommit.countDown();

            assertEquals(original.batchId(), owner.get(20, TimeUnit.SECONDS));
            Inspection mismatch = postflight.get(20, TimeUnit.SECONDS);
            Inspection replay = inspect(
                    worker, original.scopeDigest(), "receive", original.requestDigest());
            Inspection commandTypeMismatch = inspect(
                    worker, original.scopeDigest(), "seal", original.requestDigest());
            assertAll(
                    () -> assertEquals("mismatch", mismatch.disposition()),
                    () -> assertNull(mismatch.batchId()),
                    () -> assertEquals("mismatch", commandTypeMismatch.disposition()),
                    () -> assertEquals("replay", replay.disposition()),
                    () -> assertEquals(original.batchId(), replay.batchId()),
                    () -> assertEquals("receiving", replay.responseStatus()),
                    () -> assertEquals(1L, replay.responseAggregateVersion()),
                    () -> assertNotNull(replay.completedAt()),
                    () -> assertNotNull(replay.expiresAt()));
        } finally {
            allowOwnerCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void inspectionEvaluatesExpiryAfterWaitingForTheScopeLock() throws Exception {
        ensureWorkloadLogins();
        JdbcTemplate jdbc = admin();
        JdbcTemplate worker = workload(WORKER_LOGIN);
        ReceiveSpec root = root(
                0x4600, SOURCE, bytes("post-lock-expiry-key"), 12, uuid(0x4603),
                BUSINESS_TIME.plusSeconds(46), BUSINESS_TIME.plusSeconds(46));
        assertEquals(root.batchId(), receive(worker, root));
        String expiringScope = rawDigest("post-lock-expiry-scope");
        String expiringRequest = requestDigest("post-lock-expiry-request");
        Instant expiresAt = databaseNow(jdbc).plusSeconds(1);
        seedExpiredClaim(jdbc, expiringScope, root.batchId(), expiringRequest, expiresAt);

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch inspectionStarted = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> holder = executor.submit(
                    () -> holdScopeLock(expiringScope, lockHeld, releaseLock));
            assertTrue(lockHeld.await(10, TimeUnit.SECONDS));
            Future<Inspection> inspection = executor.submit(() -> {
                inspectionStarted.countDown();
                return inspect(
                        workload(WORKER_LOGIN), expiringScope, "receive", expiringRequest);
            });
            assertTrue(inspectionStarted.await(10, TimeUnit.SECONDS));
            waitUntilAfter(jdbc, expiresAt);
            releaseLock.countDown();

            holder.get(20, TimeUnit.SECONDS);
            assertEquals("fresh", inspection.get(20, TimeUnit.SECONDS).disposition(),
                    "expiry must use clock_timestamp obtained after the lock wait");
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void danglingAndSelfPredecessorsAreRejectedWithoutOrphans() {
        ensureWorkloadLogins();
        JdbcTemplate jdbc = admin();
        JdbcTemplate worker = workload(WORKER_LOGIN);

        ReceiveSpec dangling = child(
                0x5000, SOURCE, bytes("dangling-predecessor-key"), 2, uuid(0x5003),
                uuid(0x50ff), BUSINESS_TIME.plusSeconds(51));
        ReceiveSpec self = child(
                0x5100, SOURCE, bytes("self-predecessor-key"), 2, uuid(0x5103),
                uuid(0x5102), BUSINESS_TIME.plusSeconds(52));

        assertAll(
                () -> assertRejectedWithoutOrphans(jdbc, worker, dangling, "dangling"),
                () -> assertRejectedWithoutOrphans(jdbc, worker, self, "self"));
    }

    @Test
    void correctionBindingsVersionsTimesAndHeadAreFailClosedWithoutOrphans() {
        ensureWorkloadLogins();
        JdbcTemplate jdbc = admin();
        JdbcTemplate worker = workload(WORKER_LOGIN);

        assertAll(
                () -> wrongSourceIsRejected(jdbc, worker),
                () -> wrongBusinessKeyIsRejected(jdbc, worker),
                () -> wrongLineageIsRejected(jdbc, worker),
                () -> nonIncreasingSourceVersionIsRejected(jdbc, worker),
                () -> regressingEffectiveTimeIsRejected(jdbc, worker),
                () -> nonHeadPredecessorIsRejected(jdbc, worker));
    }

    @Test
    void concurrentChildrenOfOnePredecessorHaveExactlyOneWinner() throws Exception {
        ensureWorkloadLogins();
        JdbcTemplate jdbc = admin();
        JdbcTemplate worker = workload(WORKER_LOGIN);
        byte[] key = bytes("concurrent-child-key");
        UUID lineage = uuid(0x7003);
        ReceiveSpec root = root(
                0x7000, SOURCE, key, 1, lineage,
                BUSINESS_TIME.plusSeconds(70), BUSINESS_TIME.plusSeconds(70));
        assertEquals(root.batchId(), receive(worker, root));
        ReceiveSpec first = child(
                0x7010, SOURCE, key, 2, lineage, root.batchId(),
                BUSINESS_TIME.plusSeconds(71));
        ReceiveSpec second = child(
                0x7020, SOURCE, key, 3, lineage, root.batchId(),
                BUSINESS_TIME.plusSeconds(72));

        List<Object> outcomes = runConcurrently(first, second);

        assertAll(
                () -> assertEquals(1, outcomes.stream().filter(UUID.class::isInstance).count()),
                () -> assertEquals(1, outcomes.stream()
                        .filter(DataAccessException.class::isInstance).count()),
                () -> assertEquals(1, jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_data_batch
                         where supersedes_batch_id=?
                        """, Integer.class, root.batchId())),
                () -> assertEquals(1, countAnyScope(
                        jdbc, first.scopeDigest(), second.scopeDigest())),
                () -> assertEquals(1, countAnyAudit(
                        jdbc, first.commandId(), second.commandId())));
    }

    @Test
    void concurrentRootsCannotReuseOneLineageAcrossDifferentBusinessKeys() throws Exception {
        ensureWorkloadLogins();
        JdbcTemplate jdbc = admin();
        UUID sharedLineage = uuid(0x8003);
        ReceiveSpec first = root(
                0x8010, SOURCE, bytes("concurrent-root-key-a"), 1, sharedLineage,
                BUSINESS_TIME.plusSeconds(80), BUSINESS_TIME.plusSeconds(80));
        ReceiveSpec second = root(
                0x8020, SOURCE, bytes("concurrent-root-key-b"), 1, sharedLineage,
                BUSINESS_TIME.plusSeconds(80), BUSINESS_TIME.plusSeconds(80));

        List<Object> outcomes = runConcurrently(first, second);

        assertAll(
                () -> assertEquals(1, outcomes.stream().filter(UUID.class::isInstance).count()),
                () -> assertEquals(1, outcomes.stream()
                        .filter(DataAccessException.class::isInstance).count()),
                () -> assertEquals(1, jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_data_batch
                         where lineage_id=? and supersedes_batch_id is null
                        """, Integer.class, sharedLineage)),
                () -> assertEquals(1, countAnyScope(
                        jdbc, first.scopeDigest(), second.scopeDigest())),
                () -> assertEquals(1, countAnyAudit(
                        jdbc, first.commandId(), second.commandId())));
    }

    private static void wrongSourceIsRejected(JdbcTemplate jdbc, JdbcTemplate worker) {
        byte[] key = bytes("wrong-source-key");
        UUID lineage = uuid(0x5203);
        ReceiveSpec root = root(
                0x5200, SOURCE, key, 1, lineage,
                BUSINESS_TIME.plusSeconds(100), BUSINESS_TIME.plusSeconds(100));
        receive(worker, root);
        assertRejectedWithoutOrphans(jdbc, worker, child(
                0x5210, OTHER_SOURCE, key, 2, lineage, root.batchId(),
                BUSINESS_TIME.plusSeconds(101)), "wrong source");
    }

    private static void wrongBusinessKeyIsRejected(JdbcTemplate jdbc, JdbcTemplate worker) {
        UUID lineage = uuid(0x5303);
        ReceiveSpec root = root(
                0x5300, SOURCE, bytes("right-business-key"), 1, lineage,
                BUSINESS_TIME.plusSeconds(110), BUSINESS_TIME.plusSeconds(110));
        receive(worker, root);
        assertRejectedWithoutOrphans(jdbc, worker, child(
                0x5310, SOURCE, bytes("wrong-business-key"), 2, lineage, root.batchId(),
                BUSINESS_TIME.plusSeconds(111)), "wrong business key");
    }

    private static void wrongLineageIsRejected(JdbcTemplate jdbc, JdbcTemplate worker) {
        byte[] key = bytes("wrong-lineage-key");
        UUID lineage = uuid(0x5403);
        ReceiveSpec root = root(
                0x5400, SOURCE, key, 1, lineage,
                BUSINESS_TIME.plusSeconds(120), BUSINESS_TIME.plusSeconds(120));
        receive(worker, root);
        assertRejectedWithoutOrphans(jdbc, worker, child(
                0x5410, SOURCE, key, 2, uuid(0x54ff), root.batchId(),
                BUSINESS_TIME.plusSeconds(121)), "wrong lineage");
    }

    private static void nonIncreasingSourceVersionIsRejected(
            JdbcTemplate jdbc, JdbcTemplate worker) {
        byte[] key = bytes("source-version-regression-key");
        UUID lineage = uuid(0x5503);
        ReceiveSpec root = root(
                0x5500, SOURCE, key, 5, lineage,
                BUSINESS_TIME.plusSeconds(130), BUSINESS_TIME.plusSeconds(130));
        receive(worker, root);
        assertRejectedWithoutOrphans(jdbc, worker, child(
                0x5510, SOURCE, key, 4, lineage, root.batchId(),
                BUSINESS_TIME.plusSeconds(131)), "non-increasing source version");
    }

    private static void regressingEffectiveTimeIsRejected(
            JdbcTemplate jdbc, JdbcTemplate worker) {
        byte[] key = bytes("effective-time-regression-key");
        UUID lineage = uuid(0x5603);
        Instant rootEffectiveAt = BUSINESS_TIME.plusSeconds(140);
        ReceiveSpec root = root(
                0x5600, SOURCE, key, 1, lineage, rootEffectiveAt, rootEffectiveAt);
        receive(worker, root);
        assertRejectedWithoutOrphans(jdbc, worker, child(
                0x5610, SOURCE, key, 2, lineage, root.batchId(),
                rootEffectiveAt.minusSeconds(1)), "effectiveAt regression");
    }

    private static void nonHeadPredecessorIsRejected(JdbcTemplate jdbc, JdbcTemplate worker) {
        byte[] key = bytes("non-head-predecessor-key");
        UUID lineage = uuid(0x5703);
        ReceiveSpec root = root(
                0x5700, SOURCE, key, 1, lineage,
                BUSINESS_TIME.plusSeconds(150), BUSINESS_TIME.plusSeconds(150));
        receive(worker, root);
        ReceiveSpec head = child(
                0x5710, SOURCE, key, 2, lineage, root.batchId(),
                BUSINESS_TIME.plusSeconds(151));
        assertEquals(head.batchId(), receive(worker, head));
        assertRejectedWithoutOrphans(jdbc, worker, child(
                0x5720, SOURCE, key, 3, lineage, root.batchId(),
                BUSINESS_TIME.plusSeconds(152)), "non-head predecessor");
    }

    private static void assertRejectedWithoutOrphans(
            JdbcTemplate jdbc, JdbcTemplate worker, ReceiveSpec spec, String caseName) {
        Footprint before = footprint(jdbc, spec);
        Attempt outcome = attempt(worker, spec);
        Footprint after = footprint(jdbc, spec);
        assertAll(caseName,
                () -> assertNotNull(outcome.failure(), caseName + " must fail closed"),
                () -> assertEquals(before, after,
                        caseName + " must leave no batch, idempotency, or audit orphan"));
    }

    private static Attempt attempt(JdbcTemplate worker, ReceiveSpec spec) {
        try {
            return new Attempt(receive(worker, spec), null);
        } catch (DataAccessException failure) {
            return new Attempt(null, failure);
        }
    }

    private static List<Object> runConcurrently(
            ReceiveSpec first, ReceiveSpec second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> firstResult = executor.submit(concurrentReceive(first, ready, start));
            Future<Object> secondResult = executor.submit(concurrentReceive(second, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "both database calls must be ready");
            start.countDown();
            return List.of(
                    firstResult.get(20, TimeUnit.SECONDS),
                    secondResult.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static Callable<Object> concurrentReceive(
            ReceiveSpec spec, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            JdbcTemplate worker = workload(WORKER_LOGIN);
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent receive start timed out");
            }
            Attempt outcome = attempt(worker, spec);
            return outcome.failure() == null ? outcome.batchId() : outcome.failure();
        };
    }

    private static UUID receive(JdbcTemplate worker, ReceiveSpec spec) {
        DataBatchPostgreSqlEvidenceFixtures.AuditEnvelope audit =
                DataBatchPostgreSqlEvidenceFixtures.acceptedAudit(
                        WORKER_LOGIN, spec.commandId(), spec.batchId(), "data-batch.receive",
                        1, spec.traceId(), spec.requestDigest(), spec.receivedAt());
        return worker.queryForObject("""
                select batch_id from ingestion_quality.iq_receive_data_batch(
                  ?::uuid, ?::uuid, ?::varchar, ?::bytea, ?::bigint, ?::uuid,
                  ?::uuid, ?::varchar, ?::timestamptz, ?::char(71),
                  ?::timestamptz, ?::char(32), ?::char(64), ?::char(71),
                  ?::jsonb, ?::char(64))
                """, UUID.class,
                spec.commandId(), spec.batchId(), spec.sourceId(), spec.businessKeyUtf8(),
                spec.sourceVersion(), spec.lineageId(), spec.supersedesBatchId(),
                spec.correctionReason(), Timestamp.from(spec.effectiveAt()),
                spec.manifestDigest(), Timestamp.from(spec.receivedAt()), spec.traceId(),
                spec.scopeDigest(), spec.requestDigest(), audit.payload(), audit.digest());
    }

    private static UUID receiveWithoutAutoCommit(
            ReceiveSpec spec,
            CountDownLatch readyToCommit,
            CountDownLatch allowCommit) throws Exception {
        try (Connection connection = dataSource(WORKER_LOGIN).getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcTemplate transactionalWorker = new JdbcTemplate(
                        new SingleConnectionDataSource(connection, true));
                UUID result = receive(transactionalWorker, spec);
                readyToCommit.countDown();
                if (!allowCommit.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("owner commit release timed out");
                }
                connection.commit();
                return result;
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static Void holdScopeLock(
            String scopeDigest,
            CountDownLatch lockHeld,
            CountDownLatch releaseLock) throws Exception {
        try (Connection connection = dataSource(WORKER_LOGIN).getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcTemplate transactionalWorker = new JdbcTemplate(
                        new SingleConnectionDataSource(connection, true));
                transactionalWorker.queryForObject("""
                        select pg_catalog.pg_advisory_xact_lock(
                          pg_catalog.hashtextextended(?::char(64), 1227901253))
                        """, (row, ignored) -> Boolean.TRUE, scopeDigest);
                lockHeld.countDown();
                if (!releaseLock.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("scope lock release timed out");
                }
                connection.commit();
                return null;
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static Inspection inspect(
            JdbcTemplate worker,
            String scopeDigest,
            String commandType,
            String requestDigest) {
        return worker.queryForObject("""
                select disposition, batch_id, response_status,
                       response_aggregate_version, completed_at, expires_at
                  from ingestion_quality.iq_inspect_batch_command_precedence(
                    ?::char(64), ?::varchar, ?::char(71))
                """, (row, ignored) -> new Inspection(
                        row.getString("disposition"),
                        row.getObject("batch_id", UUID.class),
                        row.getString("response_status"),
                        nullableLong(row, "response_aggregate_version"),
                        nullableInstant(row.getTimestamp("completed_at")),
                        nullableInstant(row.getTimestamp("expires_at"))),
                scopeDigest, commandType, requestDigest);
    }

    private static Long nullableLong(java.sql.ResultSet row, String column)
            throws java.sql.SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static Instant nullableInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static ReceiveSpec root(
            long stem,
            String sourceId,
            byte[] businessKey,
            long sourceVersion,
            UUID lineageId,
            Instant effectiveAt,
            Instant receivedAt) {
        return new ReceiveSpec(
                uuid(stem + 1), uuid(stem + 2), sourceId, businessKey, sourceVersion,
                lineageId, null, null, effectiveAt, digest("manifest:" + stem), receivedAt,
                trace(stem), rawDigest("scope:" + stem), requestDigest("request:" + stem));
    }

    private static ReceiveSpec child(
            long stem,
            String sourceId,
            byte[] businessKey,
            long sourceVersion,
            UUID lineageId,
            UUID predecessorId,
            Instant effectiveAt) {
        return new ReceiveSpec(
                uuid(stem + 1), uuid(stem + 2), sourceId, businessKey, sourceVersion,
                lineageId, predecessorId, "SOURCE_CORRECTION", effectiveAt,
                digest("manifest:" + stem), effectiveAt, trace(stem),
                rawDigest("scope:" + stem), requestDigest("request:" + stem));
    }

    private static ReceiveSpec copy(
            ReceiveSpec source,
            UUID commandId,
            UUID batchId,
            String scopeDigest,
            String requestDigest) {
        return new ReceiveSpec(
                commandId, batchId, source.sourceId(), source.businessKeyUtf8(),
                source.sourceVersion(), source.lineageId(), source.supersedesBatchId(),
                source.correctionReason(), source.effectiveAt(), source.manifestDigest(),
                source.receivedAt(), trace(commandId.getLeastSignificantBits()), scopeDigest,
                requestDigest);
    }

    private static void seedExpiredClaim(
            JdbcTemplate jdbc,
            String scopeDigest,
            UUID batchId,
            String requestDigest,
            Instant expiresAt) {
        jdbc.update("""
                insert into ingestion_quality.iq_batch_idempotency
                  (scope_digest, authenticated_actor, command_type, request_digest,
                   batch_id, status, response_status, response_aggregate_version,
                   claimed_at, completed_at, expires_at)
                values (?, ?, 'receive', ?, ?, 'completed', 'receiving', 1,
                        ?::timestamptz - interval '90 days',
                        ?::timestamptz - interval '89 days', ?::timestamptz)
                """, scopeDigest, WORKER_LOGIN, requestDigest, batchId,
                Timestamp.from(expiresAt), Timestamp.from(expiresAt),
                Timestamp.from(expiresAt));
    }

    private static ClaimTimes claimTimes(JdbcTemplate jdbc, String scopeDigest) {
        return jdbc.queryForObject("""
                select claimed_at, completed_at, expires_at
                  from ingestion_quality.iq_batch_idempotency
                 where scope_digest=?
                """, (row, ignored) -> new ClaimTimes(
                        row.getTimestamp("claimed_at").toInstant(),
                        row.getTimestamp("completed_at").toInstant(),
                        row.getTimestamp("expires_at").toInstant()), scopeDigest);
    }

    private static Footprint footprint(JdbcTemplate jdbc, ReceiveSpec spec) {
        return new Footprint(
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_data_batch where batch_id=?
                        """, Integer.class, spec.batchId()),
                countByScope(jdbc, spec.scopeDigest()),
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_local_audit_fact
                         where audit_id=?
                        """, Integer.class, spec.commandId()),
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_local_audit_outbox
                         where event_id=?
                        """, Integer.class, spec.commandId()));
    }

    private static int countByScope(JdbcTemplate jdbc, String scopeDigest) {
        return jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_batch_idempotency
                 where scope_digest=?
                """, Integer.class, scopeDigest);
    }

    private static int countAnyScope(JdbcTemplate jdbc, String first, String second) {
        return jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_batch_idempotency
                 where scope_digest in (?, ?)
                """, Integer.class, first, second);
    }

    private static int countAnyAudit(JdbcTemplate jdbc, UUID first, UUID second) {
        return jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_local_audit_fact
                 where audit_id in (?, ?)
                """, Integer.class, first, second);
    }

    private static Instant databaseNow(JdbcTemplate jdbc) {
        Timestamp value = jdbc.queryForObject(
                "select clock_timestamp()", Timestamp.class);
        assertNotNull(value);
        return value.toInstant();
    }

    private static void waitUntilAfter(JdbcTemplate jdbc, Instant boundary)
            throws InterruptedException {
        for (int attempt = 0; attempt < 240; attempt++) {
            if (databaseNow(jdbc).isAfter(boundary)) return;
            Thread.sleep(25);
        }
        throw new IllegalStateException("database expiry boundary was not reached");
    }

    private static void assertBetween(Instant actual, Instant lower, Instant upper) {
        assertTrue(!actual.isBefore(lower) && !actual.isAfter(upper),
                () -> actual + " not in [" + lower + ", " + upper + "]");
    }

    private static void ensureWorkloadLogins() {
        admin().execute("""
                do $roles$
                begin
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_batch_lineage_worker_login') then
                        create role scholarsense_iq_batch_lineage_worker_login login inherit;
                    end if;
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_batch_lineage_retention_login') then
                        create role scholarsense_iq_batch_lineage_retention_login login inherit;
                    end if;
                end
                $roles$;
                alter role scholarsense_iq_batch_lineage_worker_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_batch_lineage_retention_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                revoke scholarsense_ingestion_quality_online,
                       scholarsense_ingestion_quality_quality_worker,
                       scholarsense_ingestion_quality_relay,
                       scholarsense_ingestion_quality_retention_executor,
                       scholarsense_ingestion_quality_batch_owner
                    from scholarsense_iq_batch_lineage_worker_login,
                         scholarsense_iq_batch_lineage_retention_login;
                grant scholarsense_ingestion_quality_quality_worker
                    to scholarsense_iq_batch_lineage_worker_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_retention_executor
                    to scholarsense_iq_batch_lineage_retention_login
                    with inherit true, set false;
                """);
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
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " required");
        }
        return value;
    }

    private static Migration migration(Path path) {
        Matcher matcher = VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException("invalid migration " + path);
        }
        return new Migration(Integer.parseInt(matcher.group(1)), path);
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString("019fe540-0000-7000-8000-%012x".formatted(suffix));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String trace(long seed) {
        return sha256(bytes("trace:" + Long.toUnsignedString(seed))).substring(0, 32);
    }

    private static String digest(String material) {
        return "sha256:" + rawDigest(material);
    }

    private static String requestDigest(String material) {
        return digest("request-body:" + material);
    }

    private static String rawDigest(String material) {
        return sha256(bytes(material));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Migration(int version, Path path) {}

    private record ClaimTimes(Instant claimedAt, Instant completedAt, Instant expiresAt) {}

    private record Footprint(
            int batchRows, int idempotencyRows, int auditRows, int auditOutboxRows) {}

    private record Attempt(UUID batchId, DataAccessException failure) {}

    private record Inspection(
            String disposition,
            UUID batchId,
            String responseStatus,
            Long responseAggregateVersion,
            Instant completedAt,
            Instant expiresAt) {}

    private record ReceiveSpec(
            UUID commandId,
            UUID batchId,
            String sourceId,
            byte[] businessKeyUtf8,
            long sourceVersion,
            UUID lineageId,
            UUID supersedesBatchId,
            String correctionReason,
            Instant effectiveAt,
            String manifestDigest,
            Instant receivedAt,
            String traceId,
            String scopeDigest,
            String requestDigest) {}
}
