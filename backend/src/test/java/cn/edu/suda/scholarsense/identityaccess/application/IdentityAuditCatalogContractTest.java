package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class IdentityAuditCatalogContractTest {

    private static final Map<IdentityAuditAction, Set<String>> USED_REASONS =
            Map.ofEntries(
            Map.entry(IdentityAuditAction.SESSION_LOGIN, Set.of("IDENTITY_LOGIN_COMPLETED")),
            Map.entry(IdentityAuditAction.SESSION_REFRESH, Set.of(
                    "IDENTITY_SESSION_REFRESHED", "IDENTITY_TOKEN_REUSE_DETECTED",
                    "IDENTITY_SESSION_VERSION_CONFLICT", "IDENTITY_SESSION_REQUIRED",
                    "IDENTITY_REMOTE_PROVIDER_UNAVAILABLE", "IDENTITY_LOCAL_COMMIT_FAILED")),
            Map.entry(IdentityAuditAction.SESSION_LOGOUT, Set.of(
                    "IDENTITY_SESSION_LOGGED_OUT", "IDENTITY_SESSION_VERSION_CONFLICT",
                    "IDENTITY_IDEMPOTENCY_MISMATCH", "IDENTITY_SESSION_REQUIRED")),
            Map.entry(IdentityAuditAction.SESSION_ACCOUNT_SWITCH, Set.of(
                    "IDENTITY_SESSION_ACCOUNT_SWITCHED", "IDENTITY_SESSION_VERSION_CONFLICT",
                    "IDENTITY_IDEMPOTENCY_MISMATCH", "IDENTITY_SESSION_REQUIRED")),
            Map.entry(IdentityAuditAction.HOST_INPUT_REJECT, Set.of(
                    "HOST_ORIGIN_FORBIDDEN", "HOST_SOURCE_FORBIDDEN",
                    "HOST_MESSAGE_INVALID", "HOST_MESSAGE_REPLAYED")),
            Map.entry(IdentityAuditAction.SESSION_VIEW, Set.of(
                    "AUTHORIZATION_ALLOWED", "IDENTITY_SESSION_REQUIRED", "IDENTITY_SESSION_EXPIRED",
                    "IDENTITY_DEPENDENCY_UNAVAILABLE")),
            Map.entry(IdentityAuditAction.SYNC_APPLIED, Set.of("IDENTITY_SYNC_APPLIED")),
            Map.entry(IdentityAuditAction.SYNC_REJECTED, syncRejectionReasons()),
            Map.entry(IdentityAuditAction.SYNC_FAILED, syncFailureReasons()),
            Map.entry(IdentityAuditAction.SYNC_RECONCILED, Set.of(
                    "IDENTITY_RECONCILIATION_MATCHED",
                    "IDENTITY_RECONCILIATION_DIFFERENCES_FOUND")),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_SYNC_APPLIED,
                    Set.of(
                            "RESPONSIBILITY_SYNC_APPLIED",
                            "RESPONSIBILITY_SYNC_HEARTBEAT")),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_SYNC_REJECTED,
                    responsibilitySyncRejectionReasons()),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_SYNC_RECONCILED,
                    Set.of(
                            "RESPONSIBILITY_RECONCILIATION_MATCHED",
                            "RESPONSIBILITY_RECONCILIATION_DIFFERENCES",
                            "RESPONSIBILITY_RECONCILIATION_THRESHOLD_FAILED")),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_V2_RECONCILED,
                    Set.of(
                            "RESPONSIBILITY_V2_RECONCILIATION_MATCHED",
                            "RESPONSIBILITY_V2_RECONCILIATION_DIFFERENCES")),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_V2_CUTOVER_REQUESTED,
                    Set.of("RESPONSIBILITY_V2_CUTOVER_REQUESTED")),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_V2_CUTOVER_DENIED,
                    Set.of("RESPONSIBILITY_V2_CUTOVER_DENIED")),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_V2_CUTOVER_FAILED,
                    Set.of("RESPONSIBILITY_V2_CUTOVER_FAILED")),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_V2_ACTIVATED,
                    Set.of("RESPONSIBILITY_V2_CUTOVER_ACTIVATED")),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_EXCEPTION_OPENED,
                    Set.of(
                            "RESPONSIBILITY_COLLEGE_INACTIVE",
                            "RESPONSIBILITY_COLLEGE_MISMATCH",
                            "RESPONSIBILITY_COLLEGE_MISSING",
                            "RESPONSIBILITY_EFFECTIVE_END_REACHED",
                            "RESPONSIBILITY_INACTIVE_RECIPIENT",
                            "RESPONSIBILITY_MULTIPLE_RECIPIENTS",
                            "RESPONSIBILITY_NON_R1_RECIPIENT",
                            "RESPONSIBILITY_RECONCILIATION_DIFFERENCES",
                            "RESPONSIBILITY_ZERO_RECIPIENT")),
            Map.entry(
                    IdentityAuditAction.RESPONSIBILITY_EXCEPTION_RESOLVED,
                    Set.of(
                            "RESPONSIBILITY_RECONCILIATION_MATCHED",
                            "RESPONSIBILITY_VALID")),
            Map.entry(
                    IdentityAuditAction.ACCESS_INVALIDATION_PUBLISHED,
                    Set.of(
                            "ACCESS_INVALIDATION_CORRECTED",
                            "ACCESS_INVALIDATION_REVOKED",
                            "ACCESS_INVALIDATION_EXPIRED",
                            "ACCESS_INVALIDATION_INVALIDATED",
                            "ACCESS_INVALIDATION_REVALIDATED")),
            Map.entry(
                    IdentityAuditAction.AUTHORIZATION_OBJECT_DECIDED,
                    Set.of(
                            "AUTHORIZATION_ALLOWED",
                            "AUTHORIZATION_OBJECT_UNAVAILABLE",
                            "AUTHORIZATION_DEPENDENCY_UNAVAILABLE")),
            Map.entry(
                    IdentityAuditAction.AUTHORIZATION_SHELL_VIEWED,
                    Set.of(
                            "AUTHORIZATION_SHELL_AVAILABLE",
                            "AUTHORIZATION_SHELL_UNAVAILABLE",
                            "AUTHORIZATION_SURFACE_FORBIDDEN")),
            Map.entry(
                    IdentityAuditAction.AUTHORIZATION_DECISION_RECHECKED,
                    Set.of(
                            "AUTHORIZATION_RECHECK_ALLOWED",
                            "AUTHORIZATION_RECHECK_STALE",
                            "AUTHORIZATION_DECISION_STALE")));

    @Test
    void everyImplementedIdentityActionAndReasonIsActiveInTheVersionedCatalog() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Map<String, Set<String>> activeIdentityActions = new HashMap<>();
        for (String version : List.of(
                "1.0.0", "1.1.0", "1.2.0", "1.3.0", "1.4.0")) {
            Path catalogPath = Path.of(
                    "..", "contracts", "audit", "action-catalog-" + version + ".json");
            JsonNode root = json.readTree(Files.readString(catalogPath));
            root.get("actions").valueStream()
                    .filter(action -> "identity-access".equals(action.get("ownerModule").asText()))
                    .filter(action -> "active".equals(action.get("status").asText()))
                    .forEach(action -> activeIdentityActions
                            .computeIfAbsent(
                                    action.get("code").asText(),
                                    ignored -> new java.util.HashSet<>())
                            .addAll(action.get("allowedReasonCodes")
                                    .valueStream()
                                    .map(JsonNode::asText)
                                    .toList()));
        }

        Set<String> implemented = Arrays.stream(IdentityAuditAction.values())
                .map(IdentityAuditAction::code)
                .collect(Collectors.toSet());
        assertEquals(implemented, activeIdentityActions.keySet());

        USED_REASONS.forEach((action, reasons) -> {
            Set<String> allowed = activeIdentityActions.get(action.code());
            assertTrue(allowed.containsAll(reasons), action.code() + " has an unregistered runtime reason");
        });
    }

    private static Set<String> syncFailureReasons() {
        java.util.HashSet<String> reasons = new java.util.HashSet<>(syncRejectionReasons());
        reasons.add("IDENTITY_SYNC_PERSISTENCE_UNAVAILABLE");
        return Set.copyOf(reasons);
    }

    private static Set<String> syncRejectionReasons() {
        return Set.of(
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
                "IDENTITY_SUBJECT_BINDING_CONFLICT",
                "IDENTITY_SUBJECT_REBIND_FORBIDDEN",
                "IDENTITY_SYNC_FENCING_STALE",
                "IDENTITY_BINDING_REFERENCE_INVALID",
                "IDENTITY_EXTERNAL_ID_DUPLICATE",
                "IDENTITY_EXTERNAL_ID_TOKEN_INVALID",
                "IDENTITY_ORGANIZATION_TYPE_INVALID",
                "IDENTITY_PSEUDONYMIZATION_UNAVAILABLE",
                "IDENTITY_STATUS_INVALID",
                "IDENTITY_ORGANIZATION_ORPHAN",
                "IDENTITY_ORGANIZATION_SELF_PARENT",
                "IDENTITY_ORGANIZATION_CYCLE",
                "IDENTITY_ROLE_UNKNOWN",
                "IDENTITY_ROLE_MAPPING_UNAPPROVED");
    }

    private static Set<String> responsibilitySyncRejectionReasons() {
        return Set.of(
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
    }
}
