package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.Objects;

public record AuthorizationDecisionToken(
        AuthorizationEvidenceVersions evidenceVersions,
        long objectVersion,
        String policyVersion) {
    public AuthorizationDecisionToken {
        Objects.requireNonNull(evidenceVersions, "evidenceVersions");
        if (objectVersion < 1 || !"RFP-1.0.0".equals(policyVersion)) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_DECISION_TOKEN_INVALID");
        }
    }
}
