package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.List;

public record CurrentAuthorizedShellProjection(
        String schemaVersion,
        String policyVersion,
        String fixtureVersion,
        Instant evaluatedAt,
        AuthorizedShellSurface defaultSurface,
        List<AuthorizedShellMenuItem> menuItems,
        List<AuthorizedShellEntryCapability> entryCapabilities,
        List<AuthorizedShellActionEntryCapability> actionCapabilities,
        String dependencyStatus) {
    public CurrentAuthorizedShellProjection {
        menuItems = List.copyOf(menuItems);
        entryCapabilities = List.copyOf(entryCapabilities);
        actionCapabilities = List.copyOf(actionCapabilities);
    }
}
