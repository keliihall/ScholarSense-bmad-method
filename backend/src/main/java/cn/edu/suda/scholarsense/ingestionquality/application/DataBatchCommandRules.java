package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatch;
import java.util.UUID;

final class DataBatchCommandRules {
    private DataBatchCommandRules() {}

    static String text(String value, int maximumLength) {
        if (value == null || value.isBlank() || maximumLength < 1) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
        int scalarCount = 0;
        for (int offset = 0; offset < value.length(); ) {
            char current = value.charAt(offset);
            if (Character.isHighSurrogate(current)) {
                if (offset + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(offset + 1))) {
                    throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
                }
                offset += 2;
            } else {
                if (Character.isLowSurrogate(current)) {
                    throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
                }
                offset++;
            }
            if (++scalarCount > maximumLength) {
                throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
            }
        }
        return value;
    }

    static UUID uuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
        return value;
    }

    static long expectedVersion(long value) {
        if (value < 1 || value > DataBatch.MAX_VERSION) {
            throw new IllegalArgumentException("INGESTION_QUALITY_EXPECTED_VERSION_INVALID");
        }
        return value;
    }

    static String digest(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
        return value;
    }

    static String impactScopeCode(String value) {
        String code = text(value, 64);
        if (!code.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
        return code;
    }

    static String traceId(String value) {
        if (value == null || !value.matches("[0-9a-f]{32}")
                || value.matches("0{32}")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
        return value;
    }
}
