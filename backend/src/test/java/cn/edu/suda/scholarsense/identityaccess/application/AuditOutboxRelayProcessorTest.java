package cn.edu.suda.scholarsense.identityaccess.application;

import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.NOW;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.outbox;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.auditoperations.api.AuditIngressResult;
import cn.edu.suda.scholarsense.auditoperations.api.AuditContractRejection;
import cn.edu.suda.scholarsense.auditoperations.api.AuditLedgerIngressPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditOutboxRelayProcessorTest {

    @Test
    void successfulCenterAppendConfirmsOnlyWithTheClaimAttemptsFencingToken() {
        MemoryWork work = new MemoryWork(1);
        AuditOutboxRelayProcessor relay = relay(work, source -> AuditIngressResult.appended(
                1, "a".repeat(64), source.fact().traceId()));

        AuditRelayBatchResult result = relay.runBatch();

        assertEquals(1, result.confirmed());
        assertEquals(List.of("confirm:1"), work.actions);
        assertEquals(100, work.batchSize);
        assertEquals(Duration.ofSeconds(60), work.lease);
    }

    @Test
    void crashAfterCenterCommitReplaysAsExactDuplicateAndThenConfirmsWithoutSecondAppend() {
        MemoryWork work = new MemoryWork(7);
        work.failFirstConfirm = true;
        int[] calls = {0};
        AuditLedgerIngressPort center = source -> {
            calls[0]++;
            return calls[0] == 1
                    ? AuditIngressResult.appended(41, "b".repeat(64), source.fact().traceId())
                    : AuditIngressResult.exactDuplicate(41, "b".repeat(64), source.fact().traceId());
        };
        AuditOutboxRelayProcessor relay = relay(work, center);

        relay.runBatch();
        work.attempts = 8;
        AuditRelayBatchResult replay = relay.runBatch();

        assertEquals(2, calls[0]);
        assertEquals(1, replay.confirmed());
        assertTrue(work.actions.contains("confirm:8"));
    }

    @Test
    void collisionsArePermanentFailuresAndTransientErrorsUseCappedJitteredBackoff() {
        MemoryWork collision = new MemoryWork(2);
        relay(collision, source -> AuditIngressResult.collision(source.fact().traceId())).runBatch();
        assertEquals(List.of("fail:2:AUDIT_INGESTION_DUPLICATE_CONFLICT"), collision.actions);

        MemoryWork unavailable = new MemoryWork(10);
        relay(unavailable, source -> {
            throw new IllegalStateException("center unavailable");
        }).runBatch();
        assertEquals(List.of("retry:10:AUDIT_LEDGER_UNAVAILABLE:30"), unavailable.actions);

        assertEquals(Duration.ofMillis(500), AuditRetryPolicy.delay(1, 0.5));
        assertEquals(Duration.ofSeconds(60), AuditRetryPolicy.delay(100, 1.0));
    }

    @Test
    void quarantinedEnvelopePersistsCenterFindingBeforeSourceIsMarkedFailed() {
        MemoryWork work = new MemoryWork(3);
        AuditContractRejection rejection = new AuditContractRejection(
                "identity-access", "c".repeat(64), "d".repeat(32), NOW);
        work.claim = new RejectedLocalAudit(outbox().eventId(), 3, rejection);
        List<String> centerActions = new ArrayList<>();
        AuditLedgerIngressPort center = new AuditLedgerIngressPort() {
            @Override
            public AuditIngressResult ingest(
                    cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord source) {
                throw new AssertionError("invalid envelope must not be ingested");
            }

            @Override
            public AuditIngressResult rejectContract(AuditContractRejection value) {
                centerActions.add("finding:" + value.safeDigest());
                return AuditIngressResult.rejected(
                        "AUDIT_INGESTION_CONTRACT_REJECTED", false, value.traceId());
            }
        };

        AuditRelayBatchResult result = relay(work, center).runBatch();

        assertEquals(1, result.failed());
        assertEquals(List.of("finding:" + "c".repeat(64)), centerActions);
        assertEquals(List.of("fail:3:AUDIT_INGESTION_CONTRACT_REJECTED"), work.actions);
    }

    private static AuditOutboxRelayProcessor relay(MemoryWork work, AuditLedgerIngressPort center) {
        return new AuditOutboxRelayProcessor(
                work,
                center,
                () -> NOW.plusSeconds(120),
                () -> 0.5,
                100,
                Duration.ofSeconds(60));
    }

    private static final class MemoryWork implements IdentityAuditRelayWorkRepository {
        private long attempts;
        private int batchSize;
        private Duration lease;
        private boolean failFirstConfirm;
        private AuditRelayClaim claim;
        private final List<String> actions = new ArrayList<>();

        private MemoryWork(long attempts) {
            this.attempts = attempts;
        }

        @Override
        public List<AuditRelayClaim> claimDue(int batchSize, Instant now, Duration lease) {
            this.batchSize = batchSize;
            this.lease = lease;
            return List.of(claim == null ? new ClaimedLocalAudit(outbox(), attempts) : claim);
        }

        @Override
        public boolean confirm(java.util.UUID eventId, long expectedAttempts, Instant confirmedAt) {
            actions.add("confirm:" + expectedAttempts);
            if (failFirstConfirm) {
                failFirstConfirm = false;
                throw new IllegalStateException("crash before source confirm");
            }
            return expectedAttempts == attempts;
        }

        @Override
        public boolean retry(
                java.util.UUID eventId,
                long expectedAttempts,
                Instant nextAttemptAt,
                String errorCode) {
            actions.add("retry:" + expectedAttempts + ":" + errorCode + ":"
                    + Duration.between(NOW.plusSeconds(120), nextAttemptAt).toSeconds());
            return expectedAttempts == attempts;
        }

        @Override
        public boolean fail(java.util.UUID eventId, long expectedAttempts, String errorCode, Instant failedAt) {
            actions.add("fail:" + expectedAttempts + ":" + errorCode);
            return expectedAttempts == attempts;
        }
    }
}
