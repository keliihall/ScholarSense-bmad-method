package cn.edu.suda.scholarsense.subjectregistry.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.subjectregistry.application.IdentifierProtectionContext;
import cn.edu.suda.scholarsense.subjectregistry.application.IdentifierProtectionUnavailableException;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AesGcmHmacIdentifierProtectionAdapterTest {

    private static final IdentifierProtectionContext CONTEXT = new IdentifierProtectionContext(
            "SRC-P0-CARD-001", "card-number", "SOURCE_NATIVE_IDENTIFIER");

    @Test
    void deterministicTokenAndRandomizedCiphertextAreBoundToEnvironmentAndKeyVersion() {
        RotatingKeys keys = new RotatingKeys();
        AesGcmHmacIdentifierProtectionAdapter adapter =
                new AesGcmHmacIdentifierProtectionAdapter(keys);

        var first = adapter.protect("00AB12", CONTEXT);
        var second = adapter.protect("00AB12", CONTEXT);

        assertEquals(first.token(), second.token());
        assertNotEquals(first.ciphertext(), second.ciphertext());
        assertEquals("00AB12", adapter.reveal(first));

        keys.active = keys.v2;
        var rotated = adapter.protect("00AB12", CONTEXT);
        assertNotEquals(first.token(), rotated.token());
        assertEquals("v2", rotated.token().keyVersion());
        assertEquals("00AB12", adapter.reveal(first), "old ciphertext stays decryptable by key reference");
    }

    @Test
    void missingOrInvalidKeysFailClosedWithoutReturningPlaintext() {
        AesGcmHmacIdentifierProtectionAdapter unavailable =
                new AesGcmHmacIdentifierProtectionAdapter(new SubjectRegistryProtectionKeyPort() {
                    @Override
                    public SubjectRegistryProtectionKeys active() {
                        throw new IllegalStateException("kms unavailable");
                    }

                    @Override
                    public SubjectRegistryProtectionKeys byReference(
                            String environment, String keyRef, String keyVersion) {
                        throw new IllegalStateException("kms unavailable");
                    }
                });

        assertThrows(IdentifierProtectionUnavailableException.class,
                () -> unavailable.protect("00AB12", CONTEXT));
    }

    private static final class RotatingKeys implements SubjectRegistryProtectionKeyPort {
        private final SubjectRegistryProtectionKeys v1 = keys("v1", (byte) 1, (byte) 2);
        private final SubjectRegistryProtectionKeys v2 = keys("v2", (byte) 3, (byte) 4);
        private final Map<String, SubjectRegistryProtectionKeys> all = Map.of("v1", v1, "v2", v2);
        private SubjectRegistryProtectionKeys active = v1;

        @Override
        public SubjectRegistryProtectionKeys active() {
            return active;
        }

        @Override
        public SubjectRegistryProtectionKeys byReference(
                String environment, String keyRef, String keyVersion) {
            return all.get(keyVersion);
        }

        private static SubjectRegistryProtectionKeys keys(
                String version, byte encryptionByte, byte tokenByte) {
            return new SubjectRegistryProtectionKeys(
                    "prod", "kms://subject-registry/identifier-bundle", version,
                    new SecretKeySpec(repeat(encryptionByte), "AES"),
                    new SecretKeySpec(repeat(tokenByte), "HmacSHA256"));
        }

        private static byte[] repeat(byte value) {
            byte[] result = new byte[32];
            java.util.Arrays.fill(result, value);
            return result;
        }
    }
}
