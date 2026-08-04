package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.Objects;

public record CatalogValidationFailure(String code, String fieldPath) {
    public CatalogValidationFailure {
        if (code == null || !code.matches("[A-Z0-9_]{4,96}")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_VALIDATION_CODE_INVALID");
        }
        if (fieldPath == null || fieldPath.isBlank() || fieldPath.length() > 256) {
            throw new IllegalArgumentException("INGESTION_QUALITY_FIELD_PATH_INVALID");
        }
        Objects.requireNonNull(code);
    }
}
