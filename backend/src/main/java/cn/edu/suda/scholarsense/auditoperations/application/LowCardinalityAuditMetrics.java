package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Enforces fixed low-cardinality dimensions before data reaches a metrics backend. */
public final class LowCardinalityAuditMetrics {
    private static final Set<String> METRICS = Set.of(
            "audit.ingestion.outcome", "audit.availability.state",
            "audit.alert.delivery", "audit.verifier.result");
    private static final Map<String, Set<String>> VALUES = Map.of(
            "outcome", Set.of("appended", "duplicate", "collision", "rejected",
                    "confirmed", "retried", "fenced", "healthy", "unhealthy"),
            "state", Set.of("healthy", "degraded", "blocked", "unavailable"),
            "severity", Set.of("warning", "critical"),
            "mode", Set.of("incremental", "full-chain"));

    private final AuditMetricSink sink;

    public LowCardinalityAuditMetrics(AuditMetricSink sink) {
        this.sink = Objects.requireNonNull(sink);
    }

    public void record(String metricName, Map<String, String> labels) {
        if (!METRICS.contains(metricName) || labels == null || labels.isEmpty()) {
            throw new IllegalArgumentException("AUDIT_METRIC_DIMENSION_INVALID");
        }
        for (Map.Entry<String, String> label : labels.entrySet()) {
            Set<String> permitted = VALUES.get(label.getKey());
            if (permitted == null || !permitted.contains(label.getValue())) {
                throw new IllegalArgumentException("AUDIT_METRIC_DIMENSION_INVALID");
            }
        }
        sink.record(metricName, Map.copyOf(labels));
    }
}
