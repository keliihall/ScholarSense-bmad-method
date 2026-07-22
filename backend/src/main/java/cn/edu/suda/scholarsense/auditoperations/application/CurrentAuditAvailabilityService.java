package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.AuditAvailability;
import java.util.Objects;

public final class CurrentAuditAvailabilityService implements AuditAvailabilityUseCase {
    private final AuditBacklogMeasurementPort measurements;
    private final AuditBacklogEvaluator evaluator;
    private final AuditClock clock;
    private final AuditAvailabilityObserver observer;
    private final AuditTransactionPort transactions;

    public CurrentAuditAvailabilityService(
            AuditBacklogMeasurementPort measurements,
            AuditBacklogEvaluator evaluator,
            AuditClock clock) {
        this(measurements, evaluator, clock, (availability, measurement) -> {});
    }

    public CurrentAuditAvailabilityService(
            AuditBacklogMeasurementPort measurements,
            AuditBacklogEvaluator evaluator,
            AuditClock clock,
            AuditAvailabilityObserver observer) {
        this(measurements, evaluator, clock, observer, new AuditTransactionPort() {
            @Override
            public <T> T required(java.util.function.Supplier<T> work) {
                return work.get();
            }
        });
    }

    public CurrentAuditAvailabilityService(
            AuditBacklogMeasurementPort measurements,
            AuditBacklogEvaluator evaluator,
            AuditClock clock,
            AuditAvailabilityObserver observer,
            AuditTransactionPort transactions) {
        this.measurements = Objects.requireNonNull(measurements);
        this.evaluator = Objects.requireNonNull(evaluator);
        this.clock = Objects.requireNonNull(clock);
        this.observer = Objects.requireNonNull(observer);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public AuditAvailabilityView current(String traceId) {
        return transactions.required(() -> currentInTransaction(traceId));
    }

    private AuditAvailabilityView currentInTransaction(String traceId) {
        AuditBacklogMeasurement measurement = measurements.current();
        AuditAvailability value = evaluator.evaluate(measurement, clock.now(), traceId);
        observer.observe(value, measurement);
        return new AuditAvailabilityView(
                value.state().name(),
                value.policyVersion(),
                value.reasonCodes().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                value.observedAt(),
                value.freshUntil());
    }
}
