package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Cross-module, transport-neutral current identity. It intentionally contains no source identifiers. */
public record AuthoritativeIdentityContext(
        UUID accountId,
        List<String> roleIds,
        List<UUID> organizationIds,
        long aggregateVersion,
        long sourceVersion,
        long sourceWatermark,
        IdentityFreshness freshness,
        Map<String, String> policyVersions,
        Instant effectiveAt) {
    private static final Set<String> ROLES = Set.of(
            "R1-COUNSELOR",
            "R2-COLLEGE-MANAGER",
            "R3-STUDENT-AFFAIRS",
            "R4-SCHOOL-LEADER",
            "R5-COLLABORATOR",
            "R6-DATA-OWNER",
            "R7-PLATFORM-OPS");
    private static final Set<String> REQUIRED_POLICIES = Set.of(
            "identitySessionPolicy", "roleFieldPolicy", "roleMapping",
            "roleMappingDigest");

    public AuthoritativeIdentityContext {
        requireUuidV7(accountId);
        roleIds = List.copyOf(roleIds);
        if (roleIds.isEmpty()
                || roleIds.stream().anyMatch(role -> !ROLES.contains(role))
                || roleIds.size() != Set.copyOf(roleIds).size()) {
            throw new IllegalArgumentException("IDENTITY_CONTEXT_ROLES_INVALID");
        }
        organizationIds = List.copyOf(organizationIds);
        if (organizationIds.isEmpty()) {
            throw new IllegalArgumentException("IDENTITY_CONTEXT_ORGANIZATIONS_REQUIRED");
        }
        organizationIds.forEach(AuthoritativeIdentityContext::requireUuidV7);
        if (aggregateVersion < 1 || sourceVersion < 1 || sourceWatermark < 1) {
            throw new IllegalArgumentException("IDENTITY_CONTEXT_VERSION_INVALID");
        }
        Objects.requireNonNull(freshness, "freshness");
        policyVersions = Map.copyOf(policyVersions);
        if (!policyVersions.keySet().equals(REQUIRED_POLICIES)
                || !policyVersions.get("identitySessionPolicy")
                        .matches("[A-Z][A-Z0-9.-]{2,63}")
                || !policyVersions.get("roleFieldPolicy")
                        .matches("[A-Z][A-Z0-9.-]{2,63}")
                || !policyVersions.get("roleMapping")
                        .matches("[A-Z][A-Z0-9.-]{2,63}")
                || !policyVersions.get("roleMappingDigest")
                        .matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("IDENTITY_CONTEXT_POLICIES_INVALID");
        }
        Objects.requireNonNull(effectiveAt, "effectiveAt");
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new IllegalArgumentException("IDENTITY_CONTEXT_UUIDV7_REQUIRED");
        }
    }
}
