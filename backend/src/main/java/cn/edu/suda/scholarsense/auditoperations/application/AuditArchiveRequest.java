package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;
import java.util.UUID;

public record AuditArchiveRequest(
        UUID manifestId,
        String fixtureId,
        String scopeHash,
        long sequenceStart,
        long sequenceEnd,
        String createdBy,
        Instant retentionUntil,
        String traceId) {
    public AuditArchiveRequest {
        if (manifestId == null || manifestId.version() != 7 || manifestId.variant() != 2
                || fixtureId == null || fixtureId.isBlank()
                || scopeHash == null || !scopeHash.matches("[0-9a-f]{64}")
                || sequenceStart < 1 || sequenceEnd < sequenceStart
                || createdBy == null || createdBy.isBlank()
                || retentionUntil == null
                || traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("AUDIT_ARCHIVE_REQUEST_INVALID");
        }
    }
}
