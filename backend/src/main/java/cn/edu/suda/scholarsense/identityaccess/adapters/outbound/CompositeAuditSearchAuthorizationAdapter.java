package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchAuthorizationRequest;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchCapabilityManifest;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchView;
import cn.edu.suda.scholarsense.identityaccess.api.AuthoritativeIdentityContextQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.FieldVisibility;
import cn.edu.suda.scholarsense.identityaccess.api.IdentityFreshness;
import cn.edu.suda.scholarsense.identityaccess.application.CompositeAuthorizationService;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySessionByPseudonymQueryPort;
import cn.edu.suda.scholarsense.identityaccess.domain.ActionId;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthorizationEvidenceVersions;
import cn.edu.suda.scholarsense.identityaccess.domain.CompositeAuthorizationEvaluator;
import cn.edu.suda.scholarsense.identityaccess.domain.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.domain.CompositeAuthorizationRequest;
import cn.edu.suda.scholarsense.identityaccess.domain.ObjectClass;
import cn.edu.suda.scholarsense.identityaccess.domain.RoleFieldPolicyCatalog;
import cn.edu.suda.scholarsense.identityaccess.domain.RolePackage;
import cn.edu.suda.scholarsense.identityaccess.domain.ScopeAnchor;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Production audit-search binding; fixture-role strings never participate in this adapter. */
public final class CompositeAuditSearchAuthorizationAdapter
        implements AuditSearchAuthorizationPort {
    private final IdentitySessionByPseudonymQueryPort sessions;
    private final AuthoritativeIdentityContextQueryPort identities;
    private final TrustedTimeSource time;
    private final CompositeAuthorizationService authorization;

    public CompositeAuditSearchAuthorizationAdapter(
            IdentitySessionByPseudonymQueryPort sessions,
            AuthoritativeIdentityContextQueryPort identities,
            TrustedTimeSource time,
            RoleFieldPolicyCatalog policy) {
        this.sessions = java.util.Objects.requireNonNull(sessions);
        this.identities = java.util.Objects.requireNonNull(identities);
        this.time = java.util.Objects.requireNonNull(time);
        this.authorization = new CompositeAuthorizationService(
                new CompositeAuthorizationEvaluator(), policy);
    }

    @Override
    public AuditSearchAuthorizationDecision authorize(AuditSearchAuthorizationRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        if (!"audit-domain".equals(request.scope())) {
            return AuditSearchAuthorizationDecision.denied("AUDIT_SEARCH_FORBIDDEN");
        }
        Instant now;
        try {
            now = time.now().instant();
        } catch (RuntimeException unavailable) {
            return AuditSearchAuthorizationDecision.denied("AUDIT_SEARCH_AUTHORITY_UNAVAILABLE");
        }
        var session = sessions.findCurrent(request.sessionPseudonym()).orElse(null);
        if (session == null || !session.activeAt(now)) {
            return AuditSearchAuthorizationDecision.denied("AUDIT_SEARCH_FORBIDDEN");
        }
        Optional<cn.edu.suda.scholarsense.identityaccess.api.AuthoritativeIdentityContext> current;
        try {
            current = identities.findCurrent(session.actorPseudonym());
        } catch (RuntimeException unavailable) {
            current = Optional.empty();
        }
        if (current.isEmpty()
                || current.orElseThrow().freshness() != IdentityFreshness.FRESH
                || !RoleFieldPolicyCatalog.POLICY_VERSION.equals(
                        current.orElseThrow().policyVersions().get("roleFieldPolicy"))) {
            return AuditSearchAuthorizationDecision.denied("AUDIT_SEARCH_AUTHORITY_UNAVAILABLE");
        }
        var identity = current.orElseThrow();
        boolean business = request.requestedView() == AuditSearchView.BUSINESS;
        ObjectClass objectClass = business ? ObjectClass.AGGREGATE_REPORT : ObjectClass.TELEMETRY;
        String action = business
                ? "audit.search-business-metadata"
                : "audit.search-technical-metadata";
        Set<ScopeAnchor> anchors = business
                ? Set.of(ScopeAnchor.SCHOOL_GOVERNANCE)
                : Set.of(ScopeAnchor.TECHNICAL_OBJECT);
        var domainDecision = authorization.authorize(new CompositeAuthorizationRequest(
                session.actorPseudonym(),
                identity.roleIds().stream()
                        .map(RolePackage::fromAuthorityId)
                        .collect(Collectors.toUnmodifiableSet()),
                objectClass,
                ActionId.of(action),
                1,
                anchors,
                "AUDIT_SEARCH",
                Set.of(),
                new AuthorizationEvidenceVersions(
                        identity.aggregateVersion(), 0, 0, 0, 1),
                now,
                true,
                true,
                false,
                Set.of(),
                Optional.empty(),
                Optional.empty()));
        if (domainDecision.outcome() != CompositeAuthorizationOutcome.ALLOW) {
            String reason = domainDecision.outcome()
                    == CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE
                    ? "AUDIT_SEARCH_AUTHORITY_UNAVAILABLE"
                    : "AUDIT_SEARCH_FORBIDDEN";
            return AuditSearchAuthorizationDecision.denied(reason);
        }
        Map<String, FieldVisibility> fields = domainDecision.fieldProjectionSummary().entrySet()
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().code(),
                        entry -> FieldVisibility.valueOf(entry.getValue().name())));
        return new AuditSearchAuthorizationDecision(
                true,
                domainDecision.policyVersion(),
                action,
                Set.of(request.scope()),
                fields,
                null);
    }

    @Override
    public AuditSearchCapabilityManifest capabilityManifest() {
        return new AuditSearchCapabilityManifest("RFP-1.0.0", true, true, false);
    }
}
