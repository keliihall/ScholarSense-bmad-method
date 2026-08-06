package cn.edu.suda.scholarsense.ingestionquality.application;

@FunctionalInterface
public interface CatalogPublicationGuard {
    void requireAvailable(String traceId);
}
