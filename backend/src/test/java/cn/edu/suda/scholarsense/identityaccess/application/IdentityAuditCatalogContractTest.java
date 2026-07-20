package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class IdentityAuditCatalogContractTest {

    private static final Map<IdentityAuditAction, Set<String>> USED_REASONS = Map.of(
            IdentityAuditAction.SESSION_LOGIN, Set.of("IDENTITY_LOGIN_COMPLETED"),
            IdentityAuditAction.SESSION_REFRESH, Set.of(
                    "IDENTITY_SESSION_REFRESHED", "IDENTITY_TOKEN_REUSE_DETECTED",
                    "IDENTITY_SESSION_VERSION_CONFLICT", "IDENTITY_SESSION_REQUIRED",
                    "IDENTITY_REMOTE_PROVIDER_UNAVAILABLE", "IDENTITY_LOCAL_COMMIT_FAILED"),
            IdentityAuditAction.SESSION_LOGOUT, Set.of(
                    "IDENTITY_SESSION_LOGGED_OUT", "IDENTITY_SESSION_VERSION_CONFLICT",
                    "IDENTITY_IDEMPOTENCY_MISMATCH", "IDENTITY_SESSION_REQUIRED"),
            IdentityAuditAction.SESSION_ACCOUNT_SWITCH, Set.of(
                    "IDENTITY_SESSION_ACCOUNT_SWITCHED", "IDENTITY_SESSION_VERSION_CONFLICT",
                    "IDENTITY_IDEMPOTENCY_MISMATCH", "IDENTITY_SESSION_REQUIRED"),
            IdentityAuditAction.HOST_INPUT_REJECT, Set.of(
                    "HOST_ORIGIN_FORBIDDEN", "HOST_SOURCE_FORBIDDEN",
                    "HOST_MESSAGE_INVALID", "HOST_MESSAGE_REPLAYED"),
            IdentityAuditAction.SESSION_VIEW, Set.of(
                    "AUTHORIZATION_ALLOWED", "IDENTITY_SESSION_REQUIRED", "IDENTITY_SESSION_EXPIRED"));

    @Test
    void everyImplementedIdentityActionAndReasonIsActiveInTheVersionedCatalog() throws Exception {
        Path catalogPath = Path.of("..", "contracts", "audit", "action-catalog-1.0.0.json");
        JsonNode root = new ObjectMapper().readTree(Files.readString(catalogPath));
        Map<String, JsonNode> activeIdentityActions = root.get("actions").valueStream()
                .filter(action -> "identity-access".equals(action.get("ownerModule").asText()))
                .filter(action -> "active".equals(action.get("status").asText()))
                .collect(Collectors.toMap(action -> action.get("code").asText(), action -> action));

        Set<String> implemented = Arrays.stream(IdentityAuditAction.values())
                .map(IdentityAuditAction::code)
                .collect(Collectors.toSet());
        assertEquals(implemented, activeIdentityActions.keySet());

        USED_REASONS.forEach((action, reasons) -> {
            JsonNode catalogAction = activeIdentityActions.get(action.code());
            Set<String> allowed = catalogAction.get("allowedReasonCodes").valueStream()
                    .map(JsonNode::asText)
                    .collect(Collectors.toSet());
            assertTrue(allowed.containsAll(reasons), action.code() + " has an unregistered runtime reason");
        });
    }
}
