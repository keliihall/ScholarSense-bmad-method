package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import cn.edu.suda.scholarsense.identityaccess.domain.RoleFieldPolicyCatalog;
import cn.edu.suda.scholarsense.identityaccess.domain.RolePackage;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CurrentAuthorizedShellServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private final CurrentAuthorizedShellService service =
            new CurrentAuthorizedShellService(RoleFieldPolicyCatalog.approved());

    @Test
    void everySingleRoleGetsItsFrozenDefaultSurfaceWithoutFakeProviderContent() {
        var expected = java.util.Map.of(
                RolePackage.R1, "care-workbench",
                RolePackage.R2, "college-governance",
                RolePackage.R3, "rule-operations-governance",
                RolePackage.R4, "school-dashboard",
                RolePackage.R5, "collaboration-orders",
                RolePackage.R6, "data-quality",
                RolePackage.R7, "technical-operations");

        expected.forEach((role, surface) -> {
            var projection = service.project(Set.of(role), List.of(), NOW);
            assertEquals(surface, projection.defaultSurface().surfaceId());
            assertEquals("shell.home", projection.defaultSurface().routeName());
            assertEquals("not-installed", projection.defaultSurface().providerState());
            assertEquals(List.of(), projection.menuItems());
            assertEquals(List.of(), projection.entryCapabilities());
        });
    }

    @Test
    void multiRoleUsesStablePriorityAndOnlyAuthorizedInstalledCapabilitiesBecomeMenus() {
        var capabilities = List.of(
                new InstalledShellCapability(
                        "identity-session",
                        "当前会话",
                        "shell.session",
                        ShellCapabilityState.AVAILABLE,
                        Set.of(RolePackage.R1, RolePackage.R7)),
                new InstalledShellCapability(
                        "audit-search",
                        "审计检索",
                        "audit.search",
                        ShellCapabilityState.AVAILABLE,
                        Set.of(RolePackage.R3, RolePackage.R7)),
                new InstalledShellCapability(
                        "future-mock",
                        "0 条待办",
                        "baseline.home",
                        ShellCapabilityState.NOT_INSTALLED,
                        Set.of(RolePackage.R1)));

        var projection = service.project(
                Set.of(RolePackage.R7, RolePackage.R1), capabilities, NOW);

        assertEquals("care-workbench", projection.defaultSurface().surfaceId());
        assertEquals(List.of("identity-session", "audit-search"),
                projection.menuItems().stream().map(AuthorizedShellMenuItem::id).toList());
        assertFalse(projection.menuItems().stream()
                .anyMatch(item -> item.routeName().startsWith("baseline")));
        assertEquals(List.of("identity-session", "audit-search", "future-mock"),
                projection.entryCapabilities().stream()
                        .map(AuthorizedShellEntryCapability::id)
                        .toList());
        assertEquals("not-installed", projection.entryCapabilities().get(2).state());
    }

    @Test
    void unavailableInstalledProviderIsHonestButNeverRenderedAsAnAuthorizedMenu() {
        var projection = service.project(
                Set.of(RolePackage.R3),
                List.of(new InstalledShellCapability(
                        "audit-search",
                        "审计检索",
                        "audit.search",
                        ShellCapabilityState.UNAVAILABLE,
                        Set.of(RolePackage.R3, RolePackage.R7))),
                NOW);

        assertEquals(List.of(), projection.menuItems());
        assertEquals("unavailable", projection.entryCapabilities().getFirst().state());
        assertEquals("unavailable", projection.dependencyStatus());
    }
}
