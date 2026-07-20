package cn.edu.suda.scholarsense.identityaccess.application;

/** Decrypts one envelope secret through the deployment KMS; callers must erase the returned chars. */
@FunctionalInterface
public interface EnvelopeDecryptionPort {
    char[] decrypt(EncryptedSecret encrypted, String purpose);
}
