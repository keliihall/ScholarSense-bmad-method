package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentitySyncJobServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-24T01:00:00Z");
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "identity-authority",
            "sandbox-0",
            "identity-org");

    @Test
    void automaticPollingDoesNotReplaceTerminalFailureWithANewRetryBudget() {
        var store = new FakeJobStore(false, 41);
        var service = new IdentitySyncJobService(store, IdentitySyncJobServiceTest::trustedNow);

        assertTrue(service.ensureRequested(
                KEY, 5, "0123456789abcdef0123456789abcdef").isEmpty());
        assertEquals(1, store.atomicEnqueueCalls);
    }

    @Test
    void newPollingJobCarriesTheDurableCheckpointWatermark() {
        var store = new FakeJobStore(true, 41);
        var service = new IdentitySyncJobService(store, IdentitySyncJobServiceTest::trustedNow);

        Optional<UUID> requested = service.ensureRequested(
                KEY, 5, "0123456789abcdef0123456789abcdef");

        assertTrue(requested.isPresent());
        assertEquals(41, store.enqueued.lastSuccessfulWatermark());
        assertEquals(NOW, store.enqueued.nextAttemptAt());
    }

    @Test
    void terminalFailureRequiresAnAttributedResolutionBeforeReplacement() {
        var store = new FakeJobStore(true, 41);
        var service = new IdentitySyncJobService(store, IdentitySyncJobServiceTest::trustedNow);

        Optional<UUID> requested = service.resolveFailureAndRequest(
                KEY,
                3,
                "operator:hei",
                "UPSTREAM_DATA_REPAIRED",
                "0123456789abcdef0123456789abcdef");

        assertTrue(requested.isPresent());
        assertEquals("operator:hei", store.resolution.resolvedBy());
        assertEquals("UPSTREAM_DATA_REPAIRED", store.resolution.resolutionCode());
        assertEquals(store.enqueued.jobId(), requested.orElseThrow());
    }

    private static TrustedTime trustedNow() {
        return new TrustedTime(
                NOW,
                new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(50),
                        "evidence://signed/clock/campus-ntp-a.json"));
    }

    private static final class FakeJobStore implements IdentitySyncJobPort {
        private final boolean eligible;
        private final long watermark;
        private int atomicEnqueueCalls;
        private IdentitySyncJob enqueued;
        private IdentitySyncFailureResolution resolution;

        private FakeJobStore(boolean eligible, long watermark) {
            this.eligible = eligible;
            this.watermark = watermark;
        }

        @Override
        public void enqueue(IdentitySyncJob job) {
            enqueued = job;
        }

        @Override
        public boolean enqueueIfEligible(IdentitySyncJob job) {
            atomicEnqueueCalls++;
            if (eligible) {
                enqueued = job;
            }
            return eligible;
        }

        @Override
        public boolean resolveFailureAndEnqueue(
                IdentitySyncFailureResolution resolution,
                IdentitySyncJob replacement) {
            this.resolution = resolution;
            if (eligible) {
                enqueued = replacement;
            }
            return eligible;
        }

        @Override
        public long lastSuccessfulWatermark(CheckpointKey key) {
            return watermark;
        }

        @Override
        public boolean hasPending(CheckpointKey key) {
            throw new AssertionError("check-then-insert is not atomic");
        }

        @Override
        public Optional<IdentitySyncJob> nextDue(CheckpointKey routeKey, Instant now) {
            return Optional.empty();
        }

        @Override
        public Optional<RunningIdentitySyncAttempt> start(
                UUID jobId, String leaseOwner, Instant now) {
            return Optional.empty();
        }

        @Override
        public void save(
                RunningIdentitySyncAttempt attempt,
                IdentitySyncJob completed,
                Instant now) {}
    }
}
