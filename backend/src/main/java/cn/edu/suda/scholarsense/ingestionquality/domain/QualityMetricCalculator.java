package cn.edu.suda.scholarsense.ingestionquality.domain;

import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.Applicability;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.Calculation;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.MetricDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.Operand;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.SourcePolicy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** Deterministic QMDP metric calculator using exact integer rational arithmetic. */
public final class QualityMetricCalculator {
    private static final String CALENDAR_EXACT_PAIR =
            "QMDP-1.0.0/SRC-P0-CALENDAR-001/calendar-exactly-one-current-day-type";

    QualityMetricResult calculate(
            MetricDefinition definition,
            MeasuredQualityInputs inputs) {
        Objects.requireNonNull(definition);
        Objects.requireNonNull(inputs);
        validateDefinition(definition);
        requireExactOperands(definition.calculation(), inputs.operands());

        if (!inputs.applicable()) {
            if (inputs.operands().values().stream().anyMatch(value -> value.signum() != 0)) {
                throw IngestionQualityDomainRules.invalid();
            }
            return result(
                    definition,
                    QualityMetricResultStatus.NOT_APPLICABLE,
                    false,
                    BigInteger.ZERO,
                    BigInteger.ZERO,
                    null);
        }

        BigInteger numerator = operand(definition.calculation().numerator(), inputs.operands());
        BigInteger denominator = operand(definition.calculation().denominator(), inputs.operands());
        if (denominator.signum() == 0) {
            throw new IngestionQualityException(
                    IngestionQualityErrorCode.QUALITY_POLICY_ZERO_DENOMINATOR);
        }
        validateCardinality(definition, numerator, denominator);

        QualityMetricOperator operator = QualityMetricOperator.fromWire(definition.operator());
        BigInteger thresholdNumerator = BigInteger.valueOf(definition.thresholdNumerator());
        BigInteger thresholdDenominator = BigInteger.valueOf(definition.thresholdDenominator());
        boolean passed = QualityMetricResult.compare(
                numerator, denominator, thresholdNumerator, thresholdDenominator, operator);
        BigInteger value = switch (definition.calculation().kind()) {
            case "ratio", "composite-and" ->
                    QualityMetricResult.roundedBasisPoints(numerator, denominator);
            case "count", "duration" -> null;
            default -> throw IngestionQualityDomainRules.invalid();
        };
        return result(
                definition,
                passed ? QualityMetricResultStatus.PASSED : QualityMetricResultStatus.FAILED,
                true,
                numerator,
                denominator,
                value);
    }

    public QualityAssessment assess(
            ExecutableQualityPolicy policy,
            String sourceId,
            Map<String, MeasuredQualityInputs> inputsByFormula) {
        Objects.requireNonNull(policy);
        String assessedSourceId = IngestionQualityDomainRules.requireText(sourceId, 64);
        Map<String, MeasuredQualityInputs> frozenInputs;
        try {
            frozenInputs = Map.copyOf(Objects.requireNonNull(inputsByFormula));
        } catch (RuntimeException invalidInputs) {
            throw IngestionQualityDomainRules.invalid();
        }
        validatePolicySemantics(policy);

        SourcePolicy source = uniqueSource(policy, assessedSourceId);
        List<MetricDefinition> definitions = orderedDefinitions(policy, assessedSourceId);
        Set<String> expectedFormulas = new LinkedHashSet<>();
        definitions.forEach(definition -> {
            if (!expectedFormulas.add(definition.formulaId())) {
                throw IngestionQualityDomainRules.invalid();
            }
        });
        if (!frozenInputs.keySet().equals(expectedFormulas)) {
            throw IngestionQualityDomainRules.invalid();
        }

        List<QualityMetricResult> results = new ArrayList<>(definitions.size());
        int applicableHardGates = 0;
        boolean failed = false;
        for (MetricDefinition definition : definitions) {
            MeasuredQualityInputs inputs = Objects.requireNonNull(
                    frozenInputs.get(definition.formulaId()));
            boolean applicable = deriveApplicability(definition, source, inputs);
            if (inputs.applicable() != applicable) {
                throw IngestionQualityDomainRules.invalid();
            }
            QualityMetricResult result = calculate(definition, inputs);
            results.add(result);
            if (result.applicable() && definition.hardGate()) applicableHardGates++;
            failed |= definition.hardGate()
                    && result.applicable()
                    && result.result() == QualityMetricResultStatus.FAILED;
        }
        if (applicableHardGates < policy.overallResult().minimumApplicableHardGates()) {
            throw IngestionQualityDomainRules.invalid();
        }
        return new QualityAssessment(
                failed ? QualityOverallResult.QUALITY_FAILED
                        : QualityOverallResult.QUALITY_PASSED,
                results);
    }

    /** Exact QMDP common-then-source-gate plan shared by measurement, calculation and hashing. */
    public static List<MetricDefinition> orderedDefinitions(
            ExecutableQualityPolicy policy,
            String sourceId) {
        Objects.requireNonNull(policy);
        validatePolicySemantics(policy);
        SourcePolicy source = uniqueSource(
                policy, IngestionQualityDomainRules.requireText(sourceId, 64));
        Set<String> selected = new HashSet<>(source.applicableCommonMetricIds());
        List<MetricDefinition> definitions = Stream.concat(
                        policy.commonMetrics().stream()
                                .filter(metric -> selected.contains(metric.metricId())),
                        source.sourceGates().stream())
                .toList();
        long selectedCommon = definitions.stream()
                .filter(metric -> metric.definitionKind().equals("common"))
                .count();
        if (selected.size() != source.applicableCommonMetricIds().size()
                || selectedCommon != selected.size()) {
            throw IngestionQualityDomainRules.invalid();
        }
        return definitions;
    }

