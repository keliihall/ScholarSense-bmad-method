package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;

public record AuditSearchSnapshot(long sourceLedgerHead, long projectionWatermark, Instant dataCutoffAt) {
    public AuditSearchSnapshot {
        if (sourceLedgerHead < 0 || projectionWatermark < 0 || projectionWatermark > sourceLedgerHead
                || dataCutoffAt == null) {
            throw new IllegalArgumentException("AUDIT_SEARCH_SNAPSHOT_INVALID");
        }
    }
}
