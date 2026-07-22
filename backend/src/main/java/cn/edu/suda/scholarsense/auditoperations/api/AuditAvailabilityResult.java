package cn.edu.suda.scholarsense.auditoperations.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record AuditAvailabilityResult(
        AuditAvailabilityState state,
        String policyVersion,
        Set<String> reasonCodes,
        Instant observedAt,
        Instant freshUntil,
        String traceId) {
    public AuditAvailabilityResult {
        Objects.requireNonNull(state, "state");
        if (!"AUDIT-INGESTION-POLICY-1.0.0".equals(policyVersion)) {
            throw new IllegalArgumentException("AUDIT_AVAILABILITY_POLICY_INVALID");
        }
        reasonCodes = Set.copyOf(reasonCodes);
        if (reasonCodes.stream().anyMatch(code -> !code.matches("AUDIT_[A-Z0-9_]+"))) {
            throw new IllegalArgumentException("AUDIT_AVAILABILITY_REASON_INVALID");
        }
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(freshUntil, "freshUntil");
        if (freshUntil.isBefore(observedAt) || traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("AUDIT_AVAILABILITY_RESULT_INVALID");
        }
    }

    public boolean allowsHighRiskAt(Instant instant) {
        return state == AuditAvailabilityState.HEALTHY && !instant.isAfter(freshUntil);
    }
}
