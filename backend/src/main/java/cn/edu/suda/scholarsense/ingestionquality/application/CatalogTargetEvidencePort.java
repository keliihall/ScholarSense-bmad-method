package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;

@FunctionalInterface
public interface CatalogTargetEvidencePort {
    CatalogEvidenceSet verifiedEvidenceFor(DataSourceCatalog catalog);
}
