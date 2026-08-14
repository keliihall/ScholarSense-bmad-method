package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.RolePackage;
import java.util.Set;

public record InstalledShellActionCapability(
        String actionType, ShellCapabilityState state, Set<RolePackage> authorizedRoles) {
    public InstalledShellActionCapability {
        if (!"quality-fuse.recover".equals(actionType)) {
            throw new IllegalArgumentException("IDENTITY_SHELL_ACTION_TYPE_INVALID");
        }
        java.util.Objects.requireNonNull(state, "state");
        authorizedRoles = Set.copyOf(authorizedRoles);
        if (authorizedRoles.isEmpty()) {
            throw new IllegalArgumentException("IDENTITY_SHELL_ACTION_ROLES_REQUIRED");
        }
    }
}
