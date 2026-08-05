package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import java.util.UUID;

public record ValidateCatalogCommand(
        UUID catalogId,
        long expectedVersion,
        CatalogActorContext actorContext,
        String traceId) {
    public ValidateCatalogCommand {
        if (expectedVersion < 1 || expectedVersion > DataSourceCatalog.MAX_VERSION) {
            throw new IllegalArgumentException("INGESTION_QUALITY_EXPECTED_VERSION_INVALID");
        }
    }
}
