package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record CompositeAuthorizationRequest(
        String actorPseudonym,
        Set<RolePackage> rolePackages,
        ObjectClass objectClass,
        ActionId actionId,
        long objectVersion,
        Set<ScopeAnchor> scopeAnchors,
        String purpose,
        Set<String> fieldAllowlist,
        AuthorizationEvidenceVersions evidenceVersions,
        Instant serverNow,
        boolean identityAvailable,
        boolean trustedClock,
        boolean separationOfDutyConflict,
        Set<String> highRiskApprovals,
        Optional<DelegationEvidence> delegation,
        Optional<EffectiveWindow> taskWindow) {
    public CompositeAuthorizationRequest {
        if (actorPseudonym == null || actorPseudonym.isBlank()) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_ACTOR_REQUIRED");
        }
        rolePackages = Set.copyOf(rolePackages);
        Objects.requireNonNull(objectClass, "objectClass");
        Objects.requireNonNull(actionId, "actionId");
        if (objectVersion < 1) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_OBJECT_VERSION_INVALID");
        }
        scopeAnchors = Set.copyOf(scopeAnchors);
        fieldAllowlist = Set.copyOf(fieldAllowlist);
        Objects.requireNonNull(evidenceVersions, "evidenceVersions");
        Objects.requireNonNull(serverNow, "serverNow");
        highRiskApprovals = Set.copyOf(highRiskApprovals);
        delegation = Objects.requireNonNull(delegation, "delegation");
        taskWindow = Objects.requireNonNull(taskWindow, "taskWindow");
    }

    public CompositeAuthorizationRequest withTrustedClock(boolean value) {
        return copy(purpose, separationOfDutyConflict, highRiskApprovals, value);
    }

    public CompositeAuthorizationRequest withSeparationOfDutyConflict(boolean value) {
        return copy(purpose, value, highRiskApprovals, trustedClock);
    }

    public CompositeAuthorizationRequest withHighRiskApprovals(Set<String> value) {
        return copy(purpose, separationOfDutyConflict, value, trustedClock);
    }

    public CompositeAuthorizationRequest withPurpose(String value) {
        return copy(value, separationOfDutyConflict, highRiskApprovals, trustedClock);
    }

    private CompositeAuthorizationRequest copy(
            String newPurpose,
            boolean newConflict,
            Set<String> newApprovals,
            boolean newTrustedClock) {
        return new CompositeAuthorizationRequest(
                actorPseudonym,
                rolePackages,
                objectClass,
                actionId,
                objectVersion,
                scopeAnchors,
                newPurpose,
                fieldAllowlist,
                evidenceVersions,
                serverNow,
                identityAvailable,
                newTrustedClock,
                newConflict,
                newApprovals,
                delegation,
                taskWindow);
    }
}
