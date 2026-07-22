package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.application.AuditMetricSink;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.Map;
import java.util.Objects;

/** Production metrics bridge; label vocabulary is constrained by LowCardinalityAuditMetrics. */
public final class MicrometerAuditMetricSink implements AuditMetricSink {
    private final MeterRegistry registry;

    public MicrometerAuditMetricSink(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    @Override
    public void record(String metricName, Map<String, String> labels) {
        Iterable<Tag> tags = labels.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Tag.of(entry.getKey(), entry.getValue()))
                .toList();
        registry.counter(metricName, tags).increment();
    }
}
