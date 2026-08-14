package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationClaim;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationCheckpoint;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Orchestrates external validation without holding an owner transaction open. */
public final class RecoveryValidationJobProcessor {
    static final int MAX_CHECKPOINTS_PER_RUN = 32;
    private static final int BATCH_SIZE = 20;
    private static final Duration LEASE = Duration.ofSeconds(120);

    private final RecoveryValidationWorkPort work;
    private final RecoveryValidationExternalWorkPort externalWork;
    private final RecoveryValidationTrustedTimePort time;
    private final String workerDigest;

    public RecoveryValidationJobProcessor(
            RecoveryValidationWorkPort work,
            RecoveryValidationExternalWorkPort externalWork,
            RecoveryValidationTrustedTimePort time,
            String workerDigest) {
        this.work = Objects.requireNonNull(work);
        this.externalWork = Objects.requireNonNull(externalWork);
        this.time = Objects.requireNonNull(time);
        if (workerDigest == null || !workerDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_VALIDATION_WORKER_INVALID");
        }
        this.workerDigest = workerDigest;
    }

    public RecoveryValidationWorkerResult runBatch() {
        List<RecoveryValidationCandidate> candidates = Objects.requireNonNull(
                work.findClaimable(BATCH_SIZE, now()));
        int claimed = 0, succeeded = 0, failed = 0, cancelled = 0, fenced = 0;
        int checkpoints = 0, yielded = 0, retriesScheduled = 0;
        for (RecoveryValidationCandidate candidate : candidates) {
            RecoveryValidationClaim claim;
            try {
                claim = work.claim(
                        candidate.jobId(), workerDigest, now(), LEASE);
                claimed++;
            } catch (RuntimeException lostClaim) {
                fenced++;
                continue;
            }
            long checkpointVersion = claim.checkpointVersion();
            RecoveryValidationCheckpoint currentCheckpoint = claim.checkpoint();
            int jobCheckpoints = 0;
            boolean terminal = false;
            while (!terminal) {
                if (jobCheckpoints >= MAX_CHECKPOINTS_PER_RUN) {
                    Instant current = now();
                    if (work.yield(candidate.jobId(), claim.leaseGeneration(),
                            current.plusSeconds(1), current)) yielded++;
                    else fenced++;
                    break;
                }
                RecoveryValidationExecution execution;
                try {
                    execution = Objects.requireNonNull(externalWork.execute(
                            new RecoveryValidationExecutionRequest(
                                    candidate.jobId(), claim.leaseGeneration(), checkpointVersion,
                                    currentCheckpoint)));
                } catch (RuntimeException unavailable) {
                    int scheduled = retryOrFail(candidate.jobId(), claim,
                            RecoveryValidationErrorCode.DEPENDENCY_UNAVAILABLE);
                    if (scheduled == 1) retriesScheduled++;
                    else if (scheduled == 0) failed++;
                    else fenced++;
                    break;
                }
                if (!work.isLeaseCurrent(
                        candidate.jobId(), claim.leaseGeneration(), now())) {
                    fenced++;
                    break;
                }
                switch (execution.outcome()) {
                    case CHECKPOINT -> {
                        long next = execution.checkpoint().checkpointVersion();
                        if (next != checkpointVersion + 1) {
                            if (work.fail(candidate.jobId(), claim.leaseGeneration(),
                                    RecoveryValidationErrorCode.RETRY_EXHAUSTED,
                                    now())) failed++;
                            else fenced++;
                            terminal = true;
                        } else if (!work.checkpoint(
                                candidate.jobId(), claim.leaseGeneration(), checkpointVersion,
                                execution.checkpoint(), now())) {
                            fenced++;
                            terminal = true;
                        } else {
                            checkpointVersion = next;
                            currentCheckpoint = execution.checkpoint();
                            jobCheckpoints++;
                            checkpoints++;
                        }
                    }
                    case SUCCEEDED -> {
                        if (work.complete(candidate.jobId(), claim.leaseGeneration(),
                                execution.result(), now())) succeeded++;
                        else fenced++;
                        terminal = true;
                    }
                    case FAILED -> {
                        int scheduled = retryOrFail(
                                candidate.jobId(), claim, execution.errorCode());
                        if (scheduled == 1) retriesScheduled++;
                        else if (scheduled == 0) failed++;
                        else fenced++;
                        terminal = true;
                    }
                    case CANCELLED -> {
                        if (work.cancel(candidate.jobId(), claim.leaseGeneration(),
                                now())) cancelled++;
                        else fenced++;
                        terminal = true;
                    }
                }
            }
        }
        return new RecoveryValidationWorkerResult(
                claimed, succeeded, failed, cancelled, fenced, checkpoints,
                yielded, retriesScheduled);
    }

    /** 1=retry scheduled, 0=terminal failure, -1=stale fence. */
    private int retryOrFail(
            java.util.UUID jobId,
            RecoveryValidationClaim claim,
            RecoveryValidationErrorCode error) {
        Instant current = now();
        boolean retryable = error == RecoveryValidationErrorCode.DEPENDENCY_UNAVAILABLE;
        if (retryable && claim.attemptNumber() < cn.edu.suda.scholarsense.ingestionquality.domain
                .RecoveryValidationJob.MAXIMUM_ATTEMPTS) {
            Duration backoff = Duration.ofSeconds(1L << (claim.attemptNumber() - 1));
            return work.retry(jobId, claim.leaseGeneration(), error,
                    current.plus(backoff), current) ? 1 : -1;
        }
        return work.fail(jobId, claim.leaseGeneration(), error, current) ? 0 : -1;
    }

    private Instant now() {
        Instant value = Objects.requireNonNull(time.now());
        if (value.getNano() % 1_000 != 0) {
            throw new IllegalStateException("INGESTION_QUALITY_TRUSTED_TIME_INVALID");
        }
        return value;
    }
}
