package cn.edu.suda.scholarsense.identityaccess.application;

import java.nio.charset.StandardCharsets;

public record SensitiveFieldCryptoContext(
        String actorPseudonym,
        String serviceIdentity,
        String classification,
        String purpose,
        String environment,
        String objectClass,
        String objectIdentityDigest,
        String fieldName,
        String keyRef,
        String keyVersion,
        String traceId) {
    public SensitiveFieldCryptoContext {
        require(actorPseudonym, "[a-z0-9][a-z0-9._-]{7,127}", "FIELD_CRYPTO_ACTOR_INVALID");
        require(serviceIdentity, "[a-z][a-z0-9._-]{2,63}", "FIELD_CRYPTO_SERVICE_INVALID");
        require(classification, "[A-Z][A-Z0-9_]{2,63}", "FIELD_CRYPTO_CLASSIFICATION_INVALID");
        require(purpose, "[a-z][a-z0-9.-]{2,127}", "FIELD_CRYPTO_PURPOSE_INVALID");
        require(environment, "[a-z][a-z0-9-]{2,31}", "FIELD_CRYPTO_ENVIRONMENT_INVALID");
        require(objectClass, "[A-Za-z][A-Za-z0-9]{2,63}", "FIELD_CRYPTO_OBJECT_CLASS_INVALID");
        require(objectIdentityDigest, "[0-9a-f]{64}", "FIELD_CRYPTO_OBJECT_IDENTITY_INVALID");
        require(fieldName, "[A-Za-z][A-Za-z0-9]{1,63}", "FIELD_CRYPTO_FIELD_INVALID");
        require(keyRef, "[a-z0-9][a-z0-9/._-]{2,127}", "FIELD_CRYPTO_KEY_REFERENCE_INVALID");
        require(keyVersion, "[a-z0-9][a-z0-9._-]{2,63}", "FIELD_CRYPTO_KEY_VERSION_INVALID");
        require(traceId, "[0-9a-f]{32}", "FIELD_CRYPTO_TRACE_INVALID");
    }

    public byte[] aad() {
        String canonical = String.join(
                "\u001f",
                "FIELD-CRYPTO-AAD-1.0.0",
                environment,
                objectClass,
                objectIdentityDigest,
                fieldName,
                classification,
                purpose,
                keyRef,
                keyVersion);
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private static void require(String value, String pattern, String code) {
        if (value == null || !value.matches(pattern)) {
            throw new IllegalArgumentException(code);
        }
    }
}
