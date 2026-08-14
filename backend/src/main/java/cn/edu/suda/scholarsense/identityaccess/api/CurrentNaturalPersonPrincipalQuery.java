package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.UUID;

/** Resolves an authenticated account to the identity-authority-owned natural person. */
public record CurrentNaturalPersonPrincipalQuery(UUID accountId, String traceId) {
    public CurrentNaturalPersonPrincipalQuery {
        if (accountId == null || accountId.version() != 7 || accountId.variant() != 2
                || traceId == null || !traceId.matches("(?!0{32})[0-9a-f]{32}")) {
            throw new IllegalArgumentException("IDENTITY_NATURAL_PERSON_QUERY_INVALID");
        }
    }
}
