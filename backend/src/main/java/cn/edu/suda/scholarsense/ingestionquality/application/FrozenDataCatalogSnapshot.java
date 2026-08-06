package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyBinding;
import cn.edu.suda.scholarsense.ingestionquality.domain.SourceContract;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record FrozenDataCatalogSnapshot(
        UUID catalogId,
        String contractVersion,
        List<SourceContract> sources,
        List<DependencyBinding> dependencies,
        String contentDigest,
        Instant occurredAt,
        CatalogEvidenceSet evidence) {
    public FrozenDataCatalogSnapshot {
        Objects.requireNonNull(catalogId);
        sources = List.copyOf(sources).stream()
                .sorted(Comparator.comparing(SourceContract::sourceId)).toList();
        dependencies = List.copyOf(dependencies).stream()
                .sorted(Comparator.comparing(DependencyBinding::dependencyId)).toList();
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(evidence);
    }

    public DataSourceCatalog draft() {
        return DataSourceCatalog.draft(
                catalogId, contractVersion, sources, dependencies, contentDigest, occurredAt);
    }
}
