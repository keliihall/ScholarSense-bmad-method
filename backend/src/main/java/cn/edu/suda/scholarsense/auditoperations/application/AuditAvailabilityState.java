package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.AvailabilityState;
import cn.edu.suda.scholarsense.auditoperations.domain.FindingCode;
import java.util.Objects;
import java.util.Set;

/** Persisted hysteresis state required to survive worker restarts. */
public record AuditAvailabilityState(
        AvailabilityState state,
        Set<FindingCode> reasonCodes,
        int recoveryHealthyCount) {
    public AuditAvailabilityState {
        Objects.requireNonNull(state, "state");
        reasonCodes = Set.copyOf(reasonCodes);
        if (recoveryHealthyCount < 0 || recoveryHealthyCount > 1) {
            throw new IllegalArgumentException("AUDIT_AVAILABILITY_RECOVERY_STATE_INVALID");
        }
    }
}
