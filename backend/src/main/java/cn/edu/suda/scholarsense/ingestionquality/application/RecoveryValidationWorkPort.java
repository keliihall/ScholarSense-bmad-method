package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationCheckpoint;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationClaim;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationErrorCode;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Owner-local atomic operations. Implementations fence every checkpoint and finalizer. */
public interface RecoveryValidationWorkPort {
    List<RecoveryValidationCandidate> findClaimable(int limit, Instant trustedNow);

    RecoveryValidationClaim claim(
            UUID jobId, String workerDigest, Instant trustedNow, Duration leaseDuration);

    /** Rechecks the half-open lease after external I/O; no mutation may follow a false result. */
    boolean isLeaseCurrent(
            UUID jobId, long leaseGeneration, Instant trustedNow);

    boolean checkpoint(
            UUID jobId, long leaseGeneration, long expectedCheckpointVersion,
            RecoveryValidationCheckpoint checkpoint, Instant trustedNow);

    boolean complete(
            UUID jobId, long leaseGeneration, RecoveryValidationResult result,
            Instant trustedNow);

    boolean fail(
            UUID jobId, long leaseGeneration, RecoveryValidationErrorCode errorCode,
            Instant trustedNow);

    /** Atomically releases the lease and schedules one bounded technical retry. */
    boolean retry(
            UUID jobId, long leaseGeneration, RecoveryValidationErrorCode errorCode,
            Instant nextAttemptAt, Instant trustedNow);

    /** Releases a healthy bounded-page run without consuming another failure attempt. */
    boolean yield(
            UUID jobId, long leaseGeneration, Instant nextAttemptAt, Instant trustedNow);

    boolean cancel(UUID jobId, long leaseGeneration, Instant trustedNow);
}
