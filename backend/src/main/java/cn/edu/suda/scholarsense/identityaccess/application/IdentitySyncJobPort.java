package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdentitySyncJobPort {
    void enqueue(IdentitySyncJob job);

    /**
     * Atomically enqueues a scheduler-created job only when the checkpoint has
     * neither active work nor an unresolved terminal failure.
     */
    boolean enqueueIfEligible(IdentitySyncJob job);

    /**
     * Atomically records an operator resolution for the current terminal
     * failure and enqueues its replacement job.
     */
    boolean resolveFailureAndEnqueue(
            IdentitySyncFailureResolution resolution,
            IdentitySyncJob replacement);

    long lastSuccessfulWatermark(CheckpointKey key);

    boolean hasPending(CheckpointKey key);

    Optional<IdentitySyncJob> nextDue(Instant now);

    Optional<RunningIdentitySyncAttempt> start(
            UUID jobId, String leaseOwner, Instant now);

    void save(
            RunningIdentitySyncAttempt attempt,
            IdentitySyncJob completed,
            Instant now);
}
