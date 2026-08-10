package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

final class IngestionQualityDomainRules {
    static final long MAX_SAFE_VERSION = 9_007_199_254_740_991L;

    private IngestionQualityDomainRules() {}

    static String requireText(String value, int maximumLength) {
        if (value == null || value.isBlank() || maximumLength < 1) {
            throw invalid();
        }
        int scalarCount = 0;
        for (int offset = 0; offset < value.length(); ) {
            char current = value.charAt(offset);
            if (Character.isHighSurrogate(current)) {
                if (offset + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(offset + 1))) {
                    throw invalid();
                }
                offset += 2;
            } else {
                if (Character.isLowSurrogate(current)) throw invalid();
                offset++;
            }
            if (++scalarCount > maximumLength) throw invalid();
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

    static String requireProductionWatermark(String sourceId, String watermark) {
        requireText(sourceId, 64);
        requireText(watermark, 512);
        if (watermark.matches("sha256:[0-9a-f]{64}")) return watermark;

        String prefix = sourceId.toLowerCase(Locale.ROOT) + "@";
        if (!watermark.startsWith(prefix)) throw invalid();
        String dateText = watermark.substring(prefix.length());
        if (!dateText.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
        try {
            LocalDate date = LocalDate.parse(dateText);
            if (date.getYear() < 1 || date.getYear() > 9_999
                    || !date.toString().equals(dateText)) {
                throw invalid();
            }
        } catch (DateTimeException invalidDate) {
            throw invalid();
        }
        return watermark;
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
