package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;

/** Storage-neutral archive boundary. Production implementations must prove every declared capability. */
public interface AuditArchivePort {
    AuditArchiveCapabilities capabilities();

    ArchiveObjectVersion writeOnce(String objectId, byte[] content, Instant retentionUntil);

    ArchiveReadResult read(String objectId, String versionId);

    void applyLegalHold(String objectId, String versionId, String holdReference);
}
