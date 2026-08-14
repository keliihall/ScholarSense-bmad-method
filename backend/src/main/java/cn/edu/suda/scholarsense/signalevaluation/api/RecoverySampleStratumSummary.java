package cn.edu.suda.scholarsense.signalevaluation.api;

/** Bounded PII-free counts for one controlled sample stratum. */
public record RecoverySampleStratumSummary(
        String stratumCode,
        long populationCount,
        int selectedCount,
        int mismatchCount,
        String summaryDigest) {

    public RecoverySampleStratumSummary {
        if (stratumCode == null || !stratumCode.matches("[A-Z][A-Z0-9_]{1,63}")
                || populationCount < 0
                || populationCount > 9_007_199_254_740_991L
                || selectedCount < 0
                || selectedCount > RecoverySampleRecomputeProviderPort
                        .MAXIMUM_SELECTED_SUBJECT_WINDOWS
                || selectedCount > populationCount
                || mismatchCount < 0
                || mismatchCount > selectedCount
                || summaryDigest == null
                || !summaryDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("RECOVERY_SAMPLE_STRATUM_INVALID");
        }
    }
}
