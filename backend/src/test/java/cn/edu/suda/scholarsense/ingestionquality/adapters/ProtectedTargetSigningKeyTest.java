package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProtectedTargetSigningKeyTest {
    @TempDir
    Path temporary;

    @Test
    void loadsExactBytesOnlyFromAnAbsoluteProtectedRegularFile() throws Exception {
        byte[] expected = "target-authority-key-material-32+".getBytes(StandardCharsets.US_ASCII);
        Path key = protectedKey("target-signing.key", expected);

        byte[] loaded = ProtectedTargetSigningKey.load(key);

        assertArrayEquals(expected, loaded);
    }

    @Test
    void rejectsMissingRelativeAndShortKeyMaterial() throws Exception {
        assertThrows(
                IllegalStateException.class,
                () -> ProtectedTargetSigningKey.load(
                        temporary.resolve("missing-target-signing.key")));
        assertThrows(
                IllegalStateException.class,
                () -> ProtectedTargetSigningKey.load(Path.of("relative-target-signing.key")));

        Path shortKey = protectedKey(
                "short-target-signing.key", "too-short".getBytes(StandardCharsets.US_ASCII));
        assertThrows(
                IllegalStateException.class,
                () -> ProtectedTargetSigningKey.load(shortKey));
    }

    @Test
    void rejectsSymbolicLinkEvenWhenItTargetsAProtectedRegularFile() throws Exception {
        Path target = protectedKey(
                "actual-target-signing.key",
                "target-authority-key-material-32+".getBytes(StandardCharsets.US_ASCII));
        Path link = temporary.resolve("linked-target-signing.key");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException unsupported) {
            return;
        }

        assertThrows(
                IllegalStateException.class,
                () -> ProtectedTargetSigningKey.load(link));
    }

    @Test
    void rejectsGroupOrWorldAccessibleKeyMaterial() throws Exception {
        Path key = Files.write(
                temporary.resolve("insecure-target-signing.key"),
                "target-authority-key-material-32+".getBytes(StandardCharsets.US_ASCII));
        try {
            Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException nonPosix) {
            return;
        }

        assertThrows(
                IllegalStateException.class,
                () -> ProtectedTargetSigningKey.load(key));
    }

    @Test
    void rejectsARegularFileReplacementBetweenValidationAndOpen() throws Exception {
        byte[] expected = "target-authority-key-material-32+".getBytes(StandardCharsets.US_ASCII);
        Path key = protectedKey("replaceable-target-signing.key", expected);
        Path replacement = protectedKey("replacement-target-signing.key", expected);

        assertThrows(
                IllegalStateException.class,
                () -> ProtectedTargetSigningKey.load(key, () -> replace(replacement, key)));
    }

    private static void replace(Path replacement, Path target) {
        try {
            Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private Path protectedKey(String name, byte[] content) throws Exception {
        Path file = Files.write(temporary.resolve(name), content);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignoredNonPosix) {
            // The production loader retains regular-file and no-follow checks on such hosts.
        }
        return file;
    }
}
