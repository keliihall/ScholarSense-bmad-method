package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.RolePackage;
import java.util.Objects;
import java.util.Set;

/** One installed provider contribution after adapter-level contract validation. */
public record InstalledShellCapability(
        String id,
        String label,
        String routeName,
        ShellCapabilityState state,
        Set<RolePackage> authorizedRoles) {
    public InstalledShellCapability {
        if (id == null || !id.matches("[a-z][a-z0-9.-]{2,63}")) {
            throw new IllegalArgumentException("IDENTITY_SHELL_CAPABILITY_ID_INVALID");
        }
        if (label == null || label.isBlank() || label.length() > 64) {
            throw new IllegalArgumentException("IDENTITY_SHELL_CAPABILITY_LABEL_INVALID");
        }
        if (routeName == null || !routeName.matches("[a-z][a-z0-9.-]{2,63}")) {
            throw new IllegalArgumentException("IDENTITY_SHELL_CAPABILITY_ROUTE_INVALID");
        }
        Objects.requireNonNull(state, "state");
        authorizedRoles = Set.copyOf(authorizedRoles);
        if (authorizedRoles.isEmpty()) {
            throw new IllegalArgumentException("IDENTITY_SHELL_CAPABILITY_ROLES_REQUIRED");
        }
    }
}
