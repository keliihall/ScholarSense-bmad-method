package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;

public record RecoveryFullReconciliationResult(
        RecoveryValidationDependencyAvailability availability,
        Coverage coverage,
        long expectedCount,
        long actualCount,
        long mismatchCount,
        String summaryDigest,
        Instant completedAt,
        RecoveryValidationDependencyError errorCode,
        boolean retryable,
        String traceId) {

    public enum Coverage { FULL }

    public RecoveryFullReconciliationResult {
        boolean available = availability == RecoveryValidationDependencyAvailability.AVAILABLE;
        if (availability == null
                || available != (coverage == Coverage.FULL)
                || expectedCount < 0 || actualCount < 0 || mismatchCount < 0
                || available && mismatchCount > Math.max(expectedCount, actualCount)
                || available != RecoveryValidationPortRules.digest(summaryDigest)
                || available != (completedAt != null)
                || available != (errorCode == null)
                || available && retryable
                || !RecoveryValidationPortRules.trace(traceId)) {
            throw new IllegalArgumentException("RECOVERY_RECONCILIATION_RESULT_INVALID");
        }
    }

    public boolean qualified() {
        return availability == RecoveryValidationDependencyAvailability.AVAILABLE
                && coverage == Coverage.FULL && expectedCount == actualCount
                && mismatchCount == 0;
    }

    public static RecoveryFullReconciliationResult available(
            long expectedCount,
            long actualCount,
            long mismatchCount,
            String summaryDigest,
            Instant completedAt,
            String traceId) {
        return new RecoveryFullReconciliationResult(
                RecoveryValidationDependencyAvailability.AVAILABLE, Coverage.FULL,
                expectedCount, actualCount, mismatchCount, summaryDigest, completedAt,
                null, false, traceId);
    }

    public static RecoveryFullReconciliationResult notInstalled(String traceId) {
        return new RecoveryFullReconciliationResult(
                RecoveryValidationDependencyAvailability.NOT_INSTALLED, null,
                0, 0, 0, null, null,
                RecoveryValidationDependencyError.PROVIDER_NOT_INSTALLED, false, traceId);
    }
}
