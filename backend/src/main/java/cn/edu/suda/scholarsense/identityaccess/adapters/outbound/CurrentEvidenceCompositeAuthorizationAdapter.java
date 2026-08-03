package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationDelegationEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationEvidenceAvailability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceQuery;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationScopeAnchor;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationScopeEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.AuthoritativeIdentityContext;
import cn.edu.suda.scholarsense.identityaccess.api.AuthoritativeIdentityContextQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecisionToken;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.FieldVisibility;
import cn.edu.suda.scholarsense.identityaccess.api.IdentityFreshness;
import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeQuery;
import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeValidity;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationFenceQueryPort;
import cn.edu.suda.scholarsense.identityaccess.application.AuthorizationAuditKind;
import cn.edu.suda.scholarsense.identityaccess.application.AuthorizationAuditPort;
import cn.edu.suda.scholarsense.identityaccess.application.AuthorizationAuditRequest;
import cn.edu.suda.scholarsense.identityaccess.application.CompositeAuthorizationService;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySessionByPseudonymQueryPort;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.ActionId;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthorizationEvidenceVersions;
import cn.edu.suda.scholarsense.identityaccess.domain.CompositeAuthorizationEvaluator;
import cn.edu.suda.scholarsense.identityaccess.domain.DelegationEvidence;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveWindow;
import cn.edu.suda.scholarsense.identityaccess.domain.FieldClass;
import cn.edu.suda.scholarsense.identityaccess.domain.ObjectClass;
import cn.edu.suda.scholarsense.identityaccess.domain.RoleFieldPolicyCatalog;
import cn.edu.suda.scholarsense.identityaccess.domain.RolePackage;
import cn.edu.suda.scholarsense.identityaccess.domain.ScopeAnchor;
import cn.edu.suda.scholarsense.identityaccess.domain.Visibility;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Instant;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Per-request composition of current identity, responsibility, fence and owner evidence. */
public final class CurrentEvidenceCompositeAuthorizationAdapter implements CompositeAuthorizationPort {
    private static final long POLICY_SEQUENCE = 1;

    private final AuthoritativeIdentityContextQueryPort identities;
    private final ResponsibilityScopeQueryPort responsibilities;
    private final AuthorizationObjectEvidenceQueryPort objectEvidence;
    private final AccessInvalidationFenceQueryPort invalidationFence;
    private final TrustedTimeSource time;
    private final RoleFieldPolicyCatalog policy;
    private final CompositeAuthorizationService authorization;
    private final AuthorizationAuditPort audit;
    private final IdentitySessionByPseudonymQueryPort sessions;

    public CurrentEvidenceCompositeAuthorizationAdapter(
            AuthoritativeIdentityContextQueryPort identities,
            ResponsibilityScopeQueryPort responsibilities,
            AuthorizationObjectEvidenceQueryPort objectEvidence,
            AccessInvalidationFenceQueryPort invalidationFence,
            TrustedTimeSource time,
            RoleFieldPolicyCatalog policy,
            AuthorizationAuditPort audit) {
        this(identities, responsibilities, objectEvidence, invalidationFence, time, policy, audit, null);
    }

    public CurrentEvidenceCompositeAuthorizationAdapter(
            AuthoritativeIdentityContextQueryPort identities,
            ResponsibilityScopeQueryPort responsibilities,
            AuthorizationObjectEvidenceQueryPort objectEvidence,
            AccessInvalidationFenceQueryPort invalidationFence,
            TrustedTimeSource time,
            RoleFieldPolicyCatalog policy,
            AuthorizationAuditPort audit,
            IdentitySessionByPseudonymQueryPort sessions) {
        this.identities = java.util.Objects.requireNonNull(identities);
        this.responsibilities = java.util.Objects.requireNonNull(responsibilities);
        this.objectEvidence = java.util.Objects.requireNonNull(objectEvidence);
        this.invalidationFence = java.util.Objects.requireNonNull(invalidationFence);
        this.time = java.util.Objects.requireNonNull(time);
        this.policy = java.util.Objects.requireNonNull(policy);
        this.audit = java.util.Objects.requireNonNull(audit);
        this.sessions = sessions;
        this.authorization = new CompositeAuthorizationService(
                new CompositeAuthorizationEvaluator(), policy);
    }

