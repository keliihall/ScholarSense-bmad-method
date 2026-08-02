package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccessInvalidationOutboxClaim(
        UUID outboxId,
        UUID eventId,
        String eventType,
        String eventPayload,
        String payloadDigest,
        String deliveryKey,
        long attemptNo,
        long fencingToken,
        String leaseOwner,
        String traceId,
        Instant claimedAt) {
    public AccessInvalidationOutboxClaim {
        Objects.requireNonNull(outboxId, "outboxId");
        Objects.requireNonNull(eventId, "eventId");
        if (!"scholarsense.identity-access.responsibility.changed.v1"
                .equals(eventType)
                || eventPayload == null
                || payloadDigest == null
                || !payloadDigest.matches("[0-9a-f]{64}")
                || deliveryKey == null
                || attemptNo < 1
                || fencingToken < 1
                || leaseOwner == null
                || leaseOwner.isBlank()
                || traceId == null
                || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_OUTBOX_CLAIM_INVALID");
        }
        Objects.requireNonNull(claimedAt, "claimedAt");
    }
}
