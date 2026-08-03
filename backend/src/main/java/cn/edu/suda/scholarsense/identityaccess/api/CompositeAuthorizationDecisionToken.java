package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.Objects;

/** Opaque-to-consumers version tuple used by the pre-commit recheck boundary. */
public record CompositeAuthorizationDecisionToken(
        long identityVersion,
        long relationVersion,
        long grantVersion,
        long invalidationVersion,
        long policySequence,
        long objectVersion,
        String policyVersion) {
    public CompositeAuthorizationDecisionToken {
        if (identityVersion < 0 || relationVersion < 0 || grantVersion < 0
                || invalidationVersion < 0 || policySequence < 0 || objectVersion < 1) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_TOKEN_VERSION_INVALID");
        }
        if (!"RFP-1.0.0".equals(policyVersion)) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_TOKEN_POLICY_INVALID");
        }
        Objects.requireNonNull(policyVersion, "policyVersion");
    }
}
