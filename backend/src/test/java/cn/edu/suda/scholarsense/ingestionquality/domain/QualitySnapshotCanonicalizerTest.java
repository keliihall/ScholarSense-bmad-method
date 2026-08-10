package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenExecutableQualityPolicyLoader;
import cn.edu.suda.scholarsense.ingestionquality.application.VerifiedQualityContract;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class QualitySnapshotCanonicalizerTest {

    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
    private static final Path GOLDENS = REPOSITORY.resolve(
            "contracts/ingestion-quality/batch-quality/fixtures/valid/"
                    + "quality-snapshot-hash-vectors-1.0.0.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final VerifiedQualityContract CONTRACT =
            new FrozenExecutableQualityPolicyLoader(REPOSITORY).loadVerified();
    private static final QualitySnapshotCanonicalizer CANONICALIZER =
            new QualitySnapshotCanonicalizer(CONTRACT.policy(), CONTRACT.hashProfile());

    @ParameterizedTest(name = "{0}")
    @MethodSource("controlledGoldens")
    void matchesAllThreeControlledCanonicalByteAndHashGoldens(Golden golden) {
        QualitySnapshotMaterial material = CANONICALIZER.materialize(snapshot(golden));

        assertArrayEquals(
                HexFormat.of().parseHex(golden.expectedCanonicalUtf8Hex()),
                CANONICALIZER.canonicalUtf8(material));
        assertEquals(golden.expectedImmutableHash(), CANONICALIZER.immutableHash(material));
    }

    @Test
    void materialIsExactlyTheApprovedTwentySixFieldsAndRebuildsPolicyArrayOrder() {
        Golden golden = golden("root-policy-order-golden");
        QualitySnapshot input = snapshot(golden);
        QualitySnapshotMaterial material = CANONICALIZER.materialize(input);

        assertEquals(
                CONTRACT.hashProfile().includedTopLevelFields(),
                Arrays.stream(QualitySnapshotMaterial.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(expectedFormulaOrder(input.sourceId()),
                material.metricResults().stream().map(QualityMetricResult::formulaId).toList());
        assertNotEquals(
                input.metricResults().stream().map(QualityMetricResult::formulaId).toList(),
                material.metricResults().stream().map(QualityMetricResult::formulaId).toList());
        assertEquals(List.of("CONTINUITY", "PRIMARY_KEY", "\uE000", "\uD800\uDC00"),
                material.impactScopeCodes());
    }

    @Test
    void excludedRuntimeFieldsAndSelfHashAreStableButIncludedFieldsMutateTheHash() {
        QualitySnapshot baseline = snapshot(golden("root-policy-order-golden"));
        QualitySnapshot excludedMutation = copy(
                baseline,
                uuid("019fe66d-7c00-7000-8000-000000000099"),
                baseline.evaluatedAt().plusSeconds(1),
                "fedcba9876543210fedcba9876543210",
                baseline.aggregateVersion() + 1,
                "sha256:" + "f".repeat(64),
                baseline.watermark(),
                baseline.cutoffAt(),
                baseline.metricResults(),
                baseline.supersedesSnapshotId());
        QualitySnapshot includedMutation = copy(
                baseline,
                baseline.snapshotId(),
                baseline.evaluatedAt(),
                baseline.traceId(),
                baseline.aggregateVersion(),
                baseline.immutableHash(),
                baseline.watermark() + "-changed",
                baseline.cutoffAt(),
                baseline.metricResults(),
                baseline.supersedesSnapshotId());
        QualitySnapshot metricMutation = copy(
                baseline,
                baseline.snapshotId(),
                baseline.evaluatedAt(),
                baseline.traceId(),
                baseline.aggregateVersion(),
                baseline.immutableHash(),
                baseline.watermark(),
                baseline.cutoffAt(),
                mutateFailedNumerator(baseline.metricResults()),
                baseline.supersedesSnapshotId());
        QualitySnapshot predecessorMutation = copy(
                baseline,
                baseline.snapshotId(),
                baseline.evaluatedAt(),
                baseline.traceId(),
                baseline.aggregateVersion(),
                baseline.immutableHash(),
                baseline.watermark(),
                baseline.cutoffAt(),
                baseline.metricResults(),
                uuid("019fe66d-7c00-7000-8000-000000000001"));

        String baselineHash = hash(baseline);
        assertEquals(baselineHash, hash(excludedMutation));
        assertArrayEquals(bytes(baseline), bytes(excludedMutation));
        assertNotEquals(baselineHash, hash(includedMutation));
        assertNotEquals(baselineHash, hash(metricMutation));
        assertNotEquals(baselineHash, hash(predecessorMutation));

        String canonical = canonical(baseline);
        CONTRACT.hashProfile().excludedSnapshotFields()
                .forEach(field -> assertFalse(canonical.contains("\"" + field + "\""), field));
    }

    @Test
    void canonicalMaterialKeepsExplicitNullsUtcSixMicrosAndMinimalStringEscapes() {
        QualitySnapshot baseline = snapshot(golden("root-policy-order-golden"));
        String canonical = canonical(baseline);

        assertTrue(canonical.contains("\"supersedesSnapshotId\":null"));
        assertTrue(canonical.contains("\"reasonCode\":null"));
        assertTrue(canonical.contains("\"valueBasisPoints\":null"));
        assertTrue(canonical.contains("\"cutoffAt\":\"2026-08-09T00:00:00.000000Z\""));
        assertTrue(canonical.contains(
                "\"observationWindow\":{\"endAt\":\"2026-08-09T00:00:00.000000Z\","
                        + "\"startAt\":\"2026-08-08T00:00:00.000000Z\"}"));
        assertTrue(canonical.contains(
                "\"watermark\":\"src\\nquote\\\"slash\\\\tab\\tcontrol\\u0001\""));
        assertTrue(canonical.contains("\"sourceOwnerRef\":\"校历数据 owner\""));
        assertFalse(canonical.contains("\\/"));
        assertFalse(canonical.contains("\\u6821"));

        QualitySnapshot adjacentMicrosecond = copy(
                baseline,
                baseline.snapshotId(),
                baseline.evaluatedAt(),
                baseline.traceId(),
                baseline.aggregateVersion(),
                baseline.immutableHash(),
                baseline.watermark(),
                baseline.cutoffAt().plusNanos(1_000),
                baseline.metricResults(),
                baseline.supersedesSnapshotId());
        assertTrue(canonical(adjacentMicrosecond)
                .contains("\"cutoffAt\":\"2026-08-09T00:00:00.000001Z\""));
        assertNotEquals(hash(baseline), hash(adjacentMicrosecond));
    }

    @Test
    void canonicalJsonOrdersUnicodeCodePointKeysWritesDirectUtf8AndRejectsLoneSurrogates() {
        LinkedHashMap<String, Object> unicode = new LinkedHashMap<>();
        unicode.put("\uD83D\uDE00", "火箭\uD83D\uDE80");
        unicode.put("\uE000", "边界");
        unicode.put("私域", "学林/知微");

        assertEquals(
                "{\"私域\":\"学林/知微\",\"\uE000\":\"边界\","
                        + "\"\uD83D\uDE00\":\"火箭\uD83D\uDE80\"}",
                new String(QualityCanonicalJson.canonicalUtf8(unicode), StandardCharsets.UTF_8));
        assertEquals(
                "{\"text\":\"\\b\\f\\n\\r\\t\\\"\\\\/ \\u0000\\u0001\"}",
                new String(QualityCanonicalJson.canonicalUtf8(Map.of(
                        "text", "\b\f\n\r\t\"\\/ \u0000\u0001")), StandardCharsets.UTF_8));

        assertThrows(IngestionQualityException.class,
                () -> QualityCanonicalJson.canonicalUtf8(Map.of("valid", "\uD800")));
        assertThrows(IngestionQualityException.class,
                () -> QualityCanonicalJson.canonicalUtf8(Map.of("\uDC00", "valid")));
    }

    @Test
    void arbitraryMaterialCannotBypassThePolicyBoundSnapshotEntryPoint() throws Exception {
        assertFalse(Modifier.isPublic(QualitySnapshotMaterial.class.getModifiers()));
        assertFalse(Modifier.isPublic(QualitySnapshotCanonicalizer.class
                .getDeclaredMethod("materialize", QualitySnapshot.class).getModifiers()));
        assertFalse(Modifier.isPublic(QualitySnapshotCanonicalizer.class
                .getDeclaredMethod("canonicalUtf8", QualitySnapshotMaterial.class)
                .getModifiers()));
        assertFalse(Modifier.isPublic(QualitySnapshotCanonicalizer.class
                .getDeclaredMethod("immutableHash", QualitySnapshotMaterial.class)
                .getModifiers()));
        assertTrue(Modifier.isPublic(QualitySnapshotCanonicalizer.class
                .getDeclaredMethod("immutableHash", QualitySnapshot.class).getModifiers()));
        assertTrue(Modifier.isPublic(QualitySnapshotCanonicalizer.class
                .getDeclaredMethod("verify", QualitySnapshot.class).getModifiers()));
    }

    private static Stream<Golden> controlledGoldens() throws IOException {
        JsonNode root = JSON.readTree(Files.readAllBytes(GOLDENS));
        assertEquals("QSHM-VECTORS-1.0.0", root.required("fixtureVersion").asText());
        assertEquals("QSHM-1.0.0", root.required("hashProfileVersion").asText());
        assertEquals(3, root.required("cases").size());
        return stream(root.required("cases")).map(QualitySnapshotCanonicalizerTest::golden);
    }

    private static Golden golden(String caseId) {
        try (Stream<Golden> goldens = controlledGoldens()) {
            return goldens.filter(candidate -> candidate.caseId().equals(caseId))
                    .findFirst().orElseThrow();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Golden golden(JsonNode node) {
        return new Golden(
                node.required("caseId").asText(),
                node.required("snapshot"),
                node.required("expectedCanonicalUtf8Hex").asText(),
                node.required("expectedImmutableHash").asText());
    }

    private static QualitySnapshot snapshot(Golden golden) {
        JsonNode node = golden.snapshot();
        JsonNode window = node.required("observationWindow");
        return new QualitySnapshot(
                CONTRACT.hashProfile().domainTag(),
                text(node, "hashProfileVersion"),
                text(node, "hashProfileDigest"),
                uuid(text(node, "batchId")),
                text(node, "sourceId"),
                dataBatchStatus(text(node, "assessedBatchStatus")),
                overallResult(text(node, "overallResult")),
                new BatchObservationWindow(
                        instant(window, "startAt"), instant(window, "endAt")),
                instant(node, "cutoffAt"),
                text(node, "watermark"),
                metricResults(node.required("metricResults")),
                strings(node.required("impactScopeCodes")),
                text(node, "sourceOwnerRef"),
                text(node, "approvalRef"),
                instant(node, "effectiveAt"),
                text(node, "retentionScheduleVersion"),
                text(node, "qualityMetricDecisionProfileVersion"),
                text(node, "qualityMetricDecisionProfileDigest"),
                text(node, "qualityGateVersion"),
                text(node, "qualityGateDigest"),
                text(node, "canonicalizationProfile"),
                text(node, "manifestDigest"),
                text(node, "sourceSchemaVersion"),
                text(node, "sourceSchemaDigest"),
                uuid(text(node, "lineageId")),
                nullableUuid(node.get("supersedesSnapshotId")),
                uuid(text(node, "snapshotId")),
                instant(node, "evaluatedAt"),
                text(node, "traceId"),
                node.required("aggregateVersion").longValue(),
                golden.expectedImmutableHash());
    }

    private static List<QualityMetricResult> metricResults(JsonNode array) {
        return stream(array).map(node -> new QualityMetricResult(
                text(node, "metricId"),
                text(node, "formulaId"),
                text(node, "formulaVersion"),
                metricStatus(text(node, "result")),
                node.required("applicable").asBoolean(),
                integer(node, "numerator"),
                integer(node, "denominator"),
                nullableInteger(node.get("valueBasisPoints")),
                QualityMetricUnit.fromWire(text(node, "unit")),
                QualityMetricOperator.fromWire(text(node, "operator")),
                integer(node, "thresholdNumerator"),
                integer(node, "thresholdDenominator"),
                QualityMetricBoundary.fromWire(text(node, "boundary")),
                nullableText(node.get("reasonCode")))).toList();
    }

    private static List<String> expectedFormulaOrder(String sourceId) {
        ExecutableQualityPolicy.SourcePolicy source = CONTRACT.policy().sources().stream()
                .filter(candidate -> candidate.sourceId().equals(sourceId))
                .findFirst().orElseThrow();
        Stream<String> common = CONTRACT.policy().commonMetrics().stream()
                .filter(metric -> source.applicableCommonMetricIds().contains(metric.metricId()))
                .map(ExecutableQualityPolicy.MetricDefinition::formulaId);
        Stream<String> gates = source.sourceGates().stream()
                .map(ExecutableQualityPolicy.MetricDefinition::formulaId);
        return Stream.concat(common, gates).toList();
    }

    private static List<QualityMetricResult> mutateFailedNumerator(
            List<QualityMetricResult> metrics) {
        ArrayList<QualityMetricResult> result = new ArrayList<>(metrics);
        int index = 0;
        while (index < result.size()
                && result.get(index).result() != QualityMetricResultStatus.FAILED) {
            index++;
        }
        QualityMetricResult metric = result.get(index);
        BigInteger numerator = metric.numerator().subtract(BigInteger.ONE);
        result.set(index, new QualityMetricResult(
                metric.metricId(), metric.formulaId(), metric.formulaVersion(), metric.result(),
                metric.applicable(), numerator, metric.denominator(), numerator,
                metric.unit(), metric.operator(), metric.thresholdNumerator(),
                metric.thresholdDenominator(), metric.boundary(), metric.reasonCode()));
        return List.copyOf(result);
    }

    private static QualitySnapshot copy(
            QualitySnapshot value,
            UUID snapshotId,
            Instant evaluatedAt,
            String traceId,
            long aggregateVersion,
            String immutableHash,
            String watermark,
            Instant cutoffAt,
            List<QualityMetricResult> metricResults,
            UUID supersedesSnapshotId) {
        return new QualitySnapshot(
                value.domainTag(), value.hashProfileVersion(), value.hashProfileDigest(),
                value.batchId(), value.sourceId(), value.assessedBatchStatus(),
                value.overallResult(), value.observationWindow(), cutoffAt, watermark,
                metricResults, value.impactScopeCodes(), value.sourceOwnerRef(),
                value.approvalRef(), value.effectiveAt(), value.retentionScheduleVersion(),
                value.qualityMetricDecisionProfileVersion(),
                value.qualityMetricDecisionProfileDigest(), value.qualityGateVersion(),
                value.qualityGateDigest(), value.canonicalizationProfile(),
                value.manifestDigest(), value.sourceSchemaVersion(), value.sourceSchemaDigest(),
                value.lineageId(), supersedesSnapshotId, snapshotId, evaluatedAt, traceId,
                aggregateVersion, immutableHash);
    }

    private static byte[] bytes(QualitySnapshot snapshot) {
        return CANONICALIZER.canonicalUtf8(CANONICALIZER.materialize(snapshot));
    }

    private static String canonical(QualitySnapshot snapshot) {
        return new String(bytes(snapshot), StandardCharsets.UTF_8);
    }

    private static String hash(QualitySnapshot snapshot) {
        return CANONICALIZER.immutableHash(CANONICALIZER.materialize(snapshot));
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false);
    }

    private static List<String> strings(JsonNode array) {
        return stream(array).map(JsonNode::asText).toList();
    }

    private static String text(JsonNode parent, String field) {
        return parent.required(field).asText();
    }

    private static Instant instant(JsonNode parent, String field) {
        return Instant.parse(text(parent, field));
    }

    private static BigInteger integer(JsonNode parent, String field) {
        return parent.required(field).bigIntegerValue();
    }

    private static BigInteger nullableInteger(JsonNode node) {
        return node == null || node.isNull() ? null : node.bigIntegerValue();
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static UUID nullableUuid(JsonNode node) {
        return node == null || node.isNull() ? null : uuid(node.asText());
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static DataBatchStatus dataBatchStatus(String wireValue) {
        return Arrays.stream(DataBatchStatus.values())
                .filter(value -> value.wireValue().equals(wireValue))
                .findFirst().orElseThrow();
    }

    private static QualityOverallResult overallResult(String wireValue) {
        return Arrays.stream(QualityOverallResult.values())
                .filter(value -> value.wireValue().equals(wireValue))
                .findFirst().orElseThrow();
    }

    private static QualityMetricResultStatus metricStatus(String wireValue) {
        return Arrays.stream(QualityMetricResultStatus.values())
                .filter(value -> value.wireValue().equals(wireValue))
                .findFirst().orElseThrow();
    }

    private record Golden(
            String caseId,
            JsonNode snapshot,
            String expectedCanonicalUtf8Hex,
            String expectedImmutableHash) {
        @Override
        public String toString() {
            return caseId;
        }
    }
}
