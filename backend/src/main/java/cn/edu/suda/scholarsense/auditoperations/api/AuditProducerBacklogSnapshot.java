package cn.edu.suda.scholarsense.auditoperations.api;

import java.time.Instant;
import java.util.Objects;

/** Transport-neutral producer-owned delivery state; it contains no audit facts or identifiers. */
public record AuditProducerBacklogSnapshot(
        long unconfirmedCount,
        long oldestUnconfirmedAgeSeconds,
        long retryableUnconfirmedCount,
        long oldestRetryableUnconfirmedAgeSeconds,
        boolean permanentFailureActive,
        Instant measuredAt,
        boolean available) {
    public AuditProducerBacklogSnapshot {
        if (unconfirmedCount < 0 || oldestUnconfirmedAgeSeconds < 0
                || retryableUnconfirmedCount < 0 || oldestRetryableUnconfirmedAgeSeconds < 0
                || retryableUnconfirmedCount > unconfirmedCount) {
            throw new IllegalArgumentException("AUDIT_BACKLOG_SNAPSHOT_INVALID");
        }
        Objects.requireNonNull(measuredAt, "measuredAt");
    }
}
