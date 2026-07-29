package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.domain.OrganizationType;
import cn.edu.suda.scholarsense.identityaccess.domain.TargetRole;
import cn.edu.suda.scholarsense.runtime.IdentityAuthorityRuntimeProfile;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Immutable, source-owner-provided role catalog resolved from the controlled runtime resource.
 *
 * <p>The adapter must not infer a role from names or organization paths. Every accepted source
 * code is pinned here by version and content digest.
 */
public final class ApprovedIdentityRoleMapping {
    private static final String VERSION = "IDENTITY-ROLE-MAPPING-1.0.0";
    private static final String SOURCE_ID = "SRC-P0-RESPONSIBILITY-001";
    private static final String SOURCE_OWNER =
            "SRC-P0-RESPONSIBILITY-001 controlled sandbox source owner";
    private static final Set<String> ROOT_FIELDS = Set.of(
            "version", "sourceId", "environment", "productionEligible", "providedBy",
            "approvedBy", "approvedAt", "effectiveAt", "digest", "entries");
    private final Map<String, Entry> entries;

    private ApprovedIdentityRoleMapping(Map<String, Entry> entries) {
        this.entries = Map.copyOf(entries);
    }

    public static ApprovedIdentityRoleMapping from(
            IdentityAuthorityRuntimeProfile profile, ObjectMapper json) {
        return from(profile, json, ApprovedIdentityRoleMapping.class::getResourceAsStream);
    }

