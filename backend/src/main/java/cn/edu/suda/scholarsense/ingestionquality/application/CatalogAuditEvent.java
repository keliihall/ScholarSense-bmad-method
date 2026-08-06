package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import java.time.Instant;
import java.util.UUID;

public record CatalogAuditEvent(
        String action,
        String result,
        UUID catalogId,
        Long aggregateVersion,
        String auditActorRef,
        String sourceIp,
        String traceId,
        Instant occurredAt,
        TimeSourceProfile timeSourceProfile,
        String idempotencyKeyDigest) {
    public CatalogAuditEvent {
        if (aggregateVersion != null
                && (aggregateVersion < 1 || aggregateVersion > DataSourceCatalog.MAX_VERSION)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_AGGREGATE_VERSION_INVALID");
        }
    }
}