    private static SourcePolicy uniqueSource(
            ExecutableQualityPolicy policy,
            String sourceId) {
        List<SourcePolicy> matches = policy.sources().stream()
                .filter(candidate -> candidate.sourceId().equals(sourceId))
                .toList();
        if (matches.size() != 1) throw IngestionQualityDomainRules.invalid();
        return matches.getFirst();
    }

    private static boolean deriveApplicability(
            MetricDefinition definition,
            SourcePolicy source,
            MeasuredQualityInputs inputs) {
        Applicability applicability = definition.applicability();
        return switch (applicability.predicateId()) {
            case "always" -> true;
            case "source-in-approved-set" -> applicability.sourceIds().contains(source.sourceId());
            case "source-field-group-present" -> !definition.fieldSet().isEmpty()
                    || source.sourceGates().stream().anyMatch(gate ->
                            gate.metricId().equals(definition.metricId())
                                    && !gate.fieldSet().isEmpty());
            case "overlap-records-present" -> {
                BigInteger overlapRecords = inputs.operands().get("overlap-records");
                if (overlapRecords == null) throw IngestionQualityDomainRules.invalid();
                yield overlapRecords.signum() > 0;
            }
            default -> throw IngestionQualityDomainRules.invalid();
        };
    }

    private static void validatePolicySemantics(ExecutableQualityPolicy policy) {
        var numeric = policy.numericSemantics();
        var overall = policy.overallResult();
        if (!numeric.representation().equals("non-negative-integer-rational")
                || numeric.basisPointScale() != 10_000
                || numeric.valueScale() != 0
                || !numeric.roundingMode().equals("HALF_UP")
                || !numeric.comparisonStage().equals("pre-rounding-cross-multiply")
                || !numeric.zeroDenominator()
                        .equals("evaluation-error/QUALITY_POLICY_ZERO_DENOMINATOR")
                || !overall.operator().equals("AND")
                || overall.minimumApplicableHardGates() < 1
                || !overall.evaluationErrorResult().equals("sealed/evaluation-error")
                || !overall.failedResult().equals("quality-failed")
                || !overall.passedResult().equals("quality-passed")) {
            throw IngestionQualityDomainRules.invalid();
        }
    }

    private static void validateDefinition(MetricDefinition definition) {
        Calculation calculation = definition.calculation();
        QualityMetricUnit unit = QualityMetricUnit.fromWire(definition.unit());
        QualityMetricOperator.fromWire(definition.operator());
        QualityMetricBoundary.fromWire(definition.boundary());
        if (definition.thresholdNumerator() < 0
                || definition.thresholdDenominator() < 1
                || definition.valueScale() != 0
                || !definition.denominatorZeroBehavior().equals("evaluation-error")
                || !definition.roundingMode().equals("HALF_UP")
                || !definition.comparisonStage().equals("pre-rounding-cross-multiply")) {
            throw IngestionQualityDomainRules.invalid();
        }
        boolean shapeMatches = switch (calculation.kind()) {
            case "ratio" -> unit == QualityMetricUnit.BASIS_POINT;
            case "count" -> unit == QualityMetricUnit.COUNT
                    && constantOne(calculation.denominator());
            case "duration" -> unit == QualityMetricUnit.MILLISECOND
                    && constantOne(calculation.denominator());
            case "composite-and" -> unit == QualityMetricUnit.MEMBER_COUNT;
            default -> false;
        };
        if (!shapeMatches) throw IngestionQualityDomainRules.invalid();
    }

    private static boolean constantOne(Operand operand) {
        return operand.operandId() == null && Long.valueOf(1L).equals(operand.constantValue());
    }

    private static void requireExactOperands(
            Calculation calculation,
            Map<String, BigInteger> measured) {
        Set<String> expected = new LinkedHashSet<>();
        if (calculation.numerator().operandId() != null) {
            expected.add(calculation.numerator().operandId());
        }
        if (calculation.denominator().operandId() != null) {
            expected.add(calculation.denominator().operandId());
        }
        if (!measured.keySet().equals(expected)) throw IngestionQualityDomainRules.invalid();
    }

    private static BigInteger operand(Operand operand, Map<String, BigInteger> measured) {
        if (operand.operandId() != null) {
            return Objects.requireNonNull(measured.get(operand.operandId()));
        }
        return BigInteger.valueOf(Objects.requireNonNull(operand.constantValue()));
    }

    private static void validateCardinality(
            MetricDefinition definition,
            BigInteger numerator,
            BigInteger denominator) {
        String kind = definition.calculation().kind();
        if ((kind.equals("ratio")
                        && !definition.formulaId().equals(CALENDAR_EXACT_PAIR)
                        && numerator.compareTo(denominator) > 0)
                || (kind.equals("composite-and") && numerator.compareTo(denominator) > 0)) {
            throw IngestionQualityDomainRules.invalid();
        }
    }

    private static QualityMetricResult result(
            MetricDefinition definition,
            QualityMetricResultStatus status,
            boolean applicable,
            BigInteger numerator,
            BigInteger denominator,
            BigInteger value) {
        return new QualityMetricResult(
                definition.metricId(),
                definition.formulaId(),
                definition.formulaVersion(),
                status,
                applicable,
                numerator,
                denominator,
                value,
                QualityMetricUnit.fromWire(definition.unit()),
                QualityMetricOperator.fromWire(definition.operator()),
                BigInteger.valueOf(definition.thresholdNumerator()),
                BigInteger.valueOf(definition.thresholdDenominator()),
                QualityMetricBoundary.fromWire(definition.boundary()),
                null);
    }
}
