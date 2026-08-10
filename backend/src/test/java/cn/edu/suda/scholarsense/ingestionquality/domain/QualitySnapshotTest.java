package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class QualitySnapshotTest {

    private static final BigInteger MAX_SAFE_INTEGER =
            BigInteger.valueOf(9_007_199_254_740_991L);
    private static final UUID SNAPSHOT_ID = uuid("019fe66d-7c00-7000-8000-000000000004");
    private static final UUID BATCH_ID = uuid("019fe66d-7c00-7000-8000-000000000011");
    private static final UUID LINEAGE_ID = uuid("019fe66d-7c00-7000-8000-000000000021");
    private static final Instant WINDOW_START = Instant.parse("2026-08-08T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-08-09T00:00:00Z");
    private static final String HASH_PROFILE_DIGEST =
            "sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2";
    private static final String IMMUTABLE_HASH =
            "sha256:31a0a659703230a888e6d3512f1805cffb77ec89c05decb5ce05ec70df455724";

    @Test
    void recordsFreezeTheApprovedQshmFieldSetsWithoutRawCount() {
        assertTrue(QualitySnapshot.class.isRecord());
        assertTrue(QualityMetricResult.class.isRecord());
        assertTrue(QualityAssessment.class.isRecord());
        assertTrue(Modifier.isFinal(QualitySnapshot.class.getModifiers()));

        assertRecordComponents(QualityMetricResult.class, List.of(
                "metricId", "formulaId", "formulaVersion", "result", "applicable",
                "numerator", "denominator", "valueBasisPoints", "unit", "operator",
                "thresholdNumerator", "thresholdDenominator", "boundary", "reasonCode"));
        assertRecordComponents(QualityAssessment.class,
                List.of("overallResult", "metricResults"));

        Set<String> included = Set.of(
                "domainTag", "hashProfileVersion", "hashProfileDigest", "batchId", "sourceId",
                "assessedBatchStatus", "overallResult", "observationWindow", "cutoffAt",
                "watermark", "metricResults", "impactScopeCodes", "sourceOwnerRef",
                "approvalRef", "effectiveAt", "retentionScheduleVersion",
                "qualityMetricDecisionProfileVersion", "qualityMetricDecisionProfileDigest",
                "qualityGateVersion", "qualityGateDigest", "canonicalizationProfile",
                "manifestDigest", "sourceSchemaVersion", "sourceSchemaDigest", "lineageId",
                "supersedesSnapshotId");
        Set<String> excluded = Set.of(
                "snapshotId", "evaluatedAt", "traceId", "aggregateVersion", "immutableHash");
        Set<String> actual = new LinkedHashSet<>(recordComponentNames(QualitySnapshot.class));

        assertEquals(26, included.size());
        assertEquals(5, excluded.size());
        assertEquals(31, actual.size());
        assertEquals(union(included, excluded), actual);
        assertFalse(Stream.of(QualitySnapshot.class, QualityMetricResult.class, QualityAssessment.class)
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(RecordComponent::getName)
                .anyMatch("rawCount"::equals));
    }

    @Test
    void metricEnumsExposeOnlyTheApprovedWireVocabulary() {
        assertEquals(List.of("passed", "failed", "not-applicable"),
                Arrays.stream(QualityMetricResultStatus.values())
                        .map(QualityMetricResultStatus::wireValue).toList());
        assertEquals(List.of("basis-point", "count", "millisecond", "member-count"),
                Arrays.stream(QualityMetricUnit.values())
                        .map(QualityMetricUnit::wireValue).toList());
        assertEquals(List.of(">=", "<=", "="),
                Arrays.stream(QualityMetricOperator.values())
                        .map(QualityMetricOperator::wireValue).toList());
        assertEquals(List.of("inclusive"),
                Arrays.stream(QualityMetricBoundary.values())
                        .map(QualityMetricBoundary::wireValue).toList());
        assertEquals(List.of("quality-passed", "quality-failed"),
                Arrays.stream(QualityOverallResult.values())
                        .map(QualityOverallResult::wireValue).toList());
    }

    @Test
    void metricEvidenceUsesClosedOperandsAndSafeIntegers() {
        QualityMetricResult passed = passedMetric();
        assertEquals(BigInteger.valueOf(9_950), passed.numerator());
        assertEquals(BigInteger.valueOf(10_000), passed.denominator());
        assertEquals(BigInteger.valueOf(9_950), passed.valueBasisPoints());

        assertThrows(IngestionQualityException.class, () -> metric(
                QualityMetricResultStatus.PASSED, true,
                MAX_SAFE_INTEGER.add(BigInteger.ONE), BigInteger.ONE, null));
        assertThrows(IngestionQualityException.class, () -> metric(
                QualityMetricResultStatus.PASSED, true,
                BigInteger.valueOf(-1), BigInteger.ONE, null));
        assertThrows(IngestionQualityException.class, () -> metric(
                QualityMetricResultStatus.NOT_APPLICABLE, false,
                BigInteger.ONE, BigInteger.ZERO, null));
        assertThrows(IngestionQualityException.class, () -> metric(
                QualityMetricResultStatus.PASSED, false,
                BigInteger.ZERO, BigInteger.ZERO, null));
        assertThrows(IngestionQualityException.class, () -> new QualityMetricResult(
                "PRIMARY_KEY_COMPLETENESS_BP", "wrong/formula", "1.0.0",
                QualityMetricResultStatus.PASSED, true,
                BigInteger.ONE, BigInteger.ONE, BigInteger.valueOf(10_000),
                QualityMetricUnit.BASIS_POINT,
                QualityMetricOperator.GREATER_THAN_OR_EQUAL,
                BigInteger.ONE, BigInteger.ONE, QualityMetricBoundary.INCLUSIVE, null));

        QualityMetricResult notApplicable = metric(
                QualityMetricResultStatus.NOT_APPLICABLE, false,
                BigInteger.ZERO, BigInteger.ZERO, null);
        assertFalse(notApplicable.applicable());
    }

    @Test
    void assessmentAndSnapshotDefensivelyCopyEvidenceAndKeepOverallConsistent() {
        ArrayList<QualityMetricResult> mutableMetrics = new ArrayList<>(List.of(passedMetric()));
        QualityAssessment assessment = new QualityAssessment(
                QualityOverallResult.QUALITY_PASSED, mutableMetrics);
        mutableMetrics.clear();

        assertEquals(1, assessment.metricResults().size());
        assertThrows(UnsupportedOperationException.class,
                () -> assessment.metricResults().add(passedMetric()));
        assertThrows(IngestionQualityException.class, () -> new QualityAssessment(
                QualityOverallResult.QUALITY_FAILED, List.of(passedMetric())));
        assertThrows(IngestionQualityException.class, () -> new QualityAssessment(
                QualityOverallResult.QUALITY_PASSED, List.of(failedMetric())));

        ArrayList<QualityMetricResult> snapshotMetrics =
                new ArrayList<>(assessment.metricResults());
        ArrayList<String> scopes = new ArrayList<>(List.of("CONTINUITY", "PRIMARY_KEY"));
        QualitySnapshot snapshot = snapshot(
                DataBatchStatus.QUALITY_PASSED,
                QualityOverallResult.QUALITY_PASSED,
                snapshotMetrics,
                scopes,
                IMMUTABLE_HASH);
        snapshotMetrics.clear();
        scopes.clear();

        assertEquals(1, snapshot.metricResults().size());
        assertEquals(List.of("CONTINUITY", "PRIMARY_KEY"), snapshot.impactScopeCodes());
        assertNotSame(snapshotMetrics, snapshot.metricResults());
        assertNotSame(scopes, snapshot.impactScopeCodes());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.metricResults().add(passedMetric()));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.impactScopeCodes().add("FRESHNESS"));
        assertThrows(IngestionQualityException.class, () -> snapshot(
                DataBatchStatus.QUALITY_FAILED,
                QualityOverallResult.QUALITY_PASSED,
                List.of(passedMetric()),
                List.of(),
                IMMUTABLE_HASH));
    }

    @Test
    void snapshotRequiresARealExternallyGeneratedImmutableHash() {
        assertThrows(IngestionQualityException.class, () -> snapshot(
                DataBatchStatus.QUALITY_PASSED,
                QualityOverallResult.QUALITY_PASSED,
                List.of(passedMetric()),
                List.of(),
                null));
        assertThrows(IngestionQualityException.class, () -> snapshot(
                DataBatchStatus.QUALITY_PASSED,
                QualityOverallResult.QUALITY_PASSED,
                List.of(passedMetric()),
                List.of(),
                "sha256:" + "0".repeat(64)));
        assertThrows(IngestionQualityException.class, () -> snapshot(
                DataBatchStatus.SEALED,
                QualityOverallResult.QUALITY_FAILED,
                List.of(failedMetric()),
                List.of(),
                IMMUTABLE_HASH));
    }

    @Test
    void qshmTextUsesUnicodeScalarLengthAndRejectsUnpairedSurrogates() {
        String supplementaryScalar = new String(Character.toChars(0x10000));
        String exactlySixtyFourScalars = supplementaryScalar.repeat(64);

        QualitySnapshot valid = snapshot(
                DataBatchStatus.QUALITY_PASSED,
                QualityOverallResult.QUALITY_PASSED,
                List.of(passedMetric()),
                List.of(exactlySixtyFourScalars),
                IMMUTABLE_HASH);
        assertEquals(List.of(exactlySixtyFourScalars), valid.impactScopeCodes());

        assertThrows(IngestionQualityException.class, () -> snapshot(
                DataBatchStatus.QUALITY_PASSED,
                QualityOverallResult.QUALITY_PASSED,
                List.of(passedMetric()),
                List.of(exactlySixtyFourScalars + "A"),
                IMMUTABLE_HASH));
        assertThrows(IngestionQualityException.class, () -> snapshot(
                DataBatchStatus.QUALITY_PASSED,
                QualityOverallResult.QUALITY_PASSED,
                List.of(passedMetric()),
                List.of("\uD800"),
                IMMUTABLE_HASH));
        assertThrows(IngestionQualityException.class, () -> snapshot(
                DataBatchStatus.QUALITY_PASSED,
                QualityOverallResult.QUALITY_PASSED,
                List.of(passedMetric()),
                List.of("\uDC00"),
                IMMUTABLE_HASH));
    }

    private static QualityMetricResult passedMetric() {
        return metric(
                QualityMetricResultStatus.PASSED, true,
                BigInteger.valueOf(9_950), BigInteger.valueOf(10_000),
                BigInteger.valueOf(9_950));
    }

    private static QualityMetricResult failedMetric() {
        return metric(
                QualityMetricResultStatus.FAILED, true,
                BigInteger.valueOf(9_949), BigInteger.valueOf(10_000),
                BigInteger.valueOf(9_949));
    }

    private static QualityMetricResult metric(
            QualityMetricResultStatus result,
            boolean applicable,
            BigInteger numerator,
            BigInteger denominator,
            BigInteger valueBasisPoints) {
        return new QualityMetricResult(
                "PRIMARY_KEY_COMPLETENESS_BP",
                "QMDP-1.0.0/PRIMARY_KEY_COMPLETENESS_BP",
                "1.0.0",
                result,
                applicable,
                numerator,
                denominator,
                valueBasisPoints,
                QualityMetricUnit.BASIS_POINT,
                QualityMetricOperator.GREATER_THAN_OR_EQUAL,
                BigInteger.valueOf(9_950),
                BigInteger.valueOf(10_000),
                QualityMetricBoundary.INCLUSIVE,
                null);
    }

    private static QualitySnapshot snapshot(
            DataBatchStatus status,
            QualityOverallResult overallResult,
            List<QualityMetricResult> metricResults,
            List<String> impactScopes,
            String immutableHash) {
        return new QualitySnapshot(
                "scholarsense.ingestion-quality.quality-snapshot.immutable-hash.v1",
                "QSHM-1.0.0",
                HASH_PROFILE_DIGEST,
                BATCH_ID,
                "SRC-P0-CALENDAR-001",
                status,
                overallResult,
                new BatchObservationWindow(WINDOW_START, WINDOW_END),
                WINDOW_END,
                "calendar@2026-08-09",
                metricResults,
                impactScopes,
                "calendar-owner",
                "AUTH-2026-08-08-001",
                Instant.parse("2026-08-09T02:02:22Z"),
                "RS-1.0.0",
                "QMDP-1.0.0",
                "sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8",
                "QG-1.0.0",
                "sha256:0a6701cc598be754813bdddbe7e0cbff7b65c1e03ca2cd026d63ee2d366a4e3a",
                "SCHOLARSENSE-CANONICAL-JSON-1.0.0",
                "sha256:" + "a".repeat(64),
                "BC-1.0.0",
                "sha256:7436c9709e1c331c99d6c018d3129537bb4c24db1462396b3b82abcbe2cf2f87",
                LINEAGE_ID,
                null,
                SNAPSHOT_ID,
                Instant.parse("2026-08-09T02:03:00.123456Z"),
                "0123456789abcdef0123456789abcdef",
                3,
                immutableHash);
    }

    private static void assertRecordComponents(Class<?> type, List<String> expected) {
        assertEquals(expected, recordComponentNames(type));
    }

    private static List<String> recordComponentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        LinkedHashSet<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        return union;
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
