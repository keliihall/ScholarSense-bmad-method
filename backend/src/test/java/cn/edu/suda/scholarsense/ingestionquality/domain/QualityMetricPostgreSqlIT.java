package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenExecutableQualityPolicyLoader;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.MetricDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.Operand;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL 18.4 parity evidence for the controlled QMDP metric arithmetic. */
class QualityMetricPostgreSqlIT {
    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
    private static final Path POLICY_PATH = REPOSITORY.resolve(
            "contracts/ingestion-quality/batch-quality/executable-quality-policy-1.0.0.json");
    private static final Path VECTORS_PATH = REPOSITORY.resolve(
            "contracts/ingestion-quality/batch-quality/fixtures/valid/"
                    + "quality-metric-vectors-1.0.0.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExecutableQualityPolicy POLICY =
            new FrozenExecutableQualityPolicyLoader(REPOSITORY).loadVerified().policy();
    private static final QualityMetricCalculator CALCULATOR = new QualityMetricCalculator();

    @Test
    void allTwentySevenControlledVectorsHaveIndependentJavaPostgreSqlAndExpectedParity()
            throws Exception {
        JsonNode root = JSON.readTree(Files.readAllBytes(VECTORS_PATH));
        List<JsonNode> vectors = stream(root.required("cases")).toList();

        assertEquals(27, vectors.size());
        assertEquals(17, POLICY.sources().size());
        assertEquals(POLICY.sources().stream()
                        .map(ExecutableQualityPolicy.SourcePolicy::sourceId)
                        .collect(java.util.stream.Collectors.toSet()),
                vectors.stream()
                .filter(vector -> vector.has("sourceId"))
                .map(vector -> vector.required("sourceId").asText())
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("ratio", "count", "duration"), vectors.stream()
                .map(QualityMetricPostgreSqlIT::definition)
                .map(metric -> metric.calculation().kind())
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(vectors.stream()
                .map(vector -> vector.required("caseId").asText())
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(Set.of(
                        "ratio-boundary-pass",
                        "ratio-one-unit-below-fail",
                        "ratio-one-unit-above-pass",
                        "zero-denominator-evaluation-error",
                        "count-zero-pass",
                        "count-one-fail",
                        "duration-boundary-pass",
                        "duration-one-millisecond-over-fail",
                        "not-applicable-closed-predicate")));

        try (Connection connection = connection()) {
            assertEquals("180004", scalar(
                    connection, "select current_setting('server_version_num')"));
            installSqlFixture(connection);
            for (JsonNode vector : vectors) {
                String caseId = vector.required("caseId").asText();
                Actual expectedJava = expected(vector.required("expectedJava"));
                Actual expectedSql = expected(vector.required("expectedSql"));
                Actual javaActual = javaResult(definition(vector), vector.required("input"));
                Actual sqlActual = sqlResult(connection, vector);

                assertEquals(expectedJava, javaActual, caseId + " Java");
                assertEquals(expectedSql, sqlActual, caseId + " PostgreSQL");
                assertEquals(javaActual, sqlActual, caseId + " cross-implementation");
            }
        }
    }

    @Test
    void compositeAndUsesHalfUpCrossMultiplicationAndKeepsTechnicalErrorsDistinctFromFailure()
            throws Exception {
        List<SyntheticCase> cases = List.of(
                new SyntheticCase("composite-3-of-3", bi(3), bi(3),
                        new Actual("passed", bi(10_000), null)),
                new SyntheticCase("composite-2-of-3", bi(2), bi(3),
                        new Actual("failed", bi(6_667), null)),
                new SyntheticCase("half-up-exact-tie-1-of-32", bi(1), bi(32),
                        new Actual("failed", bi(313), null)),
                new SyntheticCase("pre-rounding-trap-19899-of-20000", bi(19_899), bi(20_000),
                        new Actual("failed", bi(9_950), null)),
                new SyntheticCase("composite-0-of-0", BigInteger.ZERO, BigInteger.ZERO,
                        new Actual("evaluation-error", null,
                                "QUALITY_POLICY_ZERO_DENOMINATOR")));
        MetricDefinition composite = common("SOURCE_CONTINUITY_GATE");

        try (Connection connection = connection()) {
            installSqlFixture(connection);
            for (SyntheticCase testCase : cases) {
                ObjectNode vector = JSON.createObjectNode();
                vector.put("caseId", testCase.caseId());
                vector.put("metricId", composite.metricId());
                ObjectNode input = vector.putObject("input");
                input.put("applicable", true);
                input.put("numerator", testCase.numerator());
                input.put("denominator", testCase.denominator());

                Actual javaActual = javaResult(composite, input);
                Actual sqlActual = sqlResult(connection, vector);
                assertEquals(testCase.expected(), javaActual, testCase.caseId() + " Java");
                assertEquals(testCase.expected(), sqlActual, testCase.caseId() + " PostgreSQL");
                assertEquals(javaActual, sqlActual, testCase.caseId() + " parity");
            }
        }

        assertNotEquals("failed", cases.getLast().expected().result());
        assertEquals("evaluation-error", cases.getLast().expected().result());
    }

    @Test
    void missingMalformedAndUnexpectedMeasurementFieldsFailAsTechnicalErrors() throws Exception {
        MetricDefinition composite = common("SOURCE_CONTINUITY_GATE");
        ObjectNode valid = syntheticVector(
                "measurement-shape-control", composite, bi(995), bi(1_000));

        try (Connection connection = connection()) {
            installSqlFixture(connection);

            for (String requiredField : List.of("applicable", "numerator", "denominator")) {
                ObjectNode missing = valid.deepCopy();
                ((ObjectNode) missing.required("input")).remove(requiredField);
                assertSqlTechnicalError(connection, missing, "missing " + requiredField);
            }

            ObjectNode nullApplicable = valid.deepCopy();
            ((ObjectNode) nullApplicable.required("input")).putNull("applicable");
            assertSqlTechnicalError(connection, nullApplicable, "null applicable");

            ObjectNode stringNumerator = valid.deepCopy();
            ((ObjectNode) stringNumerator.required("input")).put("numerator", "995");
            assertSqlTechnicalError(connection, stringNumerator, "string numerator");

            ObjectNode fractionalDenominator = valid.deepCopy();
            ((ObjectNode) fractionalDenominator.required("input")).put("denominator", 1_000.5);
            assertSqlTechnicalError(connection, fractionalDenominator, "fractional denominator");

            ObjectNode unexpected = valid.deepCopy();
            ((ObjectNode) unexpected.required("input")).put("unapprovedOperand", 1);
            assertSqlTechnicalError(connection, unexpected, "unexpected measurement field");
        }
    }

    @Test
    void safeIntegerBoundaryIsAcceptedAndOutOfRangeOrNegativeOperandsFailClosed()
            throws Exception {
        MetricDefinition composite = common("SOURCE_CONTINUITY_GATE");
        BigInteger maxSafeInteger = new BigInteger("9007199254740991");
        ObjectNode boundary = syntheticVector(
                "maximum-safe-integer", composite, maxSafeInteger, maxSafeInteger);

        try (Connection connection = connection()) {
            installSqlFixture(connection);
            Actual expected = new Actual("passed", bi(10_000), null);
            assertEquals(expected, javaResult(composite, boundary.required("input")));
            assertEquals(expected, sqlResult(connection, boundary));

            ObjectNode numeratorAboveMaximum = boundary.deepCopy();
            ((ObjectNode) numeratorAboveMaximum.required("input"))
                    .put("numerator", maxSafeInteger.add(BigInteger.ONE));
            assertSqlTechnicalError(
                    connection, numeratorAboveMaximum, "numerator above maximum safe integer");

            ObjectNode denominatorAboveMaximum = boundary.deepCopy();
            ((ObjectNode) denominatorAboveMaximum.required("input"))
                    .put("denominator", maxSafeInteger.add(BigInteger.ONE));
            assertSqlTechnicalError(
                    connection, denominatorAboveMaximum, "denominator above maximum safe integer");

            ObjectNode negativeNumerator = boundary.deepCopy();
            ((ObjectNode) negativeNumerator.required("input")).put("numerator", -1);
            assertSqlTechnicalError(connection, negativeNumerator, "negative numerator");

            ObjectNode negativeDenominator = boundary.deepCopy();
            ((ObjectNode) negativeDenominator.required("input")).put("denominator", -1);
            assertSqlTechnicalError(connection, negativeDenominator, "negative denominator");
        }
    }

    private static void installSqlFixture(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                create or replace function pg_temp.quality_metric(
                    policy_value jsonb,
                    vector_value jsonb)
                returns jsonb
                language plpgsql
                immutable
                strict
                as $function$
                declare
                  definition_value jsonb;
                  definition_count integer;
                  input_value jsonb;
                  calculation_kind text;
                  unit_value text;
                  operator_value text;
                  applicable_value boolean;
                  numerator_value numeric;
                  denominator_value numeric;
                  threshold_numerator numeric;
                  threshold_denominator numeric;
                  comparison_value integer;
                  passed_value boolean;
                  basis_points_value numeric;
                begin
                  if jsonb_typeof(policy_value) <> 'object'
                     or jsonb_typeof(vector_value) <> 'object' then
                    raise exception 'quality metric inputs must be JSON objects';
                  end if;
                  if policy_value->>'profileVersion' is distinct from 'QMDP-1.0.0'
                     or policy_value#>>'{numericSemantics,representation}'
                          is distinct from 'non-negative-integer-rational'
                     or policy_value#>>'{numericSemantics,basisPointScale}'
                          is distinct from '10000'
                     or policy_value#>>'{numericSemantics,valueScale}' is distinct from '0'
                     or policy_value#>>'{numericSemantics,roundingMode}'
                          is distinct from 'HALF_UP'
                     or policy_value#>>'{numericSemantics,comparisonStage}'
                          is distinct from 'pre-rounding-cross-multiply'
                     or policy_value#>>'{numericSemantics,zeroDenominator}'
                          is distinct from
                             'evaluation-error/QUALITY_POLICY_ZERO_DENOMINATOR' then
                    raise exception 'quality metric numeric semantics are not QMDP-1.0.0';
                  end if;

                  if vector_value ? 'sourceId' or vector_value ? 'gateId' then
                    if not (vector_value ? 'sourceId' and vector_value ? 'gateId')
                       or jsonb_typeof(vector_value->'sourceId') <> 'string'
                       or jsonb_typeof(vector_value->'gateId') <> 'string' then
                      raise exception 'source-scoped metric requires sourceId and gateId';
                    end if;
                    select count(*), min(gate_value::text)::jsonb
                      into definition_count, definition_value
                      from jsonb_array_elements(policy_value->'sources')
                        source(source_value)
                      cross join lateral jsonb_array_elements(
                        source_value->'sourceGates') gate(gate_value)
                     where source_value->>'sourceId' = vector_value->>'sourceId'
                       and gate_value->>'sourceId' = vector_value->>'sourceId'
                       and gate_value->>'metricId' = vector_value->>'metricId'
                       and gate_value->>'gateId' = vector_value->>'gateId';
                  else
                    select count(*), min(metric_value::text)::jsonb
                      into definition_count, definition_value
                      from jsonb_array_elements(policy_value->'commonMetrics')
                        metric(metric_value)
                     where metric_value->>'metricId' = vector_value->>'metricId'
                       and metric_value->>'definitionKind' = 'common';
                  end if;
                  if definition_count <> 1 then
                    raise exception 'quality metric definition is not uniquely scoped';
                  end if;

                  calculation_kind := definition_value#>>'{calculation,kind}';
                  unit_value := definition_value->>'unit';
                  operator_value := definition_value->>'operator';
                  if definition_value->>'formulaVersion' is distinct from '1.0.0'
                     or definition_value->>'boundary' is distinct from 'inclusive'
                     or definition_value->>'denominatorZeroBehavior'
                          is distinct from 'evaluation-error'
                     or definition_value->>'valueScale' is distinct from '0'
                     or definition_value->>'roundingMode' is distinct from 'HALF_UP'
                     or definition_value->>'comparisonStage'
                          is distinct from 'pre-rounding-cross-multiply'
                     or calculation_kind not in ('ratio', 'count', 'duration', 'composite-and')
                     or operator_value not in ('>=', '<=', '=')
                     or (calculation_kind = 'ratio' and unit_value <> 'basis-point')
                     or (calculation_kind = 'count' and unit_value <> 'count')
                     or (calculation_kind = 'duration' and unit_value <> 'millisecond')
                     or (calculation_kind = 'composite-and' and unit_value <> 'member-count') then
                    raise exception 'quality metric definition semantics are invalid';
                  end if;

                  if (definition_value->>'thresholdNumerator')
                        !~ '^(0|[1-9][0-9]*)$'
                     or (definition_value->>'thresholdDenominator')
                        !~ '^[1-9][0-9]*$' then
                    raise exception 'quality metric threshold is not a non-negative rational';
                  end if;
                  threshold_numerator := (definition_value->>'thresholdNumerator')::numeric;
                  threshold_denominator := (definition_value->>'thresholdDenominator')::numeric;

                  input_value := vector_value->'input';
                  if jsonb_typeof(input_value) is distinct from 'object'
                     or not input_value ?& array['applicable', 'numerator', 'denominator']
                     or exists (
                       select 1
                         from jsonb_object_keys(input_value) input_key
                        where input_key <> all (array[
                          'applicable', 'numerator', 'denominator',
                          'observedAt', 'cutoffAt', 'timezone']))
                     or jsonb_typeof(input_value->'applicable') is distinct from 'boolean'
                     or jsonb_typeof(input_value->'numerator') is distinct from 'number'
                     or jsonb_typeof(input_value->'denominator') is distinct from 'number'
                     or (input_value->>'numerator') !~ '^(0|[1-9][0-9]*)$'
                     or (input_value->>'denominator') !~ '^(0|[1-9][0-9]*)$' then
                    raise exception 'quality metric measurement is not a non-negative integer rational';
                  end if;
                  applicable_value := (input_value->>'applicable')::boolean;
                  numerator_value := (input_value->>'numerator')::numeric;
                  denominator_value := (input_value->>'denominator')::numeric;
                  if numerator_value > 9007199254740991
                     or denominator_value > 9007199254740991
                     or threshold_numerator > 9007199254740991
                     or threshold_denominator > 9007199254740991 then
                    raise exception 'quality metric integer exceeds the safe contract range';
                  end if;

                  if not applicable_value then
                    if numerator_value <> 0 or denominator_value <> 0 then
                      raise exception 'not-applicable quality measurement must be closed';
                    end if;
                    return jsonb_build_object(
                      'result', 'not-applicable',
                      'valueBasisPoints', null,
                      'reasonCode', null);
                  end if;

                  if denominator_value = 0 then
                    return jsonb_build_object(
                      'result', 'evaluation-error',
                      'valueBasisPoints', null,
                      'reasonCode', 'QUALITY_POLICY_ZERO_DENOMINATOR');
                  end if;
                  if calculation_kind in ('count', 'duration')
                     and denominator_value <> 1 then
                    raise exception 'count and duration denominator must equal one';
                  end if;
                  if definition_value#>>'{calculation,denominator,kind}' = 'constant'
                     and denominator_value <>
                         (definition_value#>>'{calculation,denominator,value}')::numeric then
                    raise exception 'measured denominator does not match the formula constant';
                  end if;
                  if (calculation_kind = 'composite-and'
                        and numerator_value > denominator_value)
                     or (calculation_kind = 'ratio'
                        and definition_value->>'formulaId' <>
                          'QMDP-1.0.0/SRC-P0-CALENDAR-001/'
                            || 'calendar-exactly-one-current-day-type'
                        and numerator_value > denominator_value) then
                    raise exception 'quality metric cardinality is invalid';
                  end if;

                  comparison_value := sign(
                      numerator_value * threshold_denominator
                        - denominator_value * threshold_numerator);
                  passed_value := case operator_value
                    when '>=' then comparison_value >= 0
                    when '<=' then comparison_value <= 0
                    when '=' then comparison_value = 0
                  end;
                  if calculation_kind in ('ratio', 'composite-and') then
                    basis_points_value := round(
                        numerator_value * 10000 / denominator_value, 0);
                  else
                    basis_points_value := null;
                  end if;
                  return jsonb_build_object(
                    'result', case when passed_value then 'passed' else 'failed' end,
                    'valueBasisPoints', basis_points_value,
                    'reasonCode', null);
                end
                $function$;
                """)) {
            statement.execute();
        }
    }

    private static Actual sqlResult(Connection connection, JsonNode vector) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                select pg_temp.quality_metric(?::jsonb, ?::jsonb)
                """)) {
            statement.setString(1, Files.readString(POLICY_PATH));
            statement.setString(2, vector.toString());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return expected(JSON.readTree(result.getString(1)));
            }
        }
    }

