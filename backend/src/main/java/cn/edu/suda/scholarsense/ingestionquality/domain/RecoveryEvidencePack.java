package cn.edu.suda.scholarsense.ingestionquality.domain;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryVersionBindings.ResolvedPolicyEvidence;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable and self-contained recovery evidence snapshot.
 *
 * <p>Only controlled identifiers, version/digest fences, timestamps, counts, statuses and stable
 * codes are admitted. There are intentionally no actor, principal, subject, note or free-text
 * fields.
 */
public record RecoveryEvidencePack(
        UUID evidencePackId,
        long evidencePackVersion,
        RecoveryObjectBinding object,
        RecoveryVersionBindings versionBindings,
        ResolvedPolicyEvidence policyEvidence,
        QualityRecoverySourceClass sourceClass,
        QualityEvidence qualityEvidence,
        BatchEvidence batchEvidence,
        BackfillEvidence backfillEvidence,
        ReconciliationEvidence reconciliationEvidence,
        SampleEvidence sampleEvidence,
        String previewDigest,
        ReadinessDecision readinessDecision,
        List<MissingEvidenceCode> missingEvidenceCodes,
        Instant trustedStartedAt,
        Instant trustedCompletedAt,
        String evidencePackDigest) {
    private static final int MAX_MISSING_CODES = 32;

    public RecoveryEvidencePack {
        evidencePackId = uuidV7(evidencePackId);
        evidencePackVersion = RecoveryVersionBindings.safePositive(evidencePackVersion);
        object = Objects.requireNonNull(object);
        versionBindings = Objects.requireNonNull(versionBindings);
        policyEvidence = Objects.requireNonNull(policyEvidence);
        sourceClass = Objects.requireNonNull(sourceClass);
        if (!policyEvidence.matches(versionBindings, sourceClass)
                || batchEvidence == null
                || batchEvidence.requiredConsecutivePassedBatches()
                        != policyEvidence.requiredConsecutivePassedBatches()) {
            throw invalid();
        }
        qualityEvidence = Objects.requireNonNull(qualityEvidence);
        batchEvidence = Objects.requireNonNull(batchEvidence);
        backfillEvidence = Objects.requireNonNull(backfillEvidence);
        reconciliationEvidence = Objects.requireNonNull(reconciliationEvidence);
        sampleEvidence = Objects.requireNonNull(sampleEvidence);
        previewDigest = RecoveryVersionBindings.digest(previewDigest);
        readinessDecision = Objects.requireNonNull(readinessDecision);
        List<MissingEvidenceCode> codes = List.copyOf(Objects.requireNonNull(missingEvidenceCodes))
                .stream().sorted(Comparator.naturalOrder()).toList();
        if (codes.size() > MAX_MISSING_CODES || codes.stream().anyMatch(Objects::isNull)
                || codes.stream().distinct().count() != codes.size()) {
            throw invalid();
        }
        missingEvidenceCodes = codes;
        trustedStartedAt = RecoveryWindowImpact.microsecond(trustedStartedAt);
        trustedCompletedAt = RecoveryWindowImpact.microsecond(trustedCompletedAt);
        if (trustedCompletedAt.isBefore(trustedStartedAt)
                || reconciliationEvidence.completedAt().isAfter(trustedCompletedAt)
                || sampleEvidence.completedAt().isAfter(trustedCompletedAt)
                || backfillEvidence.trustedNow().isAfter(trustedCompletedAt)) {
            throw invalid();
        }
        evidencePackDigest = RecoveryVersionBindings.digest(evidencePackDigest);

        if (object.expectedEligibilityVersion() != versionBindings.expectedEligibilityVersion()
                || object.expectedEpisodeVersion() != versionBindings.expectedEpisodeVersion()
                || object.expectedTaskVersion() != versionBindings.expectedTaskVersion()
                || object.episodeGeneration() != versionBindings.episodeGeneration()) {
            throw invalid();
        }
        EnumSet<MissingEvidenceCode> locallyRequired = localFailures(
                policyEvidence,
                qualityEvidence, batchEvidence, backfillEvidence,
                reconciliationEvidence, sampleEvidence);
        Set<MissingEvidenceCode> supplied = EnumSet.noneOf(MissingEvidenceCode.class);
        supplied.addAll(codes);
        if (!supplied.equals(locallyRequired)
                || (readinessDecision == ReadinessDecision.READY_FOR_D4
                        && (!supplied.isEmpty() || !locallyRequired.isEmpty()))
                || (readinessDecision == ReadinessDecision.NOT_READY && supplied.isEmpty())) {
            throw invalid();
        }
    }

    public String schemaVersion() {
        return "QUALITY-RECOVERY-EVIDENCE-PACK-1.0.0";
    }

    public String canonicalizationProfile() {
        return "SCHOLARSENSE-CANONICAL-JSON-1.0.0";
    }

    public String runtimeEvidenceClaim() {
        return "contract-only";
    }

    /**
     * Freezes an already evaluated QRP decision without recalculating thresholds in the pack.
     * The evaluator remains the single executable readiness authority.
     */
    public static RecoveryEvidencePack create(
            UUID evidencePackId,
            long evidencePackVersion,
            RecoveryObjectBinding object,
            RecoveryVersionBindings versionBindings,
            QualityRecoverySourceClass sourceClass,
            QualityRecoveryPolicy policy,
            QualityEvidence qualityEvidence,
            QualityRecoveryReadiness.Decision readiness,
            String sequenceEvidenceDigest,
            BackfillEvidence backfillEvidence,
            ReconciliationEvidence reconciliationEvidence,
            SampleEvidence sampleEvidence,
            String previewDigest,
            Instant trustedStartedAt,
            Instant trustedCompletedAt,
            String evidencePackDigest) {
        Objects.requireNonNull(readiness);
        ResolvedPolicyEvidence resolved = ResolvedPolicyEvidence.from(
                policy, sourceClass, versionBindings);
        if (readiness.requiredConsecutivePassedBatches()
                != resolved.requiredConsecutivePassedBatches()) {
            throw invalid();
        }
        List<MissingEvidenceCode> missing = readiness.missingEvidence().stream()
                .map(value -> MissingEvidenceCode.valueOf(value.name()))
                .sorted()
                .toList();
        ReadinessDecision decision = readiness.readyForD4()
                ? ReadinessDecision.READY_FOR_D4 : ReadinessDecision.NOT_READY;
        return new RecoveryEvidencePack(
                evidencePackId, evidencePackVersion, object, versionBindings, resolved, sourceClass,
                qualityEvidence,
                new BatchEvidence(
                        readiness.requiredConsecutivePassedBatches(),
                        readiness.actualConsecutivePassedBatches(),
                        sequenceEvidenceDigest),
                backfillEvidence, reconciliationEvidence, sampleEvidence, previewDigest,
                decision, missing, trustedStartedAt, trustedCompletedAt, evidencePackDigest);
    }

    private static EnumSet<MissingEvidenceCode> localFailures(
            ResolvedPolicyEvidence policy,
            QualityEvidence quality,
            BatchEvidence batches,
            BackfillEvidence backfill,
            ReconciliationEvidence reconciliation,
            SampleEvidence sample) {
        EnumSet<MissingEvidenceCode> result = EnumSet.noneOf(MissingEvidenceCode.class);
        if (!quality.allRequiredMetricsPassed()) {
            result.add(MissingEvidenceCode.QUALITY_GATE_NOT_PASSED);
        }
        if (!batches.qualified()) {
            result.add(MissingEvidenceCode.CONSECUTIVE_BATCHES_INSUFFICIENT);
        }
        if (backfill.status() != BackfillStatus.SUCCEEDED
                || backfill.lookbackDays() != policy.lookbackDays()) {
            result.add(MissingEvidenceCode.BACKFILL_INCOMPLETE);
        }
        if (!policy.reconciliationCoverage().equals(reconciliation.coverage())
                || reconciliation.mismatchCount()
                        != policy.reconciliationExpectedMismatchCount()
                || reconciliation.expectedCount() != reconciliation.actualCount()) {
            result.add(MissingEvidenceCode.RECONCILIATION_MISMATCH);
        }
        if (sample.providerAvailability() != SampleProviderAvailability.AVAILABLE) {
            result.add(MissingEvidenceCode.SAMPLE_PROVIDER_UNAVAILABLE);
        }
        if (!sample.strataTotalsMatchOverall()) {
            result.add(MissingEvidenceCode.SAMPLE_MISMATCH);
        }
        long required = policy.allIfPopulationFewer()
                && sample.populationCount() < policy.minimumSampleSubjectWindows()
                ? sample.populationCount() : policy.minimumSampleSubjectWindows();
        if (sample.stratified() != policy.sampleStratified()) {
            result.add(MissingEvidenceCode.SAMPLE_INSUFFICIENT);
        }
        if (sample.selectedCount() < required) {
            result.add(MissingEvidenceCode.SAMPLE_INSUFFICIENT);
        }
        if (sample.mismatchCount() != policy.sampleExpectedMismatchCount()
                || !sample.expectedDigest().equals(sample.actualDigest())) {
            result.add(MissingEvidenceCode.SAMPLE_MISMATCH);
        }
        return result;
    }

    public record RecoveryObjectBinding(
            UUID recoveryRequestId,
            UUID eligibilityId,
            UUID episodeId,
            UUID taskId,
            long episodeGeneration,
            String sourceId,
            String dependencyId,
            long expectedEligibilityVersion,
            long expectedEpisodeVersion,
            long expectedTaskVersion) {
        public RecoveryObjectBinding {
            recoveryRequestId = uuidV7(recoveryRequestId);
            eligibilityId = uuidV7(eligibilityId);
            episodeId = uuidV7(episodeId);
            taskId = uuidV7(taskId);
            episodeGeneration = RecoveryVersionBindings.safePositive(episodeGeneration);
            if (sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$")
                    || dependencyId == null
                    || !dependencyId.matches("^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$")) {
                throw invalid();
            }
            expectedEligibilityVersion = RecoveryVersionBindings.safePositive(
                    expectedEligibilityVersion);
            expectedEpisodeVersion = RecoveryVersionBindings.safePositive(expectedEpisodeVersion);
            expectedTaskVersion = RecoveryVersionBindings.safePositive(expectedTaskVersion);
        }
    }

    public record QualityEvidence(
            String snapshotIdsDigest,
            String snapshotHashesDigest,
            String metricResultsDigest,
            boolean allRequiredMetricsPassed,
            String sourceWatermark,
            String dependencyWatermark) {
        public QualityEvidence {
            snapshotIdsDigest = RecoveryVersionBindings.digest(snapshotIdsDigest);
            snapshotHashesDigest = RecoveryVersionBindings.digest(snapshotHashesDigest);
            metricResultsDigest = RecoveryVersionBindings.digest(metricResultsDigest);
            sourceWatermark = watermark(sourceWatermark);
            dependencyWatermark = watermark(dependencyWatermark);
        }
    }

    public record BatchEvidence(
            int requiredConsecutivePassedBatches,
            long actualConsecutivePassedBatches,
            String sequenceEvidenceDigest) {
        public BatchEvidence {
            if (requiredConsecutivePassedBatches < 1) throw invalid();
            actualConsecutivePassedBatches = RecoveryVersionBindings.safeNonNegative(
                    actualConsecutivePassedBatches);
            sequenceEvidenceDigest = RecoveryVersionBindings.digest(sequenceEvidenceDigest);
        }

        public boolean qualified() {
            return actualConsecutivePassedBatches >= requiredConsecutivePassedBatches;
        }

        public String sequenceEvidenceVersion() {
            return "QUALITY-RECOVERY-BATCH-SEQUENCE-1.0.0";
        }
    }

    public record BackfillEvidence(
            String lastKnownGoodWatermark,
            Instant trustedNow,
            String backfillStartWatermark,
            String backfillCompletedWatermark,
            long lookbackDays,
            String evidenceDigest,
            BackfillStatus status) {
        public BackfillEvidence {
            lastKnownGoodWatermark = watermark(lastKnownGoodWatermark);
            trustedNow = RecoveryWindowImpact.microsecond(trustedNow);
            backfillStartWatermark = watermark(backfillStartWatermark);
            backfillCompletedWatermark = watermark(backfillCompletedWatermark);
            lookbackDays = RecoveryVersionBindings.safePositive(lookbackDays);
            evidenceDigest = RecoveryVersionBindings.digest(evidenceDigest);
            status = Objects.requireNonNull(status);
        }
    }

    public record ReconciliationEvidence(
            String coverage,
            long expectedCount,
            long actualCount,
            long mismatchCount,
            String summaryDigest,
            Instant completedAt) {
        public ReconciliationEvidence {
            if (coverage == null || !coverage.matches("^[a-z][a-z-]{1,31}$")) throw invalid();
            expectedCount = RecoveryVersionBindings.safeNonNegative(expectedCount);
            actualCount = RecoveryVersionBindings.safeNonNegative(actualCount);
            mismatchCount = RecoveryVersionBindings.safeNonNegative(mismatchCount);
            if (mismatchCount > Math.max(expectedCount, actualCount)) throw invalid();
            summaryDigest = RecoveryVersionBindings.digest(summaryDigest);
            completedAt = RecoveryWindowImpact.microsecond(completedAt);
        }

        public String summaryVersion() {
            return "QUALITY-RECOVERY-RECONCILIATION-SUMMARY-1.0.0";
        }
    }

    public record SampleEvidence(
            String providerVersion,
            SampleProviderAvailability providerAvailability,
            boolean stratified,
            String selectionSeed,
            long populationCount,
            long selectedCount,
            List<StratumSummary> strata,
            String strataSummaryDigest,
            String expectedDigest,
            String actualDigest,
            long mismatchCount,
            Instant completedAt) {
        private static final long MAX_SELECTED = 10_000;

        public SampleEvidence {
            if (providerVersion == null
                    || !providerVersion.matches("^RECOVERY-SAMPLE-PROVIDER-[0-9]+\\.[0-9]+\\.[0-9]+$")) {
                throw invalid();
            }
            providerAvailability = Objects.requireNonNull(providerAvailability);
            selectionSeed = RecoveryVersionBindings.digest(selectionSeed);
            populationCount = RecoveryVersionBindings.safeNonNegative(populationCount);
            selectedCount = RecoveryVersionBindings.safeNonNegative(selectedCount);
            mismatchCount = RecoveryVersionBindings.safeNonNegative(mismatchCount);
            strata = List.copyOf(Objects.requireNonNull(strata)).stream()
                    .sorted(Comparator.comparing(StratumSummary::stratumCode))
                    .toList();
            if (selectedCount > MAX_SELECTED || selectedCount > populationCount
                    || mismatchCount > selectedCount || strata.size() > 128
                    || strata.stream().anyMatch(Objects::isNull)
                    || strata.stream().map(StratumSummary::stratumCode).distinct().count()
                            != strata.size()) {
                throw invalid();
            }
            strataSummaryDigest = RecoveryVersionBindings.digest(strataSummaryDigest);
            expectedDigest = RecoveryVersionBindings.digest(expectedDigest);
            actualDigest = RecoveryVersionBindings.digest(actualDigest);
            completedAt = RecoveryWindowImpact.microsecond(completedAt);
        }

        public String summaryVersion() {
            return "QUALITY-RECOVERY-SAMPLE-SUMMARY-1.0.0";
        }

        boolean strataTotalsMatchOverall() {
            if (!stratified) return strata.isEmpty();
            try {
                long strataPopulation = 0;
                long strataSelected = 0;
                long strataMismatch = 0;
                for (StratumSummary stratum : strata) {
                    strataPopulation = Math.addExact(strataPopulation, stratum.populationCount());
                    strataSelected = Math.addExact(strataSelected, stratum.selectedCount());
                    strataMismatch = Math.addExact(strataMismatch, stratum.mismatchCount());
                }
                return !strata.isEmpty()
                        && strataPopulation == populationCount
                        && strataSelected == selectedCount
                        && strataMismatch == mismatchCount;
            } catch (ArithmeticException overflow) {
                return false;
            }
        }
    }

    /** PII-free bounded counts for one controlled sampling stratum. */
    public record StratumSummary(
            String stratumCode,
            long populationCount,
            long selectedCount,
            long mismatchCount,
            String summaryDigest) {
        public StratumSummary {
            if (stratumCode == null
                    || !stratumCode.matches("^[A-Z][A-Z0-9_]{1,63}$")) {
                throw invalid();
            }
            populationCount = RecoveryVersionBindings.safeNonNegative(populationCount);
            selectedCount = RecoveryVersionBindings.safeNonNegative(selectedCount);
            mismatchCount = RecoveryVersionBindings.safeNonNegative(mismatchCount);
            if (selectedCount > 10_000 || selectedCount > populationCount
                    || mismatchCount > selectedCount) {
                throw invalid();
            }
            summaryDigest = RecoveryVersionBindings.digest(summaryDigest);
        }
    }

    public enum ReadinessDecision {
        READY_FOR_D4("ready-for-d4"), NOT_READY("not-ready");

        private final String wireValue;

        ReadinessDecision(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public enum BackfillStatus {
        SUCCEEDED("succeeded"), FAILED("failed"), UNAVAILABLE("unavailable");

        private final String wireValue;

        BackfillStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public enum SampleProviderAvailability {
        AVAILABLE("available"), UNAVAILABLE("unavailable"), NOT_INSTALLED("not-installed");

        private final String wireValue;

        SampleProviderAvailability(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public enum MissingEvidenceCode {
        QUALITY_GATE_NOT_PASSED,
        CONSECUTIVE_BATCHES_INSUFFICIENT,
        BACKFILL_INCOMPLETE,
        RECONCILIATION_MISMATCH,
        SAMPLE_INSUFFICIENT,
        SAMPLE_MISMATCH,
        SAMPLE_PROVIDER_UNAVAILABLE,
        REQUIRED_DEPENDENCY_NOT_ELIGIBLE,
        ACTIVE_EPISODE_MISSING,
        OPEN_RECOVERY_TASK_MISSING,
        VERSION_OR_DIGEST_UNKNOWN
    }

    private static String watermark(String value) {
        if (value == null
                || !value.matches("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-=]{0,511}$")) {
            throw invalid();
        }
        return value;
    }

    private static UUID uuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("QUALITY_RECOVERY_EVIDENCE_PACK_INVALID");
    }
}
