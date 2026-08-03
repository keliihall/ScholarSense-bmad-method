package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Narrow object-owner query; it contains opaque identifiers only. */
public record AuthorizationObjectEvidenceQuery(
        String actorPseudonym,
        UUID accountId,
        Set<UUID> organizationIds,
        String objectClass,
        String actionId,
        String objectTokenDigest,
        long expectedObjectVersion,
        Instant serverNow) {
    public AuthorizationObjectEvidenceQuery {
        if (actorPseudonym == null || actorPseudonym.isBlank()) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_ACTOR_REQUIRED");
        }
        requireUuidV7(accountId);
        organizationIds = Set.copyOf(organizationIds);
        organizationIds.forEach(AuthorizationObjectEvidenceQuery::requireUuidV7);
        if (objectClass == null || !objectClass.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_OBJECT_CLASS_INVALID");
        }
        if (actionId == null
                || !actionId.matches("[a-z][a-z0-9.-]{2,127}")
                || actionId.contains("/")) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_ACTION_NOT_EXPANDED");
        }
        if (objectTokenDigest == null || !objectTokenDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_OBJECT_TOKEN_INVALID");
        }
        if (expectedObjectVersion < 1) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_OBJECT_VERSION_INVALID");
        }
        Objects.requireNonNull(serverNow, "serverNow");
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_UUIDV7_REQUIRED");
        }
    }
}
