package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.shared.outbox.AuditProducerBacklogSnapshot;
import cn.edu.suda.scholarsense.shared.time.AuditAvailabilityResult;
import cn.edu.suda.scholarsense.shared.time.AuditAvailabilityState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SharedAuditPublicationGuardTest {
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final String TRACE = "00112233445566778899aabbccddeeff";

    @Test
    void healthyFreshAuditAndBacklogAllowsPublication() {
        var guard = new SharedAuditPublicationGuard(
                trace -> availability(AuditAvailabilityState.HEALTHY),
                () -> backlog(false, true, 1), Clock.fixed(NOW, ZoneOffset.UTC));
        assertDoesNotThrow(() -> guard.requireAvailable(TRACE));
    }

    @Test
    void unavailableOrPermanentBacklogFailsClosed() {
        var unavailable = new SharedAuditPublicationGuard(
                trace -> availability(AuditAvailabilityState.UNAVAILABLE),
                () -> backlog(false, true, 0), Clock.fixed(NOW, ZoneOffset.UTC));
        var failed = new SharedAuditPublicationGuard(
                trace -> availability(AuditAvailabilityState.HEALTHY),
                () -> backlog(true, true, 0), Clock.fixed(NOW, ZoneOffset.UTC));
        assertCode(assertThrows(IngestionQualityApplicationException.class,
                () -> unavailable.requireAvailable(TRACE)));
        assertCode(assertThrows(IngestionQualityApplicationException.class,
                () -> failed.requireAvailable(TRACE)));
    }

    private static AuditAvailabilityResult availability(AuditAvailabilityState state) {
        return new AuditAvailabilityResult(state, "AUDIT-INGESTION-POLICY-1.0.0",
                Set.of(), NOW, NOW.plusSeconds(60), TRACE);
    }

    private static AuditProducerBacklogSnapshot backlog(boolean failed, boolean available, long age) {
        return new AuditProducerBacklogSnapshot(1, age, 1, age, failed, NOW, available);
    }

    private static void assertCode(IngestionQualityApplicationException failure) {
        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", failure.code());
    }
}
