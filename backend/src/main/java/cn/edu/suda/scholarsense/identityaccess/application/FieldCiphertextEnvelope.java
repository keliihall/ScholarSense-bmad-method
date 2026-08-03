package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.Arrays;

/** Protected field payload. The algorithm name is adapter metadata, not a production choice. */
public final class FieldCiphertextEnvelope {
    private final String keyRef;
    private final String keyVersion;
    private final byte[] nonce;
    private final byte[] ciphertext;
    private final String algorithm;

    public FieldCiphertextEnvelope(
            String keyRef,
            String keyVersion,
            byte[] nonce,
            byte[] ciphertext,
            String algorithm) {
        if (keyRef == null || !keyRef.matches("[a-z0-9][a-z0-9/._-]{2,127}")) {
            throw new IllegalArgumentException("FIELD_CRYPTO_KEY_REFERENCE_INVALID");
        }
        if (keyVersion == null || !keyVersion.matches("[a-z0-9][a-z0-9._-]{2,63}")) {
            throw new IllegalArgumentException("FIELD_CRYPTO_KEY_VERSION_INVALID");
        }
        if (nonce == null || nonce.length < 12 || nonce.length > 32) {
            throw new IllegalArgumentException("FIELD_CRYPTO_NONCE_INVALID");
        }
        if (ciphertext == null || ciphertext.length < 16) {
            throw new IllegalArgumentException("FIELD_CRYPTO_CIPHERTEXT_INVALID");
        }
        if (algorithm == null || !algorithm.matches("[A-Z0-9-]{3,63}")) {
            throw new IllegalArgumentException("FIELD_CRYPTO_ALGORITHM_INVALID");
        }
        this.keyRef = keyRef;
        this.keyVersion = keyVersion;
        this.nonce = Arrays.copyOf(nonce, nonce.length);
        this.ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
        this.algorithm = algorithm;
    }

    public String keyRef() {
        return keyRef;
    }

    public String keyVersion() {
        return keyVersion;
    }

    public byte[] nonce() {
        return Arrays.copyOf(nonce, nonce.length);
    }

    public byte[] ciphertext() {
        return Arrays.copyOf(ciphertext, ciphertext.length);
    }

    public String algorithm() {
        return algorithm;
    }
}
