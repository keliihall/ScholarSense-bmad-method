package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogRepository {
    Optional<DataSourceCatalog> find(UUID catalogId);
    List<DataSourceCatalog> list(int offset, int limit);
    Optional<CatalogCurrentPointer> currentPointer();
    default Optional<DataSourceCatalog> current() {
        return currentPointer().flatMap(pointer -> find(pointer.catalogId()));
    }
    void save(DataSourceCatalog catalog, long expectedVersion);
    default void saveValidation(
            DataSourceCatalog catalog, long expectedVersion, String traceId) {
        save(catalog, expectedVersion);
    }
    void publish(
            DataSourceCatalog catalog,
            long expectedVersion,
            long expectedCurrentVersion,
            CatalogEvidenceSet evidence);
}
