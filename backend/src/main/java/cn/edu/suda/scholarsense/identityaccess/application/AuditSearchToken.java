package cn.edu.suda.scholarsense.identityaccess.application;

public record AuditSearchToken(String value, String profileVersion, String keyVersion) {
    public AuditSearchToken {
        if (value == null || !value.matches("(?:ast|ost|ipt|agt|gst)_v1_k[0-9]+_[0-9a-f]{64}")) {
            throw new IllegalArgumentException("AUDIT_SEARCH_TOKEN_INVALID");
        }
        if (!"AUDIT-TOKENIZATION-1.0.0".equals(profileVersion)
                || keyVersion == null || !keyVersion.matches("k[0-9]+")) {
            throw new IllegalArgumentException("AUDIT_TOKENIZATION_PROFILE_INVALID");
        }
    }
}
