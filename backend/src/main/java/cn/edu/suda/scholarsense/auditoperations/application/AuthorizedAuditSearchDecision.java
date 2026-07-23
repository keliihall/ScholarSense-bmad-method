package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.Map;
import java.util.Set;

public record AuthorizedAuditSearchDecision(
        boolean allowed,
        String rfpVersion,
        String action,
        Set<String> scopes,
        Map<String, AuditFieldVisibility> fieldProjection,
        String reasonCode) {
    public AuthorizedAuditSearchDecision {
        scopes = Set.copyOf(scopes);
        fieldProjection = Map.copyOf(fieldProjection);
    }
}
