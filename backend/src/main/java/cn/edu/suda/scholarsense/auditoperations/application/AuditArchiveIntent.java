package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable pre-write evidence that makes an interrupted immutable archive operation recoverable. */
public record AuditArchiveIntent(
        String profileVersion,
        UUID manifestId,
        String fixtureId,
        String scopeHash,
        long sequenceStart,
        long sequenceEnd,
        long recordCount,
        String archiveObjectId,
        String contentDigest,
        Instant retentionUntil,
        String createdBy,
        Instant preparedAt,
        String traceId) {
    public AuditArchiveIntent {
        Objects.requireNonNull(profileVersion);
        Objects.requireNonNull(manifestId);
        Objects.requireNonNull(fixtureId);
        Objects.requireNonNull(scopeHash);
        Objects.requireNonNull(archiveObjectId);
        Objects.requireNonNull(contentDigest);
        Objects.requireNonNull(retentionUntil);
        Objects.requireNonNull(createdBy);
        Objects.requireNonNull(preparedAt);
        Objects.requireNonNull(traceId);
        if (!"AUDIT-ARCHIVE-INTENT-1.0.0".equals(profileVersion)
                || sequenceStart < 0 || sequenceEnd < sequenceStart || recordCount < 1
                || scopeHash.length() != 64 || contentDigest.length() != 64) {
            throw new IllegalArgumentException("AUDIT_ARCHIVE_INTENT_INVALID");
        }
    }
}
