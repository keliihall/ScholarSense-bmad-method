package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record DelegationEvidence(
        Instant startAt,
        Instant endAt,
        boolean basePermissionProven,
        Set<ObjectClass> allowedObjectClasses,
        Set<ActionId> allowedActions,
        Set<FieldClass> allowedFieldClasses,
        long grantVersion) {
    public DelegationEvidence {
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        allowedObjectClasses = Set.copyOf(allowedObjectClasses);
        allowedActions = Set.copyOf(allowedActions);
        allowedFieldClasses = Set.copyOf(allowedFieldClasses);
        if (!startAt.isBefore(endAt) || grantVersion < 1) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_GRANT_INVALID");
        }
    }

    public boolean activeAt(Instant instant) {
        return !instant.isBefore(startAt) && instant.isBefore(endAt);
    }

    public boolean permits(ObjectClass objectClass, ActionId actionId) {
        return allowedObjectClasses.contains(objectClass) && allowedActions.contains(actionId);
    }
}
