package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;

@FunctionalInterface
public interface CatalogAuthorizationPort {
    CatalogAuthorizationDecision authorize(
            String actorRef,
            String sourceAction,
            String dependencyAction,
            DataSourceCatalog catalog,
            String traceId);
}
