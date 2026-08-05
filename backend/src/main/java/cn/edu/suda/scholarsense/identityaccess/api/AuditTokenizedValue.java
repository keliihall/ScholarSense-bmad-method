package cn.edu.suda.scholarsense.identityaccess.api;

/** Keyed one-way token plus the exact profile/key metadata used to create it. */
public record AuditTokenizedValue(
        String value,
        String profileVersion,
        String keyVersion) {
    public AuditTokenizedValue {
        if (value == null
                || !value.matches("(?:ast|ost|ipt|agt)_v1_k[0-9]+_[0-9a-f]{64}")) {
            throw new IllegalArgumentException("AUDIT_SEARCH_TOKEN_INVALID");
        }
        if (!"AUDIT-TOKENIZATION-1.0.0".equals(profileVersion)
                || keyVersion == null || !keyVersion.matches("k[0-9]+")) {
            throw new IllegalArgumentException("AUDIT_TOKENIZATION_PROFILE_INVALID");
        }
    }
}
