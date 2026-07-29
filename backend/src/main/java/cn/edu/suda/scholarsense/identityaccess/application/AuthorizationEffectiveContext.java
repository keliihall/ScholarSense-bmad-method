package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.Objects;
import java.util.UUID;

public record AuthorizationEffectiveContext(
        UUID accountId,
        long sourceVersion,
        long watermark,
        AuthorizationFreshness freshness) {
    public AuthorizationEffectiveContext {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(freshness, "freshness");
        if (sourceVersion < 0 || watermark < 0) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_VERSION_INVALID");
        }
    }
}
