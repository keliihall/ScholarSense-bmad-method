package cn.edu.suda.scholarsense.auditoperations.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditAlertRelayProcessorTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void independentlyConfirmsSuccessAndRetriesTransportFailureWithoutRawErrorData() {
        var repository = new FakeWorkRepository(List.of(
                alert("019bf18e-6c00-7000-8000-000000000021", 1),
                alert("019bf18e-6c00-7000-8000-000000000022", 3)));
        var sent = new ArrayList<String>();
        var processor = new AuditAlertRelayProcessor(
                repository,
                payload -> {
                    sent.add(payload);
                    if (sent.size() == 2) {
                        throw new IllegalStateException("Bearer secret-student-value");
                    }
                },
                () -> NOW,
                () -> 0.5,
                new LowCardinalityAuditMetrics((name, labels) -> {}),
                100,
                Duration.ofSeconds(60));

        AuditAlertBatchResult result = processor.runBatch();

        assertEquals(new AuditAlertBatchResult(2, 1, 1, 0), result);
        assertEquals(List.of("019bf18e-6c00-7000-8000-000000000021"), repository.confirmed);
        assertEquals(List.of("019bf18e-6c00-7000-8000-000000000022"), repository.retried);
        assertEquals(NOW.plusSeconds(2), repository.nextAttemptAt,
                "attempt 3 cap is 4s and 50% jitter gives a 2s retry");
        assertEquals("AUDIT_ALERT_TRANSPORT_UNAVAILABLE", repository.errorCode);
    }

    private static ClaimedAuditAlert alert(String id, int attempts) {
        return new ClaimedAuditAlert(
                UUID.fromString(id), attempts,
                "{\"code\":\"AUDIT_INGESTION_BACKLOG\",\"severity\":\"warning\"}");
    }

    private static final class FakeWorkRepository implements AuditAlertWorkRepository {
        private final List<ClaimedAuditAlert> claims;
        private final List<String> confirmed = new ArrayList<>();
        private final List<String> retried = new ArrayList<>();
        private Instant nextAttemptAt;
        private String errorCode;

        private FakeWorkRepository(List<ClaimedAuditAlert> claims) {
            this.claims = claims;
        }

        @Override
        public List<ClaimedAuditAlert> claimDue(int batchSize, Instant now, Duration lease) {
            assertEquals(100, batchSize);
            assertEquals(Duration.ofSeconds(60), lease);
            return claims;
        }

        @Override
        public boolean confirm(UUID alertId, int expectedAttempts, Instant confirmedAt) {
            confirmed.add(alertId.toString());
            return true;
        }

        @Override
        public boolean retry(UUID alertId, int expectedAttempts, Instant retryAt, String code) {
            retried.add(alertId.toString());
            nextAttemptAt = retryAt;
            errorCode = code;
            return true;
        }
    }
}
