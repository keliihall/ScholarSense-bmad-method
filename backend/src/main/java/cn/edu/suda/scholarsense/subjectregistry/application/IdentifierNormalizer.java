package cn.edu.suda.scholarsense.subjectregistry.application;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

public final class IdentifierNormalizer {
    private IdentifierNormalizer() {}

    public static String normalize(String raw, NormalizationProfile profile) {
        Objects.requireNonNull(profile);
        if (raw == null) throw invalid();
        String normalized = trimUnicodeWhitespace(Normalizer.normalize(raw, Normalizer.Form.NFKC));
        if (normalized.isEmpty() || normalized.length() > 128
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return switch (profile) {
            case UPPERCASE_PRESERVE_LEADING_ZERO_V1 -> normalized.toUpperCase(Locale.ROOT);
            case LOWERCASE_PRESERVE_LEADING_ZERO_V1 -> normalized.toLowerCase(Locale.ROOT);
            case EXACT_PRESERVE_CASE_V1 -> normalized;
        };
    }

    private static String trimUnicodeWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static SubjectRegistryApplicationException invalid() {
        return new SubjectRegistryApplicationException("SUBJECT_REGISTRY_IDENTIFIER_INVALID");
    }
}
