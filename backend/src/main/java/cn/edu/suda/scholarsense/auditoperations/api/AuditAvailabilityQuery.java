package cn.edu.suda.scholarsense.auditoperations.api;

import cn.edu.suda.scholarsense.auditoperations.application.AuditAvailabilityUseCase;
import cn.edu.suda.scholarsense.auditoperations.application.AuditAvailabilityView;
import cn.edu.suda.scholarsense.auditoperations.application.AuditClock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public final class AuditAvailabilityQuery implements AuditAvailabilityPort {
    private final AuditAvailabilityUseCase useCase;
    private final AuditClock clock;

    public AuditAvailabilityQuery(AuditAvailabilityUseCase useCase, AuditClock clock) {
        this.useCase = Objects.requireNonNull(useCase);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public AuditAvailabilityResult current(String traceId) {
        try {
            AuditAvailabilityView value = useCase.current(traceId);
            return new AuditAvailabilityResult(
                    AuditAvailabilityState.valueOf(value.state()),
                    value.policyVersion(),
                    value.reasonCodes(),
                    value.observedAt(),
                    value.freshUntil(),
                    traceId);
        } catch (RuntimeException unavailable) {
            Instant now = clock.now();
            return new AuditAvailabilityResult(
                    AuditAvailabilityState.UNAVAILABLE,
                    "AUDIT-INGESTION-POLICY-1.0.0",
                    Set.of("AUDIT_AVAILABILITY_UNAVAILABLE"),
                    now,
                    now,
                    traceId);
        }
    }
}
