package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.FieldClass;
import cn.edu.suda.scholarsense.identityaccess.domain.RolePackage;
import cn.edu.suda.scholarsense.identityaccess.domain.ScopeAnchor;
import cn.edu.suda.scholarsense.identityaccess.domain.Visibility;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Raw inputs for an authorization audit; the fact factory pseudonymizes identities. */
public record AuthorizationAuditRequest(
        AuthorizationAuditKind kind,
        String actorPseudonym,
        Set<RolePackage> rolePackages,
        String actionId,
        String objectTokenDigest,
        String aggregateIdentity,
        Set<ScopeAnchor> scopeAnchors,
        Long objectVersion,
        Map<FieldClass, Visibility> fieldProjectionSummary,
        String result,
        Instant evaluatedAt,
        String traceId,
        String sourceIp) {
    public AuthorizationAuditRequest {
        if (kind == null || actorPseudonym == null || actorPseudonym.isBlank()
                || objectTokenDigest == null || objectTokenDigest.isBlank()
                || evaluatedAt == null
                || traceId == null || !traceId.matches("[0-9a-f]{32}")
                || !Set.of("ALLOW", "DENY", "DEPENDENCY_UNAVAILABLE").contains(result)
                || objectVersion != null && objectVersion < 1) {
            throw new IllegalArgumentException("AUTHORIZATION_AUDIT_REQUEST_INVALID");
        }
        rolePackages = Set.copyOf(rolePackages);
        scopeAnchors = Set.copyOf(scopeAnchors);
        fieldProjectionSummary = Map.copyOf(fieldProjectionSummary);
        if (!fieldProjectionSummary.keySet().equals(Set.of(FieldClass.values()))) {
            throw new IllegalArgumentException("AUTHORIZATION_AUDIT_FIELD_SUMMARY_INVALID");
        }
    }
}
