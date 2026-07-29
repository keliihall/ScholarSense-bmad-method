package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.UUID;

final class AuthorityValidation {
    static final String SOURCE_ID = "SRC-P0-RESPONSIBILITY-001";

    private AuthorityValidation() {}

    static UUID uuidV7(UUID value, String field) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new IllegalArgumentException(field + "_UUIDV7_REQUIRED");
        }
        return value;
    }

    static String sourceId(String value) {
        if (!SOURCE_ID.equals(value)) {
            throw new IllegalArgumentException("IDENTITY_SOURCE_UNTRUSTED");
        }
        return value;
    }

    static String digest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + "_DIGEST_INVALID");
        }
        return value;
    }

    static long positive(long value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + "_VERSION_INVALID");
        }
        return value;
    }
}
