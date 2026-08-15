package cn.edu.suda.scholarsense.shared.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Bounded operational signal for exporter failures; never stores exporter exceptions. */
public final class TelemetryExportMonitor implements HealthIndicator {
    private final Counter failures;
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicBoolean degraded = new AtomicBoolean();

    public TelemetryExportMonitor(MeterRegistry meters) {
        failures = Counter.builder("scholarsense.telemetry.export.failures")
                .tag("service", "scholarsense")
                .tag("module", "shared")
                .tag("operation", "telemetry.export")
                .tag("outcome", "failure")
                .tag("dependency", "otlp-traces")
                .tag("error", "export")
                .register(meters);
    }

    void failed() {
        failures.increment();
        failureCount.incrementAndGet();
        degraded.set(true);
    }

    void succeeded() {
        degraded.set(false);
    }

    public long failureCount() {
        return failureCount.get();
    }

    @Override
    public Health health() {
        Health.Builder health = degraded.get()
                ? Health.status("DEGRADED")
                : Health.up();
        return health.withDetail("failureCount", failureCount.get()).build();
    }
}
