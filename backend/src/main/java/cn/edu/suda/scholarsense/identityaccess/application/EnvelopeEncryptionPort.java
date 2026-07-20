package cn.edu.suda.scholarsense.identityaccess.application;

@FunctionalInterface
public interface EnvelopeEncryptionPort {
    EncryptedSecret encrypt(char[] plaintext, String purpose);
}
