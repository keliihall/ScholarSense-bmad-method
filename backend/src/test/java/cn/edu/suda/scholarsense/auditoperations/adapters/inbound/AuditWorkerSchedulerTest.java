package cn.edu.suda.scholarsense.auditoperations.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityPort;
import cn.edu.suda.scholarsense.auditoperations.application.AuditAlertRelayProcessor;
import cn.edu.suda.scholarsense.auditoperations.application.AuditLedgerVerifier;
import cn.edu.suda.scholarsense.auditoperations.application.LedgerVerificationResult;
import cn.edu.suda.scholarsense.auditoperations.application.LowCardinalityAuditMetrics;
import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityResult;
import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuditWorkerSchedulerTest {
    @Test
    void verifierFailureDoesNotPublishAnObservationFromAnOlderHealthyRun() {
        AuditLedgerVerifier verifier = mock(AuditLedgerVerifier.class);
        AuditAvailabilityPort availability = mock(AuditAvailabilityPort.class);
        AuditAlertRelayProcessor alerts = mock(AuditAlertRelayProcessor.class);
        when(verifier.verifyFull(anyString())).thenThrow(new IllegalStateException("read failed"));
        AuditWorkerScheduler scheduler = new AuditWorkerScheduler(verifier, availability, alerts);

        assertThrows(IllegalStateException.class, scheduler::verifyAndMeasure);

        verify(availability, never()).current(anyString());
    }

    @Test
    void successfulWorkerCycleEmitsVerifierAndAvailabilityMetrics() {
        AuditLedgerVerifier verifier = mock(AuditLedgerVerifier.class);
        AuditAvailabilityPort availability = mock(AuditAvailabilityPort.class);
        AuditAlertRelayProcessor alerts = mock(AuditAlertRelayProcessor.class);
        when(verifier.verifyFull(anyString())).thenReturn(
                new LedgerVerificationResult(true, 0, "0".repeat(64), Set.of()));
        Instant now = Instant.parse("2026-07-22T10:00:00Z");
        when(availability.current(anyString())).thenAnswer(invocation ->
                new AuditAvailabilityResult(
                        AuditAvailabilityState.DEGRADED,
                        "AUDIT-INGESTION-POLICY-1.0.0",
                        Set.of("AUDIT_INGESTION_BACKLOG"),
                        now,
                        now.plusSeconds(45),
                        invocation.getArgument(0)));
        var emitted = new ArrayList<String>();
        var scheduler = new AuditWorkerScheduler(
                verifier,
                availability,
                alerts,
                new LowCardinalityAuditMetrics((name, labels) ->
                        emitted.add(name + labels)));

        scheduler.verifyAndMeasure();

        org.junit.jupiter.api.Assertions.assertEquals(2, emitted.size());
        org.junit.jupiter.api.Assertions.assertTrue(emitted.stream().anyMatch(value ->
                value.startsWith("audit.verifier.result")
                        && value.contains("full-chain") && value.contains("healthy")));
        org.junit.jupiter.api.Assertions.assertTrue(emitted.stream().anyMatch(value ->
                value.startsWith("audit.availability.state") && value.contains("degraded")));
    }
}
