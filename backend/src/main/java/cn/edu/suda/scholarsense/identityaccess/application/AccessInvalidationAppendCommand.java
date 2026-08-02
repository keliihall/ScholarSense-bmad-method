package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccessInvalidationAppendCommand(
        AccessInvalidationFact fact,
        UUID outboxId,
        long expectedHeadVersion,
        long expectedFencingToken,
        String eventPayload,
        Instant createdAt) {
    public AccessInvalidationAppendCommand {
        Objects.requireNonNull(fact, "fact");
        if (outboxId == null
                || outboxId.version() != 7
                || outboxId.variant() != 2) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_OUTBOX_UUIDV7_REQUIRED");
        }
        if (expectedHeadVersion < 0 || expectedFencingToken < 0) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_EXPECTED_HEAD_INVALID");
        }
        if (eventPayload == null
                || eventPayload.getBytes(StandardCharsets.UTF_8).length
                        > 64 * 1024) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_PAYLOAD_TOO_LARGE");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
