package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.FieldClass;
import cn.edu.suda.scholarsense.identityaccess.domain.ScopeAnchor;
import cn.edu.suda.scholarsense.identityaccess.domain.Visibility;
import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;

/** Maps authorization decisions to the approved audit 1.4 successor vocabulary. */
public final class AuthorizationAuditService implements AuthorizationAuditPort {
    private static final Set<ScopeAnchor> AUDITABLE_SCOPES = EnumSet.of(
            ScopeAnchor.CURRENT_RESPONSIBILITY,
            ScopeAnchor.GOVERNANCE_WORK_ITEM,
            ScopeAnchor.CURRENT_TRANSFER_ASSIGNMENT,
            ScopeAnchor.OWNED_SOURCE,
            ScopeAnchor.TECHNICAL_OBJECT,
            ScopeAnchor.VALID_DELEGATION_GRANT);

    private final IdentityAuditFactFactory facts;
    private final IdentityAuditPort audit;

    public AuthorizationAuditService(IdentityAuditFactFactory facts, IdentityAuditPort audit) {
        this.facts = facts;
        this.audit = audit;
    }

    @Override
    public void record(AuthorizationAuditRequest request) {
        AuditTerms terms = terms(request.kind(), request.result());
        String actionId = request.actionId() == null
                ? defaultActionId(request.kind()) : request.actionId();
        List<String> roles = request.rolePackages().stream()
                .map(Enum::name).sorted().toList();
        List<String> scopes = request.scopeAnchors().stream()
                .filter(AUDITABLE_SCOPES::contains)
                .map(Enum::name).sorted().toList();
        String aggregateIdentity = request.aggregateIdentity() == null
                ? request.objectTokenDigest() : request.aggregateIdentity();
        IdentityAuditAuthorizationContext authorization =
                new IdentityAuditAuthorizationContext(
                        "ALLOW".equals(request.result()) ? "allow" : "deny",
                        "RFP-1.0.0",
                        scopes,
                        List.of(),
                        null,
                        actionId,
                        fieldSummary(request.fieldProjectionSummary()),
                        request.objectVersion(),
                        request.result());
        IdentityAuditRequest factRequest = new IdentityAuditRequest(
                ActorType.USER,
                request.actorPseudonym(),
                roles,
                authorization,
                terms.action(),
                terms.outcome(),
                terms.reason(),
                terms.objectType(),
                request.objectTokenDigest(),
                terms.purpose(),
                terms.projection(),
                request.sourceIp(),
                request.traceId(),
                Instant.EPOCH.equals(request.evaluatedAt()) ? null : request.evaluatedAt(),
                terms.aggregateType(),
                aggregateIdentity,
                request.objectVersion(),
                null,
                Map.of(
                        "roleFieldPolicy", "RFP-1.0.0",
                        "fixture", "RFP-FIXTURE-1.0.0",
                        "retentionSchedule", "RS-1.0.0"));
        audit.append(facts.create(factRequest));
    }

    private static Map<String, String> fieldSummary(
            Map<FieldClass, Visibility> projection) {
        Map<String, String> result = new LinkedHashMap<>();
        projection.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(FieldClass::code)))
                .forEach(entry -> result.put(
                        entry.getKey().code(), visibilityCode(entry.getValue())));
        return result;
    }

    private static String visibilityCode(Visibility visibility) {
        return switch (visibility) {
            case CLEAR -> "C";
            case MASKED -> "M";
            case HIDDEN -> "H";
        };
    }

    private static String defaultActionId(AuthorizationAuditKind kind) {
        return switch (kind) {
            case OBJECT_DECISION -> "object.authorize";
            case SHELL_VIEW -> "shell.view";
            case PRE_COMMIT_RECHECK -> "authorization.recheck";
        };
    }

    private static AuditTerms terms(AuthorizationAuditKind kind, String result) {
        return switch (kind) {
            case OBJECT_DECISION -> new AuditTerms(
                    IdentityAuditAction.AUTHORIZATION_OBJECT_DECIDED,
                    "ALLOW".equals(result) ? "accepted" : "rejected",
                    switch (result) {
                        case "ALLOW" -> "AUTHORIZATION_ALLOWED";
                        case "DENY" -> "AUTHORIZATION_OBJECT_UNAVAILABLE";
                        default -> "AUTHORIZATION_DEPENDENCY_UNAVAILABLE";
                    },
                    "authorization-object-token", "OBJECT_AUTHORIZATION",
                    "AUTHORIZED_OBJECT", "authorization-decision");
            case SHELL_VIEW -> new AuditTerms(
                    IdentityAuditAction.AUTHORIZATION_SHELL_VIEWED,
                    "ALLOW".equals(result) ? "accepted" : "rejected",
                    switch (result) {
                        case "ALLOW" -> "AUTHORIZATION_SHELL_AVAILABLE";
                        case "DENY" -> "AUTHORIZATION_SURFACE_FORBIDDEN";
                        default -> "AUTHORIZATION_SHELL_UNAVAILABLE";
                    },
                    "authorized-shell", "AUTHORIZED_SHELL_VIEW",
                    "AUTHORIZED_SHELL", "authorized-shell");
            case PRE_COMMIT_RECHECK -> new AuditTerms(
                    IdentityAuditAction.AUTHORIZATION_DECISION_RECHECKED,
                    "ALLOW".equals(result) ? "accepted" : "rejected",
                    switch (result) {
                        case "ALLOW" -> "AUTHORIZATION_RECHECK_ALLOWED";
                        case "DENY" -> "AUTHORIZATION_DECISION_STALE";
                        default -> "AUTHORIZATION_RECHECK_STALE";
                    },
                    "authorization-object-token", "PRE_COMMIT_AUTHORIZATION_RECHECK",
                    "AUTHORIZED_OBJECT", "authorization-decision");
        };
    }

    private record AuditTerms(
            IdentityAuditAction action,
            String outcome,
            String reason,
            String objectType,
            String purpose,
            String projection,
            String aggregateType) {}
}
