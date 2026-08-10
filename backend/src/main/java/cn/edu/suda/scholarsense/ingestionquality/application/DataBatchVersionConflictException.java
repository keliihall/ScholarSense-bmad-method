package cn.edu.suda.scholarsense.ingestionquality.application;

public final class DataBatchVersionConflictException extends RuntimeException {
    private final long currentVersion;

    public DataBatchVersionConflictException(long currentVersion) {
        super("INGESTION_QUALITY_VERSION_CONFLICT");
        this.currentVersion = currentVersion;
    }

    public long currentVersion() {
        return currentVersion;
    }
}
