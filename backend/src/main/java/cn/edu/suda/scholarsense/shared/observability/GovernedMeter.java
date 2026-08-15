package cn.edu.suda.scholarsense.shared.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.Map;
import java.util.Objects;

/** Single counter bridge for the OBS-1.0.0 metric-label allowlist. */
public final class GovernedMeter {
    private static final String COUNTER_NAME = "scholarsense.operation.total";
    private final MeterRegistry registry;

    public GovernedMeter(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public void increment(
            String metricName, long value, SafeObservationAttributes dimensions) {
        if (metricName == null || !metricName.matches("^[a-z][a-z0-9_.]{1,127}$")
                || value < 0 || dimensions == null) {
            throw new IllegalArgumentException("OBSERVABILITY_METRIC_INVALID");
        }
        Map<String, String> labels = dimensions.metricLabels();
        Iterable<Tag> tags = labels.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Tag.of(entry.getKey(), entry.getValue()))
                .toList();
        registry.counter(COUNTER_NAME, tags).increment(value);
    }
}
