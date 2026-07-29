package cn.edu.suda.scholarsense.identityaccess.application;

public record IdentityAuthorizationPolicySnapshot(
        boolean available,
        String identitySessionPolicyVersion,
        String roleFieldPolicyVersion,
        String roleMappingVersion,
        String roleMappingDigest) {

    public IdentityAuthorizationPolicySnapshot {
        if (available
                && (!"ISP-1.0.0".equals(identitySessionPolicyVersion)
                        || !"RFP-1.0.0".equals(roleFieldPolicyVersion)
                        || !"IDENTITY-ROLE-MAPPING-1.0.0".equals(roleMappingVersion)
                        || roleMappingDigest == null
                        || !roleMappingDigest.matches("sha256:[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(
                    "IDENTITY_AUTHORIZATION_POLICY_SNAPSHOT_INVALID");
        }
    }

    public static IdentityAuthorizationPolicySnapshot unavailable() {
        return new IdentityAuthorizationPolicySnapshot(
                false, null, null, null, null);
    }
}