    private static ObjectNode syntheticVector(
            String caseId,
            MetricDefinition definition,
            BigInteger numerator,
            BigInteger denominator) {
        ObjectNode vector = JSON.createObjectNode();
        vector.put("caseId", caseId);
        vector.put("metricId", definition.metricId());
        ObjectNode input = vector.putObject("input");
        input.put("applicable", true);
        input.put("numerator", numerator);
        input.put("denominator", denominator);
        return vector;
    }

    private static void assertSqlTechnicalError(
            Connection connection, JsonNode vector, String scenario) {
        SQLException error = assertThrows(
                SQLException.class, () -> sqlResult(connection, vector), scenario);
        assertTrue(
                error.getMessage().contains("quality metric"),
                scenario + " must surface a technical quality-metric error");
    }

    private static Actual javaResult(MetricDefinition definition, JsonNode input) {
        try {
            QualityMetricResult result = CALCULATOR.calculate(
                    definition,
                    inputs(
                            definition,
                            input.required("applicable").asBoolean(),
                            input.required("numerator").bigIntegerValue(),
                            input.required("denominator").bigIntegerValue()));
            return new Actual(
                    result.result().wireValue(), result.valueBasisPoints(), result.reasonCode());
        } catch (IngestionQualityException technical) {
            return new Actual("evaluation-error", null, technical.code());
        }
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

    private static MetricDefinition definition(JsonNode vector) {
        if (!vector.has("sourceId")) return common(vector.required("metricId").asText());
        String sourceId = vector.required("sourceId").asText();
        String metricId = vector.required("metricId").asText();
        String gateId = vector.required("gateId").asText();
        List<MetricDefinition> matches = POLICY.sources().stream()
                .filter(source -> source.sourceId().equals(sourceId))
                .flatMap(source -> source.sourceGates().stream())
                .filter(metric -> metric.metricId().equals(metricId))
                .filter(metric -> gateId.equals(metric.gateId()))
                .toList();
        if (matches.size() != 1) throw new IllegalArgumentException("source gate is not unique");
        return matches.getFirst();
    }

    private static MetricDefinition common(String metricId) {
        List<MetricDefinition> matches = POLICY.commonMetrics().stream()
                .filter(metric -> metric.metricId().equals(metricId))
                .toList();
        if (matches.size() != 1) throw new IllegalArgumentException("common metric is not unique");
        return matches.getFirst();
    }

    private static Actual expected(JsonNode node) {
        return new Actual(
                node.required("result").asText(),
                nullableInteger(node.get("valueBasisPoints")),
                nullableText(node.get("reasonCode")));
    }

    private static BigInteger nullableInteger(JsonNode node) {
        return node == null || node.isNull() ? null : node.bigIntegerValue();
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
                required("scholarsense.audit.pg.url"),
                required("scholarsense.audit.pg.user"),
                System.getProperty("scholarsense.audit.pg.password", ""));
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    property + " is required; use scripts/run_audit_postgresql_tests.sh");
        }
        return value;
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getString(1);
        }
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false);
    }

    private static BigInteger bi(long value) {
        return BigInteger.valueOf(value);
    }

    private record Actual(String result, BigInteger valueBasisPoints, String reasonCode) {}

    private record SyntheticCase(
            String caseId, BigInteger numerator, BigInteger denominator, Actual expected) {}
}
