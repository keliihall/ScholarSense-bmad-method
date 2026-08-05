package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProtectedIdentitySecurityMaterialTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsARegularSecretReplacementBetweenValidationAndOpen() throws Exception {
        byte[] content = "controlled-identity-security-key-32".getBytes(StandardCharsets.US_ASCII);
        Path secret = protectedFile("replaceable-identity.key", content);
        Path replacement = protectedFile("replacement-identity.key", content);

        assertThrows(
                IllegalStateException.class,
                () -> ProtectedIdentitySecurityMaterial.read(
                        secret,
                        32,
                        64,
                        "IDENTITY_TEST_SECURITY_MATERIAL_INVALID",
                        "IDENTITY_TEST_SECURITY_MATERIAL_UNAVAILABLE",
                        () -> replace(replacement, secret)));
    }

    private Path protectedFile(String name, byte[] content) throws Exception {
        Path file = Files.write(temporary.resolve(name), content);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignoredNonPosix) {
            // Stable file identity and no-follow checks remain mandatory on such hosts.
        }
        return file;
    }

    private static void replace(Path replacement, Path target) {
        try {
            Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}
