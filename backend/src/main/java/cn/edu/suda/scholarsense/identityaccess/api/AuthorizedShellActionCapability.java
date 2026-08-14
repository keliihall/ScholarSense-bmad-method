package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.Set;

/** Runtime activation signal for a literal action; never an object authorization decision. */
public record AuthorizedShellActionCapability(
        String actionType,
        AuthorizedShellCapabilityState state,
        Set<String> authorizedRoleIds) {
    public AuthorizedShellActionCapability {
        if (!"quality-fuse.recover".equals(actionType)) {
            throw new IllegalArgumentException("IDENTITY_SHELL_ACTION_TYPE_INVALID");
        }
        java.util.Objects.requireNonNull(state, "state");
        authorizedRoleIds = Set.copyOf(authorizedRoleIds);
        if (!authorizedRoleIds.equals(Set.of("R6-DATA-OWNER"))) {
            throw new IllegalArgumentException("IDENTITY_SHELL_ACTION_ROLES_INVALID");
        }
    }
}
