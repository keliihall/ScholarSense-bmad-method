package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;

public record RecoveryBackfillResult(
        RecoveryValidationDependencyAvailability availability,
        boolean completed,
        String startWatermarkDigest,
        String targetWatermarkDigest,
        long processedCount,
        String summaryDigest,
        Instant completedAt,
        RecoveryValidationDependencyError errorCode,
        boolean retryable,
        String traceId) {

    public RecoveryBackfillResult {
        RecoveryValidationPortRules.result(
                availability, completed, startWatermarkDigest, targetWatermarkDigest,
                processedCount, summaryDigest, completedAt, errorCode, retryable, traceId);
    }

    public static RecoveryBackfillResult available(
            String startWatermarkDigest,
            String targetWatermarkDigest,
            long processedCount,
            String summaryDigest,
            Instant completedAt,
            String traceId) {
        return new RecoveryBackfillResult(
                RecoveryValidationDependencyAvailability.AVAILABLE, true,
                startWatermarkDigest, targetWatermarkDigest, processedCount,
                summaryDigest, completedAt, null, false, traceId);
    }

    public static RecoveryBackfillResult unavailable(
            RecoveryValidationDependencyError error, boolean retryable, String traceId) {
        return new RecoveryBackfillResult(
                RecoveryValidationDependencyAvailability.UNAVAILABLE, false,
                null, null, 0, null, null, error, retryable, traceId);
    }
}
