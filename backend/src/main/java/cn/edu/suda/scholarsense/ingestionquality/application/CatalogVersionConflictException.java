package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;

public final class CatalogVersionConflictException extends RuntimeException {
    private final long currentVersion;

    public CatalogVersionConflictException(long currentVersion) {
        super("INGESTION_QUALITY_VERSION_CONFLICT");
        if (currentVersion < 0 || currentVersion > DataSourceCatalog.MAX_VERSION) {
            throw new IllegalArgumentException("INGESTION_QUALITY_AGGREGATE_VERSION_INVALID");
        }
        this.currentVersion = currentVersion;
    }

    public long currentVersion() { return currentVersion; }
}
