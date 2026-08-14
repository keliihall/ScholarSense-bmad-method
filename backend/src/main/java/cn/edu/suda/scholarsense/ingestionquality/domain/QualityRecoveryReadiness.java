package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Pure fail-closed QRP readiness evaluator. It never changes owner state. */
public final class QualityRecoveryReadiness {
    public static final String QUALITY_GATE_VERSION = "QG-1.0.0";

    public Decision evaluate(Input input) {
        Objects.requireNonNull(input);
        EnumSet<MissingEvidence> missing = EnumSet.noneOf(MissingEvidence.class);
        QualityRecoveryPolicy policy = input.policy;

        if (policy == null || !policy.contractDigest().equals(input.policyDigest)) {
            missing.add(MissingEvidence.VERSION_OR_DIGEST_UNKNOWN);
        }
        if (input.sourceClass == null) {
            missing.add(MissingEvidence.VERSION_OR_DIGEST_UNKNOWN);
        }

        int requiredBatches = 0;
        if (policy != null && input.sourceClass != null) {
            try {
                requiredBatches = policy.ruleFor(input.sourceClass).consecutivePassedBatches();
            } catch (RuntimeException unknownClass) {
                missing.add(MissingEvidence.VERSION_OR_DIGEST_UNKNOWN);
            }
        }
        int actualBatches = RecoveryBatchEvidence.consecutivePassedPublishedCount(
                input.batchEvidence);
        if (requiredBatches < 1 || actualBatches < requiredBatches) {
            missing.add(MissingEvidence.CONSECUTIVE_BATCHES_INSUFFICIENT);
        }

        if (policy == null || input.qualityGate == null
                || !input.qualityGate.qualified(policy)) {
            missing.add(MissingEvidence.QUALITY_GATE_NOT_PASSED);
            if (input.qualityGate == null || input.qualityGate.unknownBinding(policy)) {
                missing.add(MissingEvidence.VERSION_OR_DIGEST_UNKNOWN);
            }
        }

        if (!requiredDependenciesEligible(
                input.requiredMemberSet,
                input.expectedMemberSetDigest,
                input.dependencies)) {
            missing.add(MissingEvidence.REQUIRED_DEPENDENCY_NOT_ELIGIBLE);
        }

        Instant start = null;
        if (policy == null || !input.backfillCompleted
                || input.lastKnownGoodWatermark == null || input.trustedNow == null
                || !microsecond(input.lastKnownGoodWatermark)
                || !microsecond(input.trustedNow)) {
            missing.add(MissingEvidence.BACKFILL_INCOMPLETE);
        } else {
            start = backfillStart(policy, input.lastKnownGoodWatermark, input.trustedNow);
        }

        if (policy == null || input.reconciliation == null
                || !input.reconciliation.qualified(policy)) {
            missing.add(MissingEvidence.RECONCILIATION_MISMATCH);
            if (input.reconciliation != null
                    && !RecoveryReconciliationSummary.SUMMARY_VERSION.equals(
                            input.reconciliation.summaryVersion())) {
                missing.add(MissingEvidence.VERSION_OR_DIGEST_UNKNOWN);
            }
        }

        if (policy == null || input.sample == null
                || input.sample.providerAvailability()
                        != RecoverySampleSummary.ProviderAvailability.AVAILABLE) {
            missing.add(MissingEvidence.SAMPLE_PROVIDER_UNAVAILABLE);
        }
        if (policy == null || input.sample == null || !input.sample.qualified(policy)) {
            if (input.sample != null && policy != null
                    && input.sample.providerAvailability()
                            == RecoverySampleSummary.ProviderAvailability.AVAILABLE
                    && input.sample.mismatchCount()
                            != policy.sampling().expectedMismatchCount()) {
                missing.add(MissingEvidence.SAMPLE_MISMATCH);
            } else if (input.sample != null
                    && input.sample.providerAvailability()
                            == RecoverySampleSummary.ProviderAvailability.AVAILABLE) {
                missing.add(MissingEvidence.SAMPLE_INSUFFICIENT);
            }
            if (input.sample != null
                    && (!RecoverySampleSummary.SUMMARY_VERSION.equals(
                                input.sample.summaryVersion())
                            || !RecoverySampleSummary.APPROVED_PROVIDER_VERSION.equals(
                                    input.sample.providerVersion()))) {
                missing.add(MissingEvidence.VERSION_OR_DIGEST_UNKNOWN);
            }
        }

        return new Decision(missing.isEmpty(), requiredBatches, actualBatches, start, missing);
    }

    public static Instant backfillStart(
            QualityRecoveryPolicy policy,
            Instant lastKnownGoodWatermark,
            Instant trustedNow) {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(lastKnownGoodWatermark);
        Objects.requireNonNull(trustedNow);
        Instant lookbackStart = trustedNow.minus(policy.backfill().lookback());
        return lastKnownGoodWatermark.isAfter(lookbackStart)
                ? lastKnownGoodWatermark : lookbackStart;
    }

    private static boolean microsecond(Instant value) {
        return value.getNano() % 1_000 == 0;
    }

