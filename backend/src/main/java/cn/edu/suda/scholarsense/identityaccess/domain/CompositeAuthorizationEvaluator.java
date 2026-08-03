package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Pure RFP-1.0.0 evaluator. It performs no I/O and never caches object decisions. */
public final class CompositeAuthorizationEvaluator {
    public CompositeAuthorizationDecision evaluate(
            CompositeAuthorizationRequest request,
            RoleFieldPolicyCatalog policy) {
        AuthorizationDecisionToken token = new AuthorizationDecisionToken(
                request.evidenceVersions(),
                request.objectVersion(),
                RoleFieldPolicyCatalog.POLICY_VERSION);
        if (!policy.available()) {
            return dependencyUnavailable(request, token, policy.unavailableReason());
        }
        if (!request.identityAvailable()) {
            return dependencyUnavailable(request, token, "IDENTITY_AUTHORITY_UNAVAILABLE");
        }
        if (!request.trustedClock()) {
            return dependencyUnavailable(request, token, "TRUSTED_CLOCK_UNAVAILABLE");
        }
        if (request.objectClass() == ObjectClass.UNKNOWN) {
            return deny(request, token, "OBJECT_CLASS_UNKNOWN");
        }
        if (!policy.knownAction(request.actionId())) {
            return deny(request, token, "ACTION_UNKNOWN");
        }

        EnumSet<RolePackage> applicableRoles = EnumSet.noneOf(RolePackage.class);
        EnumSet<ScopeAnchor> matchedAnchors = EnumSet.noneOf(ScopeAnchor.class);
        boolean roleObjectActionMatched = false;
        for (RolePackage role : request.rolePackages()) {
            if (!policy.matches(role, request.objectClass(), request.actionId())) {
                continue;
            }
            roleObjectActionMatched = true;
            Set<ScopeAnchor> roleAnchors = policy.matchingAnchors(
                    role, request.objectClass(), request.scopeAnchors());
            if (!roleAnchors.isEmpty()) {
                applicableRoles.add(role);
                matchedAnchors.addAll(roleAnchors);
            }
        }

        if (applicableRoles.isEmpty()) {
            Optional<String> explicitDeny = policy.explicitDenyReason(
                    request.rolePackages(),
                    request.objectClass(),
                    request.actionId(),
                    request.scopeAnchors());
            if (explicitDeny.isPresent()) {
                return deny(request, token, explicitDeny.orElseThrow());
            }
            return deny(request, token,
                    roleObjectActionMatched ? "SCOPE_NOT_PROVEN" : "ROLE_OBJECT_ACTION_NOT_ALLOWED");
        }

        if (request.separationOfDutyConflict()) {
            return deny(request, token, "SEPARATION_OF_DUTY_CONFLICT");
        }

        if (applicableRoles.contains(RolePackage.R5)) {
            if (request.purpose() == null || request.purpose().isBlank()) {
                return deny(request, token, "TRANSFER_PURPOSE_REQUIRED");
            }
            if (request.taskWindow().isEmpty()
                    || !request.taskWindow().orElseThrow().contains(request.serverNow())) {
                return deny(request, token, "TASK_WINDOW_INACTIVE");
            }
        }

        Optional<DelegationEvidence> appliedDelegation = Optional.empty();
        if (matchedAnchors.contains(ScopeAnchor.VALID_DELEGATION_GRANT)
                && matchedAnchors.stream().allMatch(
                        anchor -> anchor == ScopeAnchor.VALID_DELEGATION_GRANT)) {
            if (request.delegation().isEmpty()) {
                return dependencyUnavailable(request, token, "DELEGATION_EVIDENCE_UNAVAILABLE");
            }
            DelegationEvidence delegation = request.delegation().orElseThrow();
            if (!delegation.activeAt(request.serverNow())) {
                return deny(request, token, "DELEGATION_INACTIVE");
            }
            if (!delegation.basePermissionProven()) {
                return deny(request, token, "DELEGATION_BASE_PERMISSION_REQUIRED");
            }
            if (!delegation.permits(request.objectClass(), request.actionId())) {
                return deny(request, token, "DELEGATION_SCOPE_NOT_ALLOWED");
            }
            appliedDelegation = Optional.of(delegation);
        }

        Optional<String> requiredHrap = policy.requiredHrapAction(
                request.objectClass(), request.actionId());
        if (requiredHrap.isPresent()
                && !request.highRiskApprovals().contains(requiredHrap.orElseThrow())) {
            return deny(request, token, "HRAP_APPROVAL_REQUIRED");
        }

        Map<FieldClass, Visibility> projection = mergeFields(applicableRoles, policy);
        if (appliedDelegation.isPresent()) {
            Set<FieldClass> allowed = appliedDelegation.orElseThrow().allowedFieldClasses();
            for (FieldClass fieldClass : FieldClass.values()) {
                if (!allowed.contains(fieldClass)) {
                    projection.put(fieldClass, Visibility.HIDDEN);
                }
            }
        }
        Set<String> conditionalFields = new LinkedHashSet<>();
        for (RolePackage role : applicableRoles) {
            conditionalFields.addAll(policy.conditionalClearFields(
                    role,
                    request.objectClass(),
                    request.purpose(),
                    request.fieldAllowlist()));
        }
        return new CompositeAuthorizationDecision(
                CompositeAuthorizationOutcome.ALLOW,
                "ROLE_SCOPE_MATCHED",
                applicableRoles,
                matchedAnchors,
                projection,
                conditionalFields,
                RoleFieldPolicyCatalog.POLICY_VERSION,
                request.evidenceVersions(),
                request.objectVersion(),
                request.serverNow(),
                token);
    }

    private static Map<FieldClass, Visibility> mergeFields(
            Set<RolePackage> roles, RoleFieldPolicyCatalog policy) {
        EnumMap<FieldClass, Visibility> result = new EnumMap<>(FieldClass.class);
        for (RolePackage role : roles) {
            for (Map.Entry<FieldClass, Visibility> entry
                    : policy.fieldVisibility(role).entrySet()) {
                result.merge(entry.getKey(), entry.getValue(), Visibility::strictest);
            }
        }
        return result;
    }

    private static CompositeAuthorizationDecision deny(
            CompositeAuthorizationRequest request,
            AuthorizationDecisionToken token,
            String reasonCode) {
        return decision(
                request,
                token,
                CompositeAuthorizationOutcome.DENY,
                reasonCode);
    }

    private static CompositeAuthorizationDecision dependencyUnavailable(
            CompositeAuthorizationRequest request,
            AuthorizationDecisionToken token,
            String reasonCode) {
        return decision(
                request,
                token,
                CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE,
                reasonCode);
    }

    private static CompositeAuthorizationDecision decision(
            CompositeAuthorizationRequest request,
            AuthorizationDecisionToken token,
            CompositeAuthorizationOutcome outcome,
            String reasonCode) {
        return new CompositeAuthorizationDecision(
                outcome,
                reasonCode,
                Set.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                RoleFieldPolicyCatalog.POLICY_VERSION,
                request.evidenceVersions(),
                request.objectVersion(),
                request.serverNow(),
                token);
    }
}
