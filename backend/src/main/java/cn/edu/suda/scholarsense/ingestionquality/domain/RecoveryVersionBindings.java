package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Complete immutable version and digest fence used by recovery evidence and previews.
 *
 * <p>The list is deliberately closed: accepting a partial or unknown authority would make an old
 * preview appear current after one of its governing contracts changed.
 */
public record RecoveryVersionBindings(
        List<VersionDigestBinding> versions,
        long expectedEligibilityVersion,
        long expectedEpisodeVersion,
        long expectedTaskVersion,
        long episodeGeneration,
        String ruleVersionsDigest,
        String memberSetDigest,
        String watermarksDigest) {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public RecoveryVersionBindings {
        List<VersionDigestBinding> copy = List.copyOf(Objects.requireNonNull(versions)).stream()
                .sorted((left, right) -> left.authority().compareTo(right.authority()))
                .toList();
        Map<Authority, VersionDigestBinding> byAuthority = new EnumMap<>(Authority.class);
        for (VersionDigestBinding binding : copy) {
            if (binding == null || byAuthority.put(binding.authority(), binding) != null) {
                throw invalid();
            }
        }
        if (copy.size() != Authority.values().length
                || !byAuthority.keySet().containsAll(Arrays.asList(Authority.values()))) {
            throw invalid();
        }
        versions = copy;
        expectedEligibilityVersion = safePositive(expectedEligibilityVersion);
        expectedEpisodeVersion = safePositive(expectedEpisodeVersion);
        expectedTaskVersion = safePositive(expectedTaskVersion);
        episodeGeneration = safePositive(episodeGeneration);
        ruleVersionsDigest = digest(ruleVersionsDigest);
        memberSetDigest = digest(memberSetDigest);
        watermarksDigest = digest(watermarksDigest);
    }

    public VersionDigestBinding binding(Authority authority) {
        Objects.requireNonNull(authority);
        return versions.stream()
                .filter(candidate -> candidate.authority() == authority)
                .findFirst()
                .orElseThrow(RecoveryVersionBindings::invalid);
    }

    /**
     * Policy values resolved from the exact digest-bound QRP. Its private constructor prevents a
     * caller from attaching invented thresholds to an approved policy digest.
     */
    public static final class ResolvedPolicyEvidence {
        private final String policyVersion;
        private final String policyDigest;
        private final QualityRecoverySourceClass sourceClass;
        private final int requiredConsecutivePassedBatches;
        private final Duration observationDuration;
        private final long lookbackDays;
        private final String reconciliationCoverage;
        private final long reconciliationExpectedMismatchCount;
        private final boolean sampleStratified;
        private final long minimumSampleSubjectWindows;
        private final boolean allIfPopulationFewer;
        private final long sampleExpectedMismatchCount;

        private ResolvedPolicyEvidence(
                String policyVersion,
                String policyDigest,
                QualityRecoverySourceClass sourceClass,
                int requiredConsecutivePassedBatches,
                Duration observationDuration,
                long lookbackDays,
                String reconciliationCoverage,
                long reconciliationExpectedMismatchCount,
                boolean sampleStratified,
                long minimumSampleSubjectWindows,
                boolean allIfPopulationFewer,
                long sampleExpectedMismatchCount) {
            this.policyVersion = policyVersion;
            this.policyDigest = policyDigest;
            this.sourceClass = sourceClass;
            this.requiredConsecutivePassedBatches = requiredConsecutivePassedBatches;
            this.observationDuration = observationDuration;
            this.lookbackDays = lookbackDays;
            this.reconciliationCoverage = reconciliationCoverage;
            this.reconciliationExpectedMismatchCount = reconciliationExpectedMismatchCount;
            this.sampleStratified = sampleStratified;
            this.minimumSampleSubjectWindows = minimumSampleSubjectWindows;
            this.allIfPopulationFewer = allIfPopulationFewer;
            this.sampleExpectedMismatchCount = sampleExpectedMismatchCount;
        }

        public static ResolvedPolicyEvidence from(
                QualityRecoveryPolicy policy,
                QualityRecoverySourceClass sourceClass,
                RecoveryVersionBindings bindings) {
            QualityRecoveryPolicy exactPolicy = Objects.requireNonNull(policy);
            QualityRecoverySourceClass exactSourceClass = Objects.requireNonNull(sourceClass);
            RecoveryVersionBindings exactBindings = Objects.requireNonNull(bindings);
            VersionDigestBinding qrp = exactBindings.binding(Authority.QUALITY_RECOVERY_POLICY);
            if (!exactPolicy.policyVersion().equals(qrp.version())
                    || !exactPolicy.contractDigest().equals(qrp.digest())) {
                throw invalid();
            }
            QualityRecoveryPolicy.SourceClassRule sourceRule =
                    exactPolicy.ruleFor(exactSourceClass);
            Duration lookback = exactPolicy.backfill().lookback();
            long days = lookback.toDays();
            if (days < 1 || !Duration.ofDays(days).equals(lookback)) throw invalid();
            QualityRecoveryPolicy.Sampling sampling = exactPolicy.sampling();
            return new ResolvedPolicyEvidence(
                    exactPolicy.policyVersion(), exactPolicy.contractDigest(), exactSourceClass,
                    sourceRule.consecutivePassedBatches(), sourceRule.observationDuration(), days,
                    exactPolicy.reconciliation().coverage(),
                    exactPolicy.reconciliation().expectedMismatchCount(),
                    sampling.stratified(), sampling.minimumSubjectWindows(),
                    sampling.allIfPopulationFewer(), sampling.expectedMismatchCount());
        }

        public boolean matches(
                RecoveryVersionBindings bindings,
                QualityRecoverySourceClass currentSourceClass) {
            if (sourceClass != currentSourceClass || bindings == null) return false;
            VersionDigestBinding qrp = bindings.binding(Authority.QUALITY_RECOVERY_POLICY);
            return policyVersion.equals(qrp.version()) && policyDigest.equals(qrp.digest());
        }

        public String policyVersion() {
            return policyVersion;
        }

        public String policyDigest() {
            return policyDigest;
        }

        public QualityRecoverySourceClass sourceClass() {
            return sourceClass;
        }

        public int requiredConsecutivePassedBatches() {
            return requiredConsecutivePassedBatches;
        }

        public Duration observationDuration() {
            return observationDuration;
        }

        public long lookbackDays() {
            return lookbackDays;
        }

        public String reconciliationCoverage() {
            return reconciliationCoverage;
        }

        public long reconciliationExpectedMismatchCount() {
            return reconciliationExpectedMismatchCount;
        }

        public boolean sampleStratified() {
            return sampleStratified;
        }

        public long minimumSampleSubjectWindows() {
            return minimumSampleSubjectWindows;
        }

        public boolean allIfPopulationFewer() {
            return allIfPopulationFewer;
        }

        public long sampleExpectedMismatchCount() {
            return sampleExpectedMismatchCount;
        }

        @Override
        public boolean equals(Object candidate) {
            if (this == candidate) return true;
            if (!(candidate instanceof ResolvedPolicyEvidence other)) return false;
            return requiredConsecutivePassedBatches == other.requiredConsecutivePassedBatches
                    && lookbackDays == other.lookbackDays
                    && reconciliationExpectedMismatchCount
                            == other.reconciliationExpectedMismatchCount
                    && sampleStratified == other.sampleStratified
                    && minimumSampleSubjectWindows == other.minimumSampleSubjectWindows
                    && allIfPopulationFewer == other.allIfPopulationFewer
                    && sampleExpectedMismatchCount == other.sampleExpectedMismatchCount
                    && policyVersion.equals(other.policyVersion)
                    && policyDigest.equals(other.policyDigest)
                    && sourceClass == other.sourceClass
                    && observationDuration.equals(other.observationDuration)
                    && reconciliationCoverage.equals(other.reconciliationCoverage);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    policyVersion, policyDigest, sourceClass,
                    requiredConsecutivePassedBatches, observationDuration, lookbackDays,
                    reconciliationCoverage, reconciliationExpectedMismatchCount,
                    sampleStratified, minimumSampleSubjectWindows, allIfPopulationFewer,
                    sampleExpectedMismatchCount);
        }
    }

    public enum Authority {
        QUALITY_RECOVERY_POLICY("QRP-1.0.0"),
        HIGH_RISK_ACTION_POLICY("HRAP-1.0.0"),
        HIGH_RISK_ACTION_MATRIX("HRAM-1.0.0"),
        ROLE_FIELD_POLICY("RFP-1.0.0"),
        DATA_CONTRACT_CATALOG("DCC-1.1.0"),
        QUALITY_GATE("QG-1.0.0"),
        QUALITY_METRIC_DECISION_PROFILE("QMDP-1.0.0"),
        QUALITY_SNAPSHOT_HASH_PROFILE("QSHM-1.0.0"),
        RULE_DEPENDENCY_REGISTRY("RULE-DEPENDENCY-REGISTRY-1.0.0"),
        RULE_CATALOG("RC-1.0.0"),
        SOURCE_SCHEMA(null),
        DEPENDENCY(null);

        private final String exactVersion;

        Authority(String exactVersion) {
            this.exactVersion = exactVersion;
        }
    }

    public record VersionDigestBinding(Authority authority, String version, String digest) {
        private static final Pattern CONTROLLED_VERSION = Pattern.compile(
                "^[A-Z][A-Z0-9.-]*-[0-9]+\\.[0-9]+\\.[0-9]+$");
        private static final Pattern POSITIVE_DECIMAL = Pattern.compile("^[1-9][0-9]{0,15}$");

        public VersionDigestBinding {
            authority = Objects.requireNonNull(authority);
            if (version == null) throw invalid();
            if (authority.exactVersion != null && !authority.exactVersion.equals(version)) {
                throw invalid();
            }
            if (authority == Authority.SOURCE_SCHEMA
                    && !CONTROLLED_VERSION.matcher(version).matches()) {
                throw invalid();
            }
            if (authority == Authority.DEPENDENCY
                    && !POSITIVE_DECIMAL.matcher(version).matches()) {
                throw invalid();
            }
            digest = RecoveryVersionBindings.digest(digest);
        }
    }

    static String digest(String value) {
        if (value == null || !value.matches("^sha256:[0-9a-f]{64}$")) throw invalid();
        return value;
    }

    static long safePositive(long value) {
        if (value < 1 || value > MAX_SAFE_INTEGER) throw invalid();
        return value;
    }

    static long safeNonNegative(long value) {
        if (value < 0 || value > MAX_SAFE_INTEGER) throw invalid();
        return value;
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("QUALITY_RECOVERY_VERSION_BINDINGS_INVALID");
    }
}
