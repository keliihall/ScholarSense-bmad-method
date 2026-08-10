package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenExecutableQualityPolicyLoader;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.MetricDefinition;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Task 2.4 date/lane oracle for the three approved QMDP freshness rule kinds. */
class QualityFreshnessEvaluatorTest {
    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
    private static final ExecutableQualityPolicy POLICY =
            new FrozenExecutableQualityPolicyLoader(REPOSITORY).loadVerified().policy();
    private static final QualityFreshnessEvaluator EVALUATOR =
            new QualityFreshnessEvaluator();
    private static final Instant CUTOFF = Instant.parse("2026-08-10T00:00:00Z");
    private static final BatchObservationWindow WINDOW = new BatchObservationWindow(
            CUTOFF.minus(Duration.ofHours(720)), CUTOFF);

    @Test
    void durationRuleUsesAbsoluteUtcTimeAndItsInclusiveMillisecondBoundary() {
        Instant occurredAt = OffsetDateTime.parse("2026-08-09T20:00:00+08:00").toInstant();
        Instant exact = OffsetDateTime.parse("2026-08-10T00:00:00+08:00").toInstant();

        MeasuredQualityInputs measured = measure(
                "SRC-P0-STUDENT-001",
                "incremental-record",
                List.of(
                        fields(
                                "record.sourceUpdatedAt", occurredAt,
                                "manifest.receivedAt", exact.minusMillis(1)),
                        fields(
                                "record.sourceUpdatedAt", occurredAt,
                                "manifest.receivedAt", exact),
                        fields(
                                "record.sourceUpdatedAt", occurredAt,
                                "manifest.receivedAt", exact.plusMillis(1))));

        assertFreshnessCounts(2, 3, measured);
    }

    @Test
    void rollingWindowIsExactlySevenHundredTwentyHoursAndLeftClosedRightOpen() {
        assertEquals(Duration.ofHours(720), Duration.between(WINDOW.startAt(), WINDOW.endAt()));

        MeasuredQualityInputs measured = measure(
                "SRC-P0-STUDENT-001",
                "incremental-record",
                List.of(
                        fields(
                                "record.sourceUpdatedAt", WINDOW.startAt().minusNanos(1_000),
                                "manifest.receivedAt", WINDOW.startAt().minusNanos(1_000)),
                        fields(
                                "record.sourceUpdatedAt", WINDOW.startAt(),
                                "manifest.receivedAt", WINDOW.startAt()),
                        fields(
                                "record.sourceUpdatedAt", WINDOW.endAt().minusNanos(1_000),
                                "manifest.receivedAt", WINDOW.endAt().minusNanos(1_000)),
                        fields(
                                "record.sourceUpdatedAt", WINDOW.endAt(),
                                "manifest.receivedAt", WINDOW.endAt())));

        assertFreshnessCounts(2, 2, measured);
    }

    @Test
    void offsetAndDstTaggedInputsAreComparedAsAbsoluteInstants() {
        Instant dstTagged = OffsetDateTime.parse("2026-11-01T01:30:00-04:00").toInstant();
        Instant equivalentUtc = Instant.parse("2026-11-01T05:30:00Z");
        Instant receivedAt = equivalentUtc.plus(Duration.ofHours(4));
        Instant cutoff = Instant.parse("2026-11-02T00:00:00Z");
        BatchObservationWindow window = new BatchObservationWindow(
                cutoff.minus(Duration.ofHours(720)), cutoff);

        MeasuredQualityInputs tagged = EVALUATOR.measure(
                POLICY,
                "SRC-P0-STUDENT-001",
                "incremental-record",
                window,
                cutoff,
                List.of(fields(
                        "record.sourceUpdatedAt", dstTagged,
                        "manifest.receivedAt", receivedAt)));
        MeasuredQualityInputs utc = EVALUATOR.measure(
                POLICY,
                "SRC-P0-STUDENT-001",
                "incremental-record",
                window,
                cutoff,
                List.of(fields(
                        "record.sourceUpdatedAt", equivalentUtc,
                        "manifest.receivedAt", receivedAt)));

        assertEquals(dstTagged, equivalentUtc);
        assertEquals(tagged, utc);
        assertFreshnessCounts(1, 1, tagged);
    }

