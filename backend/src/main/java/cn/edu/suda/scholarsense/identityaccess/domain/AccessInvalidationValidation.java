package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.Objects;
import java.util.UUID;

final class AccessInvalidationValidation {
    private AccessInvalidationValidation() {}

    static UUID uuidV7(UUID value, String code) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new IllegalArgumentException(code + "_UUIDV7_REQUIRED");
        }
        return value;
    }

    static String token(String value, String prefix, String code) {
        if (value == null
                || !value.matches(prefix + "[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalArgumentException(code + "_TOKEN_INVALID");
        }
        return value;
    }

    static String digest(String value, String code) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(code + "_DIGEST_INVALID");
        }
        return value;
    }

    static String traceId(String value) {
        if (value == null || !value.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_TRACE_ID_INVALID");
        }
        return value;
    }

    static String identifier(String value, String code) {
        if (value == null
                || !value.matches("[a-z][a-z0-9-]{1,63}")) {
            throw new IllegalArgumentException(code + "_INVALID");
        }
        return value;
    }

    static String partitionIdentifier(String value, String code) {
        if (value == null
                || !value.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                || value.length() > 64) {
            throw new IllegalArgumentException(code + "_INVALID");
        }
        return value;
    }

    static long positive(long value, String code) {
        if (value < 1) {
            throw new IllegalArgumentException(code + "_INVALID");
        }
        return value;
    }

    static long nonNegative(long value, String code) {
        if (value < 0) {
            throw new IllegalArgumentException(code + "_INVALID");
        }
        return value;
    }

    static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name);
    }
}
