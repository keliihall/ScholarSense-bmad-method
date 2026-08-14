package cn.edu.suda.scholarsense.ingestionquality.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Matches the V15/V16 deterministic owner UUIDv7 derivation for replay-safe planning. */
final class QualityFuseIdentifiers {
    private QualityFuseIdentifiers() {}

    static UUID derive(UUID namespaceId, String discriminator) {
        byte[] source = bytes(namespaceId);
        byte[] digest = digest(namespaceId + ":" + discriminator);
        byte[] result = new byte[16];
        System.arraycopy(source, 0, result, 0, 6);
        System.arraycopy(digest, 6, result, 6, 10);
        result[6] = (byte) ((result[6] & 0x0f) | 0x70);
        result[8] = (byte) ((result[8] & 0x0f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(result);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
