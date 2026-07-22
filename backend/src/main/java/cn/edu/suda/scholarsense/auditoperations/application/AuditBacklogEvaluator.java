package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.AuditAvailability;
import cn.edu.suda.scholarsense.auditoperations.domain.AvailabilityState;
import cn.edu.suda.scholarsense.auditoperations.domain.FindingCode;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** Stateful v1 hysteresis evaluator. A single instance must own each availability stream. */
public final class AuditBacklogEvaluator {
    private final AuditAvailabilityStatePort persistedState;
    private final AuditBacklogPolicy policy;
    private AvailabilityState currentState;
    private Set<FindingCode> currentReasons = Set.of();
    private int consecutiveHealthy;

    public AuditBacklogEvaluator() {
        this(null, AuditBacklogPolicy.version1());
    }

    public AuditBacklogEvaluator(AuditAvailabilityStatePort persistedState) {
        this(java.util.Objects.requireNonNull(persistedState), AuditBacklogPolicy.version1());
    }

    public AuditBacklogEvaluator(
            AuditAvailabilityStatePort persistedState, AuditBacklogPolicy policy) {
        this.persistedState = persistedState;
        this.policy = java.util.Objects.requireNonNull(policy);
    }

    public synchronized AuditAvailability evaluate(AuditBacklogMeasurement measurement, Instant now) {
        return evaluate(measurement, now, "0".repeat(32));
    }

    public synchronized AuditAvailability evaluate(
            AuditBacklogMeasurement measurement, Instant now, String traceId) {
        Optional<AuditAvailabilityState> prior = persistedState == null
                ? Optional.ofNullable(currentState).map(state -> new AuditAvailabilityState(
                        state, currentReasons, consecutiveHealthy))
                : persistedState.latest();
        AvailabilityState previousState = prior.map(AuditAvailabilityState::state).orElse(null);
        Set<FindingCode> previousReasons = prior
                .map(AuditAvailabilityState::reasonCodes).orElse(Set.of());
        int recoveryHealthyCount = prior
                .map(AuditAvailabilityState::recoveryHealthyCount).orElse(0);
        EnumSet<FindingCode> reasons = EnumSet.noneOf(FindingCode.class);
        boolean stale = now.isAfter(
                measurement.measuredAt().plusSeconds(policy.measurementStaleAfterSeconds()));
        AvailabilityState raw;
        if (!measurement.available() || stale) {
            raw = AvailabilityState.BLOCKED;
            reasons.add(FindingCode.AUDIT_INGESTION_BACKLOG);
        } else if (measurement.permanentFindingActive()) {
            raw = AvailabilityState.BLOCKED;
            reasons.add(FindingCode.AUDIT_INGESTION_DUPLICATE_CONFLICT);
        } else if (!measurement.chainHealthy()) {
            raw = AvailabilityState.BLOCKED;
            reasons.add(FindingCode.AUDIT_LEDGER_HEAD_MISMATCH);
        } else if (measurement.oldestUnconfirmedAgeSeconds() >= policy.blockedAgeSeconds()
                || measurement.unconfirmedCount() >= policy.blockedCount()) {
            raw = AvailabilityState.BLOCKED;
            reasons.add(FindingCode.AUDIT_INGESTION_BACKLOG);
        } else if (measurement.oldestUnconfirmedAgeSeconds() >= policy.degradedAgeSeconds()
                || measurement.unconfirmedCount() >= policy.degradedCount()) {
            raw = AvailabilityState.DEGRADED;
            reasons.add(FindingCode.AUDIT_INGESTION_BACKLOG);
        } else {
            raw = AvailabilityState.HEALTHY;
        }

        if (raw == AvailabilityState.HEALTHY
                && previousState != null && previousState != AvailabilityState.HEALTHY) {
            recoveryHealthyCount++;
            if (recoveryHealthyCount < policy.recoveryHealthyObservations()) {
                raw = previousState;
                reasons = previousReasons.isEmpty()
                        ? EnumSet.of(FindingCode.AUDIT_INGESTION_BACKLOG)
                        : EnumSet.copyOf(previousReasons);
            }
        } else if (raw != AvailabilityState.HEALTHY) {
            recoveryHealthyCount = 0;
        }
        if (raw == AvailabilityState.HEALTHY) {
            recoveryHealthyCount = 0;
            reasons.clear();
        }
        currentState = raw;
        currentReasons = Set.copyOf(reasons);
        consecutiveHealthy = recoveryHealthyCount;
        return new AuditAvailability(
                raw,
                policy.version(),
                currentReasons,
                measurement.measuredAt(),
                measurement.measuredAt().plusSeconds(policy.measurementStaleAfterSeconds()),
                traceId,
                recoveryHealthyCount);
    }
}
