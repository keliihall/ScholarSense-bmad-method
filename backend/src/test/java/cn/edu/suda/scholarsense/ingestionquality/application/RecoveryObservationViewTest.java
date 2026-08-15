package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryObservationViewTest {
    private static final UUID RECOVERY =
            UUID.fromString("018f34c0-9b80-7a11-8abc-0123456789ab");
    private static final UUID TASK =
            UUID.fromString("018f34c0-9b80-7a11-8abc-0123456789ac");
    private static final UUID APPROVAL =
            UUID.fromString("018f34c0-9b80-7a11-8abc-0123456789ad");
    private static final Instant START = Instant.parse("2026-08-14T00:00:00Z");
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    @Test
    void exactStreamingDurationAndOpenProjectionAreAccepted() {
        RecoveryObservationView view = view(
                "observing", "not-requested", "recovering", "open", null, null,
                "pending", null);

        assertEquals("PT60M", view.observationDuration());
        assertEquals(3_600_000_000L, view.requiredDurationMicros());
    }

    @Test
    void finalizedProjectionRequiresExecutedClosedEligibleAndOwnerDigest() {
        RecoveryObservationView view = view(
                "finalized", "executed", "eligible", "closed",
                START.plusSeconds(3600), DIGEST, "confirmed", null);
        assertEquals("closed", view.taskStatus());

        assertThrows(IllegalArgumentException.class, () -> view(
                "finalized", "approval-approved", "eligible", "closed",
                START.plusSeconds(3600), DIGEST, "confirmed", null));
        assertThrows(IllegalArgumentException.class, () -> view(
                "finalized", "executed", "eligible", "open", null, null,
                "pending", null));
    }

    @Test
    void retryingDeliveryRequiresItsIndependentRetryTimestamp() {
        assertThrows(IllegalArgumentException.class, () -> view(
                "observing", "not-requested", "recovering", "open", null, null,
                "retrying", null));
    }

    @Test
    void pendingFinalApprovalIsAvailableToAnIndependentCheckerAfterRefresh() {
        RecoveryObservationView view = view(
                "ready", "approval-pending", "recovering", "open", null, null,
                "pending", null);

        assertEquals(APPROVAL, view.approvalId());
        assertEquals(1L, view.approvalVersion());
        assertEquals(List.of(), view.failedMembers());
        assertEquals("11111111111111111111111111111111", view.traceId());
    }

    private static RecoveryObservationView view(
            String status,
            String finalizationState,
            String eligibility,
            String taskStatus,
            Instant closedAt,
            String ownerDigest,
            String deliveryStatus,
            Instant nextAttemptAt) {
        return new RecoveryObservationView(
                RECOVERY, 2, TASK, 3, 1, "streaming", "QRP-1.0.0", DIGEST,
                status, finalizationState,
                "not-requested".equals(finalizationState) ? null : APPROVAL,
                "not-requested".equals(finalizationState) ? null : 1L,
                1, 3, 60_000_000L, 3_600_000_000L,
                "PT60M", "wm-1", START, START.plusSeconds(60),
                START.plusSeconds(7200),
                "relapsed".equals(status)
                        ? List.of("DEP-P0-CAMPUS-ACCESS-001") : List.of(),
                null, eligibility, taskStatus, closedAt,
                ownerDigest, deliveryStatus, 0, nextAttemptAt, 0, 0,
                "11111111111111111111111111111111");
    }
}
