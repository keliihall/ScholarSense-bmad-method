package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QualityTaskRelayProcessorTest {
    private static final UUID EVENT_ID = UUID.fromString(
            "019fe8a0-0000-7000-8000-000000000701");
    private static final UUID TASK_ID = UUID.fromString(
            "019fe8a0-0000-7000-8000-000000000702");

    @Test
    void confirmationOnlyAdvancesDeliverySidecar() {
        FakeWork work = new FakeWork(claim(1));
        var processor = new QualityTaskRelayProcessor(
                work, ignored -> {
                    assertTrue(work.permitOpen);
                    return QualityTaskTargetResult.confirmed("receipt-1");
                });

        assertEquals(QualityTaskRelayResult.CONFIRMED, processor.runOnce());
        assertEquals(1, work.confirmed);
        assertEquals(0, work.retried);
        assertEquals(0, work.failed);
        assertTrue(work.permitClosed);
    }

    @Test
    void timeoutAndServerFailureRetryButPermanentClientFailureStops() {
        FakeWork retryWork = new FakeWork(claim(2));
        assertEquals(QualityTaskRelayResult.RETRY_SCHEDULED,
                new QualityTaskRelayProcessor(
                        retryWork,
                        ignored -> QualityTaskTargetResult.retryable(
                                "QUALITY_TASK_TARGET_TIMEOUT"))
                        .runOnce());
        assertEquals(1, retryWork.retried);

        FakeWork failedWork = new FakeWork(claim(2));
        assertEquals(QualityTaskRelayResult.FAILED,
                new QualityTaskRelayProcessor(
                        failedWork,
                        ignored -> QualityTaskTargetResult.permanentFailure(
                                "QUALITY_TASK_TARGET_REQUEST_REJECTED"))
                        .runOnce());
        assertEquals(1, failedWork.failed);
    }

    @Test
    void eighthRetryableAttemptBecomesBoundedTerminalFailure() {
        FakeWork work = new FakeWork(claim(8));
        var processor = new QualityTaskRelayProcessor(
                work,
                ignored -> QualityTaskTargetResult.retryable(
                        "QUALITY_TASK_TARGET_UNAVAILABLE"));

        assertEquals(QualityTaskRelayResult.FAILED, processor.runOnce());
        assertEquals(0, work.retried);
        assertEquals(1, work.failed);
        assertEquals("QUALITY_TASK_RELAY_ATTEMPTS_EXHAUSTED", work.lastCode);
    }

    @Test
    void staleFinalizerIsReportedAsFencedAndNoClaimIsIdle() {
        FakeWork stale = new FakeWork(claim(1));
        stale.acceptFinalizer = false;
        assertEquals(QualityTaskRelayResult.FENCED,
                new QualityTaskRelayProcessor(
                        stale, ignored -> QualityTaskTargetResult.confirmed("receipt-1"))
                        .runOnce());

        assertEquals(QualityTaskRelayResult.IDLE,
                new QualityTaskRelayProcessor(
                        new FakeWork(null),
                        ignored -> QualityTaskTargetResult.confirmed("receipt-1"))
                        .runOnce());
    }

    @Test
    void supersededRouteIsFencedBeforeExternalSend() {
        FakeWork stale = new FakeWork(claim(1));
        stale.authorizeSend = false;
        boolean[] delivered = {false};

        assertEquals(QualityTaskRelayResult.FENCED,
                new QualityTaskRelayProcessor(stale, ignored -> {
                    delivered[0] = true;
                    return QualityTaskTargetResult.confirmed("receipt-1");
                }).runOnce());

        assertFalse(delivered[0]);
        assertEquals(0, stale.confirmed);
        assertTrue(stale.permitClosed);
    }

    private static QualityTaskRelayClaim claim(long attempt) {
        return new QualityTaskRelayClaim(
                EVENT_ID, TASK_ID, 1, "{\"specversion\":\"1.0\"}",
                "sha256:" + "a".repeat(64), attempt, attempt);
    }

    private static final class FakeWork implements QualityTaskRelayWorkPort {
        private final ArrayDeque<QualityTaskRelayClaim> claims = new ArrayDeque<>();
        private boolean acceptFinalizer = true;
        private boolean authorizeSend = true;
        private int confirmed;
        private int retried;
        private int failed;
        private String lastCode;
        private boolean permitOpen;
        private boolean permitClosed;

        private FakeWork(QualityTaskRelayClaim claim) {
            if (claim != null) claims.add(claim);
        }

        @Override
        public QualityTaskRelayClaim claimNext() {
            return claims.poll();
        }

        @Override
        public SendPermit acquireSendPermit(QualityTaskRelayClaim claim) {
            permitOpen = true;
            return new SendPermit() {
                @Override
                public boolean authorized() {
                    return authorizeSend;
                }

                @Override
                public boolean confirm(String receiptId) {
                    assertTrue(permitOpen);
                    confirmed++;
                    return acceptFinalizer;
                }

                @Override
                public boolean retry(String errorCode) {
                    assertTrue(permitOpen);
                    retried++;
                    lastCode = errorCode;
                    return acceptFinalizer;
                }

                @Override
                public boolean fail(String errorCode) {
                    assertTrue(permitOpen);
                    failed++;
                    lastCode = errorCode;
                    return acceptFinalizer;
                }

                @Override
                public void close() {
                    permitOpen = false;
                    permitClosed = true;
                }
            };
        }
    }
}
