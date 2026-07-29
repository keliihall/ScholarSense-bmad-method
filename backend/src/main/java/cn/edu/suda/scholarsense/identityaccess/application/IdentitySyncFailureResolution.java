package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable operator decision that closes one terminal sync failure before a new
 * retry budget may be issued.
 */
public record IdentitySyncFailureResolution(
        UUID resolutionId,
        CheckpointKey key,
        String resolvedBy,
        String resolutionCode,
        String traceId,
        Instant resolvedAt) {

    public IdentitySyncFailureResolution {
        Objects.requireNonNull(resolutionId, "resolutionId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(resolvedAt, "resolvedAt");
        if (resolvedBy == null || !resolvedBy.matches("[A-Za-z0-9._:@/-]{3,128}")) {
            throw new IllegalArgumentException("resolvedBy");
        }
        if (resolutionCode == null
                || !resolutionCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("resolutionCode");
        }
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("traceId");
        }
    }
}
