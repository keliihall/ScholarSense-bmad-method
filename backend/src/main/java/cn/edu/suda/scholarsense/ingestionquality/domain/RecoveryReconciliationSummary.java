package cn.edu.suda.scholarsense.ingestionquality.domain;

/** PII-free immutable full-reconciliation result used by the recovery gate. */
public record RecoveryReconciliationSummary(
        String summaryVersion,
        Availability availability,
        Coverage coverage,
        long expectedCount,
        long actualCount,
        long mismatchCount) {
    public static final String SUMMARY_VERSION =
            "QUALITY-RECOVERY-RECONCILIATION-SUMMARY-1.0.0";

    public RecoveryReconciliationSummary {
        if (summaryVersion == null
                || expectedCount < 0
                || actualCount < 0
                || mismatchCount < 0
                || expectedCount > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || actualCount > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || mismatchCount > IngestionQualityDomainRules.MAX_SAFE_VERSION) {
            throw invalid();
        }
    }

    public boolean qualified(QualityRecoveryPolicy policy) {
        if (policy == null || availability == null || coverage == null) return false;
        return SUMMARY_VERSION.equals(summaryVersion)
                && availability == Availability.AVAILABLE
                && policy.reconciliation().coverage().equals(coverage.contractValue)
                && expectedCount == actualCount
                && mismatchCount == policy.reconciliation().expectedMismatchCount();
    }

    public enum Availability { AVAILABLE, UNAVAILABLE, UNKNOWN }

    public enum Coverage {
        FULL("full"), PARTIAL("partial"), UNKNOWN("unknown");

        private final String contractValue;

        Coverage(String contractValue) {
            this.contractValue = contractValue;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("QUALITY_RECOVERY_RECONCILIATION_INVALID");
    }
}
