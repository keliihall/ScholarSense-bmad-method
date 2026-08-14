package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** PII-free owner-private QRP evidence sealed into one immutable validation result. */
public record RecoveryValidationReadinessEvidence(
        long episodeGeneration,
        long expectedEpisodeVersion,
        long expectedTaskVersion,
        String sourceId,
        String dependencyId,
        String ruleVersionsDigest,
        String memberSetDigest,
        String watermarksDigest,
        String sourceClass,
        String sourceSchemaVersion,
        String sourceSchemaDigest,
        String dependencyVersion,
        String dependencyDigest,
        List<EligibilityBinding> eligibilityBindings,
        String currentBindingDigest,
        QualityEvidence qualityEvidence,
        BatchEvidence batchEvidence,
        BackfillEvidence backfillEvidence,
        boolean requiredMembersEligible,
        List<MissingEvidenceCode> missingEvidenceCodes,
        long impactAlreadyExpiredCount,
        long impactPotentiallyActionableCount,
        long impactExpectedToExpireCount,
        Instant trustedStartedAt,
        String evidenceDigest) {

    public RecoveryValidationReadinessEvidence {
        if (episodeGeneration < 1 || expectedEpisodeVersion < 1 || expectedTaskVersion < 1
                || episodeGeneration > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || expectedEpisodeVersion > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || expectedTaskVersion > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$")
                || dependencyId == null
                || !dependencyId.matches("^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$")
                || (!"streaming".equals(sourceClass) && !"dailyBatch".equals(sourceClass))
                || sourceSchemaVersion == null
                || !sourceSchemaVersion.matches("^[A-Z][A-Z0-9.-]*-[0-9]+\\.[0-9]+\\.[0-9]+$")
                || dependencyVersion == null || !dependencyVersion.matches("^[1-9][0-9]{0,15}$")
                || trustedStartedAt == null || trustedStartedAt.getNano() % 1_000 != 0) {
            throw invalid();
        }
        digest(ruleVersionsDigest);
        digest(memberSetDigest);
        digest(watermarksDigest);
        digest(sourceSchemaDigest);
        digest(dependencyDigest);
        digest(currentBindingDigest);
        digest(evidenceDigest);
        eligibilityBindings = List.copyOf(Objects.requireNonNull(eligibilityBindings)).stream()
                .sorted(Comparator.comparing(EligibilityBinding::ruleId)
                        .thenComparing(EligibilityBinding::ruleVersion))
                .toList();
        if (eligibilityBindings.isEmpty() || eligibilityBindings.size() > 128
                || eligibilityBindings.stream().anyMatch(Objects::isNull)
                || eligibilityBindings.stream().map(EligibilityBinding::eligibilityId)
                        .distinct().count() != eligibilityBindings.size()) {
            throw invalid();
        }
        qualityEvidence = Objects.requireNonNull(qualityEvidence);
        batchEvidence = Objects.requireNonNull(batchEvidence);
        backfillEvidence = Objects.requireNonNull(backfillEvidence);
        missingEvidenceCodes = List.copyOf(
                Objects.requireNonNull(missingEvidenceCodes)).stream().sorted().toList();
        if (missingEvidenceCodes.size() > 32
                || missingEvidenceCodes.stream().anyMatch(Objects::isNull)
                || missingEvidenceCodes.stream().distinct().count()
                        != missingEvidenceCodes.size()
                || impactAlreadyExpiredCount < 0 || impactPotentiallyActionableCount < 0
                || impactExpectedToExpireCount < 0
                || impactAlreadyExpiredCount > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || impactPotentiallyActionableCount > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || impactExpectedToExpireCount > IngestionQualityDomainRules.MAX_SAFE_VERSION) {
            throw invalid();
        }
        boolean qualityMissing = missingEvidenceCodes.contains(
                MissingEvidenceCode.QUALITY_GATE_NOT_PASSED);
        boolean batchMissing = missingEvidenceCodes.contains(
                MissingEvidenceCode.CONSECUTIVE_BATCHES_INSUFFICIENT);
        boolean backfillMissing = missingEvidenceCodes.contains(
                MissingEvidenceCode.BACKFILL_INCOMPLETE);
        boolean memberMissing = missingEvidenceCodes.contains(
                MissingEvidenceCode.REQUIRED_DEPENDENCY_NOT_ELIGIBLE);
        if (qualityMissing == qualityEvidence.allRequiredMetricsPassed()
                || batchMissing == batchEvidence.qualified()
                || backfillMissing == "succeeded".equals(backfillEvidence.status())
                || memberMissing == requiredMembersEligible) {
            throw invalid();
        }
    }

    /** Proves that persisted SQL evidence still reflects the frozen executable QRP/QRSCR. */
    public void requireFrozenAuthority(
            QualityRecoveryPolicy policy,
            QualityRecoverySourceClassRegistry registry) {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(registry);
        QualityRecoverySourceClass approvedClass = registry.requireClass(sourceId);
        QualityRecoveryPolicy.SourceClassRule approvedRule = policy.ruleFor(approvedClass);
        if (!approvedClass.contractValue().equals(sourceClass)
                || batchEvidence.requiredConsecutivePassedBatches()
                    != approvedRule.consecutivePassedBatches()
                || backfillEvidence.lookbackDays() != policy.backfill().lookback().toDays()) {
            throw invalid();
        }
    }

    public boolean qualified() {
        return missingEvidenceCodes.isEmpty()
                && qualityEvidence.allRequiredMetricsPassed()
                && batchEvidence.qualified()
                && "succeeded".equals(backfillEvidence.status())
                && requiredMembersEligible;
    }

    public record EligibilityBinding(
            UUID eligibilityId,
            String ruleId,
            String ruleVersion,
            long expectedAggregateVersion) {
        public EligibilityBinding {
            IngestionQualityDomainRules.requireUuidV7(eligibilityId);
            if (ruleId == null || !ruleId.matches("^[A-Z][A-Z0-9-]{1,63}$")
                    || ruleVersion == null || !ruleVersion.matches("^[0-9]+\\.[0-9]+\\.[0-9]+$")
                    || expectedAggregateVersion < 1
                    || expectedAggregateVersion > IngestionQualityDomainRules.MAX_SAFE_VERSION) {
                throw invalid();
            }
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
            digest(snapshotIdsDigest);
            digest(snapshotHashesDigest);
            digest(metricResultsDigest);
            watermark(sourceWatermark);
            watermark(dependencyWatermark);
        }
    }

    public record BatchEvidence(
            String sequenceEvidenceDigest,
            int requiredConsecutivePassedBatches,
            long actualConsecutivePassedBatches) {
        public BatchEvidence {
            digest(sequenceEvidenceDigest);
            if (requiredConsecutivePassedBatches < 1
                    || requiredConsecutivePassedBatches > 365
                    || actualConsecutivePassedBatches < 0
                    || actualConsecutivePassedBatches
                        > IngestionQualityDomainRules.MAX_SAFE_VERSION) {
                throw invalid();
            }
        }

        public String sequenceEvidenceVersion() {
            return "QUALITY-RECOVERY-BATCH-SEQUENCE-1.0.0";
        }

        public boolean qualified() {
            return actualConsecutivePassedBatches >= requiredConsecutivePassedBatches;
        }
    }

    public record BackfillEvidence(
            String lastKnownGoodWatermark,
            Instant trustedNow,
            int lookbackDays,
            String backfillStartWatermark,
            String backfillCompletedWatermark,
            String evidenceDigest,
            String status) {
        public BackfillEvidence {
            watermark(lastKnownGoodWatermark);
            watermark(backfillStartWatermark);
            watermark(backfillCompletedWatermark);
            digest(evidenceDigest);
            if (trustedNow == null || trustedNow.getNano() % 1_000 != 0
                    || lookbackDays < 1 || lookbackDays > 3650
                    || (!"succeeded".equals(status) && !"failed".equals(status)
                        && !"unavailable".equals(status))) {
                throw invalid();
            }
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

    private static void digest(String value) {
        IngestionQualityDomainRules.requireSha256(value);
    }

    private static void watermark(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-=]{0,511}$")) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("QUALITY_RECOVERY_READINESS_EVIDENCE_INVALID");
    }
}
