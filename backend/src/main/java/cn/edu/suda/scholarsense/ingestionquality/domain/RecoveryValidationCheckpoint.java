package cn.edu.suda.scholarsense.ingestionquality.domain;

/** A privacy-safe partial result. Cursor and summary are canonical digests only. */
public record RecoveryValidationCheckpoint(
        long checkpointVersion,
        RecoveryValidationPhase phase,
        boolean phaseCompleted,
        String opaqueResumeRef,
        String cursorDigest,
        String partialSummaryDigest,
        long processedCount,
        long mismatchCount) {

    public RecoveryValidationCheckpoint {
        IngestionQualityDomainRules.requireVersion(checkpointVersion);
        if (phase == null || opaqueResumeRef == null
                || !opaqueResumeRef.matches("resume:v1:[0-9a-f]{64}")
                || processedCount < 0 || mismatchCount < 0
                || mismatchCount > processedCount
                || processedCount > IngestionQualityDomainRules.MAX_SAFE_VERSION) {
            throw IngestionQualityDomainRules.invalid();
        }
        IngestionQualityDomainRules.requireSha256(cursorDigest);
        IngestionQualityDomainRules.requireSha256(partialSummaryDigest);
    }

    static int phaseOrdinal(RecoveryValidationPhase value) {
        return switch (value) {
            case BACKFILL -> 0;
            case FULL_RECONCILIATION -> 1;
            case SAMPLE_RECOMPUTE -> 2;
        };
    }
}
