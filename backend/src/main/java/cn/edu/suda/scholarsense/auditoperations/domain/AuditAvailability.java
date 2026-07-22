package cn.edu.suda.scholarsense.auditoperations.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record AuditAvailability(
        AvailabilityState state,
        String policyVersion,
        Set<FindingCode> reasonCodes,
        Instant observedAt,
        Instant freshUntil,
        String traceId,
        int recoveryHealthyCount) {
    public AuditAvailability(
            AvailabilityState state,
            String policyVersion,
            Set<FindingCode> reasonCodes,
            Instant observedAt,
            Instant freshUntil,
            String traceId) {
        this(state, policyVersion, reasonCodes, observedAt, freshUntil, traceId, 0);
    }

    public AuditAvailability {
        Objects.requireNonNull(state, "state");
        if (!"AUDIT-INGESTION-POLICY-1.0.0".equals(policyVersion)) {
            throw new IllegalArgumentException("AUDIT_AVAILABILITY_POLICY_INVALID");
        }
        reasonCodes = Set.copyOf(reasonCodes);
        if (state == AvailabilityState.HEALTHY && !reasonCodes.isEmpty()
                || state == AvailabilityState.BLOCKED && reasonCodes.isEmpty()) {
            throw new IllegalArgumentException("AUDIT_AVAILABILITY_REASONS_INVALID");
        }
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(freshUntil, "freshUntil");
        if (freshUntil.isBefore(observedAt)) {
            throw new IllegalArgumentException("AUDIT_AVAILABILITY_TIME_INVALID");
        }
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("AUDIT_AVAILABILITY_TRACE_INVALID");
        }
        if (recoveryHealthyCount < 0 || recoveryHealthyCount > 1
                || state == AvailabilityState.HEALTHY && recoveryHealthyCount != 0) {
            throw new IllegalArgumentException("AUDIT_AVAILABILITY_RECOVERY_STATE_INVALID");
        }
    }

    public boolean isFreshAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isAfter(freshUntil);
    }
}
