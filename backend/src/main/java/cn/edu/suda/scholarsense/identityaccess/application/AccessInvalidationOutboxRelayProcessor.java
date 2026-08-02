package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Duration;
import java.time.Instant;

/** Bounded relay; a transport acknowledgement never advances a consumer watermark. */
public final class AccessInvalidationOutboxRelayProcessor
        implements AccessInvalidationOutboxRelayPort {
    private static final int MAX_BATCH_SIZE = 100;
    private static final long MAX_ATTEMPTS = 8;
    private final AccessInvalidationRelayRepositoryPort repository;
    private final AccessInvalidationTransportPort transport;
    private final AccessInvalidationIdPort identifiers;
    private final String leaseOwner;

    public AccessInvalidationOutboxRelayProcessor(
            AccessInvalidationRelayRepositoryPort repository,
            AccessInvalidationTransportPort transport,
            AccessInvalidationIdPort identifiers,
            String leaseOwner) {
        this.repository = repository;
        this.transport = transport;
        this.identifiers = identifiers;
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_RELAY_OWNER_REQUIRED");
        }
        this.leaseOwner = leaseOwner;
    }

    @Override
    public int relay(int batchSize, Instant now) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_RELAY_BATCH_INVALID");
        }
        var claims = repository.claim(
                leaseOwner,
                batchSize,
                now,
                now.plus(Duration.ofMinutes(2)));
        for (AccessInvalidationOutboxClaim claim : claims) {
            try {
                transport.publish(
                        claim.eventId(),
                        claim.eventType(),
                        claim.eventPayload(),
                        claim.payloadDigest(),
                        claim.deliveryKey(),
                        claim.traceId());
                repository.published(
                        claim,
                        identifiers.next(now),
                        now);
            } catch (RuntimeException failure) {
                boolean quarantine =
                        claim.attemptNo() >= MAX_ATTEMPTS;
                repository.failed(
                        claim,
                        identifiers.next(now),
                        "ACCESS_INVALIDATION_TRANSPORT_UNAVAILABLE",
                        now,
                        now.plus(backoff(claim.attemptNo())),
                        quarantine);
            }
        }
        return claims.size();
    }

    private static Duration backoff(long attempt) {
        long seconds = Math.min(900, 1L << Math.min(10, attempt));
        return Duration.ofSeconds(seconds);
    }
}
