package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.Map;
import java.util.Set;

public record AuditSearchAuthorizationDecision(
        boolean allowed,
        String rfpVersion,
        String action,
        Set<String> scopes,
        Map<String, FieldVisibility> fieldProjection,
        String reasonCode) {
    public AuditSearchAuthorizationDecision {
        if (!"RFP-1.0.0".equals(rfpVersion)) {
            throw new IllegalArgumentException("AUDIT_SEARCH_RFP_VERSION_INVALID");
        }
        scopes = Set.copyOf(scopes);
        fieldProjection = Map.copyOf(fieldProjection);
        if (allowed && (action == null || action.isBlank() || reasonCode != null)) {
            throw new IllegalArgumentException("AUDIT_SEARCH_ALLOW_DECISION_INVALID");
        }
        if (!allowed && (reasonCode == null || reasonCode.isBlank())) {
            throw new IllegalArgumentException("AUDIT_SEARCH_DENY_REASON_REQUIRED");
        }
    }

    public static AuditSearchAuthorizationDecision denied(String reasonCode) {
        return new AuditSearchAuthorizationDecision(
                false, "RFP-1.0.0", null, Set.of(), Map.of(), reasonCode);
    }
}
