package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Executes durable recompute jobs; every mutation is guarded by the claimed fencing token. */
public final class SubjectWindowRecomputeProcessor {
    private static final int BATCH_SIZE = 100;
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final String WORKER_ID = "subject-recompute-worker";
    private final SubjectWindowRecomputeWorkPort work;
    private final MappingRecomputeIdPort ids;
    private final Clock clock;

    public SubjectWindowRecomputeProcessor(
            SubjectWindowRecomputeWorkPort work,
            MappingRecomputeIdPort ids,
            Clock clock) {
        this.work = Objects.requireNonNull(work);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
    }

    public SubjectWindowRecomputeWorkerResult runBatch() {
        List<SubjectWindowRecomputeCandidate> jobs = work.findClaimable(
                BATCH_SIZE, clock.instant());
        int claimed = 0, succeeded = 0, failed = 0, fenced = 0;
        for (SubjectWindowRecomputeCandidate candidate : jobs) {
            UUID jobId = candidate.jobId();
            long fence;
            try {
                fence = work.claim(jobId, WORKER_ID, clock.instant(), LEASE);
                claimed++;
            } catch (RuntimeException lostRace) {
                fenced++;
                continue;
            }
            try {
                work.checkpoint(
                        jobId, fence, candidate.checkpointSequence() + 1, clock.instant());
                work.complete(jobId, fence, clock.instant(), ids.nextId());
                succeeded++;
            } catch (RuntimeException executionFailure) {
                try {
                    if (work.fail(jobId, fence, clock.instant(),
                            "INGESTION_QUALITY_RECOMPUTE_EXECUTION_FAILED")) failed++;
                    else fenced++;
                } catch (RuntimeException staleFence) {
                    fenced++;
                }
            }
        }
        return new SubjectWindowRecomputeWorkerResult(claimed, succeeded, failed, fenced);
    }
}
