package cn.edu.suda.scholarsense.ingestionquality.domain;

public final class IngestionQualityException extends RuntimeException {
    private final String code;

    public IngestionQualityException(IngestionQualityErrorCode code) {
        super(code.name());
        this.code = code.name();
    }

    public String code() {
        return code;
    }
}
