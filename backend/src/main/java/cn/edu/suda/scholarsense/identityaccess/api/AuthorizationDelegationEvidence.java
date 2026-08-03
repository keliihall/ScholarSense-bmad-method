package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Read-only grant evidence. Grant lifecycle remains owned by its future provider. */
public record AuthorizationDelegationEvidence(
        UUID granteeAccountId,
        Instant startAt,
        Instant endAt,
        boolean basePermissionProven,
        Set<String> allowedObjectClasses,
        Set<String> allowedActions,
        Set<String> allowedFieldClasses,
        long grantVersion) {
    public AuthorizationDelegationEvidence {
        requireUuidV7(granteeAccountId);
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        allowedObjectClasses = Set.copyOf(allowedObjectClasses);
        allowedActions = Set.copyOf(allowedActions);
        allowedFieldClasses = Set.copyOf(allowedFieldClasses);
        if (!startAt.isBefore(endAt) || grantVersion < 1) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_GRANT_INVALID");
        }
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_UUIDV7_REQUIRED");
        }
    }
}
