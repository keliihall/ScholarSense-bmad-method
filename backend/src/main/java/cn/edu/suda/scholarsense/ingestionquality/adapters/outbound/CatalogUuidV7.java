package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

final class CatalogUuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private CatalogUuidV7() {}

    static UUID generate(Instant instant) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return fromBytes(instant, bytes);
    }

    static UUID deterministic(Instant instant, String identity) {
        try {
            return fromBytes(instant, MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static UUID fromBytes(Instant instant, byte[] entropy) {
        byte[] bytes = java.util.Arrays.copyOf(entropy, 16);
        long millis = instant.toEpochMilli();
        for (int index = 5; index >= 0; index--) {
            bytes[index] = (byte) (millis & 0xff);
            millis >>>= 8;
        }
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x70);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        long most = 0;
        long least = 0;
        for (int index = 0; index < 8; index++) {
            most = (most << 8) | (bytes[index] & 0xffL);
            least = (least << 8) | (bytes[index + 8] & 0xffL);
        }
        return new UUID(most, least);
    }
}
