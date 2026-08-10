package cn.edu.suda.scholarsense.ingestionquality.domain;

import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.AdvanceHorizonRule;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.DurationRule;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.FreshnessLane;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.LocalCutoffRule;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.MetricDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.SourcePolicy;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Evaluates the closed QMDP delivery-lane rules over a cutoff-anchored rolling ledger. */
public final class QualityFreshnessEvaluator {
    private static final String FRESHNESS_METRIC = "FRESHNESS_WITHIN_SLO_BP";
    private static final String WINDOW_INTERVAL = "[windowStartAt,windowEndAt)";
    private static final String STORAGE_TIMEZONE = "UTC";
    private static final String SCHEDULE_TIMEZONE = "Asia/Shanghai";
    private static final String CUTOFF_BOUNDARY = "inclusive";
    private static final long MAX_FRESHNESS_MILLISECONDS = 2_592_000_000L;
    private static final Set<String> LANE_UNITS = Set.of(
            "record", "scheduled-partition", "authority-fact", "fault-fact",
            "sealed-batch", "calendar-projection");
    private static final Set<String> DURATION_LATER_FIELDS = Set.of(
            "record.receivedAt", "manifest.receivedAt");
    private static final Set<String> DURATION_EARLIER_FIELDS = Set.of(
            "record.sourceUpdatedAt", "record.occurredAt", "record.heartbeatAt",
            "record.effectiveFrom", "record.effectiveAt", "record.correctionEffectiveAt",
            "manifest.sourceOccurredAt");
    private static final Pattern LOCAL_TIME = Pattern.compile(
            "^(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$");

    public MeasuredQualityInputs measure(
            ExecutableQualityPolicy policy,
            String sourceId,
            String laneId,
            BatchObservationWindow ledgerWindow,
            Instant cutoffAt,
            List<Map<String, Instant>> deliveryUnits) {
        Objects.requireNonNull(policy);
        String selectedSourceId = required(sourceId);
        String selectedLaneId = required(laneId);
        Objects.requireNonNull(ledgerWindow);
        Objects.requireNonNull(cutoffAt);

        validateWindow(policy, ledgerWindow, cutoffAt);
        SourcePolicy source = uniqueSource(policy, selectedSourceId);
        FreshnessLane lane = uniqueLane(source, selectedLaneId);
        validateLane(lane);
        MetricDefinition metric = uniqueFreshnessMetric(policy);

        List<Map<String, Instant>> frozenUnits = freeze(deliveryUnits);
        long expected = 0;
        long onTime = 0;
        for (Map<String, Instant> unit : frozenUnits) {
            Set<String> expectedFields = Set.of(
                    lane.rule().earlierField(), lane.rule().laterField());
            if (!unit.keySet().equals(expectedFields)) throw evidenceInvalid();
            Instant earlier = unit.get(lane.rule().earlierField());
            Instant later = unit.get(lane.rule().laterField());
            if (earlier == null || later == null) throw evidenceInvalid();
            if (!contains(ledgerWindow, windowMembershipInstant(lane, earlier))) continue;

            expected++;
            Instant arrivalAt = lane.rule() instanceof AdvanceHorizonRule ? earlier : later;
            if (!arrivalAt.isAfter(cutoffAt) && withinLaneBoundary(lane, earlier, later)) {
                onTime++;
            }
        }

        LinkedHashMap<String, BigInteger> operands = new LinkedHashMap<>();
        operands.put(
                Objects.requireNonNull(metric.calculation().numerator().operandId()),
                BigInteger.valueOf(onTime));
        operands.put(
                Objects.requireNonNull(metric.calculation().denominator().operandId()),
                BigInteger.valueOf(expected));
        return new MeasuredQualityInputs(true, operands);
    }

    private static void validateWindow(
            ExecutableQualityPolicy policy,
            BatchObservationWindow ledgerWindow,
            Instant cutoffAt) {
        var semantics = policy.windowSemantics();
        if (!WINDOW_INTERVAL.equals(semantics.interval())
                || !STORAGE_TIMEZONE.equals(semantics.storageTimezone())
                || !SCHEDULE_TIMEZONE.equals(semantics.scheduleTimezone())
                || semantics.freshnessWindowHours() != 720
                || !CUTOFF_BOUNDARY.equals(semantics.cutoffBoundary())) {
            throw contractInvalid();
        }
        Instant expectedStart;
        try {
            expectedStart = cutoffAt.minus(Duration.ofHours(semantics.freshnessWindowHours()));
        } catch (DateTimeException | ArithmeticException invalidCutoff) {
            throw contractInvalid();
        }
        if (!ledgerWindow.startAt().equals(expectedStart)
                || !ledgerWindow.endAt().equals(cutoffAt)) {
            throw contractInvalid();
        }
    }

    private static boolean contains(BatchObservationWindow window, Instant instant) {
        return !instant.isBefore(window.startAt()) && instant.isBefore(window.endAt());
    }

    private static Instant windowMembershipInstant(FreshnessLane lane, Instant earlier) {
        return lane.rule() instanceof LocalCutoffRule localCutoff
                ? localCutoffDueAt(lane, localCutoff, earlier)
                : earlier;
    }

