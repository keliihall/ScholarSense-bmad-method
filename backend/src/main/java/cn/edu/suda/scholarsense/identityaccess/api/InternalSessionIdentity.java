package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;

/** Narrow server-to-server identity result; it is not an HTTP response contract. */
public record InternalSessionIdentity(
        boolean authenticated,
        String sessionPseudonym,
        String actorPseudonym,
        long sessionVersion,
        Instant expiresAt,
        Instant warningAt,
        String profileVersion) {
    public InternalSessionIdentity {
        if (!authenticated
                || sessionPseudonym == null || sessionPseudonym.isBlank()
                || actorPseudonym == null || actorPseudonym.isBlank()) {
            throw new IllegalArgumentException("IDENTITY_INTERNAL_SESSION_INVALID");
        }
    }
}
