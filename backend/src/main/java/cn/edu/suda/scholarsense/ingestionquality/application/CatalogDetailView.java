package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyBinding;
import cn.edu.suda.scholarsense.ingestionquality.domain.SourceContract;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CatalogDetailView(
        UUID catalogId, UUID catalogReleaseId, String contractVersion, CatalogStatus status,
        long aggregateVersion, String contentDigest, String evidenceSetDigest,
        List<CatalogValidationFailureView> validationFailures,
        List<SourceContract> sources, List<DependencyBinding> dependencies,
        Instant updatedAt, Instant publishedAt) {
    public CatalogDetailView {
        validationFailures = List.copyOf(validationFailures);
        sources = List.copyOf(sources);
        dependencies = List.copyOf(dependencies);
    }

    public static CatalogDetailView from(DataSourceCatalog catalog) {
        return new CatalogDetailView(
                catalog.catalogId(), catalog.catalogReleaseId(), catalog.contractVersion(), catalog.status(),
                catalog.aggregateVersion(), catalog.contentDigest(), catalog.evidenceSetDigest(),
                catalog.validationFailures().stream()
                        .map(item -> new CatalogValidationFailureView(item.code(), item.fieldPath())).toList(),
                catalog.sources(), catalog.dependencies(), catalog.updatedAt(), catalog.publishedAt());
    }

    public record CatalogValidationFailureView(String code, String fieldPath) {}
}
