package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.shared.observability.ObservationPort;
import cn.edu.suda.scholarsense.shared.observability.SafeObservationAttributes;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContext;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContextCodec;
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
    private static final String TRACE = "1234567890abcdef1234567890abcdef";
    private static final String PERSISTED_PARENT =
            "00-" + TRACE + "-abcdef0123456789-01";

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

    @Test
    void durableAttemptAndChildrenRestoreThePersistedTraceWithoutChangingFenceOrder() {
        FakeWork work = new FakeWork(false);
        RecordingObservations observations = new RecordingObservations();

        var result = new SubjectWindowRecomputeProcessor(
                work, () -> EVENT, Clock.fixed(NOW, ZoneOffset.UTC), observations,
                new W3cTraceContextCodec()).runBatch();

        assertEquals(new SubjectWindowRecomputeWorkerResult(1, 1, 0, 0), result);
        assertEquals(List.of("job.attempt", "batch.evaluate", "job.finalize", "outbox.publish"),
                observations.operations);
        assertEquals(ObservationPort.ObservationKind.PRODUCER,
                observations.kinds.getLast());
        assertTrue(observations.outcomes.contains("success"));
        assertTrue(observations.parents.stream().allMatch(parent ->
                TRACE.equals(parent.traceId())));
        assertEquals(observations.contexts.get(0), observations.parents.get(1));
        assertEquals(observations.contexts.get(0), observations.parents.get(2));
        assertEquals(observations.contexts.get(2), observations.parents.get(3));
        assertEquals("abcdef0123456789", observations.parents.getFirst().spanId());
        assertTrue(observations.parents.getFirst().sampled());
        assertEquals(List.of("claim:60", "checkpoint:7:1", "complete:7:" + EVENT),
                work.actions);
    }

    @Test
    void legacyJobWithoutTraceparentStartsACleanRootInsteadOfInventingAParentInTheOldTrace() {
        FakeWork work = new FakeWork(false, 0, null);
        RecordingObservations observations = new RecordingObservations();

        new SubjectWindowRecomputeProcessor(
                work, () -> EVENT, Clock.fixed(NOW, ZoneOffset.UTC), observations,
                new W3cTraceContextCodec()).runBatch();

        assertTrue(!TRACE.equals(observations.parents.getFirst().traceId()));
    }

    @Test
    void checkpointFailureMarksTheEvaluationChildBeforeTheAttemptFails() {
        FakeWork work = new FakeWork(false, true);
        RecordingObservations observations = new RecordingObservations();

        var result = new SubjectWindowRecomputeProcessor(
                work, () -> EVENT, Clock.fixed(NOW, ZoneOffset.UTC), observations,
                new W3cTraceContextCodec()).runBatch();

        assertEquals(new SubjectWindowRecomputeWorkerResult(1, 0, 1, 0), result);
        assertTrue(observations.operationOutcomes.contains("batch.evaluate:failure"));
        assertTrue(observations.operationOutcomes.contains("job.attempt:failure"));
        assertTrue(observations.errorOperations.contains("batch.evaluate"));
        assertTrue(observations.errorOperations.contains("job.attempt"));
    }

    @Test
    void completionFailureMarksFinalizationAndPublicationChildrenBeforeTheAttemptFails() {
        FakeWork work = new FakeWork(true);
        RecordingObservations observations = new RecordingObservations();

        var result = new SubjectWindowRecomputeProcessor(
                work, () -> EVENT, Clock.fixed(NOW, ZoneOffset.UTC), observations,
                new W3cTraceContextCodec()).runBatch();

        assertEquals(new SubjectWindowRecomputeWorkerResult(1, 0, 1, 0), result);
        assertTrue(observations.operationOutcomes.contains("job.finalize:failure"));
        assertTrue(observations.operationOutcomes.contains("outbox.publish:failure"));
        assertTrue(observations.operationOutcomes.contains("job.attempt:failure"));
        assertTrue(observations.errorOperations.contains("job.finalize"));
        assertTrue(observations.errorOperations.contains("outbox.publish"));
        assertTrue(observations.errorOperations.contains("job.attempt"));
    }

    private static final class FakeWork implements SubjectWindowRecomputeWorkPort {
        private final boolean failCompletion;
        private final boolean failCheckpoint;
        private final long checkpoint;
        private final List<String> actions = new ArrayList<>();
        private int batchSize;

        private final String traceparent;
        private FakeWork(boolean failCompletion) { this(failCompletion, false); }
        private FakeWork(boolean failCompletion, boolean failCheckpoint) {
            this(failCompletion, failCheckpoint, 0, PERSISTED_PARENT);
        }
        private FakeWork(boolean failCompletion, long checkpoint) {
            this(failCompletion, false, checkpoint, PERSISTED_PARENT);
        }
        private FakeWork(boolean failCompletion, long checkpoint, String traceparent) {
            this(failCompletion, false, checkpoint, traceparent);
        }
        private FakeWork(
                boolean failCompletion,
                boolean failCheckpoint,
                long checkpoint,
                String traceparent) {
            this.failCompletion = failCompletion;
            this.failCheckpoint = failCheckpoint;
            this.checkpoint = checkpoint;
            this.traceparent = traceparent;
        }

        @Override public List<SubjectWindowRecomputeCandidate> findClaimable(
                int requestedBatchSize, Instant now) {
            batchSize = requestedBatchSize;
            return List.of(new SubjectWindowRecomputeCandidate(
                    JOB, checkpoint, TRACE, traceparent));
        }
        @Override public long claim(UUID jobId, String workerId, Instant now, Duration lease) {
            actions.add("claim:" + lease.toSeconds()); return 7;
        }
        @Override public boolean checkpoint(UUID jobId, long fence, long sequence, Instant now) {
            if (failCheckpoint) throw new IllegalStateException("checkpoint failed");
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

    private static final class RecordingObservations implements ObservationPort {
        private final List<String> operations = new ArrayList<>();
        private final List<ObservationKind> kinds = new ArrayList<>();
        private final List<String> outcomes = new ArrayList<>();
        private final List<String> operationOutcomes = new ArrayList<>();
        private final List<String> errorOperations = new ArrayList<>();
        private final List<W3cTraceContext> parents = new ArrayList<>();
        private final List<W3cTraceContext> contexts = new ArrayList<>();

        @Override
        public ObservationScope start(
                String operation,
                ObservationKind kind,
                SafeObservationAttributes attributes,
                W3cTraceContext parent) {
            operations.add(operation);
            kinds.add(kind);
            parents.add(parent);
            W3cTraceContext context = new W3cTraceContext(
                    parent.traceId(), String.format("%016x", operations.size()), false);
            contexts.add(context);
            return new ObservationScope() {
                @Override public W3cTraceContext context() { return context; }
                @Override public void outcome(String outcome) {
                    outcomes.add(outcome);
                    operationOutcomes.add(operation + ":" + outcome);
                }
                @Override public void error(Throwable error) { errorOperations.add(operation); }
                @Override public void close() {}
            };
        }
    }
}
