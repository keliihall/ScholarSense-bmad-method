package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.application.AuditTokenDomain;
import cn.edu.suda.scholarsense.runtime.IdentityAuthorityRuntimeProfile;
import cn.edu.suda.scholarsense.runtime.ResponsibilityAuthorityRuntimeProfile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MountedIdentitySyncSecurityBindingsTest {
    @TempDir
    Path temporary;

    @Test
    void mountedResourcesProvideAllFiveValidatedSecurityCapabilities()
            throws Exception {
        byte[] signatureKey = "s".repeat(32).getBytes(StandardCharsets.US_ASCII);
        writeBinding(signatureKey, "k2", "k1");
        var bindings =
                new MountedIdentitySyncSecurityBindings(temporary, profile());
        byte[] payload = ("{\"signatureDigest\":\"sha256:"
                + "0".repeat(64) + "\",\"value\":1}")
                .getBytes(StandardCharsets.UTF_8);
        String signature = hmac(signatureKey, payload);

        assertEquals(
                "Bearer " + "w".repeat(32),
                bindings.authorizationHeader(
                        "account://test/identity-sync-worker"));
        assertTrue(bindings.verify(
                payload,
                signature,
                "secret://test/identity-authority-signature"));
        assertFalse(bindings.verify(
                payload,
                "0".repeat(64),
                "secret://test/identity-authority-signature"));
        var encrypted = bindings.encrypt(
                "sensitive".toCharArray(), "identity-authority-inbox");
        assertEquals(
                "config://test/identity-authority-inbox",
                encrypted.keyRef());
        assertNotEquals(
                "sensitive",
                new String(encrypted.ciphertext(), StandardCharsets.UTF_8));
        assertEquals(
                "sensitive",
                new String(bindings.decrypt(
                        encrypted, "identity-authority-inbox")));
        assertEquals(
                2,
                bindings.pseudonymizeForRead(
                                "identity-actor", "issuer\0subject")
                        .size());
        assertTrue(bindings
                .pseudonymize("identity-actor", "issuer\0subject")
                .startsWith("actor_v1_k2_"));
        assertTrue(bindings
                .tokenize(AuditTokenDomain.ACTOR, "actor")
                .value()
                .startsWith("ast_v1_k1_"));
    }

    @Test
    void rejectsWorldReadableSecretMaterial() throws Exception {
        writeBinding(
                "s".repeat(32).getBytes(StandardCharsets.US_ASCII),
                "k1",
                "none");
        try {
            Files.setPosixFilePermissions(
                    temporary.resolve("signature-hmac.key"),
                    PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException nonPosix) {
            return;
        }

        assertThrows(
                IllegalStateException.class,
                () -> new MountedIdentitySyncSecurityBindings(
                        temporary, profile()));
    }

    @Test
    void responsibilityUsesIndependentWorkloadSignatureAndEnvelopeMaterial()
            throws Exception {
        writeBinding(
                "s".repeat(32).getBytes(StandardCharsets.US_ASCII),
                "k1",
                "none");
        byte[] responsibilitySignature =
                "r".repeat(32).getBytes(StandardCharsets.US_ASCII);
        write(
                "responsibility-workload-token",
                "x".repeat(32).getBytes(StandardCharsets.US_ASCII));
        write(
                "responsibility-signature-hmac.key",
                responsibilitySignature);
        write(
                "responsibility-envelope-kek.key",
                "z".repeat(32).getBytes(StandardCharsets.US_ASCII));
        var bindings = new MountedIdentitySyncSecurityBindings(
                temporary, profile(), responsibilityProfile());
        byte[] payload = ("{\"signatureDigest\":\"sha256:"
                + "0".repeat(64) + "\",\"value\":1}")
                .getBytes(StandardCharsets.UTF_8);

        assertEquals(
                "Bearer " + "x".repeat(32),
                bindings.authorizationHeader(
                        "account://test/responsibility-sync-worker"));
        assertTrue(bindings.verify(
                payload,
                hmac(responsibilitySignature, payload),
                "secret://test/responsibility-authority-signature"));
        var encrypted = bindings.encrypt(
                "responsibility".toCharArray(),
                "responsibility-authority-inbox");
        assertEquals(
                "config://test/responsibility-authority-inbox",
                encrypted.keyRef());
        assertEquals(
                "responsibility",
                new String(bindings.decrypt(
                        encrypted,
                        "responsibility-authority-inbox")));
        assertThrows(
                RuntimeException.class,
                () -> bindings.decrypt(
                        encrypted, "identity-authority-inbox"));
    }

    private void writeBinding(
            byte[] signatureKey, String currentVersion, String previousVersion)
            throws Exception {
        write(
                "manifest.properties",
                """
                schemaVersion=IDENTITY-SYNC-SECURITY-BINDING-1.0.0
                workloadIdentityReference=account://test/identity-sync-worker
                signatureKeyReference=secret://test/identity-authority-signature
                envelopeKeyReference=config://test/identity-authority-inbox
                envelopeKeyVersion=k1
                pseudonymCurrentKeyVersion=%s
                pseudonymPreviousKeyVersion=%s
                auditKeyVersion=k1
                """.formatted(currentVersion, previousVersion)
                        .getBytes(StandardCharsets.ISO_8859_1));
        write("workload-token", "w".repeat(32).getBytes(StandardCharsets.US_ASCII));
        write("signature-hmac.key", signatureKey);
        write("envelope-kek.key", "e".repeat(32).getBytes(StandardCharsets.US_ASCII));
        write("pseudonym-current.key", "p".repeat(32).getBytes(StandardCharsets.US_ASCII));
        if (!"none".equals(previousVersion)) {
            write(
                    "pseudonym-previous.key",
                    "q".repeat(32).getBytes(StandardCharsets.US_ASCII));
        }
        write("audit-hmac.key", "a".repeat(32).getBytes(StandardCharsets.US_ASCII));
    }

    private void write(String name, byte[] content) throws Exception {
        Path file = temporary.resolve(name);
        Files.write(file, content);
        try {
            Files.setPosixFilePermissions(
                    file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignoredNonPosix) {
            // The production adapter retains no-follow checks on such hosts.
        }
    }

    private static IdentityAuthorityRuntimeProfile profile() {
        return new IdentityAuthorityRuntimeProfile(
                "IDENTITY-AUTHORITY-PROFILE-1.0.0",
                "SRC-P0-RESPONSIBILITY-001",
                "identity-authority",
                "sandbox-0",
                "identity-org",
                URI.create("https://test.identity-authority.invalid/incremental"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                "account://test/identity-sync-worker",
                "secret://test/identity-authority-signature",
                "config://test/identity-authority-inbox",
                "config://test/identity-role-mapping-1-0-0",
                "IDENTITY-ROLE-MAPPING-1.0.0",
                "sha256:" + "a".repeat(64),
                Duration.ofSeconds(30),
                5,
                false);
    }

    private static ResponsibilityAuthorityRuntimeProfile
            responsibilityProfile() {
        return new ResponsibilityAuthorityRuntimeProfile(
                "SRC-P0-RESPONSIBILITY-001",
                "responsibility-authority",
                "sandbox-0",
                "responsibility",
                URI.create(
                        "https://test.responsibility-authority.invalid/incremental"),
                Duration.ofSeconds(5),
                "account://test/responsibility-sync-worker",
                "secret://test/responsibility-authority-signature",
                "config://test/responsibility-authority-inbox",
                false);
    }

    private static String hmac(byte[] key, byte[] value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value));
    }
}
