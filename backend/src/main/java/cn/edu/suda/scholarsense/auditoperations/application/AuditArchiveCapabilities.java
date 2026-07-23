package cn.edu.suda.scholarsense.auditoperations.application;

public record AuditArchiveCapabilities(
        boolean independentFailureDomain,
        boolean appendOnly,
        boolean objectVersioning,
        boolean retentionEnforced,
        boolean legalHoldSupported,
        boolean readAfterWrite) {
    public static AuditArchiveCapabilities required() {
        return new AuditArchiveCapabilities(true, true, true, true, true, true);
    }

    public boolean satisfiesProductionBoundary() {
        return independentFailureDomain && appendOnly && objectVersioning
                && retentionEnforced && legalHoldSupported && readAfterWrite;
    }
}
