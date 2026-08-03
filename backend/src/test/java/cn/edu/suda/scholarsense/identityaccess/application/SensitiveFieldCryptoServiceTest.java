package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.domain.Visibility;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SensitiveFieldCryptoServiceTest {
    private static final String CANARY = "student-contact-canary-严禁泄露";

    @Test
    void dual_read_and_new_write_only_use_the_current_version() {
        RotatingSensitiveFieldCryptoTestAdapter adapter =
                new RotatingSensitiveFieldCryptoTestAdapter("key-v4");
        SensitiveFieldCryptoService service = new SensitiveFieldCryptoService(adapter);
        SensitiveFieldCryptoContext current = context("object-a", "studentContactPhone", "key-v4");
        SensitiveFieldCryptoContext old = context("object-a", "studentContactPhone", "key-v3");

        FieldCiphertextEnvelope currentEnvelope;
        try (WipeablePlaintext source = WipeablePlaintext.copyOf(CANARY.getBytes(StandardCharsets.UTF_8))) {
            currentEnvelope = adapter.encrypt(current, source);
        }
        assertEquals(
                CANARY,
                service.withDecryptedClearValue(
                        Visibility.CLEAR,
                        current,
                        currentEnvelope,
                        bytes -> new String(bytes, StandardCharsets.UTF_8)).orElseThrow());

        FieldCiphertextEnvelope oldEnvelope;
        try (WipeablePlaintext source = WipeablePlaintext.copyOf(CANARY.getBytes(StandardCharsets.UTF_8))) {
            oldEnvelope = adapter.encryptWithVersion(old, source);
            assertThrows(SensitiveFieldCryptoException.class, () -> adapter.encrypt(old, source));
        }
        assertEquals(
                CANARY,
                service.withDecryptedClearValue(
                        Visibility.CLEAR,
                        old,
                        oldEnvelope,
                        bytes -> new String(bytes, StandardCharsets.UTF_8)).orElseThrow());
    }

    @Test
    void aad_binds_environment_object_and_field_and_tamper_fails_closed() {
        RotatingSensitiveFieldCryptoTestAdapter adapter =
                new RotatingSensitiveFieldCryptoTestAdapter("key-v4");
        SensitiveFieldCryptoContext original = context("object-a", "studentContactPhone", "key-v4");
        FieldCiphertextEnvelope envelope;
        try (WipeablePlaintext source = WipeablePlaintext.copyOf(CANARY.getBytes(StandardCharsets.UTF_8))) {
            envelope = adapter.encrypt(original, source);
        }

        assertSafeFailure(() -> adapter.decrypt(
                context("object-b", "studentContactPhone", "key-v4"), envelope));
        assertSafeFailure(() -> adapter.decrypt(
                context("object-a", "studentContactEmail", "key-v4"), envelope));

        byte[] tampered = envelope.ciphertext();
        tampered[0] ^= 1;
        FieldCiphertextEnvelope tamperedEnvelope = new FieldCiphertextEnvelope(
                envelope.keyRef(), envelope.keyVersion(), envelope.nonce(), tampered, envelope.algorithm());
        assertSafeFailure(() -> adapter.decrypt(original, tamperedEnvelope));

        SensitiveFieldCryptoContext wrongVersion = context(
                "object-a", "studentContactPhone", "key-v9");
        SensitiveFieldCryptoException versionFailure = assertThrows(
                SensitiveFieldCryptoException.class,
                () -> adapter.decrypt(wrongVersion, envelope));
        assertEquals("FIELD_CRYPTO_KEY_VERSION_UNKNOWN", versionFailure.code());
    }

    @Test
    void hidden_and_masked_never_decrypt_and_cleartext_is_wiped_in_finally() {
        RotatingSensitiveFieldCryptoTestAdapter adapter =
                new RotatingSensitiveFieldCryptoTestAdapter("key-v4");
        SensitiveFieldCryptoService service = new SensitiveFieldCryptoService(adapter);
        SensitiveFieldCryptoContext context = context("object-a", "studentContactPhone", "key-v4");
        FieldCiphertextEnvelope envelope;
        WipeablePlaintext source = WipeablePlaintext.copyOf(CANARY.getBytes(StandardCharsets.UTF_8));
        try (source) {
            envelope = adapter.encrypt(context, source);
        }
        assertTrue(source.destroyed());

        assertTrue(service.withDecryptedClearValue(
                Visibility.HIDDEN, context, envelope, bytes -> "impossible").isEmpty());
        assertTrue(service.withDecryptedClearValue(
                Visibility.MASKED, context, envelope, bytes -> "impossible").isEmpty());
        assertEquals(0, adapter.decryptCalls());

        assertThrows(IllegalStateException.class, () -> service.withDecryptedClearValue(
                Visibility.CLEAR,
                context,
                envelope,
                bytes -> {
                    throw new IllegalStateException("controlled callback failure");
                }));
        assertEquals(1, adapter.decryptCalls());
        assertTrue(adapter.lastPlaintext().destroyed());
        assertArrayEquals(new byte[CANARY.getBytes(StandardCharsets.UTF_8).length],
                adapter.lastPlaintext().snapshotForTesting());
    }

    @Test
    void empty_plaintext_is_rejected_and_hidden_or_masked_values_of_other_lengths_never_decrypt() {
        RotatingSensitiveFieldCryptoTestAdapter adapter =
                new RotatingSensitiveFieldCryptoTestAdapter("key-v4");
        SensitiveFieldCryptoService service = new SensitiveFieldCryptoService(adapter);
        SensitiveFieldCryptoContext context = context("object-a", "studentContactPhone", "key-v4");

        IllegalArgumentException empty = assertThrows(
                IllegalArgumentException.class,
                () -> WipeablePlaintext.copyOf(new byte[0]));
        assertEquals("FIELD_CRYPTO_PLAINTEXT_EMPTY", empty.getMessage());

        for (String original : List.of("A", "学生联系电话", "🙂", "x".repeat(65_536))) {
            FieldCiphertextEnvelope envelope;
            WipeablePlaintext source = WipeablePlaintext.copyOf(
                    original.getBytes(StandardCharsets.UTF_8));
            try (source) {
                envelope = adapter.encrypt(context, source);
            }
            assertTrue(source.destroyed());
            assertTrue(service.withDecryptedClearValue(
                    Visibility.HIDDEN, context, envelope, bytes -> "impossible").isEmpty());
            assertTrue(service.withDecryptedClearValue(
                    Visibility.MASKED, context, envelope, bytes -> "impossible").isEmpty());
        }

        assertEquals(0, adapter.decryptCalls());
    }

    @Test
    void dependency_failure_contains_only_a_stable_code() {
        RotatingSensitiveFieldCryptoTestAdapter adapter =
                new RotatingSensitiveFieldCryptoTestAdapter("key-v4");
        adapter.unavailable();
        SensitiveFieldCryptoException failure = assertThrows(
                SensitiveFieldCryptoException.class,
                () -> adapter.decrypt(
                        context("object-a", "studentContactPhone", "key-v4"),
                        new FieldCiphertextEnvelope(
                                "kms/contact", "key-v4", new byte[12], new byte[16],
                                "AES-GCM-TEST-ONLY")));
        assertEquals("FIELD_CRYPTO_DEPENDENCY_UNAVAILABLE", failure.code());
        assertFalse(failure.getMessage().contains(CANARY));
    }

    @Test
    void field_crypto_is_separate_from_session_custody_and_has_no_plaintext_logging_surface()
            throws IOException {
        assertFalse(EnvelopeEncryptionPort.class.isAssignableFrom(SensitiveFieldCryptoPort.class));
        Path root = Path.of("src/main/java/cn/edu/suda/scholarsense/identityaccess/application");
        for (String name : List.of(
                "WipeablePlaintext.java",
                "FieldCiphertextEnvelope.java",
                "SensitiveFieldCryptoContext.java",
                "SensitiveFieldCryptoException.java",
                "SensitiveFieldCryptoPort.java",
                "SensitiveFieldCryptoService.java")) {
            String source = Files.readString(root.resolve(name));
            assertFalse(source.contains("LoggerFactory"), name);
            assertFalse(source.contains("System.out"), name);
            assertFalse(source.contains("new String("), name);
            assertFalse(source.contains("String plaintext"), name);
        }
    }

    private static void assertSafeFailure(org.junit.jupiter.api.function.Executable action) {
        SensitiveFieldCryptoException failure = assertThrows(
                SensitiveFieldCryptoException.class, action);
        assertEquals("FIELD_CRYPTO_AUTHENTICATION_FAILED", failure.code());
        assertFalse(failure.getMessage().contains(CANARY));
    }

    private static SensitiveFieldCryptoContext context(
            String objectIdentity, String fieldName, String keyVersion) {
        return new SensitiveFieldCryptoContext(
                "actor-pseudonym-0001",
                "scholarsense-backend",
                "CONTACT",
                "transfer.process",
                "production",
                "TransferOrder",
                digest(objectIdentity),
                fieldName,
                "kms/contact",
                keyVersion,
                "0123456789abcdef0123456789abcdef");
    }

    private static String digest(String value) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
