package cn.edu.suda.scholarsense.auditoperations.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.edu.suda.scholarsense.auditoperations.domain.AvailabilityState;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuditBacklogEvaluatorTest {
    private static final Instant NOW = Instant.parse("2026-07-20T02:00:00Z");

    @Test
    void exactGreaterThanOrEqualBoundariesProduceFrozenStates() {
        assertEquals(AvailabilityState.HEALTHY, freshEvaluator().evaluate(measurement(9_999, 59), NOW).state());
        assertEquals(AvailabilityState.DEGRADED, freshEvaluator().evaluate(measurement(10_000, 59), NOW).state());
        assertEquals(AvailabilityState.DEGRADED, freshEvaluator().evaluate(measurement(9_999, 60), NOW).state());
        assertEquals(AvailabilityState.BLOCKED, freshEvaluator().evaluate(measurement(50_000, 59), NOW).state());
        assertEquals(AvailabilityState.BLOCKED, freshEvaluator().evaluate(measurement(9_999, 300), NOW).state());
    }

    @Test
    void permanentFailureUnhealthyChainMissingAndStaleMeasurementsBlock() {
        assertEquals(AvailabilityState.BLOCKED,
                freshEvaluator().evaluate(new AuditBacklogMeasurement(0, 0, true, true, NOW, true), NOW).state());
        assertEquals(AvailabilityState.BLOCKED,
                freshEvaluator().evaluate(new AuditBacklogMeasurement(0, 0, false, false, NOW, true), NOW).state());
        assertEquals(AvailabilityState.BLOCKED,
                freshEvaluator().evaluate(new AuditBacklogMeasurement(0, 0, false, true, NOW, false), NOW).state());
        assertEquals(AvailabilityState.BLOCKED,
                freshEvaluator().evaluate(measurement(0, 0), NOW.plusSeconds(46)).state());
    }

    @Test
    void recoveryRequiresTwoConsecutiveStrictlyBelowBoundaryHealthyObservations() {
        AuditBacklogEvaluator evaluator = freshEvaluator();
        assertEquals(AvailabilityState.BLOCKED, evaluator.evaluate(measurement(50_000, 300), NOW).state());
        assertEquals(AvailabilityState.BLOCKED,
                evaluator.evaluate(measurementAt(9_999, 59, NOW.plusSeconds(15)), NOW.plusSeconds(15)).state());
        assertEquals(AvailabilityState.HEALTHY,
                evaluator.evaluate(measurementAt(9_999, 59, NOW.plusSeconds(30)), NOW.plusSeconds(30)).state());
    }

    @Test
    void restartLoadsThePersistedFirstHealthyObservationInsteadOfSkippingHysteresis() {
        AuditAvailabilityState persisted = new AuditAvailabilityState(
                AvailabilityState.BLOCKED,
                Set.of(cn.edu.suda.scholarsense.auditoperations.domain.FindingCode.AUDIT_INGESTION_BACKLOG),
                1);
        AuditBacklogEvaluator restarted = new AuditBacklogEvaluator(() -> Optional.of(persisted));

        assertEquals(AvailabilityState.HEALTHY,
                restarted.evaluate(measurement(9_999, 59), NOW).state());
    }

    private static AuditBacklogEvaluator freshEvaluator() {
        return new AuditBacklogEvaluator();
    }

    private static AuditBacklogMeasurement measurement(long count, long age) {
        return measurementAt(count, age, NOW);
    }

    private static AuditBacklogMeasurement measurementAt(long count, long age, Instant at) {
        return new AuditBacklogMeasurement(count, age, false, true, at, true);
    }
}
