package cn.edu.suda.scholarsense.auditoperations.application;

public interface AuditArchiveEvidencePort {
    /**
     * Durably records intent before immutable storage is touched. Repeating an identical intent is
     * idempotent; a conflicting intent for the same manifest id must fail closed.
     */
    AuditArchiveIntent prepare(AuditArchiveIntent intent);

    /** Atomically advances the prepared intent to verified evidence. */
    void appendVerified(AuditArchiveManifest manifest);
}
