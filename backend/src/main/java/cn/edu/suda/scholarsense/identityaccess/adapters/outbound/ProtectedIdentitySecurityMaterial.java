package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;

/** Shared no-follow reader for identity security material mounted by deployment. */
final class ProtectedIdentitySecurityMaterial {
    private ProtectedIdentitySecurityMaterial() {}

    static byte[] read(
            Path candidate,
            int minimumBytes,
            int maximumBytes,
            String invalidCode,
            String unavailableCode) {
        return read(
                candidate,
                minimumBytes,
                maximumBytes,
                invalidCode,
                unavailableCode,
                () -> {});
    }

    static byte[] read(
            Path candidate,
            int minimumBytes,
            int maximumBytes,
            String invalidCode,
            String unavailableCode,
            Runnable afterInitialValidation) {
        if (candidate == null || !candidate.isAbsolute()) {
            throw invalid(invalidCode);
        }
        if (minimumBytes < 0 || maximumBytes < minimumBytes || maximumBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("PROTECTED_SECURITY_MATERIAL_LIMIT_INVALID");
        }
        Path path = candidate.normalize();
        Snapshot before = inspect(path, invalidCode, unavailableCode);
        afterInitialValidation.run();
        try {
            byte[] value;
            Snapshot after;
            try (SeekableByteChannel channel = Files.newByteChannel(
                    path,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                value = readBounded(channel, maximumBytes);
                try {
                    after = inspect(path, invalidCode, unavailableCode);
                } catch (RuntimeException rejected) {
                    Arrays.fill(value, (byte) 0);
                    throw rejected;
                }
            }
            if (!before.sameFile(after)
                    || value.length < minimumBytes
                    || value.length > maximumBytes) {
                Arrays.fill(value, (byte) 0);
                throw invalid(invalidCode);
            }
            return value;
        } catch (NoSuchFileException missing) {
            throw invalid(invalidCode);
        } catch (IOException unavailable) {
            if (Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid(invalidCode);
            }
            throw new IllegalStateException(unavailableCode, unavailable);
        }
    }

    private static Snapshot inspect(
            Path path, String invalidCode, String unavailableCode) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.fileKey() == null) {
                throw invalid(invalidCode);
            }
            Set<PosixFilePermission> permissions = protectedPermissions(path);
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                throw invalid(invalidCode);
            }
            return new Snapshot(
                    attributes.fileKey(),
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    permissions);
        } catch (NoSuchFileException missing) {
            throw invalid(invalidCode);
        } catch (IOException unavailable) {
            throw new IllegalStateException(unavailableCode, unavailable);
        }
    }

    private static Set<PosixFilePermission> protectedPermissions(Path path)
            throws IOException {
        try {
            return Set.copyOf(
                    Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
        } catch (UnsupportedOperationException ignoredNonPosixFileSystem) {
            return Set.of();
        }
    }

    private static byte[] readBounded(SeekableByteChannel channel, int maximumBytes)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(maximumBytes + 1);
        while (buffer.hasRemaining() && channel.read(buffer) != -1) {
            // Read from the one no-follow channel so validation and use cannot diverge.
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    private record Snapshot(
            Object fileKey,
            long size,
            FileTime lastModified,
            Set<PosixFilePermission> permissions) {
        private boolean sameFile(Snapshot other) {
            return fileKey.equals(other.fileKey)
                    && size == other.size
                    && lastModified.equals(other.lastModified)
                    && permissions.equals(other.permissions);
        }
    }
}