    @Override
    public cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision authorize(
            cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest request) {
        Instant now;
        try {
            now = time.now().instant();
        } catch (RuntimeException unavailable) {
            return terminal(
                    request,
                    Instant.EPOCH,
                    CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE,
                    "TRUSTED_CLOCK_UNAVAILABLE",
                    0,
                    0,
                    0,
                    0);
        }

        if (sessions != null) {
            try {
                var session = sessions.findCurrent(request.actorPseudonym());
                if (session.isEmpty()) {
                    return terminal(
                            request, now, CompositeAuthorizationOutcome.DENY,
                            "IDENTITY_SESSION_NOT_FOUND", 0, 0, 0, 0);
                }
                if (!session.orElseThrow().activeAt(now)) {
                    return terminal(
                            request, now, CompositeAuthorizationOutcome.DENY,
                            "IDENTITY_SESSION_INACTIVE", 0, 0, 0, 0);
                }
                request = withActor(request, session.orElseThrow().actorPseudonym());
            } catch (RuntimeException unavailable) {
                return terminal(
                        request, now, CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE,
                        "IDENTITY_SESSION_UNAVAILABLE", 0, 0, 0, 0);
            }
        }

        Optional<AuthoritativeIdentityContext> current;
        try {
            current = identities.findCurrent(request.actorPseudonym());
        } catch (RuntimeException unavailable) {
            return terminal(
                    request,
                    now,
                    CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE,
                    "IDENTITY_AUTHORITY_UNAVAILABLE",
                    0,
                    0,
                    0,
                    0);
        }
        if (current.isEmpty()) {
            return terminal(
                    request,
                    now,
                    CompositeAuthorizationOutcome.DENY,
                    "IDENTITY_AUTHORITY_NOT_FOUND",
                    0,
                    0,
                    0,
                    0);
        }
        AuthoritativeIdentityContext identity = current.orElseThrow();
        if (identity.freshness() != IdentityFreshness.FRESH
                || !RoleFieldPolicyCatalog.POLICY_VERSION.equals(
                        identity.policyVersions().get("roleFieldPolicy"))) {
            return terminal(
                    request,
                    now,
                    CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE,
                    "IDENTITY_AUTHORIZATION_POLICY_UNAVAILABLE",
                    identity.aggregateVersion(),
                    0,
                    0,
                    0);
        }
        Set<RolePackage> currentRoles;
        try {
            currentRoles = identity.roleIds().stream()
                    .map(RolePackage::fromAuthorityId)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException unavailable) {
            return terminal(
                    request,
                    now,
                    CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE,
                    "IDENTITY_AUTHORIZATION_ROLE_UNAVAILABLE",
                    identity.aggregateVersion(),
                    0,
                    0,
                    0);
        }

        AuthorizationObjectEvidence ownerEvidence;
        try {
            ownerEvidence = objectEvidence.resolve(new AuthorizationObjectEvidenceQuery(
                    request.actorPseudonym(),
                    identity.accountId(),
                    Set.copyOf(identity.organizationIds()),
                    request.objectClass(),
                    request.actionId(),
                    request.objectTokenDigest(),
                    request.expectedObjectVersion(),
                    now));
        } catch (RuntimeException unavailable) {
            ownerEvidence = AuthorizationObjectEvidence.unavailable();
        }
        if (ownerEvidence.availability() != AuthorizationEvidenceAvailability.AVAILABLE) {
            String reason = ownerEvidence.availability()
                    == AuthorizationEvidenceAvailability.NOT_INSTALLED
                    ? "OBJECT_EVIDENCE_NOT_INSTALLED"
                    : "OBJECT_EVIDENCE_UNAVAILABLE";
            return terminal(
                    request,
                    now,
                    CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE,
                    reason,
                    identity.aggregateVersion(),
                    0,
                    0,
                    0);
        }
        if (ownerEvidence.objectVersion() != request.expectedObjectVersion()) {
            return terminal(
                    request,
                    now,
                    CompositeAuthorizationOutcome.DENY,
                    "OBJECT_VERSION_STALE",
                    identity.aggregateVersion(),
                    ownerEvidence.relationVersion(),
                    ownerEvidence.grantVersion(),
                    ownerEvidence.invalidationVersion());
        }

        if (request.accessLineageId().isPresent()) {
            try {
                if (invalidationFence.blocks(new AccessInvalidationLineageId(
                        request.accessLineageId().orElseThrow()))) {
                    return terminal(
                            request,
                            now,
                            CompositeAuthorizationOutcome.DENY,
                            "ACCESS_LINEAGE_INVALIDATED",
                            identity.aggregateVersion(),
                            ownerEvidence.relationVersion(),
                            ownerEvidence.grantVersion(),
                            ownerEvidence.invalidationVersion());
                }
            } catch (RuntimeException unavailable) {
                return terminal(
                        request,
                        now,
                        CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE,
                        "INVALIDATION_FENCE_UNAVAILABLE",
                        identity.aggregateVersion(),
                        ownerEvidence.relationVersion(),
                        ownerEvidence.grantVersion(),
                        ownerEvidence.invalidationVersion());
            }
        }

        EnumSet<ScopeAnchor> anchors = currentOwnerAnchors(identity, ownerEvidence);
        long relationVersion = ownerEvidence.relationVersion();
        if (request.studentSourceRefDigest().isPresent()) {
            try {
                var responsibility = responsibilities.query(new ResponsibilityScopeQuery(
                        request.studentSourceRefDigest().orElseThrow(), now));
                if (responsibility.validity()
                        == ResponsibilityScopeValidity.DEPENDENCY_UNAVAILABLE) {
                    return terminal(
                            request,
                            now,
                            CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE,
                            "RESPONSIBILITY_EVIDENCE_UNAVAILABLE",
                            identity.aggregateVersion(),
                            relationVersion,
                            ownerEvidence.grantVersion(),
                            ownerEvidence.invalidationVersion());
                }
                if (responsibility.validity() == ResponsibilityScopeValidity.VALID
                        && responsibility.studentSourceRefDigest().equals(
                                request.studentSourceRefDigest().orElseThrow())
                        && identity.accountId().equals(responsibility.counselorAccountId())
                        && identity.organizationIds().contains(
                                responsibility.collegeOrganizationId())) {
                    anchors.add(ScopeAnchor.CURRENT_RESPONSIBILITY);
                    relationVersion = Math.max(
                            relationVersion, responsibility.aggregateVersion());
                }
            } catch (RuntimeException unavailable) {
                return terminal(
                        request,
                        now,
                        CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE,
                        "RESPONSIBILITY_EVIDENCE_UNAVAILABLE",
                        identity.aggregateVersion(),
                        relationVersion,
                        ownerEvidence.grantVersion(),
                        ownerEvidence.invalidationVersion());
            }
        }

        ObjectClass objectClass = objectClass(request.objectClass());
        AuthorizationEvidenceVersions versions = new AuthorizationEvidenceVersions(
                identity.aggregateVersion(),
                relationVersion,
                ownerEvidence.grantVersion(),
                ownerEvidence.invalidationVersion(),
                POLICY_SEQUENCE);
        var domainRequest = new cn.edu.suda.scholarsense.identityaccess.domain.CompositeAuthorizationRequest(
                request.actorPseudonym(),
                currentRoles,
                objectClass,
                ActionId.of(request.actionId()),
                ownerEvidence.objectVersion(),
                anchors,
                ownerEvidence.purpose(),
                ownerEvidence.fieldAllowlist(),
                versions,
                now,
                true,
                true,
                ownerEvidence.separationOfDutyConflict(),
                ownerEvidence.highRiskApprovals(),
                delegation(identity, ownerEvidence.delegation()),
                taskWindow(ownerEvidence));
        return audited(request, publicDecision(authorization.authorize(domainRequest)));
    }