    private static boolean requiredDependenciesEligible(
            RequiredMemberSet expected,
            String expectedMemberSetDigest,
            List<RequiredDependencyEvidence> evidence) {
        if (expected == null
                || expectedMemberSetDigest == null
                || !expectedMemberSetDigest.matches("^sha256:[0-9a-f]{64}$")
                || !expected.memberSetDigest().equals(expectedMemberSetDigest)) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        Set<String> suppliedRequired = new HashSet<>();
        for (RequiredDependencyEvidence value : evidence) {
            if (value == null || !seen.add(value.dependencyId())) return false;
            if (value.required()) suppliedRequired.add(value.dependencyId());
        }
        if (!suppliedRequired.equals(expected.requiredDependencyIds())) return false;
        return evidence.stream()
                .filter(RequiredDependencyEvidence::required)
                .allMatch(RequiredDependencyEvidence::eligible);
    }

    public record Input(
            QualityRecoveryPolicy policy,
            String policyDigest,
            QualityRecoverySourceClass sourceClass,
            List<RecoveryBatchEvidence> batchEvidence,
            QualityGateEvidence qualityGate,
            RequiredMemberSet requiredMemberSet,
            String expectedMemberSetDigest,
            List<RequiredDependencyEvidence> dependencies,
            Instant lastKnownGoodWatermark,
            Instant trustedNow,
            boolean backfillCompleted,
            RecoveryReconciliationSummary reconciliation,
            RecoverySampleSummary sample) {
        public Input {
            batchEvidence = batchEvidence == null ? List.of() : List.copyOf(batchEvidence);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }

    /** Exact required-member fence resolved from the frozen RuleVersion member set. */
    public record RequiredMemberSet(
            String memberSetDigest,
            Set<String> requiredDependencyIds) {
        public RequiredMemberSet {
            if (memberSetDigest == null
                    || !memberSetDigest.matches("^sha256:[0-9a-f]{64}$")) {
                throw invalid();
            }
            TreeSet<String> copy = new TreeSet<>(Objects.requireNonNull(requiredDependencyIds));
            if (copy.stream().anyMatch(value -> value == null
                    || !value.matches("^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$"))) {
                throw invalid();
            }
            requiredDependencyIds = Collections.unmodifiableSet(copy);
        }
    }

    public record Decision(
            boolean readyForD4,
            int requiredConsecutivePassedBatches,
            int actualConsecutivePassedBatches,
            Instant backfillStart,
            Set<MissingEvidence> missingEvidence) {
        public Decision {
            missingEvidence = Collections.unmodifiableSet(
                    missingEvidence.isEmpty()
                            ? EnumSet.noneOf(MissingEvidence.class)
                            : EnumSet.copyOf(missingEvidence));
            if (readyForD4 != missingEvidence.isEmpty()) throw invalid();
        }
    }

    public record QualityGateEvidence(
            String qualityGateVersion,
            String qualityGateDigest,
            String approvedQualityGateDigest,
            EvidenceAvailability availability,
            boolean allRequiredMetricsPassed,
            long actualScaled,
            long thresholdScaled,
            ThresholdOperator operator) {
        public QualityGateEvidence {
            if (actualScaled < 0 || thresholdScaled < 0) throw invalid();
        }

        boolean qualified(QualityRecoveryPolicy policy) {
            return !unknownBinding(policy)
                    && availability == EvidenceAvailability.AVAILABLE
                    && allRequiredMetricsPassed
                    && operator != null
                    && operator.test(actualScaled, thresholdScaled);
        }

        boolean unknownBinding(QualityRecoveryPolicy policy) {
            return policy == null
                    || !policy.qualityGate().version().equals(qualityGateVersion)
                    || qualityGateDigest == null
                    || approvedQualityGateDigest == null
                    || !qualityGateDigest.matches("^sha256:[0-9a-f]{64}$")
                    || !qualityGateDigest.equals(approvedQualityGateDigest);
        }
    }

    public record RequiredDependencyEvidence(
            String dependencyId,
            boolean required,
            DependencyState state,
            EvidenceAvailability availability,
            long dependencyVersion,
            String dependencyDigest) {
        public RequiredDependencyEvidence {
            if (dependencyId == null
                    || !dependencyId.matches("^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$")
                    || dependencyVersion < 1
                    || dependencyDigest == null
                    || !dependencyDigest.matches("^sha256:[0-9a-f]{64}$")) {
                throw invalid();
            }
        }

        boolean eligible() {
            return availability == EvidenceAvailability.AVAILABLE
                    && state == DependencyState.ELIGIBLE;
        }
    }

    public enum EvidenceAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }

    public enum DependencyState { ELIGIBLE, FUSED, RECOVERING, MISSING, UNKNOWN }

    public enum ThresholdOperator {
        GREATER_THAN_OR_EQUAL {
            boolean test(long actual, long threshold) { return actual >= threshold; }
        },
        LESS_THAN_OR_EQUAL {
            boolean test(long actual, long threshold) { return actual <= threshold; }
        },
        EQUAL {
            boolean test(long actual, long threshold) { return actual == threshold; }
        };

        abstract boolean test(long actual, long threshold);
    }

    public enum MissingEvidence {
        QUALITY_GATE_NOT_PASSED,
        CONSECUTIVE_BATCHES_INSUFFICIENT,
        BACKFILL_INCOMPLETE,
        RECONCILIATION_MISMATCH,
        SAMPLE_INSUFFICIENT,
        SAMPLE_MISMATCH,
        SAMPLE_PROVIDER_UNAVAILABLE,
        REQUIRED_DEPENDENCY_NOT_ELIGIBLE,
        VERSION_OR_DIGEST_UNKNOWN
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("QUALITY_RECOVERY_READINESS_INVALID");
    }
}
