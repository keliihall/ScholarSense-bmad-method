package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;
import java.util.UUID;

public record AuditArchiveManifest(
        String schemaVersion,
        String scheduleVersion,
        UUID manifestId,
        String scopeType,
        String fixtureId,
        String scopeHash,
        long sequenceStart,
        long sequenceEnd,
        long recordCount,
        String firstPreviousHash,
        String lastEntryHash,
        String archiveObjectId,
        String archiveObjectVersionId,
        String contentDigest,
        String createdBy,
        Instant trustedCreatedAt,
        String traceId,
        boolean readBackVerified,
        Instant retentionUntil) {
    public AuditArchiveManifest {
        if (!"AUDIT-ARCHIVE-MANIFEST-1.0.0".equals(schemaVersion)
                || !"RS-1.0.0".equals(scheduleVersion)
                || manifestId == null || manifestId.version() != 7 || manifestId.variant() != 2
                || !"audit-domain".equals(scopeType) || fixtureId == null || fixtureId.isBlank()
                || scopeHash == null || !scopeHash.matches("[0-9a-f]{64}")
                || sequenceStart < 1 || sequenceEnd < sequenceStart
                || recordCount != sequenceEnd - sequenceStart + 1
                || !hash(firstPreviousHash) || !hash(lastEntryHash) || !hash(contentDigest)
                || archiveObjectId == null || archiveObjectId.isBlank()
                || archiveObjectVersionId == null || archiveObjectVersionId.isBlank()
                || createdBy == null || createdBy.isBlank() || trustedCreatedAt == null
                || traceId == null || !traceId.matches("[0-9a-f]{32}")
                || !readBackVerified || retentionUntil == null || !retentionUntil.isAfter(trustedCreatedAt)) {
            throw new IllegalArgumentException("AUDIT_ARCHIVE_MANIFEST_INVALID");
        }
    }

    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
