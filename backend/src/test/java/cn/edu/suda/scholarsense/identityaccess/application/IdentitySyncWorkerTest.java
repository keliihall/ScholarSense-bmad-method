package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeAccount;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentitySyncWorkerTest {
    private static final Instant NOW = Instant.parse("2026-07-24T01:00:00Z");
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001", "identity-authority", "sandbox-0", "identity-org");
    private static final UUID JOB_ID =
            UUID.fromString("019c1234-0000-7000-8000-000000000401");

    @Test
    void dependencyFailureQueuesNextAttemptWithoutAdvancingLastSuccess() {
        var store = new FakeJobStore(job(IdentitySyncJobStatus.QUEUED, 0, 3));
        var audits = new ArrayList<IdentitySyncAuditEvent>();
        var worker = worker(
                store,
                (_key, _watermark, _traceId) -> {
                    throw new IllegalStateException("upstream unavailable");
                },
                audits);

        IdentitySyncWorkerRun run = worker.runNext("worker-a").orElseThrow();

        assertEquals(IdentitySyncJobStatus.QUEUED, run.status());
        assertEquals(1, run.attemptNo());
        assertEquals(41, store.saved.lastSuccessfulWatermark());
        assertEquals(IdentitySourceHealth.DEGRADED, store.saved.health());
        assertEquals(NOW.plusSeconds(30), store.saved.nextAttemptAt());
        assertEquals("IDENTITY_SOURCE_DEPENDENCY_UNAVAILABLE", store.saved.reasonCode());
        assertEquals("identity.sync.failed", audits.getFirst().action());
    }

    @Test
    void normalizedHttpTimeoutIoAndServerFailureCodeConsumesRetryBudget() {
        var store = new FakeJobStore(job(IdentitySyncJobStatus.QUEUED, 0, 3));
        var run = worker(
                store,
                (_key, _watermark, _traceId) -> {
                    throw new IdentitySyncException(
                            "IDENTITY_SOURCE_DEPENDENCY_UNAVAILABLE");
                },
                new ArrayList<>()).runNext("worker-a").orElseThrow();

        assertEquals(IdentitySyncJobStatus.QUEUED, run.status());
        assertEquals(1, run.attemptNo());
        assertEquals(NOW.plusSeconds(30), store.saved.nextAttemptAt());
    }

    @Test
    void retryBudgetExhaustionFailsAndSuccessfulRecoveryUsesNextAttemptNumber() {
        var exhausted = new FakeJobStore(job(IdentitySyncJobStatus.QUEUED, 2, 3));
        var failed = worker(
                exhausted,
                (_key, _watermark, _traceId) -> {
                    throw new IllegalStateException("still unavailable");
                },
                new ArrayList<>()).runNext("worker-a").orElseThrow();

        assertEquals(IdentitySyncJobStatus.FAILED, failed.status());
        assertEquals(3, failed.attemptNo());
        assertEquals(41, exhausted.saved.lastSuccessfulWatermark());

        var recovering = new FakeJobStore(job(IdentitySyncJobStatus.QUEUED, 1, 3));
        var recovered = worker(
                recovering,
                (_key, _watermark, _traceId) -> null,
                new ArrayList<>()).runNext("worker-b").orElseThrow();

        assertEquals(IdentitySyncJobStatus.SUCCEEDED, recovered.status());
        assertEquals(2, recovered.attemptNo());
        assertEquals(42, recovering.saved.lastSuccessfulWatermark());
        assertEquals(IdentitySourceHealth.HEALTHY, recovering.saved.health());
    }

    @Test
    void poisonBatchFailsWithoutRetryAndStaleClaimCannotStart() {
        var poisonStore = new FakeJobStore(job(IdentitySyncJobStatus.QUEUED, 0, 3));
        var poisoned = worker(
                poisonStore,
                (_key, _watermark, _traceId) -> {
                    throw new IdentitySyncException("IDENTITY_SOURCE_SIGNATURE_INVALID");
                },
                new ArrayList<>()).runNext("worker-a").orElseThrow();
        assertEquals(IdentitySyncJobStatus.FAILED, poisoned.status());

        var contended = new FakeJobStore(job(IdentitySyncJobStatus.QUEUED, 0, 3));
        contended.startable = false;
        assertTrue(worker(contended, (_key, _watermark, _traceId) -> null, new ArrayList<>())
                .runNext("worker-b").isEmpty());
    }

    @Test
    void poisonRejectedBeforeNormalizationIsPersistedAndAudited() {
        var store = new FakeJobStore(job(IdentitySyncJobStatus.QUEUED, 0, 3));
        var rejections = new ArrayList<IdentitySyncRejection>();
        var audits = new ArrayList<IdentitySyncAuditEvent>();
        var worker = new IdentitySyncWorker(
                store,
                (_key, _watermark, _traceId) -> {
                    throw new IdentitySourcePoisonException(
                            "IDENTITY_ROLE_UNKNOWN",
                            UUID.fromString("019c1234-0000-7000-8000-000000000406"),
                            42,
                            42,
                            "a".repeat(64));
                },
                (batch, lease, afterApply) -> {
                    throw new AssertionError("poison must never reach processor");
                },
                audits::add,
                ignored -> {},
                directTransaction(),
                IdentitySyncWorkerTest::trustedNow,
                (key, fromInclusive, toInclusive, traceId) -> {},
                rejections::add);

        IdentitySyncWorkerRun run = worker.runNext("worker-poison").orElseThrow();

        assertEquals(IdentitySyncJobStatus.FAILED, run.status());
        assertEquals(1, rejections.size());
        assertEquals("IDENTITY_ROLE_UNKNOWN", rejections.getFirst().reasonCode());
        assertEquals(
                List.of("identity.sync.rejected", "identity.sync.failed"),
                audits.stream().map(IdentitySyncAuditEvent::action).toList());
    }

    @Test
    void signedHeartbeatSucceedsWithoutInvokingProjectionProcessorOrAdvancingWatermark() {
        var store = new FakeJobStore(job(IdentitySyncJobStatus.QUEUED, 0, 3));
        var processed = new java.util.concurrent.atomic.AtomicInteger();
        var worker = new IdentitySyncWorker(
                store,
                (_key, _watermark, _traceId) -> heartbeat(),
                (batch, lease, afterApply) -> {
                    processed.incrementAndGet();
                    throw new AssertionError("heartbeat must not reach the projection processor");
                },
                ignored -> {},
                ignored -> {},
                directTransaction(),
                IdentitySyncWorkerTest::trustedNow);

        IdentitySyncWorkerRun run = worker.runNext("worker-a").orElseThrow();

        assertEquals(IdentitySyncJobStatus.SUCCEEDED, run.status());
        assertEquals(41, run.lastSuccessfulWatermark());
        assertEquals(IdentityProjectionFreshness.FRESH, store.saved.freshness());
        assertEquals(0, processed.get());
    }

    @Test
    void pendingGapIsFetchedFromTheExactUpstreamRangeBeforeNormalPolling() {
        var store = new FakeJobStore(job(IdentitySyncJobStatus.QUEUED, 0, 3));
        var normalCalls = new java.util.concurrent.atomic.AtomicInteger();
        var ranges = new ArrayList<String>();
        IdentityAuthoritySourcePort source = new IdentityAuthoritySourcePort() {
            @Override
            public NormalizedIdentityBatch fetch(
                    CheckpointKey key, long afterWatermark, String traceId) {
                normalCalls.incrementAndGet();
                throw new AssertionError("pending replay must take precedence");
            }

            @Override
            public NormalizedIdentityBatch fetchRange(
                    CheckpointKey key,
                    long fromInclusive,
                    long toInclusive,
                    String traceId) {
                ranges.add(fromInclusive + ":" + toInclusive);
                return changeBatch(fromInclusive - 1, toInclusive);
            }
        };
        IdentityReplayPort replay = new IdentityReplayPort() {
            @Override
            public void request(
                    CheckpointKey key, long fromInclusive, long toInclusive, String traceId) {}

            @Override
            public Optional<IdentityReplayRange> nextRequested(CheckpointKey key) {
                return Optional.of(new IdentityReplayRange(42, 44));
            }
        };
        var worker = new IdentitySyncWorker(
                store,
                source,
                (batch, lease, afterApply) -> {
                    assertEquals(41, batch.fromWatermark());
                    assertEquals(44, batch.toWatermark());
                    var result = new IdentitySyncResult(
                            IdentitySyncOutcome.APPLIED,
                            "IDENTITY_SYNC_APPLIED",
                            44,
                            1,
                            1);
                    afterApply.accept(result);
                    return result;
                },
                ignored -> {},
                ignored -> {},
                directTransaction(),
                IdentitySyncWorkerTest::trustedNow,
                replay);

        IdentitySyncWorkerRun run = worker.runNext("worker-replay").orElseThrow();

        assertEquals(List.of("42:44"), ranges);
        assertEquals(0, normalCalls.get());
        assertEquals(44, run.lastSuccessfulWatermark());
    }

    @Test
    void jobStateMachineAllowsOnlyPublishedTransitionsAndKeepsDegradedSeparate() {
        IdentitySyncJob queued = job(IdentitySyncJobStatus.QUEUED, 0, 3);
        IdentitySyncJob running = queued.transitionTo(
                IdentitySyncJobStatus.RUNNING, IdentitySourceHealth.DEGRADED,
                IdentityProjectionFreshness.STALE, null, null, null, 0);
        assertEquals(IdentitySyncJobStatus.RUNNING, running.status());
        assertThrows(IllegalStateException.class, () -> queued.transitionTo(
                IdentitySyncJobStatus.SUCCEEDED, IdentitySourceHealth.HEALTHY,
                IdentityProjectionFreshness.FRESH, NOW, null, null, 42));
        IdentitySyncJob succeeded = running.transitionTo(
                IdentitySyncJobStatus.SUCCEEDED, IdentitySourceHealth.HEALTHY,
                IdentityProjectionFreshness.FRESH, NOW, null, null, 42);
        assertThrows(IllegalStateException.class, () -> succeeded.transitionTo(
                IdentitySyncJobStatus.RUNNING, IdentitySourceHealth.HEALTHY,
                IdentityProjectionFreshness.FRESH, null, null, null, 42));
    }

    private static IdentitySyncWorker worker(
            FakeJobStore store,
            IdentityAuthoritySourcePort source,
            List<IdentitySyncAuditEvent> audits) {
        return new IdentitySyncWorker(
                store,
                source,
                (batch, lease, afterApply) -> {
                    var result = new IdentitySyncResult(
                            IdentitySyncOutcome.APPLIED,
                            "IDENTITY_SYNC_APPLIED",
                            42,
                            12,
                            4);
                    afterApply.accept(result);
                    return result;
                },
                audits::add,
                observation -> {},
                directTransaction(),
                IdentitySyncWorkerTest::trustedNow);
    }

    private static IdentitySyncTransactionPort directTransaction() {
        return new IdentitySyncTransactionPort() {
            @Override
            public <T> T execute(java.util.function.Supplier<T> work) {
                return work.get();
            }
        };
    }

    private static IdentitySyncJob job(
            IdentitySyncJobStatus status, int lastAttemptNo, int retryBudget) {
        return new IdentitySyncJob(
                JOB_ID,
                KEY,
                status,
                IdentitySourceHealth.HEALTHY,
                IdentityProjectionFreshness.FRESH,
                NOW.minusSeconds(60),
                null,
                41,
                null,
                retryBudget,
                lastAttemptNo,
                null,
                "0123456789abcdef0123456789abcdef");
    }

    private static NormalizedIdentityBatch heartbeat() {
        return new NormalizedIdentityBatch(
                UUID.fromString("019c1234-0000-7000-8000-000000000402"),
                KEY,
                "IDENTITY-AUTHORITY-BATCH-1.0.0",
                41,
                41,
                41,
                NOW.minusSeconds(60),
                NOW,
                "0123456789abcdef0123456789abcdef",
                "IDENTITY-ROLE-MAPPING-1.0.0",
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                true,
                new byte[] {1},
                new byte[] {2},
                new byte[] {3},
                "config://test/identity-authority-inbox",
                "k1",
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static NormalizedIdentityBatch changeBatch(long fromWatermark, long toWatermark) {
        var account = new AuthoritativeAccount(
                UUID.fromString("019c1234-0000-7000-8000-000000000403"),
                KEY.sourceId(),
                "d".repeat(64),
                "actor_v1_k1_" + "e".repeat(64),
                AuthoritativeStatus.ACTIVE,
                new EffectiveInterval(NOW.minusSeconds(60), null),
                toWatermark,
                1);
        var fact = new IdentitySourceFact(
                UUID.fromString("019c1234-0000-7000-8000-000000000404"),
                IdentityRecordKind.ACCOUNT,
                account.externalRefDigest(),
                account.sourceVersion(),
                account.effectiveInterval(),
                "f".repeat(64),
                1);
        return new NormalizedIdentityBatch(
                UUID.fromString("019c1234-0000-7000-8000-000000000405"),
                KEY,
                "IDENTITY-AUTHORITY-BATCH-1.0.0",
                toWatermark,
                fromWatermark,
                toWatermark,
                NOW.minusSeconds(60),
                NOW,
                "0123456789abcdef0123456789abcdef",
                "IDENTITY-ROLE-MAPPING-1.0.0",
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                true,
                new byte[] {1},
                new byte[] {2},
                new byte[] {3},
                "config://test/identity-authority-inbox",
                "k1",
                List.of(account),
                List.of(),
                List.of(),
                List.of(fact));
    }

    private static TrustedTime trustedNow() {
        return new TrustedTime(
                NOW,
                new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(50),
                        "evidence://signed/clock/campus-ntp-a.json"));
    }

    private static final class FakeJobStore implements IdentitySyncJobPort {
        private final IdentitySyncJob due;
        private IdentitySyncJob saved;
        private boolean startable = true;

        private FakeJobStore(IdentitySyncJob due) {
            this.due = due;
        }

        @Override
        public void enqueue(IdentitySyncJob job) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean enqueueIfEligible(IdentitySyncJob job) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean resolveFailureAndEnqueue(
                IdentitySyncFailureResolution resolution,
                IdentitySyncJob replacement) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long lastSuccessfulWatermark(CheckpointKey key) {
            return due.lastSuccessfulWatermark();
        }

        @Override
        public boolean hasPending(CheckpointKey key) {
            return true;
        }

        @Override
        public Optional<IdentitySyncJob> nextDue(Instant now) {
            return Optional.of(due);
        }

        @Override
        public Optional<RunningIdentitySyncAttempt> start(
                UUID jobId, String leaseOwner, Instant now) {
            if (!startable) {
                return Optional.empty();
            }
            IdentitySyncJob running = due.transitionTo(
                    IdentitySyncJobStatus.RUNNING,
                    due.health(),
                    due.freshness(),
                    null,
                    null,
                    null,
                    due.lastSuccessfulWatermark());
            return Optional.of(new RunningIdentitySyncAttempt(
                    running,
                    due.lastAttemptNo() + 1,
                    new IdentityLease(
                            KEY, JOB_ID, due.lastAttemptNo() + 1,
                            due.lastAttemptNo() + 10L,
                            leaseOwner, now, now.plus(Duration.ofMinutes(2))),
                    due.lastSuccessfulWatermark(),
                    now));
        }

        @Override
        public void save(
                RunningIdentitySyncAttempt attempt,
                IdentitySyncJob completed,
                Instant now) {
            saved = completed;
        }
    }
}
