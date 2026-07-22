package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityResult;
import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityState;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HighRiskAuditGuardTest {
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-07-20T02:00:00Z");

    @Test
    void blockedStaleAndUnavailableAvailabilityFailClosedWithStableSafeCodes() {
        assertCode("AUDIT_AVAILABILITY_BLOCKED", new HighRiskAuditGuard(
                trace -> availability(AuditAvailabilityState.BLOCKED, NOW.plusSeconds(45)), () -> NOW));
        assertCode("AUDIT_AVAILABILITY_BLOCKED", new HighRiskAuditGuard(
                trace -> availability(AuditAvailabilityState.HEALTHY, NOW.minusSeconds(1)), () -> NOW));
        assertCode("AUDIT_AVAILABILITY_UNAVAILABLE", new HighRiskAuditGuard(
                trace -> availability(AuditAvailabilityState.UNAVAILABLE, NOW), () -> NOW));
        assertCode("AUDIT_AVAILABILITY_UNAVAILABLE", new HighRiskAuditGuard(
                trace -> { throw new IllegalStateException("secret backend value"); }, () -> NOW));
    }

    @Test
    void onlyFreshHealthyAvailabilityAllowsHighRiskWork() {
        new HighRiskAuditGuard(
                trace -> availability(AuditAvailabilityState.HEALTHY, NOW.plusSeconds(45)), () -> NOW)
                .requireAvailable(TRACE);
    }

    private static AuditAvailabilityResult availability(AuditAvailabilityState state, Instant freshUntil) {
        return new AuditAvailabilityResult(
                state,
                "AUDIT-INGESTION-POLICY-1.0.0",
                state == AuditAvailabilityState.HEALTHY
                        ? Set.of() : Set.of("AUDIT_INGESTION_BACKLOG"),
                NOW.minusSeconds(1),
                freshUntil,
                TRACE);
    }

    private static void assertCode(String expected, HighRiskAuditGuard guard) {
        IdentityAccessException error = assertThrows(
                IdentityAccessException.class, () -> guard.requireAvailable(TRACE));
        assertEquals(expected, error.code());
        assertEquals("audit evidence is unavailable", error.getMessage());
    }
}
