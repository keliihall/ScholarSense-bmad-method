package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAggregateType;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationSnapshot;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationState;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationChangeKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationDependencyWatermark;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationJob;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationJobKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationJobState;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageHead;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReason;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationRetention;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSourceVector;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSubjectSnapshot;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AccessInvalidationWorkersTest {
    private static final Instant NOW =
            Instant.parse("2026-07-31T12:00:00Z");
    private static final String TRACE =
            "0123456789abcdef0123456789abcdef";
    private static final AccessInvalidationLineageId SCOPE =
            new AccessInvalidationLineageId(
                    "lin_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    private static final AccessInvalidationLineageId CAUSE =
            new AccessInvalidationLineageId(
                    "lin_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");

    @Test
    void expiryAppendsSuccessorAtBoundaryAndStaleJobIsNoOp() {
        var store = new FakeStore();
        store.appended.add(scopeFact(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000701"),
                null,
                1,
                AccessInvalidationChangeKind.CORRECTED,
                AccessInvalidationReason.SOURCE_CORRECTION));
        var jobs = new FakeJobs(expiryLease(), true);
        var worker = new AccessInvalidationExpiryWorker(
                jobs,
                store,
                AccessInvalidationWorkersTest::encode,
                ids(),
                directTransactions());

        assertEquals(1, worker.run("expiry-a", 10, NOW));
        assertEquals(2, store.appended.size());
        assertEquals(
                AccessInvalidationChangeKind.EXPIRED,
                store.appended.getLast().changeKind());
        assertEquals(
                expiryLease().job().dueAt(),
                store.appended.getLast().effectiveAt());
        assertTrue(jobs.completed);

        var staleJobs = new FakeJobs(expiryLease(), false);
        int before = store.appended.size();
        new AccessInvalidationExpiryWorker(
                        staleJobs,
                        store,
                        AccessInvalidationWorkersTest::encode,
                        ids(),
                        directTransactions())
                .run("expiry-b", 10, NOW);
        assertEquals(before, store.appended.size());
        assertTrue(staleJobs.completed);
    }

    @Test
    void impactFanOutCommitsBoundedPageAndCursorTogether() {
        var store = new FakeStore();
        AccessInvalidationFact cause = causeFact();
        store.appended.add(cause);
        var jobs = new FakeJobs(impactLease(cause.eventId()), true);
        var worker = new AccessInvalidationImpactWorker(
                jobs,
                store,
                (ignored, batch, sequence, afterLineageId) -> {
                    assertEquals(0, sequence);
                    assertEquals(null, afterLineageId);
                    return List.of(scopeFact(
                            UUID.fromString(
                                    "019c0000-0000-7000-8000-000000000711"),
                            null,
                            1,
                            AccessInvalidationChangeKind.INVALIDATED,
                            AccessInvalidationReason.ACCOUNT_DISABLED));
                },
                AccessInvalidationWorkersTest::encode,
                ids(),
                directTransactions());

        assertEquals(1, worker.run("impact-a", 10, 100, NOW));
        assertEquals(2, store.appended.size());
        assertEquals(1, jobs.cursor);
        assertEquals(SCOPE.value(), jobs.cursorKey);
        assertTrue(jobs.completed);
        assertFalse(jobs.failed);
    }

    @Test
    void staleImpactCauseCompletesWithoutAppendingFanOut() {
        var store = new FakeStore();
        AccessInvalidationFact cause = causeFact();
        store.appended.add(cause);
        var jobs = new FakeJobs(impactLease(cause.eventId()), true);
        jobs.currentImpact = false;
        var resolverCalls = new AtomicInteger();

        int claimed = new AccessInvalidationImpactWorker(
                        jobs,
                        store,
                        (ignored, batch, sequence, afterLineageId) -> {
                            resolverCalls.incrementAndGet();
                            return List.of();
                        },
                        AccessInvalidationWorkersTest::encode,
                        ids(),
                        directTransactions())
                .run("impact-stale", 10, 100, NOW);

        assertEquals(1, claimed);
        assertEquals(0, resolverCalls.get());
        assertEquals(1, store.appended.size());
        assertTrue(jobs.completed);
    }

    @Test
    void reclaimedFailureUpdateDoesNotAbortRemainingClaims() {
        var store = new FakeStore();
        AccessInvalidationFact cause = causeFact();
        store.appended.add(cause);
        var lease = impactLease(cause.eventId());
        var jobs = new FakeJobs(List.of(lease, lease), true);
        jobs.throwOnFirstFailureUpdate = true;
        var resolverCalls = new AtomicInteger();

        int claimed = new AccessInvalidationImpactWorker(
                        jobs,
                        store,
                        (ignored, batch, sequence, afterLineageId) -> {
                            if (resolverCalls.incrementAndGet() == 1) {
                                throw new IllegalStateException("reclaimed");
                            }
                            return List.of();
                        },
                        AccessInvalidationWorkersTest::encode,
                        ids(),
                        directTransactions())
                .run("impact-reclaimed", 10, 100, NOW);

        assertEquals(2, claimed);
        assertEquals(2, resolverCalls.get());
        assertEquals(1, jobs.checkpointCount);
    }

    private static AccessInvalidationJobLease expiryLease() {
        var job = new AccessInvalidationJob(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000721"),
                AccessInvalidationJobKind.EXPIRY,
                SCOPE,
                AccessInvalidationJobState.RUNNING,
                NOW.minusSeconds(1),
                "expiry-a",
                1,
                1,
                TRACE,
                NOW.minusSeconds(1));
        return new AccessInvalidationJobLease(
                job,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000701"),
                1,
                NOW.plus(365, ChronoUnit.DAYS));
    }

    private static AccessInvalidationJobLease impactLease(
            UUID causeEventId) {
        var job = new AccessInvalidationJob(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000722"),
                AccessInvalidationJobKind.IMPACT,
                CAUSE,
                AccessInvalidationJobState.RUNNING,
                NOW.minusSeconds(1),
                "impact-a",
                1,
                0,
                TRACE,
                NOW.minusSeconds(1));
        return new AccessInvalidationJobLease(
                job,
                causeEventId,
                1,
                NOW.plus(365, ChronoUnit.DAYS));
    }

    private static AccessInvalidationFact causeFact() {
        return new AccessInvalidationFact(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000710"),
                TRACE,
                AccessInvalidationChangeKind.INVALIDATED,
                AccessInvalidationReason.ACCOUNT_DISABLED,
                CAUSE,
                null,
                null,
                AccessInvalidationAggregateType.IDENTITY_CAUSE,
                "cause_" + "b".repeat(40),
                1,
                1,
                NOW.minusSeconds(60),
                sourceVector(),
                snapshot(),
                invalidated(),
                retention(),
                "a".repeat(64));
    }

    private static AccessInvalidationFact scopeFact(
            UUID eventId,
            UUID supersedes,
            long version,
            AccessInvalidationChangeKind kind,
            AccessInvalidationReason reason) {
        return new AccessInvalidationFact(
                eventId,
                TRACE,
                kind,
                reason,
                SCOPE,
                supersedes,
                kind == AccessInvalidationChangeKind.INVALIDATED
                        && reason == AccessInvalidationReason.ACCOUNT_DISABLED
                        ? UUID.fromString(
                                "019c0000-0000-7000-8000-000000000710")
                        : null,
                AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE,
                SCOPE.value(),
                version,
                version,
                NOW.minusSeconds(1),
                sourceVector(),
                snapshot(),
                invalidated(),
                retention(),
                "a".repeat(64));
    }

    private static AccessInvalidationSourceVector sourceVector() {
        return new AccessInvalidationSourceVector(
                "SRC-P0-RESPONSIBILITY-001",
                7,
                7,
                List.of(new AccessInvalidationDependencyWatermark(
                        "responsibility-authority",
                        "sandbox-0",
                        7)));
    }

    private static AccessInvalidationSubjectSnapshot snapshot() {
        return new AccessInvalidationSubjectSnapshot(
                "subtok_" + "a".repeat(40),
                "scptok_" + "b".repeat(40),
                "c".repeat(64),
                "ACCESS-INVALIDATION-TOKENIZATION-1.0.0");
    }

    private static AccessInvalidationAuthorizationSnapshot invalidated() {
        return new AccessInvalidationAuthorizationSnapshot(
                AccessInvalidationAuthorizationState.INVALIDATED,
                true,
                true,
                true,
                false,
                "RFP-1.0.0");
    }

    private static AccessInvalidationRetention retention() {
        return new AccessInvalidationRetention(
                "restricted",
                "RS-1.0.0",
                NOW.plus(365, ChronoUnit.DAYS),
                false);
    }

    private static String encode(AccessInvalidationFact fact) {
        return """
                {"id":"%s","data":{"eventId":"%s"}}
                """.formatted(fact.eventId(), fact.eventId());
    }

    private static AccessInvalidationIdPort ids() {
        AtomicInteger sequence = new AtomicInteger(730);
        return ignored -> UUID.fromString(
                "019c0000-0000-7000-8000-000000000"
                        + sequence.incrementAndGet());
    }

    private static IdentitySyncTransactionPort directTransactions() {
        return new IdentitySyncTransactionPort() {
            @Override
            public <T> T execute(
                    java.util.function.Supplier<T> work) {
                return work.get();
            }
        };
    }

    private static final class FakeJobs
            implements AccessInvalidationJobStorePort {
        private final AccessInvalidationJobLease lease;
        private final List<AccessInvalidationJobLease> leases;
        private final boolean currentExpiry;
        private boolean currentImpact = true;
        private boolean completed;
        private boolean failed;
        private boolean throwOnFirstFailureUpdate;
        private int failureUpdates;
        private int checkpointCount;
        private long cursor;
        private String cursorKey;

        private FakeJobs(
                AccessInvalidationJobLease lease,
                boolean currentExpiry) {
            this.lease = lease;
            this.leases = List.of(lease);
            this.currentExpiry = currentExpiry;
        }

        private FakeJobs(
                List<AccessInvalidationJobLease> leases,
                boolean currentExpiry) {
            this.lease = leases.getFirst();
            this.leases = List.copyOf(leases);
            this.currentExpiry = currentExpiry;
        }

        @Override
        public List<AccessInvalidationJobLease> claim(
                AccessInvalidationJobKind kind,
                String leaseOwner,
                Instant now,
                int batchSize) {
            return leases;
        }

        @Override
        public boolean isCurrentExpiry(
                AccessInvalidationJobLease ignored) {
            return currentExpiry;
        }

        @Override
        public boolean isCurrentImpact(
                AccessInvalidationJobLease ignored) {
            return currentImpact;
        }

        @Override
        public void checkpoint(
                AccessInvalidationJobLease ignored,
                long nextCursor,
                boolean completed,
                Instant updatedAt) {
            this.cursor = nextCursor;
            this.completed = completed;
            checkpointCount++;
        }

        @Override
        public void checkpoint(
                AccessInvalidationJobLease ignored,
                long nextCursor,
                String nextCursorKey,
                boolean completed,
                Instant updatedAt) {
            this.cursor = nextCursor;
            this.cursorKey = nextCursorKey;
            this.completed = completed;
            checkpointCount++;
        }

        @Override
        public void failed(
                AccessInvalidationJobLease ignored,
                String reasonCode,
                Instant failedAt,
                Instant nextAttemptAt,
                boolean quarantine) {
            failed = true;
            failureUpdates++;
            if (throwOnFirstFailureUpdate && failureUpdates == 1) {
                throw new IllegalStateException(
                        "ACCESS_INVALIDATION_JOB_FENCED");
            }
        }
    }

    private static final class FakeStore
            implements AccessInvalidationStorePort {
        private final List<AccessInvalidationFact> appended =
                new ArrayList<>();

        @Override
        public AccessInvalidationFact append(
                AccessInvalidationAppendCommand command) {
            appended.add(command.fact());
            return command.fact();
        }

        @Override
        public Optional<AccessInvalidationLineageHead> head(
                String lineageId) {
            return latest(lineageId).map(fact ->
                    new AccessInvalidationLineageHead(
                            fact.lineageId(),
                            fact.eventId(),
                            fact.aggregateVersion()));
        }

        @Override
        public Optional<AccessInvalidationFact> find(UUID eventId) {
            return appended.stream()
                    .filter(fact -> fact.eventId().equals(eventId))
                    .findFirst();
        }

        @Override
        public Optional<AccessInvalidationFact> latest(
                String lineageId) {
            return appended.stream()
                    .filter(fact -> fact.lineageId().value()
                            .equals(lineageId))
                    .reduce((first, second) -> second);
        }

        @Override
        public long fencingToken(String lineageId) {
            return head(lineageId)
                    .map(AccessInvalidationLineageHead::aggregateVersion)
                    .orElse(0L);
        }
    }
}
