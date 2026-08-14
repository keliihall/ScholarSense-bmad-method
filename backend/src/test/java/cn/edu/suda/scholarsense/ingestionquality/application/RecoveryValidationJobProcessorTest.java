package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationCheckpoint;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationClaim;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationErrorCode;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationPhase;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryValidationJobProcessorTest {
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final UUID JOB = UUID.fromString(
            "019ff5a0-2000-7000-8000-000000000101");

    @Test
    void takeoverResumesAfterPersistedCheckpointAndFinalizesUnderTheNewFence() {
        FakeWork work = new FakeWork(candidate(7));
        FakeExternalWork external = new FakeExternalWork(
                RecoveryValidationExecution.checkpoint(checkpoint(8)),
                RecoveryValidationExecution.succeeded(result()));

        RecoveryValidationWorkerResult outcome = processor(work, external).runBatch();

        assertEquals(new RecoveryValidationWorkerResult(1, 1, 0, 0, 0, 1, 0, 0), outcome);
        assertEquals(List.of(
                "claim:120", "recheck:11", "checkpoint:11:7->8", "recheck:11",
                "complete:11:" + digest('f')),
                work.actions);
        assertEquals(List.of(7L, 8L), external.startingCheckpoints);
        assertEquals(20, work.batchSize);
    }

    @Test
    void staleCheckpointFenceStopsBeforeAnyLaterExternalCall() {
        FakeWork work = new FakeWork(candidate(0));
        work.acceptCheckpoint = false;
        FakeExternalWork external = new FakeExternalWork(
                RecoveryValidationExecution.checkpoint(checkpoint(1)),
                RecoveryValidationExecution.succeeded(result()));

        RecoveryValidationWorkerResult outcome = processor(work, external).runBatch();

        assertEquals(new RecoveryValidationWorkerResult(1, 0, 0, 0, 1, 0, 0, 0), outcome);
        assertEquals(1, external.calls);
        assertEquals(List.of("claim:120", "recheck:11", "checkpoint:11:0->1"), work.actions);
    }

    @Test
    void providerUnavailableAndCancellationUseControlledFencedFinalizers() {
        FakeWork unavailable = new FakeWork(candidate(0));
        RecoveryValidationWorkerResult failed = processor(unavailable,
                new FakeExternalWork(RecoveryValidationExecution.failed(
                        RecoveryValidationErrorCode.PROVIDER_NOT_INSTALLED))).runBatch();
        assertEquals(new RecoveryValidationWorkerResult(1, 0, 1, 0, 0, 0, 0, 0), failed);
        assertEquals(List.of("claim:120", "recheck:11", "fail:11:PROVIDER_NOT_INSTALLED"),
                unavailable.actions);

        FakeWork cancelled = new FakeWork(candidate(0));
        RecoveryValidationWorkerResult cancelResult = processor(cancelled,
                new FakeExternalWork(RecoveryValidationExecution.cancelled())).runBatch();
        assertEquals(new RecoveryValidationWorkerResult(1, 0, 0, 1, 0, 0, 0, 0), cancelResult);
        assertEquals(List.of("claim:120", "recheck:11", "cancel:11"), cancelled.actions);
    }

    @Test
    void dependencyExceptionBecomesControlledFailureAndLostClaimIsFenced() {
        FakeWork failedWork = new FakeWork(candidate(0));
        RecoveryValidationWorkerResult failure = processor(failedWork, request -> {
            throw new IllegalStateException("student row must never escape");
        }).runBatch();
        assertEquals(new RecoveryValidationWorkerResult(1, 0, 0, 0, 0, 0, 0, 1), failure);
        assertEquals(List.of("claim:120", "retry:11:DEPENDENCY_UNAVAILABLE:2"),
                failedWork.actions);

        FakeWork lostRace = new FakeWork(candidate(0));
        lostRace.loseClaim = true;
        RecoveryValidationWorkerResult fenced = processor(lostRace,
                new FakeExternalWork(RecoveryValidationExecution.succeeded(result()))).runBatch();
        assertEquals(new RecoveryValidationWorkerResult(0, 0, 0, 0, 1, 0, 0, 0), fenced);
    }

    @Test
    void resultReturningAfterLeaseExpiryIsDiscardedBeforeAnyFinalizer() {
        FakeWork work = new FakeWork(candidate(0));
        work.currentLease = false;

        RecoveryValidationWorkerResult outcome = processor(work,
                new FakeExternalWork(RecoveryValidationExecution.succeeded(result()))).runBatch();

        assertEquals(new RecoveryValidationWorkerResult(1, 0, 0, 0, 1, 0, 0, 0), outcome);
        assertEquals(List.of("claim:120", "recheck:11"), work.actions);
    }

    @Test
    void advancingButNonTerminatingProviderIsStoppedByThePerRunLoopGuard() {
        FakeWork work = new FakeWork(candidate(0));
        RecoveryValidationExternalWorkPort endless = request ->
                RecoveryValidationExecution.checkpoint(
                        checkpoint(request.checkpointVersion() + 1));

        RecoveryValidationWorkerResult outcome = processor(work, endless).runBatch();

        assertEquals(new RecoveryValidationWorkerResult(1, 0, 0, 0, 0,
                RecoveryValidationJobProcessor.MAX_CHECKPOINTS_PER_RUN, 1, 0), outcome);
        assertEquals(RecoveryValidationJobProcessor.MAX_CHECKPOINTS_PER_RUN * 2 + 2,
                work.actions.size());
        assertEquals("yield:11:1",
                work.actions.get(work.actions.size() - 1));
    }

    @Test
    void nonAdvancingCheckpointFailsWithoutLoopingAndNoCandidatesIsIdle() {
        FakeWork stalled = new FakeWork(candidate(3));
        RecoveryValidationWorkerResult outcome = processor(stalled,
                new FakeExternalWork(RecoveryValidationExecution.checkpoint(checkpoint(3))))
                .runBatch();
        assertEquals(new RecoveryValidationWorkerResult(1, 0, 1, 0, 0, 0, 0, 0), outcome);
        assertEquals(List.of("claim:120", "recheck:11", "fail:11:RETRY_EXHAUSTED"),
                stalled.actions);

        FakeWork idle = new FakeWork();
        assertEquals(RecoveryValidationWorkerResult.IDLE,
                processor(idle, new FakeExternalWork(
                        RecoveryValidationExecution.succeeded(result()))).runBatch());
    }

    private static RecoveryValidationJobProcessor processor(
            FakeWork work, RecoveryValidationExternalWorkPort external) {
        return new RecoveryValidationJobProcessor(
                work, external, () -> NOW, digest('b'));
    }

    private static RecoveryValidationCandidate candidate(long checkpointVersion) {
        return new RecoveryValidationCandidate(JOB, checkpointVersion);
    }

    private static RecoveryValidationCheckpoint checkpoint(long version) {
        return new RecoveryValidationCheckpoint(
                version, RecoveryValidationPhase.SAMPLE_RECOMPUTE, false,
                "resume:v1:" + "1".repeat(64),
                digest('c'), digest('d'), version * 10, 0);
    }

    private static RecoveryValidationResult result() {
        return new RecoveryValidationResult(
                UUID.fromString("019ff5a0-2000-7000-8000-000000000102"), JOB, 2,
                UUID.fromString("019ff5a0-2000-7000-8000-000000000103"),
                UUID.fromString("019ff5a0-2000-7000-8000-000000000104"),
                UUID.fromString("019ff5a0-2000-7000-8000-000000000105"),
                cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationJobStatus.SUCCEEDED,
                digest('1'), "QRP-1.0.0", digest('2'), digest('3'), digest('4'),
                digest('5'), 100, 100, 0, digest('6'),
                "QUALITY-RECOVERY-SAMPLE-SUMMARY-1.0.0", digest('7'), 100, 100,
                List.of(new RecoveryValidationResult.StratumSummary(
                        "ALL", 100, 100, 0, digest('8'))),
                digest('9'), digest('a'), digest('a'), 0,
                readiness(), true, null, NOW, digest('f'),
                "00112233445566778899aabbccddeeff");
    }

    private static cn.edu.suda.scholarsense.ingestionquality.domain
            .RecoveryValidationReadinessEvidence readiness() {
        return new cn.edu.suda.scholarsense.ingestionquality.domain
                .RecoveryValidationReadinessEvidence(
                1, 1, 1, "SRC-P0-CARD-001", "DEP-P0-CARD-001",
                digest('a'), digest('b'), digest('c'),
                "streaming", "CARD-SLICE-1.0.0", digest('1'), "1", digest('2'),
                List.of(new cn.edu.suda.scholarsense.ingestionquality.domain
                        .RecoveryValidationReadinessEvidence.EligibilityBinding(
                        UUID.fromString("019ff5a0-2000-7000-8000-000000000106"),
                        "ACC-SAFE-001", "1.0.0", 2)), digest('3'),
                new cn.edu.suda.scholarsense.ingestionquality.domain
                        .RecoveryValidationReadinessEvidence.QualityEvidence(
                        digest('4'), digest('5'), digest('6'), true,
                        "wm-source", "wm-dependency"),
                new cn.edu.suda.scholarsense.ingestionquality.domain
                        .RecoveryValidationReadinessEvidence.BatchEvidence(digest('7'), 3, 3),
                new cn.edu.suda.scholarsense.ingestionquality.domain
                        .RecoveryValidationReadinessEvidence.BackfillEvidence(
                        "wm-lkg", NOW, 90, "wm-start", "wm-complete", digest('8'),
                        "succeeded"),
                true, List.of(), 0, 1, 0, NOW, digest('9'));
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class FakeExternalWork implements RecoveryValidationExternalWorkPort {
        private final ArrayDeque<RecoveryValidationExecution> outcomes = new ArrayDeque<>();
        private final List<Long> startingCheckpoints = new ArrayList<>();
        private int calls;

        private FakeExternalWork(RecoveryValidationExecution... outcomes) {
            this.outcomes.addAll(List.of(outcomes));
        }

        @Override
        public RecoveryValidationExecution execute(RecoveryValidationExecutionRequest request) {
            calls++;
            startingCheckpoints.add(request.checkpointVersion());
            return outcomes.removeFirst();
        }
    }

    private static final class FakeWork implements RecoveryValidationWorkPort {
        private final List<RecoveryValidationCandidate> candidates;
        private final List<String> actions = new ArrayList<>();
        private boolean acceptCheckpoint = true;
        private boolean acceptFinalizer = true;
        private boolean currentLease = true;
        private boolean loseClaim;
        private int batchSize;

        private FakeWork(RecoveryValidationCandidate... candidates) {
            this.candidates = List.of(candidates);
        }

        @Override
        public List<RecoveryValidationCandidate> findClaimable(int limit, Instant trustedNow) {
            batchSize = limit;
            return candidates;
        }

        @Override
        public RecoveryValidationClaim claim(
                UUID jobId, String workerDigest, Instant trustedNow, Duration leaseDuration) {
            if (loseClaim) throw new IllegalStateException("claim lost");
            actions.add("claim:" + leaseDuration.toSeconds());
            return new RecoveryValidationClaim(
                    jobId, 2, 11, workerDigest, trustedNow,
                    trustedNow.plus(leaseDuration), candidates.getFirst().checkpointVersion(),
                    candidates.getFirst().checkpointVersion() == 0 ? null
                            : RecoveryValidationJobProcessorTest.checkpoint(
                                    candidates.getFirst().checkpointVersion()));
        }

        @Override
        public boolean isLeaseCurrent(UUID jobId, long leaseGeneration, Instant trustedNow) {
            actions.add("recheck:" + leaseGeneration);
            return currentLease;
        }

        @Override
        public boolean checkpoint(
                UUID jobId, long leaseGeneration, long expectedCheckpointVersion,
                RecoveryValidationCheckpoint checkpoint, Instant trustedNow) {
            actions.add("checkpoint:" + leaseGeneration + ":" + expectedCheckpointVersion
                    + "->" + checkpoint.checkpointVersion());
            return acceptCheckpoint;
        }

        @Override
        public boolean complete(
                UUID jobId, long leaseGeneration, RecoveryValidationResult result,
                Instant trustedNow) {
            actions.add("complete:" + leaseGeneration + ":" + result.resultDigest());
            return acceptFinalizer;
        }

        @Override
        public boolean fail(
                UUID jobId, long leaseGeneration, RecoveryValidationErrorCode errorCode,
                Instant trustedNow) {
            actions.add("fail:" + leaseGeneration + ":" + errorCode);
            return acceptFinalizer;
        }

        @Override
        public boolean retry(
                UUID jobId, long leaseGeneration, RecoveryValidationErrorCode errorCode,
                Instant nextAttemptAt, Instant trustedNow) {
            actions.add("retry:" + leaseGeneration + ":" + errorCode + ":"
                    + Duration.between(trustedNow, nextAttemptAt).toSeconds());
            return acceptFinalizer;
        }

        @Override
        public boolean yield(
                UUID jobId, long leaseGeneration, Instant nextAttemptAt, Instant trustedNow) {
            actions.add("yield:" + leaseGeneration + ":"
                    + Duration.between(trustedNow, nextAttemptAt).toSeconds());
            return acceptFinalizer;
        }

        @Override
        public boolean cancel(UUID jobId, long leaseGeneration, Instant trustedNow) {
            actions.add("cancel:" + leaseGeneration);
            return acceptFinalizer;
        }
    }
}
