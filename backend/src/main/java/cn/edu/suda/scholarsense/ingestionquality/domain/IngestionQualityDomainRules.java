package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.UUID;

final class IngestionQualityDomainRules {
    static final long MAX_SAFE_VERSION = 9_007_199_254_740_991L;

    private IngestionQualityDomainRules() {}

    static String requireText(String value, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw invalid();
        }
        return value;
    }

    static UUID requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw invalid();
        }
        return value;
    }

    static String requireUuidV7(String value) {
        requireText(value, 36);
        try {
            requireUuidV7(UUID.fromString(value));
            return value;
        } catch (IllegalArgumentException error) {
            throw invalid();
        }
    }

    static String requireSha256(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw invalid();
        }
        return value;
    }

    static long requireVersion(long value) {
        if (value < 1 || value > MAX_SAFE_VERSION) {
            throw invalid();
        }
        return value;
    }

    static IngestionQualityException invalid() {
        return new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_CONTRACT_INVALID);
    }
}
