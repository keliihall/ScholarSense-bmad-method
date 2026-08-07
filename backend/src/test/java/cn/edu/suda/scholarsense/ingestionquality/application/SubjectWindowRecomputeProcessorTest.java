package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubjectWindowRecomputeProcessorTest {
    private static final Instant NOW = Instant.parse("2026-08-06T08:00:00Z");
    private static final UUID JOB = UUID.fromString("019fcfea-6900-7000-8000-000000000001");
    private static final UUID EVENT = UUID.fromString("019fcfea-6900-7000-8000-000000000002");

    @Test
    void executesCheckpointAndPublicationUnderTheClaimedFence() {
        FakeWork work = new FakeWork(false);
        var result = new SubjectWindowRecomputeProcessor(
                work, () -> EVENT, Clock.fixed(NOW, ZoneOffset.UTC)).runBatch();

        assertEquals(new SubjectWindowRecomputeWorkerResult(1, 1, 0, 0), result);
        assertEquals(List.of("claim:60", "checkpoint:7:1", "complete:7:" + EVENT),
                work.actions);
        assertEquals(100, work.batchSize);
    }

    @Test
    void controlledExecutionFailureMovesTheOwnedJobToFailed() {
        FakeWork work = new FakeWork(true);
        var result = new SubjectWindowRecomputeProcessor(
                work, () -> EVENT, Clock.fixed(NOW, ZoneOffset.UTC)).runBatch();

        assertEquals(new SubjectWindowRecomputeWorkerResult(1, 0, 1, 0), result);
        assertEquals(List.of(
                "claim:60", "checkpoint:7:1",
                "fail:7:INGESTION_QUALITY_RECOMPUTE_EXECUTION_FAILED"), work.actions);
    }

    @Test
    void leaseTakeoverContinuesAfterThePersistedCheckpoint() {
        FakeWork work = new FakeWork(false, 9);
        var result = new SubjectWindowRecomputeProcessor(
                work, () -> EVENT, Clock.fixed(NOW, ZoneOffset.UTC)).runBatch();

        assertEquals(new SubjectWindowRecomputeWorkerResult(1, 1, 0, 0), result);
        assertEquals(List.of("claim:60", "checkpoint:7:10", "complete:7:" + EVENT),
                work.actions);
    }

    private static final class FakeWork implements SubjectWindowRecomputeWorkPort {
        private final boolean failCompletion;
        private final long checkpoint;
        private final List<String> actions = new ArrayList<>();
        private int batchSize;

        private FakeWork(boolean failCompletion) { this(failCompletion, 0); }
        private FakeWork(boolean failCompletion, long checkpoint) {
            this.failCompletion = failCompletion;
            this.checkpoint = checkpoint;
        }

        @Override public List<SubjectWindowRecomputeCandidate> findClaimable(
                int requestedBatchSize, Instant now) {
            batchSize = requestedBatchSize;
            return List.of(new SubjectWindowRecomputeCandidate(JOB, checkpoint));
        }
        @Override public long claim(UUID jobId, String workerId, Instant now, Duration lease) {
            actions.add("claim:" + lease.toSeconds()); return 7;
        }
        @Override public boolean checkpoint(UUID jobId, long fence, long sequence, Instant now) {
            actions.add("checkpoint:" + fence + ":" + sequence); return true;
        }
        @Override public MappingRecomputeCompletion complete(
                UUID jobId, long fence, Instant now, UUID eventId) {
            if (failCompletion) throw new IllegalStateException("compute failed");
            actions.add("complete:" + fence + ":" + eventId);
            return new MappingRecomputeCompletion("RECOMPUTED", true, true);
        }
        @Override public boolean fail(
                UUID jobId, long fence, Instant now, String controlledCode) {
            actions.add("fail:" + fence + ":" + controlledCode); return true;
        }
    }
}
