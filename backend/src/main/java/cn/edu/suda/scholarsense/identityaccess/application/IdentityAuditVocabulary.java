package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Identity-owned v1 audit vocabulary; shared audit types retain only structural validation. */
final class IdentityAuditVocabulary {
    private static final Set<String> PURPOSES = Set.of(
            "SESSION_CONTINUITY", "SESSION_CONTROL", "SESSION_ESTABLISHMENT",
            "HOST_INTEGRATION_SECURITY");
    private static final Set<String> PROJECTION_SCOPES = Set.of("CURRENT_SESSION");
    private static final Set<String> OBJECT_TYPES = Set.of("identity-session");
    private static final Set<String> SCOPE_CODES = Set.of("CURRENT_SESSION");
    private static final Set<String> NOT_APPLICABLE_REASONS = Set.of(
            "PRE_AUTHENTICATION", "NO_ROLE_MODEL", "SERVICE_OPERATION");
    private static final Map<String, String> POLICY_VERSIONS = Map.of(
            "identitySessionPolicy", "ISP-1.0.0",
            "hostIntegrationProfile", "HIP-1.0.0");
    private static final Map<IdentityAuditAction, Map<String, Set<String>>> RESULTS = Map.of(
            IdentityAuditAction.SESSION_LOGIN, Map.of(
                    "accepted", Set.of("IDENTITY_LOGIN_COMPLETED")),
            IdentityAuditAction.SESSION_REFRESH, Map.of(
                    "accepted", Set.of("IDENTITY_SESSION_REFRESHED"),
                    "rejected", Set.of(
                            "IDENTITY_TOKEN_REUSE_DETECTED", "IDENTITY_SESSION_VERSION_CONFLICT",
                            "IDENTITY_SESSION_REQUIRED", "IDENTITY_SESSION_EXPIRED",
                            "IDENTITY_REMOTE_PROVIDER_UNAVAILABLE", "IDENTITY_LOCAL_COMMIT_FAILED")),
            IdentityAuditAction.SESSION_LOGOUT, Map.of(
                    "accepted", Set.of("IDENTITY_SESSION_LOGGED_OUT"),
                    "rejected", Set.of(
                            "IDENTITY_SESSION_VERSION_CONFLICT", "IDENTITY_IDEMPOTENCY_MISMATCH",
                            "IDENTITY_SESSION_REQUIRED")),
            IdentityAuditAction.SESSION_ACCOUNT_SWITCH, Map.of(
                    "accepted", Set.of("IDENTITY_SESSION_ACCOUNT_SWITCHED"),
                    "rejected", Set.of(
                            "IDENTITY_SESSION_VERSION_CONFLICT", "IDENTITY_IDEMPOTENCY_MISMATCH",
                            "IDENTITY_SESSION_REQUIRED")),
            IdentityAuditAction.HOST_INPUT_REJECT, Map.of(
                    "rejected", Set.of(
                            "HOST_ORIGIN_FORBIDDEN", "HOST_SOURCE_FORBIDDEN",
                            "HOST_MESSAGE_INVALID", "HOST_MESSAGE_REPLAYED")),
            IdentityAuditAction.SESSION_VIEW, Map.of(
                    "accepted", Set.of("AUTHORIZATION_ALLOWED"),
                    "rejected", Set.of("IDENTITY_SESSION_REQUIRED", "IDENTITY_SESSION_EXPIRED")));

    private IdentityAuditVocabulary() {}

    static void validate(IdentityAuditRequest request) {
        if (request == null || request.action() == null
                || !RESULTS.getOrDefault(request.action(), Map.of())
                        .getOrDefault(request.outcome(), Set.of()).contains(request.reasonCode())
                || !optionalMember(PURPOSES, request.purpose())
                || !optionalMember(PROJECTION_SCOPES, request.projectionScope())
                || !optionalMember(OBJECT_TYPES, request.objectType())
                || !optionalMember(OBJECT_TYPES, request.aggregateType())
                || !request.roleIds().isEmpty()) {
            throw new IllegalArgumentException("AUDIT_IDENTITY_VOCABULARY_INVALID");
        }
        for (Map.Entry<String, String> entry : request.policyVersions().entrySet()) {
            if (!entry.getValue().equals(POLICY_VERSIONS.get(entry.getKey()))) {
                throw new IllegalArgumentException("AUDIT_IDENTITY_POLICY_VERSION_INVALID");
            }
        }
        validateAuthorization(request.authorizationContext());
    }

    static void validateAuthorization(IdentityAuditAuthorizationContext context) {
        if (context == null) {
            throw new IllegalArgumentException("AUDIT_IDENTITY_AUTHORIZATION_VOCABULARY_INVALID");
        }
        validateAuthorizationValues(
                context.policyVersion(), context.scopeCodes(), context.notApplicableReason());
    }

    static void validateAuthorizationValues(
            String policyVersion, List<String> scopeCodes, String notApplicableReason) {
        if (!SCOPE_CODES.containsAll(scopeCodes)
                || notApplicableReason != null && !NOT_APPLICABLE_REASONS.contains(notApplicableReason)
                || policyVersion != null && !POLICY_VERSIONS.containsValue(policyVersion)) {
            throw new IllegalArgumentException("AUDIT_IDENTITY_AUTHORIZATION_VOCABULARY_INVALID");
        }
    }

    private static boolean optionalMember(Set<String> values, String candidate) {
        return candidate == null || values.contains(candidate);
    }
}
