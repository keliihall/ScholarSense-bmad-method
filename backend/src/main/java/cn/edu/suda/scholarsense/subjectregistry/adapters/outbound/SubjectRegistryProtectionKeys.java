package cn.edu.suda.scholarsense.subjectregistry.adapters.outbound;

import java.util.Objects;
import javax.crypto.SecretKey;

public record SubjectRegistryProtectionKeys(
        String environment,
        String keyRef,
        String keyVersion,
        SecretKey encryptionKey,
        SecretKey searchTokenKey) {
    public SubjectRegistryProtectionKeys {
        if (environment == null || environment.isBlank() || environment.length() > 32
                || keyRef == null || keyRef.isBlank() || keyRef.length() > 256
                || keyVersion == null || keyVersion.isBlank() || keyVersion.length() > 64) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_PROTECTION_KEY_REF_INVALID");
        }
        Objects.requireNonNull(encryptionKey);
        Objects.requireNonNull(searchTokenKey);
        byte[] encryptionBytes = encryptionKey.getEncoded();
        byte[] tokenBytes = searchTokenKey.getEncoded();
        if (!"AES".equalsIgnoreCase(encryptionKey.getAlgorithm())
                || encryptionBytes == null || encryptionBytes.length != 32
                || !"HmacSHA256".equalsIgnoreCase(searchTokenKey.getAlgorithm())
                || tokenBytes == null || tokenBytes.length < 32) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_PROTECTION_KEY_INVALID");
        }
    }

    @Override
    public String toString() {
        return "SubjectRegistryProtectionKeys[environment=" + environment
                + ", keyRef=" + keyRef + ", keyVersion=" + keyVersion
                + ", material=REDACTED]";
    }
}
