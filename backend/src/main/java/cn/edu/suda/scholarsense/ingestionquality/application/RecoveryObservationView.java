package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** R6 observation projection; counts, digests and opaque watermarks only, never member PII. */
public record RecoveryObservationView(
        UUID recoveryId,
        long recoveryVersion,
        UUID taskId,
        long taskVersion,
        long generation,
        String sourceClass,
        String policyVersion,
        String policyDigest,
        String status,
        String finalizationState,
        UUID approvalId,
        Long approvalVersion,
        int consecutivePassedBatches,
        int requiredPassedBatches,
        long observedDurationMicros,
        long requiredDurationMicros,
        String observationDuration,
        String watermark,
        Instant recoveringStartedAt,
        Instant lastObservedAt,
        Instant latestActionableAt,
        List<String> failedMembers,
        String failureReasonCode,
        String eligibilityStatus,
        String taskStatus,
        Instant taskClosedAt,
        String ownerResultDigest,
        String deliveryStatus,
        long deliveryAttempt,
        Instant deliveryNextAttemptAt,
        long eligibleForHandoffWindowCount,
        long historyOnlyWindowCount,
        String traceId) {
    public RecoveryObservationView {
        failedMembers = List.copyOf(failedMembers);
        if (recoveryId == null || recoveryId.version() != 7 || recoveryId.variant() != 2
                || taskId == null || taskId.version() != 7 || taskId.variant() != 2
                || recoveryVersion < 1 || taskVersion < 1 || generation < 1
                || !java.util.Set.of("streaming", "daily-batch").contains(sourceClass)
                || !"QRP-1.0.0".equals(policyVersion)
                || policyDigest == null || !policyDigest.matches("^sha256:[0-9a-f]{64}$")
                || !java.util.Set.of("observing", "ready", "relapsed", "policy-drift",
                        "finalized").contains(status)
                || !java.util.Set.of("not-requested", "approval-pending",
                        "approval-approved", "approval-rejected", "cancelled", "executed")
                        .contains(finalizationState)
                || ("not-requested".equals(finalizationState)
                    != (approvalId == null && approvalVersion == null))
                || (approvalId == null) != (approvalVersion == null)
                || (approvalId != null && (approvalId.version() != 7
                    || approvalId.variant() != 2 || approvalVersion == null
                    || approvalVersion < 1 || approvalVersion > 9_007_199_254_740_991L))
                || consecutivePassedBatches < 0 || requiredPassedBatches < 1
                || observedDurationMicros < 0 || requiredDurationMicros < 1
                || !java.util.Set.of("PT60M", "P1D").contains(observationDuration)
                || ("streaming".equals(sourceClass)
                    && (!"PT60M".equals(observationDuration)
                        || requiredPassedBatches != 3
                        || requiredDurationMicros != 3_600_000_000L))
                || ("daily-batch".equals(sourceClass)
                    && (!"P1D".equals(observationDuration)
                        || requiredPassedBatches != 2
                        || requiredDurationMicros != 86_400_000_000L))
                || recoveringStartedAt == null || lastObservedAt == null
                || !microsecond(recoveringStartedAt) || !microsecond(lastObservedAt)
                || !microsecond(latestActionableAt) || !microsecond(taskClosedAt)
                || !microsecond(deliveryNextAttemptAt)
                || failedMembers.size() > 128
                || failedMembers.stream().distinct().count() != failedMembers.size()
                || failedMembers.stream().anyMatch(value -> value == null
                    || !value.matches("^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$"))
                || !java.util.Set.of("fused", "recovering", "eligible")
                        .contains(eligibilityStatus)
                || !java.util.Set.of("open", "closed").contains(taskStatus)
                || !java.util.Set.of("pending", "retrying", "confirmed", "failed")
                        .contains(deliveryStatus)
                || deliveryAttempt < 0 || eligibleForHandoffWindowCount < 0
                || historyOnlyWindowCount < 0
                || traceId == null || !traceId.matches("^(?!0{32}$)[0-9a-f]{32}$")
                || ("finalized".equals(status) != "executed".equals(finalizationState))
                || ("finalized".equals(status) != "eligible".equals(eligibilityStatus))
                || ("relapsed".equals(status) && !"fused".equals(eligibilityStatus))
                || ("closed".equals(taskStatus)
                    != (taskClosedAt != null && validDigest(ownerResultDigest)))
                || ("closed".equals(taskStatus) != "finalized".equals(status))
                || ("retrying".equals(deliveryStatus) != (deliveryNextAttemptAt != null))) {
            throw new IllegalArgumentException("INGESTION_QUALITY_OBSERVATION_VIEW_INVALID");
        }
    }

    private static boolean microsecond(Instant value) {
        return value == null || value.getNano() % 1_000 == 0;
    }

    private static boolean validDigest(String value) {
        return value != null && value.matches("^sha256:[0-9a-f]{64}$");
    }
}
