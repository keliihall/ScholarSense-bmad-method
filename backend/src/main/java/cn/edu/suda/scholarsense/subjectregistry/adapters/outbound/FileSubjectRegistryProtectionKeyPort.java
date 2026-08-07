package cn.edu.suda.scholarsense.subjectregistry.adapters.outbound;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.crypto.spec.SecretKeySpec;

/** Loads two root-readable raw key files; key material is never exposed through properties. */
public final class FileSubjectRegistryProtectionKeyPort
        implements SubjectRegistryProtectionKeyPort {
    private final SubjectRegistryProtectionKeys active;

    public FileSubjectRegistryProtectionKeyPort(
            String environment, String keyRef, String keyVersion,
            Path encryptionKeyPath, Path searchTokenKeyPath) {
        byte[] encryption = read32(encryptionKeyPath);
        byte[] search = read32(searchTokenKeyPath);
        try {
            active = new SubjectRegistryProtectionKeys(
                    environment, keyRef, keyVersion,
                    new SecretKeySpec(encryption, "AES"),
                    new SecretKeySpec(search, "HmacSHA256"));
        } finally {
            java.util.Arrays.fill(encryption, (byte) 0);
            java.util.Arrays.fill(search, (byte) 0);
        }
    }

    @Override
    public SubjectRegistryProtectionKeys active() {
        return active;
    }

    @Override
    public SubjectRegistryProtectionKeys byReference(
            String environment, String keyRef, String keyVersion) {
        if (!active.environment().equals(environment)
                || !active.keyRef().equals(keyRef)
                || !active.keyVersion().equals(keyVersion)) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_PROTECTION_KEY_UNKNOWN");
        }
        return active;
    }

    private static byte[] read32(Path path) {
        if (path == null || !path.isAbsolute()) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_PROTECTION_KEY_PATH_INVALID");
        }
        try {
            byte[] value = Files.readAllBytes(path.normalize());
            if (value.length != 32) {
                java.util.Arrays.fill(value, (byte) 0);
                throw new IllegalArgumentException("SUBJECT_REGISTRY_PROTECTION_KEY_INVALID");
            }
            return value;
        } catch (IOException unavailable) {
            throw new IllegalStateException("SUBJECT_REGISTRY_PROTECTION_KEY_UNAVAILABLE", unavailable);
        }
    }
}
