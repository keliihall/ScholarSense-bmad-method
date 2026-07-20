package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;
import cn.edu.suda.scholarsense.identityaccess.application.EnvelopeDecryptionPort;

public final class KmsEnvelopeDecryptionAdapter implements EnvelopeDecryptionPort {
    private final KmsEnvelopeDecryptClient kms;

    public KmsEnvelopeDecryptionAdapter(KmsEnvelopeDecryptClient kms) {
        this.kms = kms;
    }

    @Override
    public char[] decrypt(EncryptedSecret encrypted, String purpose) {
        char[] plaintext = kms.decrypt(encrypted, purpose);
        if (plaintext == null || plaintext.length == 0 || plaintext.length > 16_384) {
            throw new IllegalStateException("IDENTITY_KMS_DECRYPT_INVALID");
        }
        return plaintext;
    }
}
