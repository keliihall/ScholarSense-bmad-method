package cn.edu.suda.scholarsense.ingestionquality.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

/** Deterministic UUIDv7 identities for owner audit commands and business events. */
final class DataBatchOwnerIds {
    private DataBatchOwnerIds() {}

    static UUID commandId(
            UUID batchId, DataBatchCommandType type, long aggregateVersion, Instant occurredAt) {
        return id(batchId, type, aggregateVersion, occurredAt, "command");
    }

    static UUID businessEventId(
            UUID batchId, DataBatchCommandType type, long aggregateVersion, Instant occurredAt) {
        return id(batchId, type, aggregateVersion, occurredAt, "business-event");
    }

    private static UUID id(
            UUID batchId,
            DataBatchCommandType type,
            long aggregateVersion,
            Instant occurredAt,
            String kind) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(batchId.toString().getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) 0);
            sha256.update(type.name().getBytes(StandardCharsets.UTF_8));
            sha256.update(ByteBuffer.allocate(Long.BYTES).putLong(aggregateVersion).array());
            sha256.update(kind.getBytes(StandardCharsets.UTF_8));
            byte[] bytes = sha256.digest();
            long millis = occurredAt.toEpochMilli();
            for (int index = 5; index >= 0; index--) {
                bytes[index] = (byte) (millis & 0xff);
                millis >>>= 8;
            }
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x70);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            ByteBuffer value = ByteBuffer.wrap(bytes);
            return new UUID(value.getLong(), value.getLong());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
