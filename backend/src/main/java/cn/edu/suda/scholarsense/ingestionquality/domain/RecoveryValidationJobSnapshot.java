package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.UUID;

/** Persistence-neutral representation of a recovery-specific job row. */
public record RecoveryValidationJobSnapshot(
        UUID jobId,
        long jobVersion,
        RecoveryValidationJobBinding binding,
        RecoveryValidationJobStatus status,
        int attemptCount,
        long leaseGeneration,
        String leaseOwnerDigest,
        Instant claimedAt,
        Instant leaseExpiresAt,
        long checkpointVersion,
        RecoveryValidationCheckpoint checkpoint,
        String resultDigest,
        RecoveryValidationErrorCode errorCode,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {}
