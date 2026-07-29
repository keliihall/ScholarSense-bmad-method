package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Objects;

public record IdentityCheckpoint(
        CheckpointKey key,
        long sourceVersion,
        long watermark,
        long aggregateVersion,
        Instant lastSuccessfulAt,
        IdentitySourceHealth health,
        IdentityProjectionFreshness freshness) {
    public IdentityCheckpoint {
        Objects.requireNonNull(key, "key");
        if (sourceVersion < 0 || watermark < 0 || aggregateVersion < 0) {
            throw new IllegalArgumentException("IDENTITY_CHECKPOINT_VERSION_INVALID");
        }
        if (aggregateVersion > 0 && lastSuccessfulAt == null) {
            throw new IllegalArgumentException("IDENTITY_CHECKPOINT_SUCCESS_TIME_REQUIRED");
        }
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(freshness, "freshness");
    }

    public static IdentityCheckpoint initial(CheckpointKey key) {
        return new IdentityCheckpoint(
                key, 0, 0, 0, null,
                IdentitySourceHealth.DEGRADED, IdentityProjectionFreshness.STALE);
    }
}
