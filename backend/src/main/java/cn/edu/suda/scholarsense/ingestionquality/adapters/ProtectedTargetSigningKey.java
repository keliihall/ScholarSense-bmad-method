package cn.edu.suda.scholarsense.ingestionquality.adapters;

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

/** Reads deployment-mounted target authority HMAC material without exposing its path or value. */
final class ProtectedTargetSigningKey {
    private static final int MINIMUM_KEY_BYTES = 32;
    private static final int MAXIMUM_KEY_BYTES = 4 * 1024;

    private ProtectedTargetSigningKey() {}

    static byte[] load(Path candidate) {
        return load(candidate, () -> {});
    }

    static byte[] load(Path candidate, Runnable afterInitialValidation) {
        if (candidate == null || !candidate.isAbsolute()) {
            throw invalid();
        }
        Path path = candidate.normalize();
        Snapshot before = inspect(path);
        afterInitialValidation.run();
        try {
            byte[] key;
            Snapshot after;
            try (SeekableByteChannel channel = Files.newByteChannel(
                    path,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                key = readBounded(channel);
                try {
                    after = inspect(path);
                } catch (RuntimeException rejected) {
                    Arrays.fill(key, (byte) 0);
                    throw rejected;
                }
            }
            if (!before.sameFile(after)
                    || key.length < MINIMUM_KEY_BYTES
                    || key.length > MAXIMUM_KEY_BYTES) {
                Arrays.fill(key, (byte) 0);
                throw invalid();
            }
            return key;
        } catch (NoSuchFileException missing) {
            throw invalid();
        } catch (IOException unavailable) {
            if (Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid();
            }
            throw new IllegalStateException(
                    "INGESTION_QUALITY_TARGET_SIGNING_KEY_UNAVAILABLE", unavailable);
        }
    }

    private static Snapshot inspect(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.fileKey() == null) {
                throw invalid();
            }
            Set<PosixFilePermission> permissions = protectedPermissions(path);
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                throw invalid();
            }
            return new Snapshot(
                    attributes.fileKey(),
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    permissions);
        } catch (NoSuchFileException missing) {
            throw invalid();
        } catch (IOException unavailable) {
            throw new IllegalStateException(
                    "INGESTION_QUALITY_TARGET_SIGNING_KEY_UNAVAILABLE", unavailable);
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

    private static byte[] readBounded(SeekableByteChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(MAXIMUM_KEY_BYTES + 1);
        while (buffer.hasRemaining() && channel.read(buffer) != -1) {
            // Read from the one no-follow channel so validation and use cannot diverge.
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("INGESTION_QUALITY_TARGET_SIGNING_KEY_INVALID");
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
