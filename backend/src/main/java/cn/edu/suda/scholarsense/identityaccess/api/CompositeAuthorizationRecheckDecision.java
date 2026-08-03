package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.Objects;

public record CompositeAuthorizationRecheckDecision(
        CompositeAuthorizationRecheckOutcome outcome,
        String reasonCode) {
    public CompositeAuthorizationRecheckDecision {
        Objects.requireNonNull(outcome, "outcome");
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_RECHECK_REASON_INVALID");
        }
    }
}
