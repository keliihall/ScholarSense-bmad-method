package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;
import cn.edu.suda.scholarsense.identityaccess.application.EnvelopeEncryptionPort;

public final class KmsEnvelopeEncryptionAdapter implements EnvelopeEncryptionPort {
    private final KmsEnvelopeClient kms;

    public KmsEnvelopeEncryptionAdapter(KmsEnvelopeClient kms) {
        this.kms = kms;
    }

    @Override
    public EncryptedSecret encrypt(char[] plaintext, String purpose) {
        EncryptedSecret encrypted = kms.encrypt(plaintext, purpose);
        if (encrypted.keyRef().isBlank() || encrypted.keyVersion().isBlank()) {
            throw new IllegalStateException("IDENTITY_KMS_METADATA_MISSING");
        }
        return encrypted;
    }
}
