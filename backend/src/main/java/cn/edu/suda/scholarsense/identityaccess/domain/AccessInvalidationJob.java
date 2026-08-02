package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Instant;
import java.util.UUID;

public record AccessInvalidationJob(
        UUID jobId,
        AccessInvalidationJobKind kind,
        AccessInvalidationLineageId lineageId,
        AccessInvalidationJobState state,
        Instant dueAt,
        String leaseOwner,
        long fence,
        long cursor,
        String traceId,
        Instant updatedAt) {
    public AccessInvalidationJob {
        AccessInvalidationValidation.uuidV7(
                jobId, "ACCESS_INVALIDATION_JOB");
        AccessInvalidationValidation.required(kind, "kind");
        AccessInvalidationValidation.required(lineageId, "lineageId");
        AccessInvalidationValidation.required(state, "state");
        AccessInvalidationValidation.required(dueAt, "dueAt");
        AccessInvalidationValidation.nonNegative(
                fence, "ACCESS_INVALIDATION_JOB_FENCE");
        AccessInvalidationValidation.nonNegative(
                cursor, "ACCESS_INVALIDATION_JOB_CURSOR");
        AccessInvalidationValidation.traceId(traceId);
        AccessInvalidationValidation.required(updatedAt, "updatedAt");
        if (state == AccessInvalidationJobState.RUNNING
                && (leaseOwner == null || leaseOwner.isBlank())) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_JOB_LEASE_REQUIRED");
        }
    }

    public static AccessInvalidationJob pending(
            UUID jobId,
            AccessInvalidationJobKind kind,
            AccessInvalidationLineageId lineageId,
            Instant dueAt,
            String traceId) {
        return new AccessInvalidationJob(
                jobId,
                kind,
                lineageId,
                AccessInvalidationJobState.PENDING,
                dueAt,
                null,
                0,
                0,
                traceId,
                dueAt);
    }

    public boolean canClaim(Instant now) {
        return (state == AccessInvalidationJobState.PENDING
                        || state == AccessInvalidationJobState.RETRY)
                && !now.isBefore(dueAt);
    }

    public AccessInvalidationJob claim(
            String owner, long nextFence, Instant claimedAt) {
        if (!canClaim(claimedAt)
                || owner == null
                || owner.isBlank()
                || nextFence <= fence) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_JOB_CLAIM_INVALID");
        }
        return new AccessInvalidationJob(
                jobId,
                kind,
                lineageId,
                AccessInvalidationJobState.RUNNING,
                dueAt,
                owner,
                nextFence,
                cursor,
                traceId,
                claimedAt);
    }

    public AccessInvalidationJob complete(
            long expectedFence, Instant completedAt) {
        if (state != AccessInvalidationJobState.RUNNING
                || expectedFence != fence
                || completedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_JOB_FENCE_INVALID");
        }
        return new AccessInvalidationJob(
                jobId,
                kind,
                lineageId,
                AccessInvalidationJobState.COMPLETED,
                dueAt,
                leaseOwner,
                fence,
                cursor,
                traceId,
                completedAt);
    }
}
