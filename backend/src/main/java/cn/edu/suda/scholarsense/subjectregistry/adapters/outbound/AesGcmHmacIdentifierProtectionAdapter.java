package cn.edu.suda.scholarsense.subjectregistry.adapters.outbound;

import cn.edu.suda.scholarsense.subjectregistry.application.IdentifierProtectionContext;
import cn.edu.suda.scholarsense.subjectregistry.application.IdentifierProtectionPort;
import cn.edu.suda.scholarsense.subjectregistry.application.IdentifierProtectionUnavailableException;
import cn.edu.suda.scholarsense.subjectregistry.application.ProtectedIdentifierMaterial;
import cn.edu.suda.scholarsense.subjectregistry.domain.ProtectedIdentifierToken;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;

public final class AesGcmHmacIdentifierProtectionAdapter implements IdentifierProtectionPort {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SubjectRegistryProtectionKeyPort keys;
    private final SecureRandom random;

    public AesGcmHmacIdentifierProtectionAdapter(SubjectRegistryProtectionKeyPort keys) {
        this(keys, new SecureRandom());
    }

    AesGcmHmacIdentifierProtectionAdapter(
            SubjectRegistryProtectionKeyPort keys, SecureRandom random) {
        this.keys = Objects.requireNonNull(keys);
        this.random = Objects.requireNonNull(random);
    }

    @Override
    public ProtectedIdentifierMaterial protect(
            String normalizedIdentifier, IdentifierProtectionContext context) {
        if (normalizedIdentifier == null || normalizedIdentifier.isEmpty()) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_NORMALIZED_IDENTIFIER_INVALID");
        }
        Objects.requireNonNull(context);
        try {
            SubjectRegistryProtectionKeys active = Objects.requireNonNull(keys.active());
            String tokenValue = token(active, context, normalizedIdentifier);
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, active.encryptionKey(),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(active, context.purpose()));
            byte[] encrypted = cipher.doFinal(
                    normalizedIdentifier.getBytes(StandardCharsets.UTF_8));
            String ciphertext = "aesgcm-v1:"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
                    + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
            return new ProtectedIdentifierMaterial(
                    ProtectedIdentifierToken.of(
                            active.environment(), active.keyRef(), active.keyVersion(), tokenValue),
                    ciphertext, context.purpose());
        } catch (GeneralSecurityException | RuntimeException unavailable) {
            throw new IdentifierProtectionUnavailableException();
        }
    }

    @Override
    public String reveal(ProtectedIdentifierMaterial material) {
        Objects.requireNonNull(material);
        try {
            SubjectRegistryProtectionKeys referenced = Objects.requireNonNull(keys.byReference(
                    material.token().environment(), material.token().keyRef(),
                    material.token().keyVersion()));
            requireSameReference(material, referenced);
            String[] parts = material.ciphertext().split(":", -1);
            if (parts.length != 3 || !"aesgcm-v1".equals(parts[0])) {
                throw new GeneralSecurityException("unsupported ciphertext envelope");
            }
            byte[] nonce = Base64.getUrlDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getUrlDecoder().decode(parts[2]);
            if (nonce.length != NONCE_BYTES) {
                throw new GeneralSecurityException("invalid nonce");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, referenced.encryptionKey(),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(referenced, material.purpose()));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException unavailable) {
            throw new IdentifierProtectionUnavailableException();
        }
    }

    private static String token(
            SubjectRegistryProtectionKeys keys,
            IdentifierProtectionContext context,
            String normalizedIdentifier) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(keys.searchTokenKey());
        String input = String.join("\u0000",
                "subject-registry-search-token-v1",
                keys.environment(), keys.keyRef(), keys.keyVersion(),
                context.sourceId(), context.identifierType(), context.purpose(),
                normalizedIdentifier);
        return "hmac-sha256:" + HexFormat.of().formatHex(
                mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] aad(SubjectRegistryProtectionKeys keys, String purpose) {
        return String.join("\u0000",
                "subject-registry-aes-gcm-v1",
                keys.environment(), keys.keyRef(), keys.keyVersion(), purpose)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void requireSameReference(
            ProtectedIdentifierMaterial material, SubjectRegistryProtectionKeys keys)
            throws GeneralSecurityException {
        if (!material.token().environment().equals(keys.environment())
                || !material.token().keyRef().equals(keys.keyRef())
                || !material.token().keyVersion().equals(keys.keyVersion())) {
            throw new GeneralSecurityException("key reference mismatch");
        }
    }
}