    @Test
    void sealedCutoffAcceptsOneMillisecondBeforeAndEqualityButRejectsOneAfter() {
        Instant occurredAt = CUTOFF.minus(Duration.ofHours(1));

        assertFreshnessCounts(1, 1, measure(
                "SRC-P0-STUDENT-001",
                "incremental-record",
                List.of(fields(
                        "record.sourceUpdatedAt", occurredAt,
                        "manifest.receivedAt", CUTOFF.minusMillis(1)))));
        assertFreshnessCounts(1, 1, measure(
                "SRC-P0-STUDENT-001",
                "incremental-record",
                List.of(fields(
                        "record.sourceUpdatedAt", occurredAt,
                        "manifest.receivedAt", CUTOFF))));
        assertFreshnessCounts(0, 1, measure(
                "SRC-P0-STUDENT-001",
                "incremental-record",
                List.of(fields(
                        "record.sourceUpdatedAt", occurredAt,
                        "manifest.receivedAt", CUTOFF.plusMillis(1)))));
    }

    @Test
    void localCutoffUsesAsiaShanghaiAndRemainsInclusiveAtTheScheduledDueInstant() {
        Instant scheduledDateAnchor =
                OffsetDateTime.parse("2026-08-08T00:00:00+08:00").toInstant();
        Instant dayOneDueAt =
                OffsetDateTime.parse("2026-08-09T08:00:00+08:00").toInstant();
        assertEquals(Instant.parse("2026-08-09T00:00:00Z"), dayOneDueAt);

        MeasuredQualityInputs dayOne = measure(
                "SRC-P1-NETWORK-001",
                "complete-partition",
                List.of(
                        fields(
                                "manifest.scheduledDueAt", scheduledDateAnchor,
                                "manifest.receivedAt", dayOneDueAt.minusMillis(1)),
                        fields(
                                "manifest.scheduledDueAt", scheduledDateAnchor,
                                "manifest.receivedAt", dayOneDueAt),
                        fields(
                                "manifest.scheduledDueAt", scheduledDateAnchor,
                                "manifest.receivedAt", dayOneDueAt.plusMillis(1))));
        Instant dayZeroDueAt =
                OffsetDateTime.parse("2026-08-08T06:00:00+08:00").toInstant();
        MeasuredQualityInputs dayZero = measure(
                "SRC-P0-STUDENT-001",
                "daily-full-partition",
                List.of(fields(
                        "manifest.scheduledDueAt", scheduledDateAnchor,
                        "manifest.receivedAt", dayZeroDueAt)));

        assertFreshnessCounts(2, 3, dayOne);
        assertFreshnessCounts(1, 1, dayZero);
    }

    @Test
    void localCutoffLedgerMembershipUsesTheDerivedDueInstantAtBothHalfOpenEdges() {
        Instant leftEdgeAnchor =
                OffsetDateTime.parse("2026-07-10T00:00:00+08:00").toInstant();
        Instant rightEdgeAnchor =
                OffsetDateTime.parse("2026-08-09T00:00:00+08:00").toInstant();

        assertEquals(WINDOW.startAt(),
                OffsetDateTime.parse("2026-07-11T08:00:00+08:00").toInstant());
        assertEquals(WINDOW.endAt(),
                OffsetDateTime.parse("2026-08-10T08:00:00+08:00").toInstant());

        assertFreshnessCounts(1, 1, measure(
                "SRC-P1-NETWORK-001",
                "complete-partition",
                List.of(fields(
                        "manifest.scheduledDueAt", leftEdgeAnchor,
                        "manifest.receivedAt", WINDOW.startAt()))));
        assertFreshnessCounts(0, 0, measure(
                "SRC-P1-NETWORK-001",
                "complete-partition",
                List.of(fields(
                        "manifest.scheduledDueAt", rightEdgeAnchor,
                        "manifest.receivedAt", WINDOW.endAt()))));
    }