    private static EnumSet<ScopeAnchor> currentOwnerAnchors(
            AuthoritativeIdentityContext identity,
            AuthorizationObjectEvidence evidence) {
        EnumSet<ScopeAnchor> result = EnumSet.noneOf(ScopeAnchor.class);
        for (AuthorizationScopeEvidence candidate : evidence.scopeEvidence()) {
            boolean accountMatches = identity.accountId().equals(candidate.accountId());
            boolean organizationMatches = candidate.organizationId() != null
                    && identity.organizationIds().contains(candidate.organizationId());
            if (requiresActorBinding(candidate.anchor())
                    && !accountMatches
                    && !organizationMatches) {
                continue;
            }
            result.add(ScopeAnchor.valueOf(candidate.anchor().name()));
        }
        return result;
    }

    private static boolean requiresActorBinding(AuthorizationScopeAnchor anchor) {
        return anchor != AuthorizationScopeAnchor.SCHOOL_GOVERNANCE
                && anchor != AuthorizationScopeAnchor.SCHOOL_AGGREGATE
                && anchor != AuthorizationScopeAnchor.TECHNICAL_OBJECT;
    }

    private static Optional<DelegationEvidence> delegation(
            AuthoritativeIdentityContext identity,
            Optional<AuthorizationDelegationEvidence> evidence) {
        if (evidence.isEmpty()
                || !identity.accountId().equals(evidence.orElseThrow().granteeAccountId())) {
            return Optional.empty();
        }
        AuthorizationDelegationEvidence grant = evidence.orElseThrow();
        return Optional.of(new DelegationEvidence(
                grant.startAt(),
                grant.endAt(),
                grant.basePermissionProven(),
                grant.allowedObjectClasses().stream()
                        .map(CurrentEvidenceCompositeAuthorizationAdapter::objectClass)
                        .collect(Collectors.toUnmodifiableSet()),
                grant.allowedActions().stream()
                        .map(ActionId::of)
                        .collect(Collectors.toUnmodifiableSet()),
                grant.allowedFieldClasses().stream()
                        .map(CurrentEvidenceCompositeAuthorizationAdapter::fieldClass)
                        .collect(Collectors.toUnmodifiableSet()),
                grant.grantVersion()));
    }

