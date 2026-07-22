package cn.edu.suda.scholarsense.auditoperations.application;

/** Exact thresholds resolved from the versioned ingestion/backlog policy reference. */
public record AuditBacklogPolicy(
        String version,
        long measurementStaleAfterSeconds,
        long degradedAgeSeconds,
        long degradedCount,
        long blockedAgeSeconds,
        long blockedCount,
        int recoveryHealthyObservations) {

    public AuditBacklogPolicy {
        if (version == null || version.isBlank()
                || measurementStaleAfterSeconds < 1
                || degradedAgeSeconds < 1
                || degradedCount < 1
                || blockedAgeSeconds <= degradedAgeSeconds
                || blockedCount <= degradedCount
                || recoveryHealthyObservations < 1) {
            throw new IllegalArgumentException("AUDIT_BACKLOG_POLICY_INVALID");
        }
    }

    public static AuditBacklogPolicy version1() {
        return new AuditBacklogPolicy(
                "AUDIT-INGESTION-POLICY-1.0.0", 45, 60, 10_000, 300, 50_000, 2);
    }
}