    private static boolean withinLaneBoundary(
            FreshnessLane lane,
            Instant earlier,
            Instant later) {
        return switch (lane.rule()) {
            case DurationRule duration -> {
                if (later.isBefore(earlier)) throw evidenceInvalid();
                yield Duration.between(earlier, later)
                                .compareTo(Duration.ofMillis(duration.maxDurationMilliseconds()))
                        <= 0;
            }
            case LocalCutoffRule localCutoff -> {
                Instant dueAt = localCutoffDueAt(lane, localCutoff, earlier);
                yield !later.isAfter(dueAt);
            }
            case AdvanceHorizonRule advance ->
                    Duration.between(earlier, later)
                                    .compareTo(Duration.ofMillis(
                                            advance.minimumLeadMilliseconds()))
                            >= 0;
        };
    }

    private static Instant localCutoffDueAt(
            FreshnessLane lane,
            LocalCutoffRule localCutoff,
            Instant scheduleAnchor) {
        try {
            ZoneId zone = ZoneId.of(lane.timezone());
            return scheduleAnchor.atZone(zone)
                    .toLocalDate()
                    .plusDays(localCutoff.dayOffset())
                    .atTime(LocalTime.parse(localCutoff.dueLocalTime()))
                    .atZone(zone)
                    .toInstant();
        } catch (DateTimeException | ArithmeticException invalidRule) {
            throw contractInvalid();
        }
    }

    private static void validateLane(FreshnessLane lane) {
        if (!SCHEDULE_TIMEZONE.equals(lane.timezone())
                || !lane.inclusive()
                || lane.allowNoActivity()
                || !LANE_UNITS.contains(lane.unit())) {
            throw contractInvalid();
        }
        switch (lane.rule()) {
            case DurationRule duration -> {
                if (!"duration".equals(duration.kind())
                        || !DURATION_LATER_FIELDS.contains(duration.laterField())
                        || !DURATION_EARLIER_FIELDS.contains(duration.earlierField())
                        || duration.maxDurationMilliseconds() < 1
                        || duration.maxDurationMilliseconds() > MAX_FRESHNESS_MILLISECONDS) {
                    throw contractInvalid();
                }
            }
            case LocalCutoffRule localCutoff -> {
                if (!"local-cutoff".equals(localCutoff.kind())
                        || !"manifest.receivedAt".equals(localCutoff.laterField())
                        || !"manifest.scheduledDueAt".equals(localCutoff.earlierField())
                        || !LOCAL_TIME.matcher(localCutoff.dueLocalTime()).matches()
                        || localCutoff.dayOffset() < 0
                        || localCutoff.dayOffset() > 1) {
                    throw contractInvalid();
                }
            }
            case AdvanceHorizonRule advance -> {
                if (!"advance-horizon".equals(advance.kind())
                        || !"manifest.scheduledDueAt".equals(advance.laterField())
                        || !"manifest.receivedAt".equals(advance.earlierField())
                        || advance.minimumLeadMilliseconds() < 1
                        || advance.minimumLeadMilliseconds() > MAX_FRESHNESS_MILLISECONDS) {
                    throw contractInvalid();
                }
            }
        }
    }

    private static SourcePolicy uniqueSource(
            ExecutableQualityPolicy policy,
            String sourceId) {
        List<SourcePolicy> matches = policy.sources().stream()
                .filter(candidate -> candidate.sourceId().equals(sourceId))
                .toList();
        if (matches.size() != 1) throw contractInvalid();
        return matches.getFirst();
    }

    private static FreshnessLane uniqueLane(SourcePolicy source, String laneId) {
        List<FreshnessLane> matches = source.freshnessLanes().stream()
                .filter(candidate -> candidate.laneId().equals(laneId))
                .toList();
        if (matches.size() != 1) throw contractInvalid();
        return matches.getFirst();
    }

    private static MetricDefinition uniqueFreshnessMetric(ExecutableQualityPolicy policy) {
        List<MetricDefinition> matches = policy.commonMetrics().stream()
                .filter(candidate -> candidate.metricId().equals(FRESHNESS_METRIC))
                .toList();
        if (matches.size() != 1) throw contractInvalid();
        MetricDefinition metric = matches.getFirst();
        if (!"ratio".equals(metric.calculation().kind())
                || !"on-time-delivery-units".equals(
                        metric.calculation().numerator().operandId())
                || !"expected-delivery-units".equals(
                        metric.calculation().denominator().operandId())) {
            throw contractInvalid();
        }
        return metric;
    }

    private static List<Map<String, Instant>> freeze(
            List<Map<String, Instant>> deliveryUnits) {
        if (deliveryUnits == null) throw evidenceInvalid();
        List<Map<String, Instant>> result = new ArrayList<>(deliveryUnits.size());
        try {
            for (Map<String, Instant> unit : deliveryUnits) {
                result.add(Map.copyOf(Objects.requireNonNull(unit)));
            }
        } catch (RuntimeException invalidEvidence) {
            throw evidenceInvalid();
        }
        return List.copyOf(result);
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw contractInvalid();
        return value;
    }

    private static IngestionQualityException contractInvalid() {
        return new IngestionQualityException(
                IngestionQualityErrorCode.INGESTION_QUALITY_CONTRACT_INVALID);
    }

    private static IngestionQualityException evidenceInvalid() {
        return new IngestionQualityException(
                IngestionQualityErrorCode.INGESTION_QUALITY_EVIDENCE_INVALID);
    }
}
