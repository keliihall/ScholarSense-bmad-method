package cn.edu.suda.scholarsense.identityaccess.api;

public record AuditSearchQueryToken(String value, String profileVersion, String keyVersion) {
    public AuditSearchQueryToken {
        if (value == null || !value.matches("(?:ast|ost)_v1_k[0-9]+_[0-9a-f]{64}")) {
            throw new IllegalArgumentException("AUDIT_SEARCH_TOKEN_INVALID");
        }
        if (!"AUDIT-TOKENIZATION-1.0.0".equals(profileVersion)
                || keyVersion == null || !keyVersion.matches("k[0-9]+")) {
            throw new IllegalArgumentException("AUDIT_SEARCH_TOKEN_PROFILE_INVALID");
        }
    }
}
