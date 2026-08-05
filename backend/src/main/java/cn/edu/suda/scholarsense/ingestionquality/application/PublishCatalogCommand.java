package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import java.util.UUID;

public record PublishCatalogCommand(
        UUID catalogId,
        long expectedVersion,
        long expectedCurrentVersion,
        UUID catalogReleaseId,
        String idempotencyKey,
        String requestDigest,
        CatalogActorContext actorContext,
        String traceId) {
    public PublishCatalogCommand {
        if (expectedVersion < 1 || expectedVersion > DataSourceCatalog.MAX_VERSION
                || expectedCurrentVersion < 0
                || expectedCurrentVersion > DataSourceCatalog.MAX_VERSION) {
            throw new IllegalArgumentException("INGESTION_QUALITY_EXPECTED_VERSION_INVALID");
        }
    }
}
