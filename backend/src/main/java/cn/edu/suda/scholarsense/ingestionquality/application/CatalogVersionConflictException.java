package cn.edu.suda.scholarsense.ingestionquality.application;

public final class CatalogVersionConflictException extends RuntimeException {
    private final long currentVersion;

    public CatalogVersionConflictException(long currentVersion) {
        super("INGESTION_QUALITY_VERSION_CONFLICT");
        this.currentVersion = currentVersion;
    }

    public long currentVersion() { return currentVersion; }
}
