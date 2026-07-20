package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;

/** Deployment-supplied KMS client. Implementations and credentials stay outside the repository. */
@FunctionalInterface
public interface KmsEnvelopeClient {
    EncryptedSecret encrypt(char[] plaintext, String purpose);
}
