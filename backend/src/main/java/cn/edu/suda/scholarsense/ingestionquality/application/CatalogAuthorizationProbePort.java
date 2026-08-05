package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;

/** Supplies the owner-equivalent catalog shape used to authorize concealed misses. */
@FunctionalInterface
public interface CatalogAuthorizationProbePort {
    DataSourceCatalog authorizationProbe();
}
