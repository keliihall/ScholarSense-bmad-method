package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckRequest;
import cn.edu.suda.scholarsense.identityaccess.application.AuthorizationAuditKind;
import cn.edu.suda.scholarsense.identityaccess.application.AuthorizationAuditPort;
import cn.edu.suda.scholarsense.identityaccess.application.AuthorizationAuditRequest;
import cn.edu.suda.scholarsense.identityaccess.domain.FieldClass;
import cn.edu.suda.scholarsense.identityaccess.domain.RolePackage;
import cn.edu.suda.scholarsense.identityaccess.domain.ScopeAnchor;
import cn.edu.suda.scholarsense.identityaccess.domain.Visibility;
import java.util.EnumMap;
import java.util.Set;
import java.util.stream.Collectors;

/** Re-runs current evidence and compares the complete decision version tuple. */
public final class CompositeAuthorizationRecheckAdapter
        implements CompositeAuthorizationRecheckPort {
    private final CompositeAuthorizationPort authorization;
    private final AuthorizationAuditPort audit;

    public CompositeAuthorizationRecheckAdapter(
            CompositeAuthorizationPort authorization, AuthorizationAuditPort audit) {
        this.authorization = java.util.Objects.requireNonNull(authorization);
        this.audit = java.util.Objects.requireNonNull(audit);
    }

    @Override
    public CompositeAuthorizationRecheckDecision recheck(
            CompositeAuthorizationRecheckRequest request) {
        var current = authorization.authorize(request.currentRequest());
        if (current.outcome() == CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE) {
            return audited(request, current, "DEPENDENCY_UNAVAILABLE",
                    new CompositeAuthorizationRecheckDecision(
                    CompositeAuthorizationRecheckOutcome.DEPENDENCY_UNAVAILABLE,
                    "IDENTITY_AUTHORIZATION_DEPENDENCY_UNAVAILABLE"));
        }
        if (current.outcome() != CompositeAuthorizationOutcome.ALLOW
                || !request.priorDecisionToken().equals(current.decisionToken())) {
            return audited(request, current, "DENY",
                    new CompositeAuthorizationRecheckDecision(
                    CompositeAuthorizationRecheckOutcome.STALE,
                    "IDENTITY_AUTHORIZATION_DECISION_STALE"));
        }
        return audited(request, current, "ALLOW",
                new CompositeAuthorizationRecheckDecision(
                CompositeAuthorizationRecheckOutcome.CURRENT,
                "AUTHORIZATION_RECHECK_ALLOWED"));
    }

    private CompositeAuthorizationRecheckDecision audited(
            CompositeAuthorizationRecheckRequest request,
            cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision current,
            String result,
            CompositeAuthorizationRecheckDecision outcome) {
        EnumMap<FieldClass, Visibility> fields = new EnumMap<>(FieldClass.class);
        for (FieldClass field : FieldClass.values()) {
            fields.put(field, Visibility.HIDDEN);
        }
        current.fieldProjectionSummary().forEach((code, visibility) ->
                fields.put(fieldClass(code), Visibility.valueOf(visibility.name())));
        Set<RolePackage> roles = current.applicableRolePackages().stream()
                .map(RolePackage::fromAuthorityId)
                .collect(Collectors.toUnmodifiableSet());
        Set<ScopeAnchor> scopes = current.scopeAnchorSummary().stream()
                .map(ScopeAnchor::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        var authorizationRequest = request.currentRequest();
        audit.record(new AuthorizationAuditRequest(
                AuthorizationAuditKind.PRE_COMMIT_RECHECK,
                authorizationRequest.actorPseudonym(),
                roles,
                authorizationRequest.actionId(),
                authorizationRequest.objectTokenDigest(),
                null,
                scopes,
                current.objectVersion(),
                fields,
                result,
                current.evaluatedAt(),
                authorizationRequest.traceId(),
                null));
        return outcome;
    }

    private static FieldClass fieldClass(String value) {
        for (FieldClass field : FieldClass.values()) {
            if (field.name().equals(value) || field.code().equals(value)) {
                return field;
            }
        }
        throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_FIELD_UNKNOWN");
    }
}
