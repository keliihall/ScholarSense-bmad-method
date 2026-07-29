package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.Objects;

public record AuthorizationDecision(
        AuthorizationOutcome outcome,
        String reasonCode,
        AuthorizationFreshness freshness,
        String policyVersion,
        long sourceVersion) {
    public AuthorizationDecision {
        Objects.requireNonNull(outcome, "outcome");
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_REASON_INVALID");
        }
        Objects.requireNonNull(freshness, "freshness");
        if (policyVersion == null || !policyVersion.matches("[A-Z][A-Z0-9.-]{2,63}")
                || sourceVersion < 0) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_VERSION_INVALID");
        }
    }

    public static AuthorizationDecision allow(long sourceVersion, AuthorizationFreshness freshness) {
        return new AuthorizationDecision(
                AuthorizationOutcome.ALLOW,
                "AUTHORIZATION_ALLOWED",
                freshness,
                "RFP-1.0.0",
                sourceVersion);
    }

    public static AuthorizationDecision deny(String reasonCode, long sourceVersion) {
        return new AuthorizationDecision(
                AuthorizationOutcome.DENY,
                reasonCode,
                AuthorizationFreshness.STALE,
                "RFP-1.0.0",
                sourceVersion);
    }

    public static AuthorizationDecision unavailable(String reasonCode, long sourceVersion) {
        return new AuthorizationDecision(
                AuthorizationOutcome.DEPENDENCY_UNAVAILABLE,
                reasonCode,
                AuthorizationFreshness.STALE,
                "RFP-1.0.0",
                sourceVersion);
    }
}
