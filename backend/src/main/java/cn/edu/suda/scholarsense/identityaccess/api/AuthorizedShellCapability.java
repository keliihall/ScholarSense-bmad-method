package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.Set;

/** Public, static capability contribution. It is not an object authorization decision. */
public record AuthorizedShellCapability(
        String id,
        String label,
        String routeName,
        AuthorizedShellCapabilityState state,
        Set<String> authorizedRoleIds) {
    private static final Set<String> ROLES = Set.of(
            "R1-COUNSELOR",
            "R2-COLLEGE-MANAGER",
            "R3-STUDENT-AFFAIRS",
            "R4-SCHOOL-LEADER",
            "R5-COLLABORATOR",
            "R6-DATA-OWNER",
            "R7-PLATFORM-OPS");

    public AuthorizedShellCapability {
        if (id == null || !id.matches("[a-z][a-z0-9.-]{2,63}")) {
            throw new IllegalArgumentException("IDENTITY_SHELL_CAPABILITY_ID_INVALID");
        }
        if (label == null || label.isBlank() || label.length() > 64) {
            throw new IllegalArgumentException("IDENTITY_SHELL_CAPABILITY_LABEL_INVALID");
        }
        if (routeName == null || !routeName.matches("[a-z][a-z0-9.-]{2,63}")) {
            throw new IllegalArgumentException("IDENTITY_SHELL_CAPABILITY_ROUTE_INVALID");
        }
        java.util.Objects.requireNonNull(state, "state");
        authorizedRoleIds = Set.copyOf(authorizedRoleIds);
        if (authorizedRoleIds.isEmpty()
                || authorizedRoleIds.stream().anyMatch(role -> !ROLES.contains(role))) {
            throw new IllegalArgumentException("IDENTITY_SHELL_CAPABILITY_ROLES_INVALID");
        }
    }
}
