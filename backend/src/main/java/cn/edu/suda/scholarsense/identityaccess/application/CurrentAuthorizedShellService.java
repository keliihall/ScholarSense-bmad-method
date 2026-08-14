package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.RoleFieldPolicyCatalog;
import cn.edu.suda.scholarsense.identityaccess.domain.RolePackage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic projection service; it consumes current facts and never caches a shell decision. */
public final class CurrentAuthorizedShellService {
    private static final Map<String, String> TITLES = Map.of(
            "care-workbench", "关怀工作台",
            "college-governance", "学院治理",
            "rule-operations-governance", "规则与运营治理",
            "school-dashboard", "校级驾驶舱",
            "collaboration-orders", "协同工单",
            "data-quality", "数据质量",
            "technical-operations", "技术运行面板");

    private final RoleFieldPolicyCatalog policy;

    public CurrentAuthorizedShellService(RoleFieldPolicyCatalog policy) {
        this.policy = java.util.Objects.requireNonNull(policy);
    }

    public CurrentAuthorizedShellProjection project(
            Set<RolePackage> currentRoles,
            List<InstalledShellCapability> installedCapabilities,
            List<InstalledShellActionCapability> installedActions,
            Instant evaluatedAt) {
        currentRoles = Set.copyOf(currentRoles);
        installedCapabilities = List.copyOf(installedCapabilities);
        installedActions = List.copyOf(installedActions);
        java.util.Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (currentRoles.isEmpty()) {
            throw new IllegalArgumentException("IDENTITY_SHELL_ROLE_REQUIRED");
        }
        String defaultId = policy.selectDefaultSurface(currentRoles);
        var defaultSurface = new AuthorizedShellSurface(
                defaultId,
                TITLES.get(defaultId),
                "shell.home",
                "not-installed");

        List<AuthorizedShellMenuItem> menuItems = new ArrayList<>();
        List<AuthorizedShellEntryCapability> entries = new ArrayList<>();
        List<AuthorizedShellActionEntryCapability> actions = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        boolean dependencyUnavailable = false;
        for (InstalledShellCapability capability : installedCapabilities) {
            if (java.util.Collections.disjoint(
                    currentRoles, capability.authorizedRoles())) {
                continue;
            }
            if (!seenIds.add(capability.id())) {
                throw new IllegalArgumentException("IDENTITY_SHELL_CAPABILITY_DUPLICATE");
            }
            entries.add(new AuthorizedShellEntryCapability(
                    capability.id(), capability.state().wireName()));
            if (capability.state() == ShellCapabilityState.UNAVAILABLE) {
                dependencyUnavailable = true;
            }
            if (capability.state() == ShellCapabilityState.AVAILABLE) {
                menuItems.add(new AuthorizedShellMenuItem(
                        capability.id(),
                        capability.label(),
                        capability.routeName(),
                        capability.state().wireName()));
            }
        }
        Set<String> seenActions = new LinkedHashSet<>();
        for (InstalledShellActionCapability action : installedActions) {
            if (java.util.Collections.disjoint(currentRoles, action.authorizedRoles())) continue;
            if (!seenActions.add(action.actionType())) {
                throw new IllegalArgumentException("IDENTITY_SHELL_ACTION_DUPLICATE");
            }
            actions.add(new AuthorizedShellActionEntryCapability(
                    action.actionType(), action.state().wireName()));
            if (action.state() == ShellCapabilityState.UNAVAILABLE) dependencyUnavailable = true;
        }
        return new CurrentAuthorizedShellProjection(
                "AUTHORIZED-SHELL-1.1.0",
                RoleFieldPolicyCatalog.POLICY_VERSION,
                "RFP-FIXTURE-1.0.0",
                evaluatedAt,
                defaultSurface,
                menuItems,
                entries,
                actions,
                dependencyUnavailable ? "unavailable" : "available");
    }

    public CurrentAuthorizedShellProjection project(
            Set<RolePackage> currentRoles,
            List<InstalledShellCapability> installedCapabilities,
            Instant evaluatedAt) {
        return project(currentRoles, installedCapabilities, List.of(), evaluatedAt);
    }
}
