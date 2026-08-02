package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AccessInvalidationOutboxRelayProcessorTest {
    private static final Instant NOW =
            Instant.parse("2026-07-31T12:00:00Z");
    private static final String TRACE =
            "0123456789abcdef0123456789abcdef";

    @Test
    void publishesBoundedClaimWithoutPretendingConsumerApplied() {
        var repository = new FakeRepository(List.of(claim(1)));
        List<UUID> transported = new ArrayList<>();
        var processor = processor(
                repository,
                (eventId, type, payload, digest, key, traceId) ->
                        transported.add(eventId));

        assertEquals(1, processor.relay(10, NOW));
        assertEquals(List.of(claim(1).eventId()), transported);
        assertEquals(1, repository.published.size());
        assertEquals(0, repository.failed.size());
        assertThrows(
                IllegalArgumentException.class,
                () -> processor.relay(101, NOW));
    }

    @Test
    void retriesTransientFailureAndQuarantinesEighthAttempt() {
        var retry = new FakeRepository(List.of(claim(3)));
        processor(
                        retry,
                        (eventId, type, payload, digest, key, traceId) -> {
                            throw new IllegalStateException("offline");
                        })
                .relay(1, NOW);
        assertEquals(List.of(false), retry.failed);

        var poison = new FakeRepository(List.of(claim(8)));
        processor(
                        poison,
                        (eventId, type, payload, digest, key, traceId) -> {
                            throw new IllegalArgumentException("poison");
                        })
                .relay(1, NOW);
        assertEquals(List.of(true), poison.failed);
    }

    private static AccessInvalidationOutboxRelayProcessor processor(
            FakeRepository repository,
            AccessInvalidationTransportPort transport) {
        AtomicInteger ids = new AtomicInteger(600);
        return new AccessInvalidationOutboxRelayProcessor(
                repository,
                transport,
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000"
                                + ids.incrementAndGet()),
                "relay-test");
    }

    private static AccessInvalidationOutboxClaim claim(long attempt) {
        return new AccessInvalidationOutboxClaim(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000501"),
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000502"),
                "scholarsense.identity-access.responsibility.changed.v1",
                "{\"specversion\":\"1.0\"}",
                "a".repeat(64),
                "identity-access:lin_test:1",
                attempt,
                attempt,
                "relay-test",
                TRACE,
                NOW);
    }

    private static final class FakeRepository
            implements AccessInvalidationRelayRepositoryPort {
        private final List<AccessInvalidationOutboxClaim> claims;
        private final List<UUID> published = new ArrayList<>();
        private final List<Boolean> failed = new ArrayList<>();

        private FakeRepository(
                List<AccessInvalidationOutboxClaim> claims) {
            this.claims = claims;
        }

        @Override
        public List<AccessInvalidationOutboxClaim> claim(
                String leaseOwner,
                int batchSize,
                Instant now,
                Instant leaseExpiresAt) {
            return claims;
        }

        @Override
        public void published(
                AccessInvalidationOutboxClaim claim,
                UUID attemptId,
                Instant publishedAt) {
            published.add(claim.eventId());
        }

        @Override
        public void failed(
                AccessInvalidationOutboxClaim claim,
                UUID attemptId,
                String reasonCode,
                Instant failedAt,
                Instant nextAttemptAt,
                boolean quarantine) {
            failed.add(quarantine);
        }
    }
}