    @Test
    void allThirtyOneApprovedLanesResolveByTheirExactSourceAndLanePair() {
        int laneCount = 0;
        for (var source : POLICY.sources()) {
            for (var lane : source.freshnessLanes()) {
                laneCount++;
                MeasuredQualityInputs measured = measure(
                        source.sourceId(), lane.laneId(), List.of(passingUnit(lane)));
                assertFreshnessCounts(1, 1, measured);
            }
        }

        assertEquals(31, laneCount);
    }

    @Test
    void localCutoffRejectsAReceivedTimeBeyondTheDerivedShanghaiBoundary() {
        Instant scheduledDateAnchor =
                OffsetDateTime.parse("2026-08-08T00:00:00+08:00").toInstant();
        Instant dayOneDueAt =
                OffsetDateTime.parse("2026-08-09T08:00:00+08:00").toInstant();

        MeasuredQualityInputs late = measure(
                "SRC-P1-NETWORK-001",
                "complete-partition",
                List.of(fields(
                        "manifest.scheduledDueAt", scheduledDateAnchor,
                        "manifest.receivedAt", dayOneDueAt.plusMillis(1))));

        assertFreshnessCounts(0, 1, late);
    }

    @Test
    void advanceHorizonRequiresTheInclusiveSevenDayLeadWithoutUsingCalendarDays() {
        Instant receivedAt = Instant.parse("2026-08-01T00:00:00Z");
        Instant sevenDaysAhead = receivedAt.plus(Duration.ofDays(7));

        MeasuredQualityInputs measured = measure(
                "SRC-P0-CALENDAR-001",
                "normal-projection",
                List.of(
                        fields(
                                "manifest.receivedAt", receivedAt,
                                "manifest.scheduledDueAt", sevenDaysAhead.plusMillis(1)),
                        fields(
                                "manifest.receivedAt", receivedAt,
                                "manifest.scheduledDueAt", sevenDaysAhead),
                        fields(
                                "manifest.receivedAt", receivedAt,
                                "manifest.scheduledDueAt", sevenDaysAhead.minusMillis(1))));

        assertFreshnessCounts(2, 3, measured);
    }

    @Test
    void advanceHorizonComparesTheArrivalToCutoffNotTheFutureProjectionDate() {
        Instant receivedAt = CUTOFF.minusMillis(1);
        Instant futureProjectionAt = receivedAt.plus(Duration.ofDays(7));

        MeasuredQualityInputs measured = measure(
                "SRC-P0-CALENDAR-001",
                "normal-projection",
                List.of(fields(
                        "manifest.receivedAt", receivedAt,
                        "manifest.scheduledDueAt", futureProjectionAt)));

        assertFreshnessCounts(1, 1, measured);
    }

    @Test
    void laneLookupIsTheExactSourceAndLanePairRatherThanAFirstLaneIdMatch() {
        IngestionQualityException wrongSourceLaneEvidence = assertThrows(
                IngestionQualityException.class,
                () -> measure(
                        "SRC-P1-OFFCAMPUS-001",
                        "change-record",
                        List.of(fields(
                                "record.sourceUpdatedAt", CUTOFF.minus(Duration.ofHours(2)),
                                "manifest.receivedAt", CUTOFF.minus(Duration.ofHours(1))))));
        IngestionQualityException unknownLane = assertThrows(
                IngestionQualityException.class,
                () -> measure(
                        "SRC-P0-STUDENT-001",
                        "change-record",
                        List.of(fields(
                                "record.sourceUpdatedAt", CUTOFF.minus(Duration.ofHours(2)),
                                "manifest.receivedAt", CUTOFF.minus(Duration.ofHours(1))))));

        assertEquals(
                IngestionQualityErrorCode.INGESTION_QUALITY_EVIDENCE_INVALID.name(),
                wrongSourceLaneEvidence.code());
        assertEquals(
                IngestionQualityErrorCode.INGESTION_QUALITY_CONTRACT_INVALID.name(),
                unknownLane.code());
    }

