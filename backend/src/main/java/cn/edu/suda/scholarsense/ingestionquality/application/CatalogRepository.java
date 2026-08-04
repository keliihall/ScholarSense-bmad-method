package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogRepository {
    Optional<DataSourceCatalog> find(UUID catalogId);
    List<DataSourceCatalog> list(int offset, int limit);
    Optional<DataSourceCatalog> current();
    void save(DataSourceCatalog catalog, long expectedVersion);
}
