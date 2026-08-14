package cn.edu.suda.scholarsense.ingestionquality.domain;

/** Bounded, PII-free sample recomputation summary. */
public record RecoverySampleSummary(
        String summaryVersion,
        String providerVersion,
        ProviderAvailability providerAvailability,
        boolean stratified,
        long populationCount,
        long selectedCount,
        long mismatchCount) {
    public static final String SUMMARY_VERSION = "QUALITY-RECOVERY-SAMPLE-SUMMARY-1.0.0";
    public static final String APPROVED_PROVIDER_VERSION =
            "RECOVERY-SAMPLE-PROVIDER-1.0.0";

    public RecoverySampleSummary {
        if (summaryVersion == null
                || providerVersion == null
                || populationCount < 0
                || selectedCount < 0
                || mismatchCount < 0
                || populationCount > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || selectedCount > 10_000
                || mismatchCount > 10_000
                || selectedCount > populationCount) {
            throw invalid();
        }
    }

    public boolean qualified(QualityRecoveryPolicy policy) {
        if (policy == null || providerAvailability == null) return false;
        QualityRecoveryPolicy.Sampling rule = policy.sampling();
        long requiredSelection = rule.allIfPopulationFewer()
                && populationCount < rule.minimumSubjectWindows()
                ? populationCount
                : rule.minimumSubjectWindows();
        return SUMMARY_VERSION.equals(summaryVersion)
                && APPROVED_PROVIDER_VERSION.equals(providerVersion)
                && providerAvailability == ProviderAvailability.AVAILABLE
                && stratified == rule.stratified()
                && selectedCount >= requiredSelection
                && mismatchCount == rule.expectedMismatchCount();
    }

    public enum ProviderAvailability { AVAILABLE, UNAVAILABLE, NOT_INSTALLED, UNKNOWN }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("QUALITY_RECOVERY_SAMPLE_SUMMARY_INVALID");
    }
}