    @Test
    void missingOrAdditionalTimestampIsTechnicalWhileACompleteLateDeliveryIsAQualityFailure() {
        Instant occurredAt = CUTOFF.minus(Duration.ofHours(6));
        MeasuredQualityInputs late = measure(
                "SRC-P0-STUDENT-001",
                "incremental-record",
                List.of(fields(
                        "record.sourceUpdatedAt", occurredAt,
                        "manifest.receivedAt", CUTOFF.minus(Duration.ofHours(1)))));

        QualityMetricResult failed = new QualityMetricCalculator().calculate(
                freshnessDefinition(), late);
        assertEquals(QualityMetricResultStatus.FAILED, failed.result());
        assertFreshnessCounts(0, 1, late);

        IngestionQualityException technical = assertThrows(
                IngestionQualityException.class,
                () -> measure(
                        "SRC-P0-STUDENT-001",
                        "incremental-record",
                        List.of(Map.of("manifest.receivedAt", CUTOFF.minus(Duration.ofHours(1))))));
        Map<String, Instant> additionalField = new LinkedHashMap<>(fields(
                "record.sourceUpdatedAt", occurredAt,
                "manifest.receivedAt", CUTOFF.minus(Duration.ofHours(1))));
        additionalField.put("manifest.scheduledDueAt", CUTOFF.minus(Duration.ofDays(1)));
        IngestionQualityException extra = assertThrows(
                IngestionQualityException.class,
                () -> measure(
                        "SRC-P0-STUDENT-001",
                        "incremental-record",
                        List.of(additionalField)));
        assertEquals(
                IngestionQualityErrorCode.INGESTION_QUALITY_EVIDENCE_INVALID.name(),
                technical.code());
        assertEquals(
                IngestionQualityErrorCode.INGESTION_QUALITY_EVIDENCE_INVALID.name(),
                extra.code());
    }

    @Test
    void freshnessRuleObjectsOutsideTheClosedContractFailBeforeEvidenceIsMeasured() {
        var original = POLICY.sources().getFirst().freshnessLanes().getFirst();
        List<ExecutableQualityPolicy.FreshnessLane> invalidLanes = List.of(
                new ExecutableQualityPolicy.FreshnessLane(
                        original.laneId(), original.unit(), original.timezone(), true, false,
                        new ExecutableQualityPolicy.DurationRule(
                                "duration", "manifest.receivedAt",
                                "record.sourceUpdatedAt", 0)),
                new ExecutableQualityPolicy.FreshnessLane(
                        original.laneId(), original.unit(), original.timezone(), true, false,
                        new ExecutableQualityPolicy.DurationRule(
                                "duration", "manifest.receivedAt",
                                "record.sourceUpdatedAt", 2_592_000_001L)),
                new ExecutableQualityPolicy.FreshnessLane(
                        original.laneId(), original.unit(), original.timezone(), true, false,
                        new ExecutableQualityPolicy.LocalCutoffRule(
                                "local-cutoff", "manifest.receivedAt",
                                "manifest.scheduledDueAt", "06:00:00", 2)),
                new ExecutableQualityPolicy.FreshnessLane(
                        original.laneId(), original.unit(), original.timezone(), true, false,
                        new ExecutableQualityPolicy.AdvanceHorizonRule(
                                "advance-horizon", "manifest.scheduledDueAt",
                                "manifest.receivedAt", 0)));

        for (var invalidLane : invalidLanes) {
            IngestionQualityException failure = assertThrows(
                    IngestionQualityException.class,
                    () -> EVALUATOR.measure(
                            withFirstLane(invalidLane),
                            POLICY.sources().getFirst().sourceId(),
                            invalidLane.laneId(),
                            WINDOW,
                            CUTOFF,
                            List.of()));
            assertEquals(
                    IngestionQualityErrorCode.INGESTION_QUALITY_CONTRACT_INVALID.name(),
                    failure.code());
        }
    }

