package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Identity-owned v1 audit vocabulary; shared audit types retain only structural validation. */
final class IdentityAuditVocabulary {
    private static final Set<String> PURPOSES = Set.of(
            "SESSION_CONTINUITY", "SESSION_CONTROL", "SESSION_ESTABLISHMENT",
            "HOST_INTEGRATION_SECURITY", "IDENTITY_AUTHORITY_SYNC",
            "IDENTITY_AUTHORITY_RECONCILIATION");
    private static final Set<String> PROJECTION_SCOPES = Set.of(
            "CURRENT_SESSION", "IDENTITY_ORG");
    private static final Set<String> OBJECT_TYPES = Set.of(
            "identity-session", "identity-sync-job", "identity-reconciliation");
    private static final Set<String> SCOPE_CODES = Set.of("CURRENT_SESSION", "IDENTITY_ORG");
    private static final Set<String> NOT_APPLICABLE_REASONS = Set.of(
            "PRE_AUTHENTICATION", "NO_ROLE_MODEL", "SERVICE_OPERATION");
    private static final Map<String, String> POLICY_VERSIONS = Map.of(
            "identitySessionPolicy", "ISP-1.0.0",
            "hostIntegrationProfile", "HIP-1.0.0",
            "roleFieldPolicy", "RFP-1.0.0",
            "roleMapping", "IDENTITY-ROLE-MAPPING-1.0.0",
            "retentionSchedule", "RS-1.0.0");
    private static final Set<String> SYNC_FAILURE_REASONS = Set.of(
            "IDENTITY_SOURCE_DEPENDENCY_UNAVAILABLE",
            "IDENTITY_SOURCE_AUTHENTICATION_FAILED",
            "IDENTITY_SOURCE_AUTHENTICATION_UNAVAILABLE",
            "IDENTITY_SOURCE_SIGNATURE_INVALID",
            "IDENTITY_SOURCE_SIGNATURE_DIGEST_INVALID",
            "IDENTITY_SOURCE_CONTRACT_UNAPPROVED",
            "IDENTITY_SOURCE_DIGEST_INVALID",
            "IDENTITY_SOURCE_EFFECTIVE_INTERVAL_MISMATCH",
            "IDENTITY_SOURCE_PAYLOAD_INVALID",
            "IDENTITY_SOURCE_PAYLOAD_TOO_LARGE",
            "IDENTITY_SOURCE_PAYLOAD_DIGEST_INVALID",
            "IDENTITY_SOURCE_PAYLOAD_CONFLICT",
            "IDENTITY_SOURCE_RECORD_KIND_INVALID",
            "IDENTITY_SOURCE_REQUEST_INVALID",
            "IDENTITY_SOURCE_SCOPE_INVALID",
            "IDENTITY_SOURCE_KMS_BINDING_INVALID",
            "IDENTITY_SOURCE_CURSOR_GAP",
            "IDENTITY_SOURCE_VERSION_STALE",
            "IDENTITY_SOURCE_EVENT_DUPLICATE",
            "IDENTITY_SYNC_FENCING_STALE",
            "IDENTITY_SYNC_PERSISTENCE_UNAVAILABLE",
            "IDENTITY_BINDING_REFERENCE_INVALID",
            "IDENTITY_EXTERNAL_ID_DUPLICATE",
            "IDENTITY_EXTERNAL_ID_TOKEN_INVALID",
            "IDENTITY_ORGANIZATION_TYPE_INVALID",
            "IDENTITY_SUBJECT_BINDING_CONFLICT",
            "IDENTITY_SUBJECT_REBIND_FORBIDDEN",
            "IDENTITY_STATUS_INVALID",
            "IDENTITY_PSEUDONYMIZATION_UNAVAILABLE",
            "IDENTITY_ORGANIZATION_ORPHAN",
            "IDENTITY_ORGANIZATION_SELF_PARENT",
            "IDENTITY_ORGANIZATION_CYCLE",
            "IDENTITY_ROLE_UNKNOWN",
            "IDENTITY_ROLE_MAPPING_UNAPPROVED");
    private static final Set<String> SYNC_REJECTION_REASONS =
            SYNC_FAILURE_REASONS.stream()
                    .filter(reason -> !"IDENTITY_SYNC_PERSISTENCE_UNAVAILABLE".equals(reason))
                    .collect(Collectors.toUnmodifiableSet());
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
                    "rejected", Set.of(
                            "IDENTITY_SESSION_REQUIRED", "IDENTITY_SESSION_EXPIRED",
                            "IDENTITY_DEPENDENCY_UNAVAILABLE")),
            IdentityAuditAction.SYNC_APPLIED, Map.of(
                    "accepted", Set.of("IDENTITY_SYNC_APPLIED")),
            IdentityAuditAction.SYNC_REJECTED, Map.of(
                    "rejected", SYNC_REJECTION_REASONS),
            IdentityAuditAction.SYNC_FAILED, Map.of(
                    "rejected", SYNC_FAILURE_REASONS),
            IdentityAuditAction.SYNC_RECONCILED, Map.of(
                    "accepted", Set.of(
                            "IDENTITY_RECONCILIATION_MATCHED",
                            "IDENTITY_RECONCILIATION_DIFFERENCES_FOUND")));

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
