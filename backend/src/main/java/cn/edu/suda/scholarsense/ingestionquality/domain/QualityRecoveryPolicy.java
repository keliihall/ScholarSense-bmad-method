package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, executable projection of the exact approved QRP-1.0.0 contract. */
public record QualityRecoveryPolicy(
        String policyVersion,
        String contractDigest,
        List<String> authority,
        SourceClassBinding sourceClassBinding,
        Map<QualityRecoverySourceClass, SourceClassRule> sourceClasses,
        BatchQualification batchQualification,
        Backfill backfill,
        Reconciliation reconciliation,
        Sampling sampling,
        QualityGate qualityGate,
        ImpactPreview impactPreview,
        FailureFallback failureFallback,
        String runtimeEvidenceClaim) {

    public static final String VERSION = "QRP-1.0.0";
    public static final String RAW_DIGEST =
            "sha256:195a22553b13ac35da5923e704ef8385f85d17574b8a753cc1afc16c2055f366";

    private static final List<String> AUTHORITY = List.of(
            "DEC-012", "G-03", "AD-5", "AD-23", "DCC-1.1.0", "QG-1.0.0",
            "RC-1.0.0");
    private static final Map<QualityRecoverySourceClass, SourceClassRule> SOURCE_RULES = Map.of(
            QualityRecoverySourceClass.STREAMING,
            new SourceClassRule(3, Duration.ofMinutes(60)),
            QualityRecoverySourceClass.DAILY_BATCH,
            new SourceClassRule(2, Duration.ofHours(24)));

    public QualityRecoveryPolicy {
        requireEquals(VERSION, policyVersion);
        requireEquals(RAW_DIGEST, contractDigest);
        authority = List.copyOf(Objects.requireNonNull(authority));
        requireEquals(AUTHORITY, authority);
        requireEquals(
                new SourceClassBinding(
                        "ingestion-quality",
                        "approved-explicit-source-class-registry",
                        "reject"),
                sourceClassBinding);
        sourceClasses = Map.copyOf(Objects.requireNonNull(sourceClasses));
        requireEquals(SOURCE_RULES, sourceClasses);
        requireEquals(
                new BatchQualification(
                        "assessed-passed-then-exact-published",
                        List.of("sourceId", "sourceVersionOrdinal", "lineageRevision"),
                        Set.of("eventId", "occurredAt", "watermark", "batchId"),
                        true),
                batchQualification);
        requireEquals(
                new Backfill(
                        Duration.ofDays(90),
                        "max(lastKnownGoodWatermark,trustedNow-minus-P90D)",
                        true),
                backfill);
        requireEquals(new Reconciliation("full", 0), reconciliation);
        requireEquals(
                new Sampling("subject-window", true, 100, true, 0, true),
                sampling);
        requireEquals(
                new QualityGate(
                        "QG-1.0.0",
                        true,
                        "use-approved-inclusive-operator",
                        "all-current-required-members-eligible"),
                qualityGate);
        requireEquals(
                new ImpactPreview(
                        true,
                        List.of(
                                "already-expired-history-only",
                                "currently-potentially-actionable",
                                "expected-to-expire-before-observation-completes"),
                        "2.5c",
                        false),
                impactPreview);
        requireEquals(
                new FailureFallback("remain-fused", Duration.ofHours(24), false),
                failureFallback);
        requireEquals("contract-only", runtimeEvidenceClaim);
    }

    public SourceClassRule ruleFor(QualityRecoverySourceClass sourceClass) {
        SourceClassRule rule = sourceClasses.get(Objects.requireNonNull(sourceClass));
        if (rule == null) throw invalid();
        return rule;
    }

    public record SourceClassBinding(String owner, String source, String unknown) {
        public SourceClassBinding {
            requireText(owner);
            requireText(source);
            requireText(unknown);
        }
    }

    public record SourceClassRule(
            int consecutivePassedBatches,
            Duration observationDuration) {
        public SourceClassRule {
            if (consecutivePassedBatches < 1
                    || observationDuration == null
                    || observationDuration.isZero()
                    || observationDuration.isNegative()) {
                throw invalid();
            }
        }
    }

    public record BatchQualification(
            String acceptedPair,
            List<String> sequenceKey,
            Set<String> forbiddenOrderingFields,
            boolean inclusive) {
        public BatchQualification {
            requireText(acceptedPair);
            sequenceKey = List.copyOf(Objects.requireNonNull(sequenceKey));
            forbiddenOrderingFields = Set.copyOf(
                    Objects.requireNonNull(forbiddenOrderingFields));
        }
    }

    public record Backfill(
            Duration lookback,
            String startExpression,
            boolean trustedNowRequired) {
        public Backfill {
            if (lookback == null || lookback.isZero() || lookback.isNegative()) throw invalid();
            requireText(startExpression);
        }
    }

    public record Reconciliation(String coverage, int expectedMismatchCount) {
        public Reconciliation {
            requireText(coverage);
            if (expectedMismatchCount < 0) throw invalid();
        }
    }

    public record Sampling(
            String unit,
            boolean stratified,
            int minimumSubjectWindows,
            boolean allIfPopulationFewer,
            int expectedMismatchCount,
            boolean selectionSeedRequired) {
        public Sampling {
            requireText(unit);
            if (minimumSubjectWindows < 1 || expectedMismatchCount < 0) throw invalid();
        }
    }

    public record QualityGate(
            String version,
            boolean exactDigestRequired,
            String thresholdComparison,
            String requiredDependencies) {
        public QualityGate {
            requireText(version);
            requireText(thresholdComparison);
            requireText(requiredDependencies);
        }
    }

    public record ImpactPreview(
            boolean trustedMicrosecondTime,
            List<String> categories,
            String finalActionabilityOwnerStory,
            boolean authorizesExecution) {
        public ImpactPreview {
            categories = List.copyOf(Objects.requireNonNull(categories));
            requireText(finalActionabilityOwnerStory);
        }
    }

    public record FailureFallback(
            String qualificationFailure,
            Duration observationRelapseWithin,
            boolean createsBusinessObjects) {
        public FailureFallback {
            requireText(qualificationFailure);
            if (observationRelapseWithin == null
                    || observationRelapseWithin.isZero()
                    || observationRelapseWithin.isNegative()) {
                throw invalid();
            }
        }
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw invalid();
    }

    private static void requireEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("QUALITY_RECOVERY_POLICY_INVALID");
    }
}