    private static MeasuredQualityInputs measure(
            String sourceId,
            String laneId,
            List<Map<String, Instant>> deliveryUnits) {
        return EVALUATOR.measure(POLICY, sourceId, laneId, WINDOW, CUTOFF, deliveryUnits);
    }

    private static void assertFreshnessCounts(
            long expectedOnTime,
            long expectedDeliveries,
            MeasuredQualityInputs measured) {
        assertEquals(true, measured.applicable());
        assertEquals(
                Map.of(
                        "on-time-delivery-units", BigInteger.valueOf(expectedOnTime),
                        "expected-delivery-units", BigInteger.valueOf(expectedDeliveries)),
                measured.operands());
    }

    private static MetricDefinition freshnessDefinition() {
        return POLICY.commonMetrics().stream()
                .filter(metric -> metric.metricId().equals("FRESHNESS_WITHIN_SLO_BP"))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Instant> fields(
            String firstName,
            Instant firstValue,
            String secondName,
            Instant secondValue) {
        return Map.of(firstName, firstValue, secondName, secondValue);
    }

    private static Map<String, Instant> passingUnit(
            ExecutableQualityPolicy.FreshnessLane lane) {
        Instant earlier;
        Instant later;
        switch (lane.rule()) {
            case ExecutableQualityPolicy.DurationRule duration -> {
                earlier = Instant.parse("2026-08-08T00:00:00Z");
                later = earlier.plusMillis(duration.maxDurationMilliseconds());
            }
            case ExecutableQualityPolicy.LocalCutoffRule localCutoff -> {
                ZoneId zone = ZoneId.of(lane.timezone());
                earlier = OffsetDateTime.parse("2026-08-08T00:00:00+08:00").toInstant();
                later = earlier.atZone(zone).toLocalDate()
                        .plusDays(localCutoff.dayOffset())
                        .atTime(LocalTime.parse(localCutoff.dueLocalTime()))
                        .atZone(zone)
                        .toInstant();
            }
            case ExecutableQualityPolicy.AdvanceHorizonRule advance -> {
                earlier = Instant.parse("2026-08-01T00:00:00Z");
                later = earlier.plusMillis(advance.minimumLeadMilliseconds());
            }
        }
        return fields(lane.rule().earlierField(), earlier, lane.rule().laterField(), later);
    }

    private static ExecutableQualityPolicy withFirstLane(
            ExecutableQualityPolicy.FreshnessLane replacement) {
        var first = POLICY.sources().getFirst();
        var lanes = new java.util.ArrayList<>(first.freshnessLanes());
        lanes.set(0, replacement);
        var changedSource = new ExecutableQualityPolicy.SourcePolicy(
                first.sourceId(), first.owner(), first.schemaBinding(),
                first.applicableCommonMetricIds(), first.sourceGates(), lanes);
        var sources = new java.util.ArrayList<>(POLICY.sources());
        sources.set(0, changedSource);
        return new ExecutableQualityPolicy(
                POLICY.profileVersion(), POLICY.decisionId(), POLICY.authorityRef(),
                POLICY.approvalRef(), POLICY.approvedBy(), POLICY.approvedAt(),
                POLICY.effectiveAt(), POLICY.owner(), POLICY.evidenceRef(), POLICY.status(),
                POLICY.canonicalization(), POLICY.numericSemantics(), POLICY.windowSemantics(),
                POLICY.freshnessManifestBinding(), POLICY.controlledInputs(),
                POLICY.commonMetrics(), sources, POLICY.nonMetricConstraints(),
                POLICY.overallResult());
    }
}
