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
            "IDENTITY_AUTHORITY_RECONCILIATION",
            "RESPONSIBILITY_AUTHORITY_SYNC",
            "RESPONSIBILITY_AUTHORITY_RECONCILIATION",
            "RESPONSIBILITY_EXCEPTION_MANAGEMENT",
            "ACCESS_INVALIDATION_PROPAGATION",
            "OBJECT_AUTHORIZATION", "AUTHORIZED_SHELL_VIEW",
            "PRE_COMMIT_AUTHORIZATION_RECHECK");
    private static final Set<String> PROJECTION_SCOPES = Set.of(
            "CURRENT_SESSION", "IDENTITY_ORG", "RESPONSIBILITY",
            "ACCESS_INVALIDATION", "AUTHORIZED_OBJECT", "AUTHORIZED_SHELL");
    private static final Set<String> OBJECT_TYPES = Set.of(
            "identity-session", "identity-sync-job", "identity-reconciliation",
            "responsibility-sync-job", "responsibility-reconciliation",
            "responsibility-exception", "access-invalidation-fact",
            "authorization-object-token", "authorized-shell");
    private static final Set<String> AGGREGATE_TYPES = Set.of(
            "identity-session", "identity-sync-job", "identity-reconciliation",
            "responsibility-sync-job", "responsibility-reconciliation",
            "responsibility-exception", "access-invalidation-fact",
            "authorization-decision", "authorized-shell");
    private static final Set<String> SCOPE_CODES = Set.of(
            "CURRENT_SESSION", "IDENTITY_ORG", "RESPONSIBILITY",
            "ACCESS_INVALIDATION", "CURRENT_RESPONSIBILITY", "GOVERNANCE_WORK_ITEM",
            "CURRENT_TRANSFER_ASSIGNMENT", "OWNED_SOURCE", "TECHNICAL_OBJECT",
            "VALID_DELEGATION_GRANT");
    private static final Set<String> ROLE_IDS = Set.of("R1", "R2", "R3", "R4", "R5", "R6", "R7");
    private static final Set<IdentityAuditAction> AUTHORIZATION_ACTIONS = Set.of(
            IdentityAuditAction.AUTHORIZATION_OBJECT_DECIDED,
            IdentityAuditAction.AUTHORIZATION_SHELL_VIEWED,
            IdentityAuditAction.AUTHORIZATION_DECISION_RECHECKED);
    private static final Set<String> NOT_APPLICABLE_REASONS = Set.of(
            "PRE_AUTHENTICATION", "NO_ROLE_MODEL", "SERVICE_OPERATION");
    private static final Map<String, Set<String>> POLICY_VERSIONS = Map.of(
            "identitySessionPolicy", Set.of("ISP-1.0.0"),
            "hostIntegrationProfile", Set.of("HIP-1.0.0"),
            "roleFieldPolicy", Set.of("RFP-1.0.0"),
            "roleMapping", Set.of("IDENTITY-ROLE-MAPPING-1.0.0"),
            "responsibilityContract", Set.of(
                    "RESPONSIBILITY-AUTHORITY-1.0.0",
                    "RESPONSIBILITY-AUTHORITY-2.0.0"),
            "accessInvalidationContract",
                    Set.of("ACCESS-INVALIDATION-DATA-1.0.0"),
            "fixture", Set.of("RFP-FIXTURE-1.0.0"),
            "highRiskActionPolicy", Set.of("HRAP-1.0.0"),
            "retentionSchedule", Set.of("RS-1.0.0"));
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
    private static final Set<String> RESPONSIBILITY_SYNC_REJECTION_REASONS =
            Set.of(
                    "IDENTITY_SYNC_FENCING_STALE",
                    "IDENTITY_SYNC_PERSISTENCE_UNAVAILABLE",
                    "RESPONSIBILITY_DEPENDENCY_VECTOR_INVALID",
                    "RESPONSIBILITY_DEPENDENCY_WATERMARK_BEHIND",
                    "RESPONSIBILITY_DUPLICATE_RELATION",
                    "RESPONSIBILITY_IDEMPOTENCY_CONFLICT",
                    "RESPONSIBILITY_IDENTITY_PROJECTION_UNAVAILABLE",
                    "RESPONSIBILITY_REPLAY_RANGE_INVALID",
                    "RESPONSIBILITY_RECONCILIATION_FENCING_STALE",
                    "RESPONSIBILITY_RECONCILIATION_CONTRACT_STALE",
                    "RESPONSIBILITY_RECONCILIATION_PERSISTENCE_UNAVAILABLE",
                    "RESPONSIBILITY_SOURCE_AUTHENTICATION_FAILED",
                    "RESPONSIBILITY_SOURCE_CONTRACT_UNAPPROVED",
                    "RESPONSIBILITY_SOURCE_CONTRACT_VERSION_MISMATCH",
                    "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE",
                    "RESPONSIBILITY_SOURCE_DIGEST_INVALID",
                    "RESPONSIBILITY_SOURCE_ENDPOINT_UNSAFE",
                    "RESPONSIBILITY_SOURCE_KMS_BINDING_INVALID",
                    "RESPONSIBILITY_SOURCE_PAYLOAD_DIGEST_INVALID",
                    "RESPONSIBILITY_SOURCE_PAYLOAD_INVALID",
                    "RESPONSIBILITY_SOURCE_PAYLOAD_TOO_LARGE",
                    "RESPONSIBILITY_SOURCE_REPLAY_UNAVAILABLE",
                    "RESPONSIBILITY_SOURCE_REQUEST_INVALID",
                    "RESPONSIBILITY_SOURCE_SCOPE_INVALID",
                    "RESPONSIBILITY_SOURCE_SIGNATURE_DIGEST_INVALID",
                    "RESPONSIBILITY_SOURCE_SIGNATURE_INVALID",
                    "RESPONSIBILITY_SOURCE_TIME_INVALID",
                    "RESPONSIBILITY_SOURCE_VERSION_STALE",
                    "RESPONSIBILITY_SOURCE_VERSION_GAP",
                    "RESPONSIBILITY_SNAPSHOT_COUNT_MISMATCH",
                    "RESPONSIBILITY_SNAPSHOT_DIGEST_MISMATCH",
                    "RESPONSIBILITY_SNAPSHOT_INVALID",
                    "RESPONSIBILITY_SNAPSHOT_PARTIAL",
                    "RESPONSIBILITY_SNAPSHOT_PARTITION_MISSING",
                    "RESPONSIBILITY_SNAPSHOT_SIGNATURE_INVALID",
                    "RESPONSIBILITY_SNAPSHOT_UNSEALED",
                    "RESPONSIBILITY_STATUS_INVALID",
                    "RESPONSIBILITY_TYPE_INVALID",
                    "RESPONSIBILITY_V2_REPLAY_REQUIRED",
                    "RESPONSIBILITY_WATERMARK_GAP");
    private static final Set<String> RESPONSIBILITY_EXCEPTION_OPEN_REASONS =
            Set.of(
                    "RESPONSIBILITY_COLLEGE_INACTIVE",
                    "RESPONSIBILITY_COLLEGE_MISMATCH",
                    "RESPONSIBILITY_COLLEGE_MISSING",
                    "RESPONSIBILITY_EFFECTIVE_END_REACHED",
                    "RESPONSIBILITY_INACTIVE_RECIPIENT",
                    "RESPONSIBILITY_MULTIPLE_RECIPIENTS",
                    "RESPONSIBILITY_NON_R1_RECIPIENT",
                    "RESPONSIBILITY_RECONCILIATION_DIFFERENCES",
                    "RESPONSIBILITY_ZERO_RECIPIENT");
    private static final Map<IdentityAuditAction, Map<String, Set<String>>> RESULTS =
            Map.ofEntries(
            Map.entry(IdentityAuditAction.SESSION_LOGIN, Map.of(
                    "accepted", Set.of("IDENTITY_LOGIN_COMPLETED"))),
            Map.entry(IdentityAuditAction.SESSION_REFRESH, Map.of(
                    "accepted", Set.of("IDENTITY_SESSION_REFRESHED"),
                    "rejected", Set.of(
                            "IDENTITY_TOKEN_REUSE_DETECTED", "IDENTITY_SESSION_VERSION_CONFLICT",
                            "IDENTITY_SESSION_REQUIRED", "IDENTITY_SESSION_EXPIRED",
                            "IDENTITY_REMOTE_PROVIDER_UNAVAILABLE", "IDENTITY_LOCAL_COMMIT_FAILED"))),
            Map.entry(IdentityAuditAction.SESSION_LOGOUT, Map.of(
                    "accepted", Set.of("IDENTITY_SESSION_LOGGED_OUT"),
                    "rejected", Set.of(
                            "IDENTITY_SESSION_VERSION_CONFLICT", "IDENTITY_IDEMPOTENCY_MISMATCH",
                            "IDENTITY_SESSION_REQUIRED"))),
            Map.entry(IdentityAuditAction.SESSION_ACCOUNT_SWITCH, Map.of(
                    "accepted", Set.of("IDENTITY_SESSION_ACCOUNT_SWITCHED"),
                    "rejected", Set.of(
                            "IDENTITY_SESSION_VERSION_CONFLICT", "IDENTITY_IDEMPOTENCY_MISMATCH",
                            "IDENTITY_SESSION_REQUIRED"))),
            Map.entry(IdentityAuditAction.HOST_INPUT_REJECT, Map.of(
                    "rejected", Set.of(
                            "HOST_ORIGIN_FORBIDDEN", "HOST_SOURCE_FORBIDDEN",
                            "HOST_MESSAGE_INVALID", "HOST_MESSAGE_REPLAYED"))),
            Map.entry(IdentityAuditAction.SESSION_VIEW, Map.of(
                    "accepted", Set.of("AUTHORIZATION_ALLOWED"),
                    "rejected", Set.of(
                            "IDENTITY_SESSION_REQUIRED", "IDENTITY_SESSION_EXPIRED",
                            "IDENTITY_DEPENDENCY_UNAVAILABLE"))),
            Map.entry(IdentityAuditAction.SYNC_APPLIED, Map.of(
                    "accepted", Set.of("IDENTITY_SYNC_APPLIED"))),
            Map.entry(IdentityAuditAction.SYNC_REJECTED, Map.of(
                    "rejected", SYNC_REJECTION_REASONS)),
            Map.entry(IdentityAuditAction.SYNC_FAILED, Map.of(
                    "rejected", SYNC_FAILURE_REASONS)),
            Map.entry(IdentityAuditAction.SYNC_RECONCILED, Map.of(
                    "accepted", Set.of(
                            "IDENTITY_RECONCILIATION_MATCHED",
                            "IDENTITY_RECONCILIATION_DIFFERENCES_FOUND"))),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_SYNC_APPLIED,
                    Map.of(
                            "accepted",
                            Set.of(
                                    "RESPONSIBILITY_SYNC_APPLIED",
                                    "RESPONSIBILITY_SYNC_HEARTBEAT"))),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_SYNC_REJECTED,
                    Map.of(
                            "rejected",
                            RESPONSIBILITY_SYNC_REJECTION_REASONS)),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_SYNC_RECONCILED,
                    Map.of(
                            "accepted",
                            Set.of(
                                    "RESPONSIBILITY_RECONCILIATION_MATCHED",
                                    "RESPONSIBILITY_RECONCILIATION_DIFFERENCES",
                                    "RESPONSIBILITY_RECONCILIATION_THRESHOLD_FAILED"))),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_V2_RECONCILED,
                    Map.of(
                            "accepted",
                            Set.of(
                                    "RESPONSIBILITY_V2_RECONCILIATION_MATCHED",
                                    "RESPONSIBILITY_V2_RECONCILIATION_DIFFERENCES"))),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_V2_CUTOVER_REQUESTED,
                    Map.of(
                            "accepted",
                            Set.of("RESPONSIBILITY_V2_CUTOVER_REQUESTED"))),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_V2_CUTOVER_DENIED,
                    Map.of(
                            "rejected",
                            Set.of("RESPONSIBILITY_V2_CUTOVER_DENIED"))),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_V2_CUTOVER_FAILED,
                    Map.of(
                            "rejected",
                            Set.of("RESPONSIBILITY_V2_CUTOVER_FAILED"))),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_V2_ACTIVATED,
                    Map.of(
                            "accepted",
                            Set.of("RESPONSIBILITY_V2_CUTOVER_ACTIVATED"))),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_EXCEPTION_OPENED,
                    Map.of(
                            "accepted",
                            RESPONSIBILITY_EXCEPTION_OPEN_REASONS)),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_EXCEPTION_RESOLVED,
                    Map.of(
                            "accepted",
                            Set.of(
                                    "RESPONSIBILITY_RECONCILIATION_MATCHED",
                                    "RESPONSIBILITY_VALID"))),
            Map.entry(
                    IdentityAuditAction.ACCESS_INVALIDATION_PUBLISHED,
                    Map.of(
                            "accepted",
                            Set.of(
                                    "ACCESS_INVALIDATION_CORRECTED",
                                    "ACCESS_INVALIDATION_REVOKED",
                                    "ACCESS_INVALIDATION_EXPIRED",
                                    "ACCESS_INVALIDATION_INVALIDATED",
                                    "ACCESS_INVALIDATION_REVALIDATED"))),
            Map.entry(
                    IdentityAuditAction.AUTHORIZATION_OBJECT_DECIDED,
                    Map.of(
                            "accepted", Set.of("AUTHORIZATION_ALLOWED"),
                            "rejected", Set.of(
                                    "AUTHORIZATION_OBJECT_UNAVAILABLE",
                                    "AUTHORIZATION_DEPENDENCY_UNAVAILABLE"))),
            Map.entry(
                    IdentityAuditAction.AUTHORIZATION_SHELL_VIEWED,
                    Map.of(
                            "accepted", Set.of("AUTHORIZATION_SHELL_AVAILABLE"),
                            "rejected", Set.of(
                                    "AUTHORIZATION_SHELL_UNAVAILABLE",
                                    "AUTHORIZATION_SURFACE_FORBIDDEN"))),
            Map.entry(
                    IdentityAuditAction.AUTHORIZATION_DECISION_RECHECKED,
                    Map.of(
                            "accepted", Set.of("AUTHORIZATION_RECHECK_ALLOWED"),
                            "rejected", Set.of(
                                    "AUTHORIZATION_RECHECK_STALE",
                                    "AUTHORIZATION_DECISION_STALE"))));

    private IdentityAuditVocabulary() {}

    static void validate(IdentityAuditRequest request) {
        if (request == null || request.action() == null
                || !RESULTS.getOrDefault(request.action(), Map.of())
                        .getOrDefault(request.outcome(), Set.of()).contains(request.reasonCode())
                || !optionalMember(PURPOSES, request.purpose())
                || !optionalMember(PROJECTION_SCOPES, request.projectionScope())
                || !optionalMember(OBJECT_TYPES, request.objectType())
                || !optionalMember(AGGREGATE_TYPES, request.aggregateType())
                || !validRoles(request)) {
            throw new IllegalArgumentException("AUDIT_IDENTITY_VOCABULARY_INVALID");
        }
        for (Map.Entry<String, String> entry : request.policyVersions().entrySet()) {
            if (!POLICY_VERSIONS.getOrDefault(
                            entry.getKey(), Set.of())
                    .contains(entry.getValue())) {
                throw new IllegalArgumentException("AUDIT_IDENTITY_POLICY_VERSION_INVALID");
            }
        }
        validateAuthorization(request.authorizationContext());
    }

    private static boolean validRoles(IdentityAuditRequest request) {
        if (!AUTHORIZATION_ACTIONS.contains(request.action())) {
            return request.roleIds().isEmpty();
        }
        return ROLE_IDS.containsAll(request.roleIds())
                && request.roleIds().stream().distinct().count() == request.roleIds().size();
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
                || policyVersion != null
                        && POLICY_VERSIONS.values().stream()
                                .noneMatch(versions ->
                                        versions.contains(policyVersion))) {
            throw new IllegalArgumentException("AUDIT_IDENTITY_AUTHORIZATION_VOCABULARY_INVALID");
        }
    }

    private static boolean optionalMember(Set<String> values, String candidate) {
        return candidate == null || values.contains(candidate);
    }
}
