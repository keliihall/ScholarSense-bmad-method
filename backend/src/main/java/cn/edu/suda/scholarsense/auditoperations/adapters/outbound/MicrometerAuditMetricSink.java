package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.application.AuditMetricSink;
import cn.edu.suda.scholarsense.shared.observability.GovernedMeter;
import cn.edu.suda.scholarsense.shared.observability.SafeObservationAttributes;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Objects;

/** Production metrics bridge; label vocabulary is constrained by LowCardinalityAuditMetrics. */
public final class MicrometerAuditMetricSink implements AuditMetricSink {
    private final GovernedMeter governed;

    public MicrometerAuditMetricSink(MeterRegistry registry) {
        this.governed = new GovernedMeter(Objects.requireNonNull(registry));
    }

    @Override
    public void record(String metricName, Map<String, String> labels) {
        SafeObservationAttributes dimensions = SafeObservationAttributes.create()
                .low("service", "scholarsense")
                .low("module", "audit-operations")
                .low("operation", metricName);
        for (Map.Entry<String, String> label : labels.entrySet()) {
            switch (label.getKey()) {
                case "outcome", "state" -> dimensions.low("outcome", label.getValue());
                case "severity" -> dimensions.low("code", label.getValue());
                case "mode" -> dimensions.low("role", label.getValue());
                default -> throw new IllegalArgumentException("AUDIT_METRIC_DIMENSION_INVALID");
            }
        }
        governed.increment(metricName, 1, dimensions);
    }
}
