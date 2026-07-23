package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.List;

public record AuditArchiveSelection(
        boolean ledgerHealthy,
        boolean rangeFullyVerified,
        boolean unresolvedPermanentFinding,
        List<AuditArchiveRecord> records) {
    public AuditArchiveSelection {
        records = List.copyOf(records);
    }
}
