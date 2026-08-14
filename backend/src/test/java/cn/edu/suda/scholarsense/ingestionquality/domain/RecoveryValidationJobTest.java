package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryValidationJobTest {
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final UUID JOB = uuid("019ff5a0-0000-7000-8000-000000000101");

    @Test
    void queuedJobCheckpointsAndCompletesOnlyUnderItsCurrentLeaseGeneration() {
        RecoveryValidationJob job = queued();

        RecoveryValidationClaim claim = job.claim(
                digest('b'), NOW, Duration.ofMinutes(2));
        RecoveryValidationCheckpoint checkpoint = checkpoint(1, 40, 0);
        job.checkpoint(claim.leaseGeneration(), checkpoint, NOW.plusSeconds(10));
        RecoveryValidationResult result = result(
                job, RecoveryValidationJobStatus.SUCCEEDED, true, 100, 100, 0, null);
        job.complete(claim.leaseGeneration(), result, NOW.plusSeconds(20));

        assertEquals(RecoveryValidationJobStatus.SUCCEEDED, job.status());
        assertEquals(1, job.attemptCount());
        assertEquals(1, job.leaseGeneration());
        assertEquals(1, job.checkpointVersion());
        assertEquals(result.resultDigest(), job.resultDigest());
        assertNull(job.leaseOwnerDigest());
        assertEquals(NOW.plusSeconds(20), job.completedAt());
        assertFalse(job.canRetry());
    }

    @Test
    void expiredLeaseCanBeTakenOverFromThePersistedCheckpointAndStaleWorkerIsFenced() {
        RecoveryValidationJob job = queued();
        RecoveryValidationClaim first = job.claim(
                digest('b'), NOW, Duration.ofMinutes(1));
        job.checkpoint(first.leaseGeneration(), checkpoint(1, 25, 0), NOW.plusSeconds(1));

        RecoveryValidationClaim second = job.claim(
                digest('c'), NOW.plusSeconds(60), Duration.ofMinutes(1));

        assertEquals(2, second.attemptNumber());
        assertEquals(2, second.leaseGeneration());
        assertEquals(1, second.checkpointVersion());
        IngestionQualityException stale = assertThrows(IngestionQualityException.class,
                () -> job.checkpoint(
                        first.leaseGeneration(), checkpoint(2, 50, 0), NOW.plusSeconds(61)));
        assertEquals(IngestionQualityErrorCode.INGESTION_QUALITY_STALE_FENCING_TOKEN.name(),
                stale.code());
        job.checkpoint(second.leaseGeneration(), checkpoint(2, 50, 0), NOW.plusSeconds(61));
    }

    @Test
    void exactLeaseBoundaryIsExpiredAndCheckpointMustAdvanceExactlyOnce() {
        RecoveryValidationJob job = queued();
        RecoveryValidationClaim claim = job.claim(
                digest('b'), NOW, Duration.ofMinutes(1));

        IngestionQualityException expired = assertThrows(IngestionQualityException.class,
                () -> job.checkpoint(
                        claim.leaseGeneration(), checkpoint(1, 1, 0), NOW.plusSeconds(60)));
        assertEquals(IngestionQualityErrorCode.INGESTION_QUALITY_LEASE_EXPIRED.name(),
                expired.code());

        RecoveryValidationClaim takeover = job.claim(
                digest('c'), NOW.plusSeconds(60), Duration.ofMinutes(1));
        job.checkpoint(takeover.leaseGeneration(), checkpoint(1, 1, 0), NOW.plusSeconds(61));
        assertThrows(IngestionQualityException.class,
                () -> job.checkpoint(
                        takeover.leaseGeneration(), checkpoint(1, 2, 0), NOW.plusSeconds(62)));
        assertThrows(IngestionQualityException.class,
                () -> job.checkpoint(
                        takeover.leaseGeneration(), checkpoint(3, 3, 0), NOW.plusSeconds(62)));
    }

    @Test
    void checkpointPhaseMayAdvanceButNeverRegress() {
        RecoveryValidationJob job = queued();
        RecoveryValidationClaim claim = job.claim(digest('b'), NOW, Duration.ofMinutes(1));
        job.checkpoint(claim.leaseGeneration(), checkpoint(
                1, RecoveryValidationPhase.BACKFILL, true, 10, 0), NOW.plusSeconds(1));

        assertThrows(IngestionQualityException.class, () -> job.checkpoint(
                claim.leaseGeneration(), checkpoint(
                        2, RecoveryValidationPhase.SAMPLE_RECOMPUTE,
                        false, 20, 0), NOW.plusSeconds(2)));

        job.checkpoint(claim.leaseGeneration(), checkpoint(
                2, RecoveryValidationPhase.FULL_RECONCILIATION,
                true, 20, 0), NOW.plusSeconds(2));
        job.checkpoint(claim.leaseGeneration(), checkpoint(
                3, RecoveryValidationPhase.SAMPLE_RECOMPUTE,
                false, 20, 0), NOW.plusSeconds(3));
        assertEquals(RecoveryValidationPhase.SAMPLE_RECOMPUTE, job.checkpoint().phase());
    }

    @Test
    void cancellationIsTerminalAndInvalidatesTheRunningWorker() {
        RecoveryValidationJob job = queued();
        RecoveryValidationClaim claim = job.claim(
                digest('b'), NOW, Duration.ofMinutes(1));

        job.cancel(result(
                job, RecoveryValidationJobStatus.CANCELLED, false,
                0, 0, 0, RecoveryValidationResult.ResultErrorCode.CANCELLED),
                NOW.plusSeconds(10));

        assertEquals(RecoveryValidationJobStatus.CANCELLED, job.status());
        assertEquals(RecoveryValidationErrorCode.CANCELLED, job.errorCode());
        assertEquals(claim.leaseGeneration() + 1, job.leaseGeneration());
        assertThrows(IngestionQualityException.class,
                () -> job.complete(
                        claim.leaseGeneration(), result(
                                job, RecoveryValidationJobStatus.SUCCEEDED, true,
                                100, 100, 0, null), NOW.plusSeconds(11)));
        assertThrows(IngestionQualityException.class,
                () -> job.claim(digest('c'), NOW.plusSeconds(70), Duration.ofMinutes(1)));
    }

    @Test
    void retriesAreBoundedAtFiveAndCannotEnterAnInfiniteRequeueLoop() {
        RecoveryValidationJob job = queued();
        for (int attempt = 1; attempt <= RecoveryValidationJob.MAXIMUM_ATTEMPTS; attempt++) {
            Instant claimedAt = NOW.plusSeconds(attempt * 10L);
            RecoveryValidationClaim claim = job.claim(
                    digest((char) ('a' + attempt)), claimedAt, Duration.ofMinutes(1));
            RecoveryValidationResult failure = result(
                    job, RecoveryValidationJobStatus.FAILED, false, 0, 0, 0,
                    RecoveryValidationResult.ResultErrorCode.DEPENDENCY_UNAVAILABLE);
            if (attempt < RecoveryValidationJob.MAXIMUM_ATTEMPTS) {
                job.retry(claim.leaseGeneration(),
                        RecoveryValidationErrorCode.DEPENDENCY_UNAVAILABLE,
                        claimedAt.plusSeconds(2), claimedAt.plusSeconds(1));
                assertTrue(job.canRetry());
            } else {
                job.fail(claim.leaseGeneration(),
                        RecoveryValidationErrorCode.DEPENDENCY_UNAVAILABLE,
                        failure, claimedAt.plusSeconds(1));
            }
        }

        assertEquals(RecoveryValidationJobStatus.FAILED, job.status());
        assertEquals(RecoveryValidationErrorCode.RETRY_EXHAUSTED, job.errorCode());
        assertEquals(RecoveryValidationJob.MAXIMUM_ATTEMPTS, job.attemptCount());
        assertFalse(job.canRetry());
        assertThrows(IngestionQualityException.class, () -> job.retry(
                job.leaseGeneration(), RecoveryValidationErrorCode.DEPENDENCY_UNAVAILABLE,
                NOW.plusSeconds(101), NOW.plusSeconds(100)));
    }

    @Test
    void snapshotRoundTripPreservesTheDurableCheckpointAndFence() {
        RecoveryValidationJob original = queued();
        RecoveryValidationClaim claim = original.claim(
                digest('b'), NOW, Duration.ofMinutes(2));
        original.checkpoint(claim.leaseGeneration(), checkpoint(1, 7, 0), NOW.plusSeconds(1));

        RecoveryValidationJob restored = RecoveryValidationJob.restore(original.snapshot());

        assertEquals(original.snapshot(), restored.snapshot());
        assertEquals(1, restored.currentAttempt().attemptNumber());
        assertSame(RecoveryValidationJobStatus.RUNNING, restored.status());
    }

    @Test
    void restoreRejectsImpossibleTerminalShapesAndYieldDoesNotConsumeRetryBudget() {
        RecoveryValidationJob job = queued();
        RecoveryValidationClaim first = job.claim(digest('b'), NOW, Duration.ofMinutes(2));
        job.checkpoint(first.leaseGeneration(), checkpoint(1, 7, 0), NOW.plusSeconds(1));
        job.yield(first.leaseGeneration(), NOW.plusSeconds(3), NOW.plusSeconds(2));
        assertEquals(1, job.attemptCount());
        RecoveryValidationClaim resumed = job.claim(
                digest('c'), NOW.plusSeconds(3), Duration.ofMinutes(2));
        assertEquals(1, resumed.attemptNumber());
        assertEquals(1, resumed.checkpointVersion());
        assertEquals(job.checkpoint(), resumed.checkpoint());

        RecoveryValidationJobSnapshot snapshot = job.snapshot();
        assertThrows(IngestionQualityException.class, () -> RecoveryValidationJob.restore(
                new RecoveryValidationJobSnapshot(
                        snapshot.jobId(), snapshot.jobVersion(), snapshot.binding(),
                        RecoveryValidationJobStatus.SUCCEEDED, snapshot.attemptCount(),
                        snapshot.leaseGeneration(), null, null, null,
                        snapshot.checkpointVersion(), snapshot.checkpoint(), null, null, null,
                        snapshot.createdAt(), snapshot.updatedAt(), snapshot.updatedAt())));
    }

    @Test
    void resultCarriesOnlyBoundedCountsAndDigestsAndRejectsInconsistentQualification() {
        assertThrows(IngestionQualityException.class,
                () -> result(queued(), RecoveryValidationJobStatus.SUCCEEDED,
                        true, 99, 99, 1, null));
        assertThrows(IngestionQualityException.class,
                () -> result(queued(), RecoveryValidationJobStatus.SUCCEEDED,
                        true, 100, 99, 0,
                        RecoveryValidationResult.ResultErrorCode.SAMPLE_INSUFFICIENT));
        assertThrows(IngestionQualityException.class,
                () -> new RecoveryValidationCheckpoint(
                        1, RecoveryValidationPhase.FULL_RECONCILIATION, false,
                        "resume:v1:" + "1".repeat(64),
                        "student-1001", digest('e'), 1, 0));

        RecoveryValidationJob job = queued();
        RecoveryValidationClaim claim = job.claim(digest('b'), NOW, Duration.ofMinutes(1));
        assertThrows(IngestionQualityException.class, () -> job.complete(
                claim.leaseGeneration(), result(
                        queued(), RecoveryValidationJobStatus.SUCCEEDED,
                        true, 101, 101, 0, null), NOW.plusSeconds(1)));

        assertTrue(result(job, RecoveryValidationJobStatus.SUCCEEDED,
                true, 101, 101, 0, null).qualified(),
                "QRP requires at least 100, not exactly 100");
    }

    private static RecoveryValidationJob queued() {
        return RecoveryValidationJob.queued(JOB, binding(), NOW);
    }

    private static RecoveryValidationJobBinding binding() {
        return new RecoveryValidationJobBinding(
                uuid("019ff5a0-0000-7000-8000-000000000102"),
                uuid("019ff5a0-0000-7000-8000-000000000103"),
                uuid("019ff5a0-0000-7000-8000-000000000104"),
                digest('1'), "QRP-1.0.0", digest('2'), digest('3'), digest('4'),
                digest('5'), digest('6'), "00112233445566778899aabbccddeeff");
    }

    private static RecoveryValidationCheckpoint checkpoint(
            long version, long processedCount, long mismatchCount) {
        return checkpoint(version, RecoveryValidationPhase.BACKFILL,
                false, processedCount, mismatchCount);
    }

    private static RecoveryValidationCheckpoint checkpoint(
            long version,
            RecoveryValidationPhase phase,
            boolean completed,
            long processedCount,
            long mismatchCount) {
        return new RecoveryValidationCheckpoint(
                version, phase, completed, "resume:v1:" + "1".repeat(64),
                digest('d'), digest('e'), processedCount, mismatchCount);
    }

    private static RecoveryValidationResult result(
            RecoveryValidationJob job,
            RecoveryValidationJobStatus state,
            boolean qualified,
            long population,
            int selected,
            long mismatches,
            RecoveryValidationResult.ResultErrorCode error) {
        RecoveryValidationJobSnapshot snapshot = job.snapshot();
        return new RecoveryValidationResult(
                uuid("019ff5a0-0000-7000-8000-000000000105"), job.jobId(),
                snapshot.jobVersion(), job.binding().recoveryRequestId(),
                job.binding().episodeId(), job.binding().taskId(), state,
                job.binding().inputDigest(), job.binding().qualityRecoveryPolicyVersion(),
                job.binding().qualityRecoveryPolicyDigest(), digest('6'), digest('7'),
                digest('8'), 1, qualified ? 1 : 0, qualified ? 0 : 1,
                digest('9'), "QUALITY-RECOVERY-SAMPLE-SUMMARY-1.0.0", digest('a'),
                population, selected,
                population == 0 ? java.util.List.of() : java.util.List.of(
                        new RecoveryValidationResult.StratumSummary(
                                "ALL", population, selected, Math.toIntExact(mismatches),
                                digest('b'))),
                digest('c'), digest('d'), qualified ? digest('d') : digest('e'),
                mismatches, readiness(qualified), qualified, error,
                NOW, digest('f'), job.binding().traceId());
    }

    private static RecoveryValidationReadinessEvidence readiness(boolean qualified) {
        var codes = qualified ? java.util.List.<RecoveryValidationReadinessEvidence.MissingEvidenceCode>of()
                : java.util.List.of(RecoveryValidationReadinessEvidence.MissingEvidenceCode.BACKFILL_INCOMPLETE);
        return new RecoveryValidationReadinessEvidence(
                1, 1, 1, "SRC-P0-CARD-001", "DEP-P0-CARD-001",
                digest('a'), digest('b'), digest('c'),
                "streaming", "CARD-SLICE-1.0.0", digest('1'), "1", digest('2'),
                java.util.List.of(new RecoveryValidationReadinessEvidence.EligibilityBinding(
                        uuid("019ff5a0-0000-7000-8000-000000000106"), "ACC-SAFE-001",
                        "1.0.0", 2)), digest('3'),
                new RecoveryValidationReadinessEvidence.QualityEvidence(
                        digest('4'), digest('5'), digest('6'), true, "wm-source", "wm-dependency"),
                new RecoveryValidationReadinessEvidence.BatchEvidence(digest('7'), 3, 3),
                new RecoveryValidationReadinessEvidence.BackfillEvidence(
                        "wm-lkg", NOW, 90, "wm-start", "wm-complete", digest('8'),
                        qualified ? "succeeded" : "failed"),
                true, codes, 0, 1, 0, NOW, digest('9'));
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
