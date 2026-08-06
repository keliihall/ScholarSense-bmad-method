package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import java.util.Objects;
import java.util.UUID;

public record CatalogCurrentPointer(UUID catalogId, long catalogAggregateVersion, long pointerVersion) {
    public CatalogCurrentPointer {
        Objects.requireNonNull(catalogId);
        if (catalogAggregateVersion < 1
                || catalogAggregateVersion > DataSourceCatalog.MAX_VERSION
                || pointerVersion < 1
                || pointerVersion > DataSourceCatalog.MAX_VERSION) {
            throw new IllegalArgumentException("INGESTION_QUALITY_CURRENT_POINTER_INVALID");
        }
    }
}
