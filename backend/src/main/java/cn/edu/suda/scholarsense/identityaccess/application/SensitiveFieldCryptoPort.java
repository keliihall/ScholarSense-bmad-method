package cn.edu.suda.scholarsense.identityaccess.application;

/** KMS-neutral field crypto boundary. Implementations must enforce current/new-write policy. */
public interface SensitiveFieldCryptoPort {
    FieldCiphertextEnvelope encrypt(
            SensitiveFieldCryptoContext context, WipeablePlaintext plaintext);

    WipeablePlaintext decrypt(
            SensitiveFieldCryptoContext context, FieldCiphertextEnvelope envelope);
}
