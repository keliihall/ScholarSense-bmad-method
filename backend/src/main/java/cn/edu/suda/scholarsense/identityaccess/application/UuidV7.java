package cn.edu.suda.scholarsense.identityaccess.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static String generate(Instant instant) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
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
        return new UUID(most, least).toString();
    }
}
