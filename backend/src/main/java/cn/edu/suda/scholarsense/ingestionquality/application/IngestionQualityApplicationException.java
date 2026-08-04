package cn.edu.suda.scholarsense.ingestionquality.application;

public final class IngestionQualityApplicationException extends RuntimeException {
    private final String code;
    private final long currentVersion;

    public IngestionQualityApplicationException(String code) {
        this(code, -1);
    }

    public IngestionQualityApplicationException(String code, long currentVersion) {
        super(code);
        this.code = code;
        this.currentVersion = currentVersion;
    }

    public String code() { return code; }
    public long currentVersion() { return currentVersion; }
}
