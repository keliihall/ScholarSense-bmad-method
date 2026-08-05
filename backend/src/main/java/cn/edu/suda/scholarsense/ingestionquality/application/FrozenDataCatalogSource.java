package cn.edu.suda.scholarsense.ingestionquality.application;

@FunctionalInterface
public interface FrozenDataCatalogSource {
    FrozenDataCatalogSnapshot load();
}
