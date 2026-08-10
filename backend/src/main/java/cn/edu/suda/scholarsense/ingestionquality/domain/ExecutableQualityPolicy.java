package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, ordered runtime projection of QMDP-1.0.0.
 *
 * <p>The order of {@link #commonMetrics()} and {@link #sources()} is contract material. Callers
 * must therefore consume these lists as supplied instead of sorting them.
 */
public record ExecutableQualityPolicy(
        String profileVersion,
        String decisionId,
        String authorityRef,
        String approvalRef,
        String approvedBy,
        Instant approvedAt,
        Instant effectiveAt,
        String owner,
        String evidenceRef,
        String status,
        Canonicalization canonicalization,
        NumericSemantics numericSemantics,
        WindowSemantics windowSemantics,
        FreshnessManifestBinding freshnessManifestBinding,
        ControlledInputs controlledInputs,
        List<MetricDefinition> commonMetrics,
        List<SourcePolicy> sources,
        List<NonMetricConstraint> nonMetricConstraints,
        OverallResult overallResult) {

    public ExecutableQualityPolicy {
        profileVersion = required(profileVersion);
        decisionId = required(decisionId);
        authorityRef = required(authorityRef);
        approvalRef = required(approvalRef);
        approvedBy = required(approvedBy);
        Objects.requireNonNull(approvedAt);
        Objects.requireNonNull(effectiveAt);
        owner = required(owner);
        evidenceRef = required(evidenceRef);
        status = required(status);
        Objects.requireNonNull(canonicalization);
        Objects.requireNonNull(numericSemantics);
        Objects.requireNonNull(windowSemantics);
        Objects.requireNonNull(freshnessManifestBinding);
        Objects.requireNonNull(controlledInputs);
        commonMetrics = List.copyOf(commonMetrics);
        sources = List.copyOf(sources);
        nonMetricConstraints = List.copyOf(nonMetricConstraints);
        Objects.requireNonNull(overallResult);
    }

    public record Canonicalization(
            String profile,
            String encoding,
            String objectKeyOrder,
            String duplicateKeys,
            String binaryFloat,
            String time,
            String digestAlgorithm,
            String digestPrefix) {
        public Canonicalization {
            profile = required(profile);
            encoding = required(encoding);
            objectKeyOrder = required(objectKeyOrder);
            duplicateKeys = required(duplicateKeys);
            binaryFloat = required(binaryFloat);
            time = required(time);
            digestAlgorithm = required(digestAlgorithm);
            digestPrefix = required(digestPrefix);
        }
    }

    public record NumericSemantics(
            String representation,
            long basisPointScale,
            int valueScale,
            String roundingMode,
            String comparisonStage,
            String zeroDenominator) {
        public NumericSemantics {
            representation = required(representation);
            roundingMode = required(roundingMode);
            comparisonStage = required(comparisonStage);
            zeroDenominator = required(zeroDenominator);
        }
    }

    public record WindowSemantics(
            String interval,
            String storageTimezone,
            String scheduleTimezone,
            long freshnessWindowHours,
            String cutoffBoundary) {
        public WindowSemantics {
            interval = required(interval);
            storageTimezone = required(storageTimezone);
            scheduleTimezone = required(scheduleTimezone);
            cutoffBoundary = required(cutoffBoundary);
        }
    }

    public record FreshnessManifestBinding(
            String sourceOccurredAt,
            String scheduledDueAt,
            String receivedAt,
            String laneId) {
        public FreshnessManifestBinding {
            sourceOccurredAt = required(sourceOccurredAt);
            scheduledDueAt = required(scheduledDueAt);
            receivedAt = required(receivedAt);
            laneId = required(laneId);
        }
    }

    public record ControlledInputs(DigestBinding dataCatalog, DigestBinding qualityGate) {
        public ControlledInputs {
            Objects.requireNonNull(dataCatalog);
            Objects.requireNonNull(qualityGate);
        }
    }

    /** Raw digests are normalized to their {@code sha256:} form by the controlled loader. */
    public record DigestBinding(
            String path,
            String version,
            String rawDigest,
            String canonicalDigest) {
        public DigestBinding {
            path = required(path);
            version = required(version);
            rawDigest = required(rawDigest);
            canonicalDigest = required(canonicalDigest);
        }
    }

    public record MetricDefinition(
            String definitionKind,
            String metricId,
            String sourceId,
            String gateId,
            String formulaId,
            String formulaVersion,
            String category,
            Calculation calculation,
            String unit,
            String operator,
            String boundary,
            long thresholdNumerator,
            long thresholdDenominator,
            String denominatorZeroBehavior,
            int valueScale,
            String roundingMode,
            String comparisonStage,
            Applicability applicability,
            List<String> fieldSet,
            String owner,
            String approvalRef,
            Instant effectiveAt,
            String evidenceRef,
            boolean hardGate) {
        public MetricDefinition {
            definitionKind = required(definitionKind);
            metricId = required(metricId);
            formulaId = required(formulaId);
            formulaVersion = required(formulaVersion);
            category = required(category);
            Objects.requireNonNull(calculation);
            unit = required(unit);
            operator = required(operator);
            boundary = required(boundary);
            denominatorZeroBehavior = required(denominatorZeroBehavior);
            roundingMode = required(roundingMode);
            comparisonStage = required(comparisonStage);
            Objects.requireNonNull(applicability);
            fieldSet = List.copyOf(fieldSet);
            owner = required(owner);
            approvalRef = required(approvalRef);
            Objects.requireNonNull(effectiveAt);
            evidenceRef = required(evidenceRef);
        }
    }

    public record Calculation(String kind, Operand numerator, Operand denominator) {
        public Calculation {
            kind = required(kind);
            Objects.requireNonNull(numerator);
            Objects.requireNonNull(denominator);
        }
    }

    /** Exactly one of {@link #operandId()} and {@link #constantValue()} is populated. */
    public record Operand(String kind, String operandId, Long constantValue) {
        public Operand {
            kind = required(kind);
            if ((operandId == null) == (constantValue == null)) {
                throw new IllegalArgumentException("quality operand must be measured or constant");
            }
            if (operandId != null) operandId = required(operandId);
        }

        public static Operand measured(String operandId) {
            return new Operand("measured", operandId, null);
        }

        public static Operand constant(long value) {
            return new Operand("constant", null, value);
        }
    }

    public record Applicability(String predicateId, List<String> sourceIds) {
        public Applicability {
            predicateId = required(predicateId);
            sourceIds = List.copyOf(sourceIds);
        }
    }

    public record SourcePolicy(
            String sourceId,
            String owner,
            DigestBinding schemaBinding,
            List<String> applicableCommonMetricIds,
            List<MetricDefinition> sourceGates,
            List<FreshnessLane> freshnessLanes) {
        public SourcePolicy {
            sourceId = required(sourceId);
            owner = required(owner);
            Objects.requireNonNull(schemaBinding);
            applicableCommonMetricIds = List.copyOf(applicableCommonMetricIds);
            sourceGates = List.copyOf(sourceGates);
            freshnessLanes = List.copyOf(freshnessLanes);
        }
    }

    public record FreshnessLane(
            String laneId,
            String unit,
            String timezone,
            boolean inclusive,
            boolean allowNoActivity,
            FreshnessRule rule) {
        public FreshnessLane {
            laneId = required(laneId);
            unit = required(unit);
            timezone = required(timezone);
            Objects.requireNonNull(rule);
        }
    }

    public sealed interface FreshnessRule
            permits DurationRule, LocalCutoffRule, AdvanceHorizonRule {
        String kind();
        String laterField();
        String earlierField();
    }

    public record DurationRule(
            String kind,
            String laterField,
            String earlierField,
            long maxDurationMilliseconds) implements FreshnessRule {
        public DurationRule {
            kind = required(kind);
            laterField = required(laterField);
            earlierField = required(earlierField);
        }
    }

    public record LocalCutoffRule(
            String kind,
            String laterField,
            String earlierField,
            String dueLocalTime,
            int dayOffset) implements FreshnessRule {
        public LocalCutoffRule {
            kind = required(kind);
            laterField = required(laterField);
            earlierField = required(earlierField);
            dueLocalTime = required(dueLocalTime);
        }
    }

    public record AdvanceHorizonRule(
            String kind,
            String laterField,
            String earlierField,
            long minimumLeadMilliseconds) implements FreshnessRule {
        public AdvanceHorizonRule {
            kind = required(kind);
            laterField = required(laterField);
            earlierField = required(earlierField);
        }
    }

    public record NonMetricConstraint(
            String constraintId,
            String kind,
            String sourceId,
            String deniedConsumerPurpose) {
        public NonMetricConstraint {
            constraintId = required(constraintId);
            kind = required(kind);
            sourceId = required(sourceId);
            deniedConsumerPurpose = required(deniedConsumerPurpose);
        }
    }

    public record OverallResult(
            String operator,
            int minimumApplicableHardGates,
            String evaluationErrorResult,
            String failedResult,
            String passedResult) {
        public OverallResult {
            operator = required(operator);
            evaluationErrorResult = required(evaluationErrorResult);
            failedResult = required(failedResult);
            passedResult = required(passedResult);
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("quality policy value is required");
        }
        return value;
    }
}
