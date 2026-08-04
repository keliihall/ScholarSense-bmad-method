package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;

public record CatalogAuditRelayClaim(LocalAuditOutboxRecord source, long attempts) {
    public CatalogAuditRelayClaim {
        if (source == null || attempts < 1) throw new IllegalArgumentException("INGESTION_QUALITY_AUDIT_CLAIM_INVALID");
    }
}
