package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IdentityLease(
        CheckpointKey key,
        UUID jobId,
        int attemptNo,
        long fencingToken,
        String leaseOwner,
        Instant acquiredAt,
        Instant expiresAt) {
    public IdentityLease {
        Objects.requireNonNull(key, "key");
        requireUuidV7(jobId);
        if (attemptNo < 1 || fencingToken < 1) {
            throw new IllegalArgumentException("IDENTITY_SYNC_LEASE_VERSION_INVALID");
        }
        if (leaseOwner == null || !leaseOwner.matches("[a-zA-Z0-9._-]{3,128}")) {
            throw new IllegalArgumentException("IDENTITY_SYNC_LEASE_OWNER_INVALID");
        }
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(acquiredAt)) {
            throw new IllegalArgumentException("IDENTITY_SYNC_LEASE_WINDOW_INVALID");
        }
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new IllegalArgumentException("IDENTITY_SYNC_JOB_UUIDV7_REQUIRED");
        }
    }
}
