package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.Objects;

public record AuditSearchAuthorizationRequest(
        String sessionPseudonym,
        AuditSearchView requestedView,
        String objectType,
        String scope,
        String traceId) {
    public AuditSearchAuthorizationRequest {
        require(sessionPseudonym, "AUDIT_SEARCH_SESSION_REQUIRED");
        Objects.requireNonNull(requestedView, "requestedView");
        require(objectType, "AUDIT_SEARCH_OBJECT_TYPE_REQUIRED");
        require(scope, "AUDIT_SEARCH_SCOPE_REQUIRED");
        require(traceId, "AUDIT_SEARCH_TRACE_REQUIRED");
    }

    private static void require(String value, String code) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(code);
        }
    }
}
