package cn.edu.suda.scholarsense.identityaccess.api;

/** Opaque reference to a protected value; it never carries plaintext or ciphertext bytes. */
public record SensitiveValueReference(
        String referenceToken,
        String classification,
        String keyRef,
        String keyVersion) {
    public SensitiveValueReference {
        if (referenceToken == null || !referenceToken.matches("[A-Z][A-Z0-9_-]{2,127}")) {
            throw new IllegalArgumentException("FIELD_PROJECTION_VALUE_REFERENCE_INVALID");
        }
        if (classification == null || !classification.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("FIELD_PROJECTION_CLASSIFICATION_INVALID");
        }
        if (keyRef == null || !keyRef.matches("[a-z0-9][a-z0-9/._-]{2,127}")) {
            throw new IllegalArgumentException("FIELD_PROJECTION_KEY_REFERENCE_INVALID");
        }
        if (keyVersion == null || !keyVersion.matches("[a-z0-9][a-z0-9._-]{2,63}")) {
            throw new IllegalArgumentException("FIELD_PROJECTION_KEY_VERSION_INVALID");
        }
    }
}
