package cn.edu.suda.scholarsense.auditoperations.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LowCardinalityAuditMetricsTest {

    @Test
    void acceptsOnlyFixedMetricAndLabelDimensions() {
        var calls = new AtomicInteger();
        var metrics = new LowCardinalityAuditMetrics((name, labels) -> calls.incrementAndGet());

        metrics.record("audit.alert.delivery", Map.of("outcome", "confirmed"));

        assertEquals(1, calls.get());
        assertThrows(IllegalArgumentException.class, () -> metrics.record(
                "audit.alert.delivery", Map.of("auditId", "019bf18e-6c00-7000-8000-000000000021")));
        assertThrows(IllegalArgumentException.class, () -> metrics.record(
                "audit.alert.delivery", Map.of("traceId", "0123456789abcdef0123456789abcdef")));
        assertThrows(IllegalArgumentException.class, () -> metrics.record(
                "audit.alert.delivery", Map.of("outcome", "student-20260001")));
        assertThrows(IllegalArgumentException.class, () -> metrics.record(
                "audit.alert.delivery.by-user", Map.of("outcome", "confirmed")));
    }
}
