package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.Objects;

public final class AuthorizationDecisionRechecker {
    public boolean isCurrent(
            AuthorizationDecisionToken token,
            AuthorizationEvidenceVersions currentVersions,
            long currentObjectVersion,
            String currentPolicyVersion) {
        Objects.requireNonNull(token, "token");
        return token.evidenceVersions().equals(currentVersions)
                && token.objectVersion() == currentObjectVersion
                && token.policyVersion().equals(currentPolicyVersion);
    }

    public AuthorizationRecheckResult recheck(
            AuthorizationDecisionToken token,
            AuthorizationEvidenceVersions currentVersions,
            long currentObjectVersion,
            String currentPolicyVersion) {
        if (isCurrent(token, currentVersions, currentObjectVersion, currentPolicyVersion)) {
            return new AuthorizationRecheckResult(true, "AUTHORIZATION_RECHECK_ALLOWED");
        }
        return new AuthorizationRecheckResult(false, "IDENTITY_AUTHORIZATION_DECISION_STALE");
    }
}