    static ApprovedIdentityRoleMapping from(
            IdentityAuthorityRuntimeProfile profile,
            ObjectMapper json,
            Function<String, InputStream> resources) {
        try {
            URI reference = URI.create(profile.roleMappingReference());
            String path = "/identity-authority-runtime" + reference.getPath() + ".json";
            try (InputStream stream = resources.apply(path)) {
                if (stream == null) {
                    throw invalid();
                }
                JsonNode root = json.readTree(stream);
                requireExact(root, ROOT_FIELDS);
                if (!VERSION.equals(text(root, "version"))
                        || !profile.roleMappingVersion().equals(text(root, "version"))
                        || !SOURCE_ID.equals(text(root, "sourceId"))
                        || !"sandbox".equals(text(root, "environment"))
                        || root.required("productionEligible").asBoolean(true)
                        || !SOURCE_OWNER.equals(text(root, "providedBy"))
                        || !"Hei".equals(text(root, "approvedBy"))) {
                    throw invalid();
                }
                Instant approvedAt = Instant.parse(text(root, "approvedAt"));
                Instant effectiveAt = Instant.parse(text(root, "effectiveAt"));
                if (effectiveAt.isAfter(approvedAt)) {
                    throw invalid();
                }
                JsonNode values = root.required("entries");
                if (!values.isArray() || values.size() != TargetRole.values().length) {
                    throw invalid();
                }
                Map<String, Entry> parsed = new LinkedHashMap<>();
                EnumSet<TargetRole> roles = EnumSet.noneOf(TargetRole.class);
                List<Entry> ordered = new ArrayList<>();
                values.forEach(value -> {
                    Entry entry = parseEntry(value);
                    if (parsed.putIfAbsent(entry.sourceRoleCode(), entry) != null
                            || !roles.add(entry.targetRole())) {
                        throw invalid();
                    }
                    ordered.add(entry);
                });
                if (roles.size() != TargetRole.values().length) {
                    throw invalid();
                }
                String digest = "sha256:" + sha256(canonical(ordered));
                if (!digest.equals(text(root, "digest"))
                        || !digest.equals(profile.roleMappingDigest())) {
                    throw invalid();
                }
                return new ApprovedIdentityRoleMapping(parsed);
            }
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalStateException state
                    && "IDENTITY_ROLE_MAPPING_RESOURCE_INVALID".equals(state.getMessage())) {
                throw state;
            }
            throw invalid();
        }
    }

    public TargetRole resolve(
            String sourceRoleCode, String targetRoleId, OrganizationType organizationType) {
        Entry entry = entries.get(sourceRoleCode);
        if (entry == null) {
            throw new IdentitySyncException("IDENTITY_ROLE_UNKNOWN");
        }
        if (!entry.targetRole().wireName().equals(targetRoleId)
                || !entry.organizationTypes().contains(organizationType)) {
            throw new IdentitySyncException("IDENTITY_ROLE_MAPPING_UNAPPROVED");
        }
        return entry.targetRole();
    }

    private static Entry parseEntry(JsonNode value) {
        requireExact(value, Set.of("sourceRoleCode", "targetRoleId", "conditions"));
        String sourceCode = text(value, "sourceRoleCode");
        if (!sourceCode.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw invalid();
        }
        TargetRole targetRole = TargetRole.fromWire(text(value, "targetRoleId"));
        JsonNode conditions = value.required("conditions");
        Set<String> expectedConditions = targetRole == TargetRole.R7_PLATFORM_OPS
                ? Set.of(
                        "employmentStatus",
                        "organizationTypes",
                        "forbidBusinessRoleSelfGrant")
                : Set.of("employmentStatus", "organizationTypes");
        requireExact(conditions, expectedConditions);
        if (!"active".equals(text(conditions, "employmentStatus"))
                || (targetRole == TargetRole.R7_PLATFORM_OPS
                        && !conditions.required("forbidBusinessRoleSelfGrant")
                                .asBoolean(false))) {
            throw invalid();
        }
        JsonNode organizationTypes = conditions.required("organizationTypes");
        if (!organizationTypes.isArray() || organizationTypes.isEmpty()) {
            throw invalid();
        }
        EnumSet<OrganizationType> organizations =
                EnumSet.noneOf(OrganizationType.class);
        organizationTypes.forEach(node ->
                organizations.add(organizationType(node.asText())));
        if (organizations.size() != organizationTypes.size()) {
            throw invalid();
        }
        return new Entry(
                sourceCode,
                targetRole,
                Set.copyOf(organizations),
                targetRole == TargetRole.R7_PLATFORM_OPS);
    }

    private static OrganizationType organizationType(String value) {
        return switch (value) {
            case "school" -> OrganizationType.SCHOOL;
            case "college" -> OrganizationType.COLLEGE;
            case "department" -> OrganizationType.DEPARTMENT;
            default -> throw invalid();
        };
    }

    private static String canonical(List<Entry> entries) {
        StringBuilder value = new StringBuilder("[");
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) {
                value.append(',');
            }
            Entry entry = entries.get(index);
            value.append("{\"conditions\":{\"employmentStatus\":\"active\",");
            if (entry.forbidBusinessRoleSelfGrant()) {
                value.append("\"forbidBusinessRoleSelfGrant\":true,");
            }
            value.append("\"organizationTypes\":[");
            List<String> organizations = entry.organizationTypes().stream()
                    .map(OrganizationType::wireName)
                    .sorted()
                    .toList();
            for (int organizationIndex = 0;
                    organizationIndex < organizations.size();
                    organizationIndex++) {
                if (organizationIndex > 0) {
                    value.append(',');
                }
                value.append('"').append(organizations.get(organizationIndex)).append('"');
            }
            value.append("]},\"sourceRoleCode\":\"")
                    .append(entry.sourceRoleCode())
                    .append("\",\"targetRoleId\":\"")
                    .append(entry.targetRole().wireName())
                    .append("\"}");
        }
        return value.append(']').toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireExact(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }
        java.util.HashSet<String> actual = new java.util.HashSet<>();
        node.forEachEntry((key, ignored) -> actual.add(key));
        if (!actual.equals(expected)) {
            throw invalid();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid();
        }
        return value.asText();
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("IDENTITY_ROLE_MAPPING_RESOURCE_INVALID");
    }

    private record Entry(
            String sourceRoleCode,
            TargetRole targetRole,
            Set<OrganizationType> organizationTypes,
            boolean forbidBusinessRoleSelfGrant) {}
}
