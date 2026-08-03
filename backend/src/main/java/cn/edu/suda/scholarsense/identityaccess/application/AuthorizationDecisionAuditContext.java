package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persisted audit 1.4 evidence kept beside, not inside, the frozen local-audit v1 fact. */
public record AuthorizationDecisionAuditContext(
        String actorPseudonym,
        List<String> rolePackages,
        String actionId,
        String objectTokenDigest,
        String policyVersion,
        List<String> scopeAnchorSummary,
        Long objectVersion,
        Map<String, String> fieldProjectionSummary,
        String result,
        Instant trustedAt,
        String traceId) {
    private static final Set<String> FIELD_CODES = Set.of("B", "I", "C", "S", "E", "N", "G", "T");
    private static final Set<String> VISIBILITY_CODES = Set.of("C", "M", "H");
    private static final Set<String> SCOPE_CODES = Set.of(
            "CURRENT_RESPONSIBILITY",
            "GOVERNANCE_WORK_ITEM",
            "CURRENT_TRANSFER_ASSIGNMENT",
            "OWNED_SOURCE",
            "TECHNICAL_OBJECT",
            "VALID_DELEGATION_GRANT");

    public AuthorizationDecisionAuditContext {
        rolePackages = List.copyOf(rolePackages);
        scopeAnchorSummary = List.copyOf(scopeAnchorSummary);
        fieldProjectionSummary = Map.copyOf(fieldProjectionSummary);
        if (actorPseudonym == null
                || !actorPseudonym.matches("^ast_v1_k[0-9]+_[0-9a-f]{64}$")
                || rolePackages.stream().anyMatch(role -> !role.matches("^R[1-7]$"))
                || rolePackages.stream().distinct().count() != rolePackages.size()
                || actionId == null
                || !actionId.matches("^[a-z][a-z0-9.-]+$")
                || objectTokenDigest == null
                || !objectTokenDigest.matches("^[0-9a-f]{64}$")
                || !"RFP-1.0.0".equals(policyVersion)
                || scopeAnchorSummary.stream().anyMatch(scope -> !SCOPE_CODES.contains(scope))
                || scopeAnchorSummary.stream().distinct().count() != scopeAnchorSummary.size()
                || objectVersion != null && objectVersion < 1
                || !fieldProjectionSummary.keySet().equals(FIELD_CODES)
                || !VISIBILITY_CODES.containsAll(fieldProjectionSummary.values())
                || !Set.of("ALLOW", "DENY", "DEPENDENCY_UNAVAILABLE").contains(result)
                || trustedAt == null
                || traceId == null
                || !traceId.matches("^[0-9a-f]{32}$")) {
            throw new IllegalArgumentException("AUTHORIZATION_AUDIT_CONTEXT_INVALID");
        }
    }

    public Map<String, Object> asMap() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("actorPseudonym", actorPseudonym);
        fields.put("rolePackages", rolePackages);
        fields.put("actionId", actionId);
        fields.put("objectTokenDigest", objectTokenDigest);
        fields.put("policyVersion", policyVersion);
        fields.put("scopeAnchorSummary", scopeAnchorSummary);
        fields.put("objectVersion", objectVersion);
        fields.put("fieldProjectionSummary", fieldProjectionSummary);
        fields.put("result", result);
        fields.put("trustedAt", trustedAt.toString());
        fields.put("traceId", traceId);
        return Collections.unmodifiableMap(fields);
    }
}
