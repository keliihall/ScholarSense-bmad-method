package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.Objects;
import java.util.UUID;

/** An owner-supplied anchor plus the account or organization to which it is bound. */
public record AuthorizationScopeEvidence(
        AuthorizationScopeAnchor anchor,
        UUID accountId,
        UUID organizationId) {
    public AuthorizationScopeEvidence {
        Objects.requireNonNull(anchor, "anchor");
        if (accountId != null) {
            requireUuidV7(accountId);
        }
        if (organizationId != null) {
            requireUuidV7(organizationId);
        }
        if (requiresBinding(anchor) && accountId == null && organizationId == null) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_ANCHOR_BINDING_REQUIRED");
        }
    }

    private static boolean requiresBinding(AuthorizationScopeAnchor anchor) {
        return anchor != AuthorizationScopeAnchor.SCHOOL_GOVERNANCE
                && anchor != AuthorizationScopeAnchor.SCHOOL_AGGREGATE
                && anchor != AuthorizationScopeAnchor.TECHNICAL_OBJECT;
    }

    private static void requireUuidV7(UUID value) {
        if (value.version() != 7 || value.variant() != 2) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_UUIDV7_REQUIRED");
        }
    }
}
