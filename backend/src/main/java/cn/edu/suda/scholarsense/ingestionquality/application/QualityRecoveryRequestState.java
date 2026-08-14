package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Internal request projection; the browser mapper exposes only its safe status fields. */
public record QualityRecoveryRequestState(
        UUID recoveryRequestId,
        long requestVersion,
        UUID taskId,
        UUID episodeId,
        String status,
        UUID validationJobId,
        String validationStatus,
        String validationResultDigest,
        UUID previewId,
        Long previewVersion,
        String previewDigest,
        Instant previewExpiresAt,
        PreviewSummary previewSummary,
        UUID approvalId,
        Long approvalVersion,
        String approvalStatus,
        String traceId,
        Map<String, Object> binding) {
    public QualityRecoveryRequestState {
        binding = Map.copyOf(binding);
    }

    public record PreviewSummary(
            String qualityRecoveryPolicyVersion,
            long requiredConsecutivePassedBatches,
            long actualConsecutivePassedBatches,
            String observationDuration,
            String backfillStatus,
            long backfillLookbackDays,
            long reconciliationExpectedCount,
            long reconciliationActualCount,
            long reconciliationMismatchCount,
            long samplePopulationCount,
            long sampleSelectedCount,
            long sampleMismatchCount,
            long sampleStrataCount,
            long impactAlreadyExpiredCount,
            long impactPotentiallyActionableCount,
            long impactExpectedToExpireCount,
            String finalActionabilityOwnerStory) {}
}
