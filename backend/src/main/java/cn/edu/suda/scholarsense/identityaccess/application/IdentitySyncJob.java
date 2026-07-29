package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record IdentitySyncJob(
        UUID jobId,
        CheckpointKey key,
        IdentitySyncJobStatus status,
        IdentitySourceHealth health,
        IdentityProjectionFreshness freshness,
        Instant requestedAt,
        Instant completedAt,
        long lastSuccessfulWatermark,
        Instant nextAttemptAt,
        int retryBudget,
        int lastAttemptNo,
        String reasonCode,
        String traceId) {
    public IdentitySyncJob {
        requireUuidV7(jobId);
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(freshness, "freshness");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (lastSuccessfulWatermark < 0 || retryBudget < 0 || lastAttemptNo < 0) {
            throw new IllegalArgumentException("IDENTITY_SYNC_JOB_VERSION_INVALID");
        }
        if (reasonCode != null && !reasonCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("IDENTITY_SYNC_JOB_REASON_INVALID");
        }
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("IDENTITY_SYNC_TRACE_INVALID");
        }
        boolean terminal = Set.of(
                IdentitySyncJobStatus.SUCCEEDED,
                IdentitySyncJobStatus.FAILED,
                IdentitySyncJobStatus.CANCELLED).contains(status);
        if (terminal != (completedAt != null)) {
            throw new IllegalArgumentException("IDENTITY_SYNC_JOB_COMPLETION_INVALID");
        }
        if (status != IdentitySyncJobStatus.QUEUED && nextAttemptAt != null) {
            throw new IllegalArgumentException("IDENTITY_SYNC_JOB_RETRY_TIME_INVALID");
        }
    }

    public IdentitySyncJob transitionTo(
            IdentitySyncJobStatus nextStatus,
            IdentitySourceHealth nextHealth,
            IdentityProjectionFreshness nextFreshness,
            Instant nextCompletedAt,
            Instant nextAttemptAt,
            String nextReasonCode,
            long nextLastSuccessfulWatermark) {
        if (!allowed(status, nextStatus)) {
            throw new IllegalStateException("IDENTITY_SYNC_JOB_TRANSITION_INVALID");
        }
        return new IdentitySyncJob(
                jobId,
                key,
                nextStatus,
                nextHealth,
                nextFreshness,
                requestedAt,
                nextCompletedAt,
                nextLastSuccessfulWatermark,
                nextAttemptAt,
                retryBudget,
                lastAttemptNo,
                nextReasonCode,
                traceId);
    }

    private static boolean allowed(
            IdentitySyncJobStatus current, IdentitySyncJobStatus next) {
        return switch (current) {
            case QUEUED -> next == IdentitySyncJobStatus.RUNNING
                    || next == IdentitySyncJobStatus.CANCELLED;
            case RUNNING -> next == IdentitySyncJobStatus.QUEUED
                    || next == IdentitySyncJobStatus.SUCCEEDED
                    || next == IdentitySyncJobStatus.FAILED
                    || next == IdentitySyncJobStatus.CANCELLED;
            case SUCCEEDED, FAILED, CANCELLED -> false;
        };
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new IllegalArgumentException("IDENTITY_SYNC_JOB_UUIDV7_REQUIRED");
        }
    }
}
