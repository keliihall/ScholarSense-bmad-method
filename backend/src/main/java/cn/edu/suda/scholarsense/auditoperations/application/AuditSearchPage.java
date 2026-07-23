package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;
import java.util.List;

public record AuditSearchPage(
        List<ProjectedAuditRecord> items,
        int page,
        int size,
        long total,
        long asOfSequence,
        long sourceLedgerHead,
        long projectionWatermark,
        Instant dataCutoffAt,
        String retentionScheduleVersion,
        String roleFieldPolicyVersion,
        String projectionStatus) {
    public AuditSearchPage {
        items = List.copyOf(items);
    }
}