    private static Optional<EffectiveWindow> taskWindow(AuthorizationObjectEvidence evidence) {
        if (evidence.taskStartAt() == null) {
            return Optional.empty();
        }
        return Optional.of(new EffectiveWindow(evidence.taskStartAt(), evidence.taskEndAt()));
    }

    private static ObjectClass objectClass(String value) {
        try {
            return ObjectClass.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            return ObjectClass.UNKNOWN;
        }
    }

    private static FieldClass fieldClass(String value) {
        for (FieldClass fieldClass : FieldClass.values()) {
            if (fieldClass.name().equals(value) || fieldClass.code().equals(value)) {
                return fieldClass;
            }
        }
        throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_FIELD_UNKNOWN");
    }

    private static cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest withActor(
            cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest request,
            String actorPseudonym) {
        return new cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest(
                actorPseudonym,
                request.objectClass(),
                request.actionId(),
                request.objectTokenDigest(),
                request.expectedObjectVersion(),
                request.studentSourceRefDigest(),
                request.accessLineageId(),
                request.traceId());
    }

    private static cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision
            publicDecision(
                    cn.edu.suda.scholarsense.identityaccess.domain.CompositeAuthorizationDecision decision) {
        Map<String, FieldVisibility> fields = decision.fieldProjectionSummary().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().code(),
                        entry -> FieldVisibility.valueOf(entry.getValue().name())));
        var token = decision.decisionToken();
        var versions = token.evidenceVersions();
        return new cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision(
                CompositeAuthorizationOutcome.valueOf(decision.outcome().name()),
                decision.reasonCode(),
                decision.applicableRolePackages().stream()
                        .map(RolePackage::authorityId)
                        .collect(Collectors.toUnmodifiableSet()),
                decision.matchedAnchors().stream()
                        .map(Enum::name)
                        .collect(Collectors.toUnmodifiableSet()),
                fields,
                decision.clearConditionalFields(),
                decision.policyVersion(),
                decision.objectVersion(),
                decision.evaluatedAt(),
                new CompositeAuthorizationDecisionToken(
                        versions.identityVersion(),
                        versions.relationVersion(),
                        versions.grantVersion(),
                        versions.invalidationVersion(),
                        versions.policySequence(),
                        token.objectVersion(),
                        token.policyVersion()));
    }

    private cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision terminal(
            cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest request,
            Instant evaluatedAt,
            CompositeAuthorizationOutcome outcome,
            String reasonCode,
            long identityVersion,
            long relationVersion,
            long grantVersion,
            long invalidationVersion) {
        return audited(request,
                new cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision(
                outcome,
                reasonCode,
                Set.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                RoleFieldPolicyCatalog.POLICY_VERSION,
                request.expectedObjectVersion(),
                evaluatedAt,
                new CompositeAuthorizationDecisionToken(
                        identityVersion,
                        relationVersion,
                        grantVersion,
                        invalidationVersion,
                        POLICY_SEQUENCE,
                        request.expectedObjectVersion(),
                        RoleFieldPolicyCatalog.POLICY_VERSION)));
    }

    private cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision audited(
            cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest request,
            cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision decision) {
        EnumMap<FieldClass, Visibility> fields = new EnumMap<>(FieldClass.class);
        for (FieldClass field : FieldClass.values()) {
            fields.put(field, Visibility.HIDDEN);
        }
        decision.fieldProjectionSummary().forEach((code, visibility) ->
                fields.put(fieldClass(code), Visibility.valueOf(visibility.name())));
        Set<RolePackage> roles = decision.applicableRolePackages().stream()
                .map(RolePackage::fromAuthorityId)
                .collect(Collectors.toUnmodifiableSet());
        Set<ScopeAnchor> scopes = decision.scopeAnchorSummary().stream()
                .map(ScopeAnchor::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        audit.record(new AuthorizationAuditRequest(
                AuthorizationAuditKind.OBJECT_DECISION,
                request.actorPseudonym(),
                roles,
                request.actionId(),
                request.objectTokenDigest(),
                null,
                scopes,
                decision.objectVersion(),
                fields,
                decision.outcome().name(),
                decision.evaluatedAt(),
                request.traceId(),
                null));
        return decision;
    }
}
