package cn.edu.suda.scholarsense.identityaccess.domain;

public record AccessInvalidationAuthorizationSnapshot(
        AccessInvalidationAuthorizationState currentState,
        boolean accountActive,
        boolean r1EmploymentValid,
        boolean collegeActive,
        boolean relationEffective,
        String policyVersion) {
    public AccessInvalidationAuthorizationSnapshot {
        AccessInvalidationValidation.required(
                currentState, "currentState");
        if (!"RFP-1.0.0".equals(policyVersion)) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_POLICY_VERSION_INVALID");
        }
        if (currentState == AccessInvalidationAuthorizationState.REVALIDATED
                && (!accountActive
                        || !r1EmploymentValid
                        || !collegeActive
                        || !relationEffective)) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_REVALIDATED_SNAPSHOT_INVALID");
        }
    }
}
