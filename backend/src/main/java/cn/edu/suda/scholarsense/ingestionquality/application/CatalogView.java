package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogValidationFailure;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CatalogView(
        UUID catalogId,
        UUID catalogReleaseId,
        String contractVersion,
        CatalogStatus status,
        long aggregateVersion,
        long currentPointerVersion,
        String contentDigest,
        String evidenceSetDigest,
        List<CatalogValidationFailure> validationFailures,
        Instant updatedAt,
        Instant publishedAt) {

    public CatalogView {
        if (aggregateVersion < 1 || aggregateVersion > DataSourceCatalog.MAX_VERSION
                || currentPointerVersion < 0
                || currentPointerVersion > DataSourceCatalog.MAX_VERSION) {
            throw new IllegalArgumentException("INGESTION_QUALITY_AGGREGATE_VERSION_INVALID");
        }
        validationFailures = List.copyOf(validationFailures);
    }

    public static CatalogView from(DataSourceCatalog catalog, long currentPointerVersion) {
        return new CatalogView(
                catalog.catalogId(), catalog.catalogReleaseId(), catalog.contractVersion(),
                catalog.status(), catalog.aggregateVersion(), currentPointerVersion, catalog.contentDigest(),
                catalog.evidenceSetDigest(), catalog.validationFailures(), catalog.updatedAt(),
                catalog.publishedAt());
    }

    public static CatalogView from(DataSourceCatalog catalog) {
        return from(catalog, 0);
    }
}
