package cn.edu.suda.scholarsense.subjectregistry.domain;

public record ProtectedIdentifierToken(
        String environment,
        String keyRef,
        String keyVersion,
        String value) {

    public ProtectedIdentifierToken {
        SubjectRegistryDomainRules.requireBounded(environment, 1, 32);
        SubjectRegistryDomainRules.requireBounded(keyRef, 1, 256);
        SubjectRegistryDomainRules.requireBounded(keyVersion, 1, 64);
        if (value == null || !value.matches("^hmac-sha256:[0-9a-f]{64}$")) {
            throw new SubjectRegistryException(
                    SubjectRegistryErrorCode.SUBJECT_REGISTRY_IDENTIFIER_INVALID);
        }
    }

    public static ProtectedIdentifierToken of(
            String environment, String keyRef, String keyVersion, String value) {
        return new ProtectedIdentifierToken(environment, keyRef, keyVersion, value);
    }

    @Override
    public String toString() {
        return "ProtectedIdentifierToken[REDACTED]";
    }
}
