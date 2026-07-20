package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;

/** Deployment-supplied KMS decrypt client. Implementations and credentials stay outside the repository. */
@FunctionalInterface
public interface KmsEnvelopeDecryptClient {
    char[] decrypt(EncryptedSecret encrypted, String purpose);
}
