package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;

public record AuditSearchRelayClaim(LocalAuditOutboxRecord source, long attempts) {
    public AuditSearchRelayClaim {
        if (source == null || attempts < 1) throw new IllegalArgumentException("AUDIT_SEARCH_RELAY_CLAIM_INVALID");
    }
}
