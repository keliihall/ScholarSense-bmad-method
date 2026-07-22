package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MicrometerAuditMetricSinkTest {
    @Test
    void recordsIntoTheProductionMetricsRegistryWithOnlySuppliedLowCardinalityTags() {
        var registry = new SimpleMeterRegistry();
        var sink = new MicrometerAuditMetricSink(registry);

        sink.record("audit.alert.delivery", Map.of("outcome", "confirmed"));

        assertEquals(
                1.0,
                registry.counter("audit.alert.delivery", "outcome", "confirmed").count());
    }
}
