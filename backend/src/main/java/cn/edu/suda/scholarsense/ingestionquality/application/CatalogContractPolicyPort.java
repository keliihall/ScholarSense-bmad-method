package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import java.util.List;

@FunctionalInterface
public interface CatalogContractPolicyPort {
    List<CatalogContractViolation> validate(DataSourceCatalog catalog);
}
