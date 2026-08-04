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
        String contentDigest,
        String evidenceSetDigest,
        List<CatalogValidationFailure> validationFailures,
        Instant updatedAt,
        Instant publishedAt) {

    public static CatalogView from(DataSourceCatalog catalog) {
        return new CatalogView(
                catalog.catalogId(), catalog.catalogReleaseId(), catalog.contractVersion(),
                catalog.status(), catalog.aggregateVersion(), catalog.contentDigest(),
                catalog.evidenceSetDigest(), catalog.validationFailures(), catalog.updatedAt(),
                catalog.publishedAt());
    }
}
