package cn.edu.suda.scholarsense.identityaccess.application;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class RotatingSensitiveFieldCryptoTestAdapter implements SensitiveFieldCryptoPort {
    private static final int TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;

    private final Map<String, byte[]> keys = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final String currentVersion;
    private boolean available = true;
    private int decryptCalls;
    private WipeablePlaintext lastPlaintext;

    RotatingSensitiveFieldCryptoTestAdapter(String currentVersion) {
        this.currentVersion = currentVersion;
        keys.put("key-v3", key((byte) 3));
        keys.put("key-v4", key((byte) 4));
    }

    @Override
    public FieldCiphertextEnvelope encrypt(
            SensitiveFieldCryptoContext context, WipeablePlaintext plaintext) {
        if (!available) {
            throw new SensitiveFieldCryptoException("FIELD_CRYPTO_DEPENDENCY_UNAVAILABLE");
        }
        if (!currentVersion.equals(context.keyVersion())) {
            throw new SensitiveFieldCryptoException("FIELD_CRYPTO_WRITE_VERSION_NOT_CURRENT");
        }
        return encryptWithVersion(context, plaintext);
    }

    FieldCiphertextEnvelope encryptWithVersion(
            SensitiveFieldCryptoContext context, WipeablePlaintext plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] ciphertext = plaintext.use(bytes -> crypt(Cipher.ENCRYPT_MODE, context, bytes, nonce));
        return new FieldCiphertextEnvelope(
                context.keyRef(), context.keyVersion(), nonce, ciphertext, "AES-GCM-TEST-ONLY");
    }

    @Override
    public WipeablePlaintext decrypt(
            SensitiveFieldCryptoContext context, FieldCiphertextEnvelope envelope) {
        decryptCalls++;
        if (!available) {
            throw new SensitiveFieldCryptoException("FIELD_CRYPTO_DEPENDENCY_UNAVAILABLE");
        }
        if (!context.keyRef().equals(envelope.keyRef())
                || !context.keyVersion().equals(envelope.keyVersion())
                || !Set.of("key-v3", "key-v4").contains(context.keyVersion())) {
            throw new SensitiveFieldCryptoException("FIELD_CRYPTO_KEY_VERSION_UNKNOWN");
        }
        byte[] clear = crypt(Cipher.DECRYPT_MODE, context, envelope.ciphertext(), envelope.nonce());
        lastPlaintext = WipeablePlaintext.own(clear);
        return lastPlaintext;
    }

    int decryptCalls() {
        return decryptCalls;
    }

    WipeablePlaintext lastPlaintext() {
        return lastPlaintext;
    }

    void unavailable() {
        available = false;
    }

    private byte[] crypt(
            int mode,
            SensitiveFieldCryptoContext context,
            byte[] value,
            byte[] nonce) {
        byte[] key = keys.get(context.keyVersion());
        if (key == null) {
            throw new SensitiveFieldCryptoException("FIELD_CRYPTO_KEY_VERSION_UNKNOWN");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context.aad());
            return cipher.doFinal(value);
        } catch (AEADBadTagException exception) {
            throw new SensitiveFieldCryptoException("FIELD_CRYPTO_AUTHENTICATION_FAILED");
        } catch (GeneralSecurityException exception) {
            throw new SensitiveFieldCryptoException("FIELD_CRYPTO_OPERATION_FAILED");
        }
    }

    private static byte[] key(byte seed) {
        byte[] value = new byte[32];
        Arrays.fill(value, seed);
        return value;
    }
}
