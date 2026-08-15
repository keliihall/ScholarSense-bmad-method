package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservation;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationDecision;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationEvidenceStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationFact;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationFence;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationSourceClass;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryObservationJobProcessorTest {
    private static final Instant NOW = Instant.parse("2026-08-14T01:00:00Z");
    private static final UUID JOB_ID =
            UUID.fromString("018f34c0-9b80-7a11-8abc-0123456789ab");
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    @Test
    void readyDecisionCommitsOnlyAfterCurrentFenceRecheck() {
        FakeWork work = new FakeWork(observation(passed()), true);
        RecoveryObservationJobProcessor processor = new RecoveryObservationJobProcessor(
                work, () -> NOW, DIGEST);

        RecoveryObservationWorkerResult result = processor.runBatch();

        assertEquals(1, result.claimed());
        assertEquals(1, result.completed());
        assertEquals(RecoveryObservationStatus.READY, work.completed.getFirst().status());
        assertEquals(0, result.fenced());
    }

    @Test
    void staleLeaseCannotCheckpointOrFinalize() {
        FakeWork work = new FakeWork(observation(passed()), false);
        RecoveryObservationWorkerResult result = new RecoveryObservationJobProcessor(
                work, () -> NOW, DIGEST).runBatch();

        assertEquals(1, result.fenced());
        assertEquals(0, result.completed());
        assertEquals(0, work.completed.size());
    }

    @Test
    void providerOutageSchedulesRetryAndNeverBecomesBusinessRelapse() {
        RecoveryObservationEvidence unavailable = new RecoveryObservationEvidence(
                RecoveryObservationEvidenceStatus.PASSED,
                RecoveryObservationEvidenceStatus.UNAVAILABLE,
                RecoveryObservationEvidenceStatus.PASSED,
                RecoveryObservationEvidenceStatus.PASSED);
        FakeWork work = new FakeWork(observation(unavailable), true);

        RecoveryObservationWorkerResult result = new RecoveryObservationJobProcessor(
                work, () -> NOW, DIGEST).runBatch();

        assertEquals(1, result.retriesScheduled());
        assertEquals(0, result.completed());
        assertEquals(0, work.completed.size());
    }

    @Test
    void policyDriftIsCommittedAsQueryableBusinessDecision() {
        FakeWork work = new FakeWork(observation(passed()), true, false);

        RecoveryObservationWorkerResult result = new RecoveryObservationJobProcessor(
                work, () -> NOW, DIGEST).runBatch();

        assertEquals(1, result.completed());
        assertEquals(0, result.failed());
        assertEquals(RecoveryObservationStatus.POLICY_DRIFT,
                work.completed.getFirst().status());
    }

    private static RecoveryObservation observation(RecoveryObservationEvidence evidence) {
        return new RecoveryObservation(
                JOB_ID, 1, "SRC-P0-CAMPUS-ACCESS-001",
                RecoveryObservationSourceClass.STREAMING,
                Instant.parse("2026-08-14T00:00:00Z"),
                "QRP-1.0.0", DIGEST, DIGEST, DIGEST,
                pairs(), evidence);
    }

    private static List<RecoveryObservationFact> pairs() {
        ArrayList<RecoveryObservationFact> facts = new ArrayList<>();
        for (int ordinal = 1; ordinal <= 3; ordinal++) {
            UUID batch = UUID.fromString(
                    "018f34c0-9b8" + ordinal + "-7a11-8abc-0123456789ab");
            UUID snapshot = UUID.fromString(
                    "018f34c0-9c8" + ordinal + "-7a11-8abc-0123456789ab");
            facts.add(new RecoveryObservationFact(
                    UUID.fromString("018f34c0-9d8" + ordinal
                            + "-7a11-8abc-0123456789ab"),
                    batch, snapshot, ordinal, 0,
                    RecoveryObservationFact.Stage.ASSESSED_PASSED,
                    DIGEST, "wm-" + ordinal,
                    Instant.parse("2026-08-14T00:0" + ordinal + ":00Z")));
            facts.add(new RecoveryObservationFact(
                    UUID.fromString("018f34c0-9e8" + ordinal
                            + "-7a11-8abc-0123456789ab"),
                    batch, snapshot, ordinal, 0,
                    RecoveryObservationFact.Stage.PUBLISHED,
                    DIGEST, "wm-" + ordinal,
                    Instant.parse("2026-08-14T00:1" + ordinal + ":00Z")));
        }
        return facts;
    }

    private static RecoveryObservationEvidence passed() {
        return new RecoveryObservationEvidence(
                RecoveryObservationEvidenceStatus.PASSED,
                RecoveryObservationEvidenceStatus.PASSED,
                RecoveryObservationEvidenceStatus.PASSED,
                RecoveryObservationEvidenceStatus.PASSED);
    }

    private static final class FakeWork implements RecoveryObservationWorkPort {
        private final RecoveryObservation observation;
        private final boolean current;
        private final boolean allRecovering;
        private final List<RecoveryObservationDecision> completed = new ArrayList<>();

        private FakeWork(RecoveryObservation observation, boolean current) {
            this(observation, current, true);
        }

        private FakeWork(
                RecoveryObservation observation, boolean current, boolean allRecovering) {
            this.observation = observation;
            this.current = current;
            this.allRecovering = allRecovering;
        }

        @Override
        public List<RecoveryObservationCandidate> findClaimable(int limit, Instant now) {
            return List.of(new RecoveryObservationCandidate(JOB_ID));
        }

        @Override
        public RecoveryObservationClaim claim(
                UUID jobId, String workerDigest, Instant now, Duration lease) {
            return new RecoveryObservationClaim(
                    jobId, 7, 1, observation,
                    new RecoveryObservationFence("QRP-1.0.0", DIGEST, DIGEST, DIGEST),
                    allRecovering);
        }

        @Override
        public boolean isLeaseCurrent(UUID jobId, long leaseGeneration, Instant now) {
            return current;
        }

        @Override
        public boolean complete(
                UUID jobId, long leaseGeneration,
                RecoveryObservationDecision decision, Instant now) {
            completed.add(decision);
            return true;
        }

        @Override
        public boolean retry(
                UUID jobId, long leaseGeneration, Instant nextAttemptAt, Instant now) {
            return true;
        }

        @Override
        public boolean yield(
                UUID jobId, long leaseGeneration, Instant nextAttemptAt, Instant now) {
            return true;
        }

        @Override
        public boolean fail(UUID jobId, long leaseGeneration, Instant now) {
            return true;
        }
    }
}
