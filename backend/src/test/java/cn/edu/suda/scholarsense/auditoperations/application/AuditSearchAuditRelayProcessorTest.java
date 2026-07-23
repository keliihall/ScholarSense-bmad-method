package cn.edu.suda.scholarsense.auditoperations.application;

import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.NOW;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.outbox;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditSearchAuditRelayProcessorTest {
    @Test
    void exactReplayConfirmsOnceAndCenterFailureLeavesFencedRetryWithoutRecursiveAudit() {
        MemoryWork exact = new MemoryWork(new AuditSearchRelayClaim(outbox(), 1));
        var exactProcessor = new AuditSearchAuditRelayProcessor(
                exact, source -> new AuditCenterIngressResult(
                        AuditCenterIngressOutcome.EXACT_DUPLICATE, null, false),
                () -> NOW, () -> 1.0, 100, Duration.ofSeconds(60));

        assertEquals(new AuditSearchRelayBatchResult(1, 1, 0, 0, 0), exactProcessor.runBatch());
        assertEquals(1, exact.confirmed);

        MemoryWork unavailable = new MemoryWork(new AuditSearchRelayClaim(outbox(), 1));
        var failedProcessor = new AuditSearchAuditRelayProcessor(
                unavailable, source -> { throw new IllegalStateException("database unavailable"); },
                () -> NOW, () -> 1.0, 100, Duration.ofSeconds(60));

        assertEquals(new AuditSearchRelayBatchResult(1, 0, 1, 0, 0), failedProcessor.runBatch());
        assertEquals(1, unavailable.retried);
    }

    private static final class MemoryWork implements AuditSearchRelayWorkPort {
        private final AuditSearchRelayClaim claim;
        private int confirmed;
        private int retried;

        private MemoryWork(AuditSearchRelayClaim claim) { this.claim = claim; }

        @Override public List<AuditSearchRelayClaim> claimDue(int batchSize, java.time.Instant now, Duration lease) {
            return List.of(claim);
        }
        @Override public boolean confirm(UUID eventId, long attempts, java.time.Instant at) { confirmed++; return true; }
        @Override public boolean retry(UUID eventId, long attempts, java.time.Instant at, String code) { retried++; return true; }
        @Override public boolean fail(UUID eventId, long attempts, java.time.Instant at, String code) { return true; }
    }
}
