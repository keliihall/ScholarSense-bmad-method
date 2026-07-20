package cn.edu.suda.scholarsense.identityaccess.application;

public record EncryptedSecret(
        byte[] ciphertext,
        byte[] wrappedDataKey,
        String keyRef,
        String keyVersion,
        byte[] nonce) {

    public EncryptedSecret {
        ciphertext = ciphertext.clone();
        wrappedDataKey = wrappedDataKey.clone();
        nonce = nonce.clone();
        if (ciphertext.length == 0 || wrappedDataKey.length == 0 || nonce.length == 0
                || keyRef == null || keyRef.isBlank() || keyVersion == null || keyVersion.isBlank()) {
            throw new IllegalArgumentException("IDENTITY_ENCRYPTED_SECRET_INVALID");
        }
    }

    @Override public byte[] ciphertext() { return ciphertext.clone(); }
    @Override public byte[] wrappedDataKey() { return wrappedDataKey.clone(); }
    @Override public byte[] nonce() { return nonce.clone(); }
}
