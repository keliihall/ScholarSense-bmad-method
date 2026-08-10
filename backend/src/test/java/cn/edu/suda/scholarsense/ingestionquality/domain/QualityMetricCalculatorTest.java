package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenExecutableQualityPolicyLoader;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.MetricDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.Operand;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class QualityMetricCalculatorTest {
    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
    private static final Path VECTORS = REPOSITORY.resolve(
            "contracts/ingestion-quality/batch-quality/fixtures/valid/"
                    + "quality-metric-vectors-1.0.0.json");
    private static final ExecutableQualityPolicy POLICY =
            new FrozenExecutableQualityPolicyLoader(REPOSITORY).loadVerified().policy();
    private static final QualityMetricCalculator CALCULATOR = new QualityMetricCalculator();

    @ParameterizedTest(name = "{0}")
    @MethodSource("controlledVectors")
    void matchesAllTwentySevenControlledJavaVectors(VectorCase vector) {
        MetricDefinition definition = definition(vector);
        MeasuredQualityInputs inputs = inputs(
                definition, vector.applicable(), vector.numerator(), vector.denominator());

        Actual actual;
        try {
            QualityMetricResult result = CALCULATOR.calculate(definition, inputs);
            actual = new Actual(
                    result.result().wireValue(), result.valueBasisPoints(), result.reasonCode());
            assertEquals(definition.metricId(), result.metricId());
            assertEquals(definition.formulaId(), result.formulaId());
            assertEquals(definition.formulaVersion(), result.formulaVersion());
            assertEquals(vector.applicable(), result.applicable());
            assertEquals(vector.numerator(), result.numerator());
            assertEquals(vector.denominator(), result.denominator());
            assertEquals(definition.unit(), result.unit().wireValue());
            assertEquals(definition.operator(), result.operator().wireValue());
            assertEquals(BigInteger.valueOf(definition.thresholdNumerator()),
                    result.thresholdNumerator());
            assertEquals(BigInteger.valueOf(definition.thresholdDenominator()),
                    result.thresholdDenominator());
            assertEquals(definition.boundary(), result.boundary().wireValue());
        } catch (IngestionQualityException technical) {
            actual = new Actual("evaluation-error", null, technical.code());
        }

        assertEquals(vector.expected(), actual);
    }

    @Test
    void controlledFixtureContainsExactlyTwentySevenCasesAndAllFourCalculationKinds()
            throws IOException {
        List<VectorCase> vectors = controlledVectors().toList();

        assertEquals(27, vectors.size());
        assertEquals(
                List.of("composite-and", "count", "duration", "ratio"),
                Stream.concat(
                                vectors.stream().map(QualityMetricCalculatorTest::definition),
                                Stream.of(common("SOURCE_CONTINUITY_GATE")))
                        .map(definition -> definition.calculation().kind())
                        .distinct()
                        .sorted()
                        .toList());
    }

    @Test
    void compositeAndUsesClosedMemberOperandsAndHalfUpBasisPoints() {
        MetricDefinition composite = common("SOURCE_CONTINUITY_GATE");

        QualityMetricResult passed = CALCULATOR.calculate(
                composite, inputs(composite, true, bi(3), bi(3)));
        QualityMetricResult failed = CALCULATOR.calculate(
                composite, inputs(composite, true, bi(2), bi(3)));

        assertEquals("passed", passed.result().wireValue());
        assertEquals(bi(10_000), passed.valueBasisPoints());
        assertEquals("failed", failed.result().wireValue());
        assertEquals(bi(6_667), failed.valueBasisPoints());
        assertThrows(IngestionQualityException.class,
                () -> CALCULATOR.calculate(composite, inputs(composite, true, bi(4), bi(3))));
        IngestionQualityException zero = assertThrows(
                IngestionQualityException.class,
                () -> CALCULATOR.calculate(
                        composite, inputs(composite, true, BigInteger.ZERO, BigInteger.ZERO)));
        assertEquals("QUALITY_POLICY_ZERO_DENOMINATOR", zero.code());
    }

    @Test
    void comparesBeforeRoundingAndAllowsOnlyTheApprovedCalendarNonSubsetRatio() {
        MetricDefinition ratio = common("PRIMARY_KEY_COMPLETENESS_BP");
        QualityMetricResult roundedButFailed = CALCULATOR.calculate(
                ratio, inputs(ratio, true, bi(19_899), bi(20_000)));
        MetricDefinition calendar = POLICY.sources().stream()
                .filter(source -> source.sourceId().equals("SRC-P0-CALENDAR-001"))
                .flatMap(source -> source.sourceGates().stream())
                .filter(definition -> definition.gateId()
                        .equals("calendar-exactly-one-current-day-type"))
                .findFirst().orElseThrow();
        QualityMetricResult duplicateCalendarDay = CALCULATOR.calculate(
                calendar, inputs(calendar, true, bi(2), bi(1)));

        assertEquals(bi(9_950), roundedButFailed.valueBasisPoints());
        assertEquals(QualityMetricResultStatus.FAILED, roundedButFailed.result());
        assertEquals(bi(20_000), duplicateCalendarDay.valueBasisPoints());
        assertEquals(QualityMetricResultStatus.FAILED, duplicateCalendarDay.result());
        assertThrows(IngestionQualityException.class,
                () -> CALCULATOR.calculate(ratio, inputs(ratio, true, bi(2), bi(1))));
    }

    @Test
    void preRoundingComparisonUsesBigIntegerWithoutLongMultiplicationOverflow() {
        MetricDefinition ratio = common("PRIMARY_KEY_COMPLETENESS_BP");
        BigInteger factor = BigInteger.TEN.pow(12);

        QualityMetricResult result = CALCULATOR.calculate(
                ratio,
                inputs(ratio, true, factor.multiply(bi(995)), factor.multiply(bi(1_000))));

        assertEquals("passed", result.result().wireValue());
        assertEquals(bi(9_950), result.valueBasisPoints());
        assertEquals(factor.multiply(bi(995)), result.numerator());
        assertEquals(factor.multiply(bi(1_000)), result.denominator());
    }

    @Test
    void rejectsAnythingOtherThanTheExactMeasuredOperandSet() {
        MetricDefinition ratio = common("PRIMARY_KEY_COMPLETENESS_BP");
        Map<String, BigInteger> missing = Map.of(
                ratio.calculation().numerator().operandId(), bi(995));
        Map<String, BigInteger> extra = new LinkedHashMap<>(
                inputs(ratio, true, bi(995), bi(1_000)).operands());
        extra.put("raw-count", BigInteger.ONE);

        assertThrows(IngestionQualityException.class,
                () -> CALCULATOR.calculate(ratio, new MeasuredQualityInputs(true, missing)));
        assertThrows(IngestionQualityException.class,
                () -> CALCULATOR.calculate(ratio, new MeasuredQualityInputs(true, extra)));
    }

    @Test
    void assessmentPreservesQmdpCommonThenSourceGateOrderAndComputesOverall() {
        String sourceId = "SRC-P0-STUDENT-001";
        Map<String, MeasuredQualityInputs> passing = passingInputs(sourceId);

        QualityAssessment passed = CALCULATOR.assess(POLICY, sourceId, passing);
        Map<String, MeasuredQualityInputs> failing = new LinkedHashMap<>(passing);
        MetricDefinition primaryKey = common("PRIMARY_KEY_COMPLETENESS_BP");
        failing.put(primaryKey.formulaId(),
                inputs(primaryKey, true, bi(9_949), bi(10_000)));
        QualityAssessment failed = CALCULATOR.assess(POLICY, sourceId, failing);

        assertEquals("quality-passed", passed.overallResult().wireValue());
        assertEquals("quality-failed", failed.overallResult().wireValue());
        assertEquals(List.of(
                        "QMDP-1.0.0/PRIMARY_KEY_COMPLETENESS_BP",
                        "QMDP-1.0.0/P0_SUBJECT_MAPPING_BP",
                        "QMDP-1.0.0/REQUIRED_FIELD_VALIDITY_BP",
                        "QMDP-1.0.0/VALID_RECORD_RATE_BP",
                        "QMDP-1.0.0/CORE_FIELD_COVERAGE_BP",
                        "QMDP-1.0.0/FRESHNESS_WITHIN_SLO_BP",
                        "QMDP-1.0.0/UNRESOLVED_INTERVAL_CONFLICT_COUNT",
                        "QMDP-1.0.0/DUPLICATE_BUSINESS_KEY_COUNT",
                        "QMDP-1.0.0/VERSION_REGRESSION_COUNT",
                        "QMDP-1.0.0/SOURCE_CONTINUITY_GATE",
                        "QMDP-1.0.0/SCHEMA_ALLOWLIST_COMPATIBILITY_BP",
                        "QMDP-1.0.0/FORBIDDEN_FIELD_COUNT",
                        "QMDP-1.0.0/SRC-P0-STUDENT-001/student-core-field-group",
                        "QMDP-1.0.0/SRC-P0-STUDENT-001/student-effective-interval-conflict"),
                passed.metricResults().stream().map(QualityMetricResult::formulaId).toList());
    }

    @Test
    void allSeventeenSourcesHaveTheExactClosedFormulaCountAndIgnoreInputMapOrder() {
        List<Integer> expectedCounts = List.of(
                14, 13, 14, 14, 14, 14, 13, 14, 11, 14, 12, 12, 12, 12, 13, 12, 11);

        for (int index = 0; index < POLICY.sources().size(); index++) {
            String sourceId = POLICY.sources().get(index).sourceId();
            Map<String, MeasuredQualityInputs> ordered = passingInputs(sourceId);
            LinkedHashMap<String, MeasuredQualityInputs> reversed = new LinkedHashMap<>();
            ordered.entrySet().stream().toList().reversed().forEach(entry ->
                    reversed.put(entry.getKey(), entry.getValue()));

            QualityAssessment first = CALCULATOR.assess(POLICY, sourceId, ordered);
            QualityAssessment second = CALCULATOR.assess(POLICY, sourceId, reversed);

            assertEquals(expectedCounts.get(index), first.metricResults().size(), sourceId);
            assertEquals(first, second, sourceId);
            assertEquals(
                    definitions(sourceId).stream().map(MetricDefinition::formulaId).toList(),
                    first.metricResults().stream().map(QualityMetricResult::formulaId).toList(),
                    sourceId);
        }
    }

    @Test
    void dynamicNotApplicableIsClosedAndDoesNotTurnAnOtherwisePassingAssessmentIntoFailure() {
        String sourceId = "SRC-P1-OFFCAMPUS-001";
        Map<String, MeasuredQualityInputs> inputs = passingInputs(sourceId);
        String overlapFormula =
                "QMDP-1.0.0/SRC-P1-OFFCAMPUS-001/p0-accommodation-overlap-disambiguation";
        MetricDefinition overlap = definitions(sourceId).stream()
                .filter(definition -> definition.formulaId().equals(overlapFormula))
                .findFirst().orElseThrow();
        inputs.put(overlapFormula,
                inputs(overlap, false, BigInteger.ZERO, BigInteger.ZERO));

        QualityAssessment assessment = CALCULATOR.assess(POLICY, sourceId, inputs);
        QualityMetricResult notApplicable = assessment.metricResults().stream()
                .filter(result -> result.formulaId().equals(overlapFormula))
                .findFirst()
                .orElseThrow();

        assertEquals("quality-passed", assessment.overallResult().wireValue());
        assertEquals("not-applicable", notApplicable.result().wireValue());
        assertFalse(notApplicable.applicable());
        assertEquals(BigInteger.ZERO, notApplicable.numerator());
        assertEquals(BigInteger.ZERO, notApplicable.denominator());
        assertNull(notApplicable.valueBasisPoints());
        assertNull(notApplicable.reasonCode());
    }

    @Test
    void assessmentDerivesApplicabilityAndRejectsCallerControlledGateRemoval() {
        String sourceId = "SRC-P1-OFFCAMPUS-001";
        Map<String, MeasuredQualityInputs> inputs = passingInputs(sourceId);
        String overlapFormula =
                "QMDP-1.0.0/SRC-P1-OFFCAMPUS-001/p0-accommodation-overlap-disambiguation";
        MetricDefinition overlap = definitions(sourceId).stream()
                .filter(definition -> definition.formulaId().equals(overlapFormula))
                .findFirst().orElseThrow();
        inputs.put(overlapFormula, inputs(overlap, false, BigInteger.ONE, BigInteger.ONE));

        assertThrows(IngestionQualityException.class,
                () -> CALCULATOR.assess(POLICY, sourceId, inputs));
    }

    @Test
    void incompleteFormulaSetAndZeroDenominatorRemainTechnicalAndProduceNoAssessment() {
        String sourceId = "SRC-P0-STUDENT-001";
        Map<String, MeasuredQualityInputs> incomplete = passingInputs(sourceId);
        incomplete.remove(common("PRIMARY_KEY_COMPLETENESS_BP").formulaId());
        Map<String, MeasuredQualityInputs> extra = passingInputs(sourceId);
        extra.put("QMDP-1.0.0/UNKNOWN", new MeasuredQualityInputs(true, Map.of()));
        MetricDefinition primaryKey = common("PRIMARY_KEY_COMPLETENESS_BP");

        assertThrows(IngestionQualityException.class,
                () -> CALCULATOR.assess(POLICY, sourceId, incomplete));
        assertThrows(IngestionQualityException.class,
                () -> CALCULATOR.assess(POLICY, sourceId, extra));
        IngestionQualityException zero = assertThrows(
                IngestionQualityException.class,
                () -> CALCULATOR.calculate(
                        primaryKey, inputs(primaryKey, true, BigInteger.ZERO, BigInteger.ZERO)));
        assertEquals("QUALITY_POLICY_ZERO_DENOMINATOR", zero.code());
    }

    @Test
    void qshmEvidenceHasNoIndependentRawCountField() {
        assertFalse(Arrays.stream(QualityMetricResult.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("rawCount")));
    }

    private static Map<String, MeasuredQualityInputs> passingInputs(String sourceId) {
        Map<String, MeasuredQualityInputs> inputs = new LinkedHashMap<>();
        definitions(sourceId).forEach(definition -> inputs.put(
                definition.formulaId(), passingInput(definition)));
        return inputs;
    }

    private static MeasuredQualityInputs passingInput(MetricDefinition definition) {
        if (definition.calculation().kind().equals("count")) {
            return inputs(definition, true,
                    BigInteger.valueOf(definition.thresholdNumerator()),
                    BigInteger.valueOf(definition.thresholdDenominator()));
        }
        return inputs(definition, true,
                BigInteger.valueOf(definition.thresholdNumerator()),
                BigInteger.valueOf(definition.thresholdDenominator()));
    }

    private static List<MetricDefinition> definitions(String sourceId) {
        var source = POLICY.sources().stream()
                .filter(candidate -> candidate.sourceId().equals(sourceId))
                .findFirst()
                .orElseThrow();
        var selected = source.applicableCommonMetricIds();
        return Stream.concat(
                        POLICY.commonMetrics().stream()
                                .filter(metric -> selected.contains(metric.metricId())),
                        source.sourceGates().stream())
                .toList();
    }

    private static MeasuredQualityInputs inputs(
            MetricDefinition definition,
            boolean applicable,
            BigInteger numerator,
            BigInteger denominator) {
        Map<String, BigInteger> operands = new LinkedHashMap<>();
        bind(operands, definition.calculation().numerator(), numerator);
        bind(operands, definition.calculation().denominator(), denominator);
        return new MeasuredQualityInputs(applicable, operands);
    }

    private static void bind(
            Map<String, BigInteger> measured, Operand operand, BigInteger value) {
        if (operand.operandId() == null) {
            assertEquals(BigInteger.valueOf(operand.constantValue()), value);
            return;
        }
        BigInteger previous = measured.put(operand.operandId(), value);
        if (previous != null) assertEquals(previous, value);
    }

    private static MetricDefinition definition(VectorCase vector) {
        if (vector.sourceId() == null) return common(vector.metricId());
        return POLICY.sources().stream()
                .filter(source -> source.sourceId().equals(vector.sourceId()))
                .flatMap(source -> source.sourceGates().stream())
                .filter(metric -> metric.metricId().equals(vector.metricId()))
                .filter(metric -> vector.gateId().equals(metric.gateId()))
                .findFirst()
                .orElseThrow();
    }

    private static MetricDefinition common(String metricId) {
        return POLICY.commonMetrics().stream()
                .filter(metric -> metric.metricId().equals(metricId))
                .findFirst()
                .orElseThrow();
    }

    private static Stream<VectorCase> controlledVectors() throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(VECTORS));
        return root.required("cases").valueStream().map(QualityMetricCalculatorTest::vector);
    }

    private static VectorCase vector(JsonNode node) {
        JsonNode input = node.required("input");
        JsonNode expected = node.required("expectedJava");
        return new VectorCase(
                node.required("caseId").asText(),
                node.required("metricId").asText(),
                nullableText(node.get("sourceId")),
                nullableText(node.get("gateId")),
                input.required("applicable").asBoolean(),
                input.required("numerator").bigIntegerValue(),
                input.required("denominator").bigIntegerValue(),
                new Actual(
                        expected.required("result").asText(),
                        nullableInteger(expected.get("valueBasisPoints")),
                        nullableText(expected.get("reasonCode"))));
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static BigInteger nullableInteger(JsonNode node) {
        return node == null || node.isNull() ? null : node.bigIntegerValue();
    }

    private static BigInteger bi(long value) {
        return BigInteger.valueOf(value);
    }

    private record Actual(String result, BigInteger valueBasisPoints, String reasonCode) {}

    private record VectorCase(
            String caseId,
            String metricId,
            String sourceId,
            String gateId,
            boolean applicable,
            BigInteger numerator,
            BigInteger denominator,
            Actual expected) {
        @Override
        public String toString() {
            return caseId;
        }
    }
}
