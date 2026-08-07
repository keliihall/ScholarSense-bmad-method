package cn.edu.suda.scholarsense.subjectregistry.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.edu.suda.scholarsense.subjectregistry.adapters.SubjectMappingRelayProcessor;
import cn.edu.suda.scholarsense.subjectregistry.api.SubjectMappingChangedConsumerPort;
import cn.edu.suda.scholarsense.subjectregistry.api.SubjectMappingConsumptionOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubjectMappingRelayProcessorTest {
    private static final Instant NOW = Instant.parse("2026-08-06T08:00:00Z");

    @Test
    void confirmsCommittedIqConsumptionAndFencesByAttempt() {
        FakeWork work = new FakeWork(claim(2));
        SubjectMappingChangedConsumerPort consumer = ignored -> SubjectMappingConsumptionOutcome.APPLIED;

        var result = new SubjectMappingRelayProcessor(
                work, consumer, Clock.fixed(NOW, ZoneOffset.UTC)).runBatch();

        assertEquals(new SubjectMappingRelayResult(1, 1, 0, 0, 0), result);
        assertEquals(List.of("confirm:2"), work.mutations);
        assertEquals(100, work.batchSize);
        assertEquals(Duration.ofSeconds(60), work.lease);
    }

    @Test
    void retriesUnavailableConsumerAndFailsPoisonWithoutAcknowledging() {
        FakeWork unavailable = new FakeWork(claim(3));
        var retried = new SubjectMappingRelayProcessor(
                unavailable, ignored -> { throw new IllegalStateException("down"); },
                Clock.fixed(NOW, ZoneOffset.UTC)).runBatch();
        assertEquals(new SubjectMappingRelayResult(1, 0, 1, 0, 0), retried);
        assertEquals(List.of("retry:3:SUBJECT_MAPPING_CONSUMER_UNAVAILABLE"),
                unavailable.mutations);

        FakeWork poison = new FakeWork(claim(4));
        var failed = new SubjectMappingRelayProcessor(
                poison, ignored -> SubjectMappingConsumptionOutcome.POISON_QUARANTINED,
                Clock.fixed(NOW, ZoneOffset.UTC)).runBatch();
        assertEquals(new SubjectMappingRelayResult(1, 0, 0, 1, 0), failed);
        assertEquals(List.of("fail:4:SUBJECT_MAPPING_EVENT_POISON"), poison.mutations);
    }

    private static SubjectMappingRelayClaim claim(long attempts) {
        return new SubjectMappingRelayClaim(event(), attempts);
    }

    private static SubjectMappingRelayEvent event() {
        return new SubjectMappingRelayEvent(
                UUID.fromString("019fcfea-6700-7000-8000-000000000001"),
                UUID.fromString("019fcfea-6700-7000-8000-000000000002"), 1,
                NOW, UUID.fromString("019fcfea-6700-7000-8000-000000000002"),
                "SRC-P0-CARD-001",
                Set.of("019fcfea-6700-7000-8000-000000000003"),
                "wm-42", "00112233445566778899aabbccddeeff");
    }

    private static final class FakeWork implements SubjectMappingRelayWorkPort {
        private final List<SubjectMappingRelayClaim> claims;
        private final List<String> mutations = new ArrayList<>();
        private int batchSize;
        private Duration lease;

        private FakeWork(SubjectMappingRelayClaim claim) { claims = List.of(claim); }

        @Override public List<SubjectMappingRelayClaim> claimDue(
                int requestedBatchSize, Instant now, Duration requestedLease) {
            batchSize = requestedBatchSize;
            lease = requestedLease;
            return claims;
        }
        @Override public boolean confirm(UUID id, long attempts, Instant at) {
            mutations.add("confirm:" + attempts); return true;
        }
        @Override public boolean retry(UUID id, long attempts, Instant at, String code) {
            mutations.add("retry:" + attempts + ":" + code); return true;
        }
        @Override public boolean fail(UUID id, long attempts, Instant at, String code) {
            mutations.add("fail:" + attempts + ":" + code); return true;
        }
    }
}
