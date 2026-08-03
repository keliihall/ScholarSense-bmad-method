package cn.edu.suda.scholarsense.identityaccess.domain;

public record AuthorizationEvidenceVersions(
        long identityVersion,
        long relationVersion,
        long grantVersion,
        long invalidationVersion,
        long policySequence) {
    public AuthorizationEvidenceVersions {
        if (identityVersion < 0 || relationVersion < 0 || grantVersion < 0
                || invalidationVersion < 0 || policySequence < 0) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_EVIDENCE_VERSION_INVALID");
        }
    }
}
