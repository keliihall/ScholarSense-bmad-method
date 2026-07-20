package cn.edu.suda.scholarsense.identityaccess.application;

public record AuditTokenizationMetadata(String profileVersion, String keyVersion) {
    public AuditTokenizationMetadata {
        if (!"AUDIT-TOKENIZATION-1.0.0".equals(profileVersion)
                || keyVersion == null || !keyVersion.matches("k[0-9]+")) {
            throw new IllegalArgumentException("AUDIT_TOKENIZATION_PROFILE_INVALID");
        }
    }
}
