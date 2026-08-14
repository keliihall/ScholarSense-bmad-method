package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Immutable, job-bound validation output containing controlled counts and digests only. */
public record RecoveryValidationResult(
        UUID resultId,
        UUID jobId,
        long jobVersion,
        UUID recoveryRequestId,
        UUID episodeId,
        UUID taskId,
        RecoveryValidationJobStatus state,
        String inputDigest,
        String qualityRecoveryPolicyVersion,
        String qualityRecoveryPolicyDigest,
        String backfillStartWatermarkDigest,
        String backfillTargetWatermarkDigest,
        String backfillSummaryDigest,
        long reconciliationExpectedCount,
        long reconciliationActualCount,
        long reconciliationMismatchCount,
        String reconciliationSummaryDigest,
        String sampleSummaryVersion,
        String sampleSummaryDigest,
        long populationCount,
        int selectedCount,
        List<StratumSummary> strata,
        String strataSummaryDigest,
        String expectedDigest,
        String actualDigest,
        long mismatchCount,
        RecoveryValidationReadinessEvidence readinessEvidence,
        boolean qualified,
        ResultErrorCode errorCode,
        Instant completedAt,
        String resultDigest,
        String traceId) {

    public RecoveryValidationResult {
        IngestionQualityDomainRules.requireUuidV7(resultId);
        IngestionQualityDomainRules.requireUuidV7(jobId);
        IngestionQualityDomainRules.requireVersion(jobVersion);
        IngestionQualityDomainRules.requireUuidV7(recoveryRequestId);
        IngestionQualityDomainRules.requireUuidV7(episodeId);
        IngestionQualityDomainRules.requireUuidV7(taskId);
        if (state == null || !state.terminal()
                || !"QRP-1.0.0".equals(qualityRecoveryPolicyVersion)
                || !"QUALITY-RECOVERY-SAMPLE-SUMMARY-1.0.0".equals(sampleSummaryVersion)
                || completedAt == null || completedAt.getNano() % 1_000 != 0
                || traceId == null || !traceId.matches("(?!0{32})[0-9a-f]{32}")) {
            throw IngestionQualityDomainRules.invalid();
        }
        IngestionQualityDomainRules.requireSha256(inputDigest);
        IngestionQualityDomainRules.requireSha256(qualityRecoveryPolicyDigest);
        IngestionQualityDomainRules.requireSha256(backfillStartWatermarkDigest);
        IngestionQualityDomainRules.requireSha256(backfillTargetWatermarkDigest);
        IngestionQualityDomainRules.requireSha256(backfillSummaryDigest);
        IngestionQualityDomainRules.requireSha256(reconciliationSummaryDigest);
        IngestionQualityDomainRules.requireSha256(sampleSummaryDigest);
        IngestionQualityDomainRules.requireSha256(strataSummaryDigest);
        IngestionQualityDomainRules.requireSha256(expectedDigest);
        IngestionQualityDomainRules.requireSha256(actualDigest);
        IngestionQualityDomainRules.requireSha256(resultDigest);
        readinessEvidence = java.util.Objects.requireNonNull(readinessEvidence);
        if (reconciliationExpectedCount < 0 || reconciliationActualCount < 0
                || reconciliationMismatchCount < 0
                || reconciliationExpectedCount > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || reconciliationActualCount > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || reconciliationMismatchCount > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || populationCount < 0
                || populationCount > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || selectedCount < 0 || selectedCount > 10_000
                || selectedCount > populationCount || mismatchCount < 0
                || mismatchCount > selectedCount) {
            throw IngestionQualityDomainRules.invalid();
        }
        strata = List.copyOf(strata == null ? List.of() : strata).stream()
                .sorted(Comparator.comparing(StratumSummary::stratumCode)).toList();
        if (strata.size() > 128 || strata.stream().anyMatch(java.util.Objects::isNull)
                || strata.stream().map(StratumSummary::stratumCode).distinct().count()
                        != strata.size()
                || !strataTotalsMatch(strata, populationCount, selectedCount, mismatchCount)) {
            throw IngestionQualityDomainRules.invalid();
        }
        boolean successful = state == RecoveryValidationJobStatus.SUCCEEDED;
        boolean cancelled = state == RecoveryValidationJobStatus.CANCELLED;
        boolean technicalFailure = state == RecoveryValidationJobStatus.FAILED;
        boolean businessFailure = errorCode == ResultErrorCode.BACKFILL_FAILED
                || errorCode == ResultErrorCode.READINESS_NOT_MET
                || errorCode == ResultErrorCode.RECONCILIATION_MISMATCH
                || errorCode == ResultErrorCode.SAMPLE_INSUFFICIENT
                || errorCode == ResultErrorCode.SAMPLE_MISMATCH;
        if ((qualified && (!successful || errorCode != null
                    || !readinessEvidence.qualified()
                    || reconciliationExpectedCount != reconciliationActualCount
                    || reconciliationMismatchCount != 0 || mismatchCount != 0))
                || (successful && !qualified && !businessFailure)
                || (cancelled != (errorCode == ResultErrorCode.CANCELLED))
                || (technicalFailure && errorCode != ResultErrorCode.PROVIDER_NOT_INSTALLED
                    && errorCode != ResultErrorCode.DEPENDENCY_UNAVAILABLE)) {
            throw IngestionQualityDomainRules.invalid();
        }
    }

    public boolean transitionApplied() {
        return false;
    }

    public String schemaVersion() {
        return "QUALITY-RECOVERY-VALIDATION-RESULT-1.1.0";
    }

    private static boolean strataTotalsMatch(
            List<StratumSummary> strata, long population, int selected, long mismatch) {
        if (strata.isEmpty()) return population == 0 && selected == 0 && mismatch == 0;
        try {
            long p = 0, s = 0, m = 0;
            for (StratumSummary value : strata) {
                p = Math.addExact(p, value.populationCount());
                s = Math.addExact(s, value.selectedCount());
                m = Math.addExact(m, value.mismatchCount());
            }
            return p == population && s == selected && m == mismatch;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    public record StratumSummary(
            String stratumCode,
            long populationCount,
            int selectedCount,
            int mismatchCount,
            String summaryDigest) {
        public StratumSummary {
            if (stratumCode == null || !stratumCode.matches("[A-Z][A-Z0-9_]{1,63}")
                    || populationCount < 0 || selectedCount < 0 || mismatchCount < 0
                    || selectedCount > 10_000 || selectedCount > populationCount
                    || mismatchCount > selectedCount) {
                throw IngestionQualityDomainRules.invalid();
            }
            IngestionQualityDomainRules.requireSha256(summaryDigest);
        }
    }

    public enum ResultErrorCode {
        READINESS_NOT_MET,
        BACKFILL_FAILED,
        RECONCILIATION_MISMATCH,
        SAMPLE_INSUFFICIENT,
        SAMPLE_MISMATCH,
        PROVIDER_NOT_INSTALLED,
        DEPENDENCY_UNAVAILABLE,
        CANCELLED
    }
}
