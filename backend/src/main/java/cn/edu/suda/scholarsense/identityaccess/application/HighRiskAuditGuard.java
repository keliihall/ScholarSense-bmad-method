package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityPort;
import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityResult;
import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityState;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import java.time.Instant;
import java.util.Objects;

/** Shared identity conformance point: blocked, stale or unreachable audit evidence always fails closed. */
public final class HighRiskAuditGuard implements HighRiskOperationGuard {
    private final AuditAvailabilityPort availability;
    private final AuditRelayClock clock;

    public HighRiskAuditGuard(AuditAvailabilityPort availability, AuditRelayClock clock) {
        this.availability = Objects.requireNonNull(availability);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void requireAvailable(String traceId) {
        try {
            AuditAvailabilityResult result = availability.current(traceId);
            Instant now = clock.now();
            if (result.state() == AuditAvailabilityState.UNAVAILABLE) {
                throw unavailable();
            }
            if (result.state() != AuditAvailabilityState.HEALTHY || !result.allowsHighRiskAt(now)) {
                throw blocked();
            }
        } catch (IdentityAccessException blocked) {
            throw blocked;
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
    }

    private static IdentityAccessException blocked() {
        return new IdentityAccessException(
                "AUDIT_AVAILABILITY_BLOCKED", "audit evidence is unavailable");
    }

    private static IdentityAccessException unavailable() {
        return new IdentityAccessException(
                "AUDIT_AVAILABILITY_UNAVAILABLE", "audit evidence is unavailable");
    }
}
