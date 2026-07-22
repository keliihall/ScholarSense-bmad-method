package cn.edu.suda.scholarsense.auditoperations.api;

import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.TRACE_ID;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.NOW;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.outbox;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import cn.edu.suda.scholarsense.auditoperations.application.AuditAppendOutcome;
import cn.edu.suda.scholarsense.auditoperations.application.AuditAppendResult;
import cn.edu.suda.scholarsense.auditoperations.application.LowCardinalityAuditMetrics;
import org.junit.jupiter.api.Test;

class AuditOperationsApiTest {

    @Test
    void ingressResultExposesOnlyTransportNeutralOutcomeAndSafeIdentifiers() {
        AuditIngressResult appended = AuditIngressResult.appended(
                1, "a".repeat(64), TRACE_ID);
        assertEquals(AuditIngressOutcome.APPENDED, appended.outcome());
        assertFalse(appended.retryable());
        assertThrows(IllegalArgumentException.class, () -> new AuditIngressResult(
                AuditIngressOutcome.COLLISION, 1L, "a".repeat(64), null, false, TRACE_ID));
    }

    @Test
    void availabilityFailsClosedForBlockedStaleAndUnavailableObservations() {
        Instant now = Instant.parse("2026-07-20T02:00:00Z");
        AuditAvailabilityResult healthy = new AuditAvailabilityResult(
                AuditAvailabilityState.HEALTHY,
                "AUDIT-INGESTION-POLICY-1.0.0",
                Set.of(),
                now,
                now.plusSeconds(45),
                TRACE_ID);
        assertTrue(healthy.allowsHighRiskAt(now.plusSeconds(45)));

        AuditAvailabilityResult blocked = new AuditAvailabilityResult(
                AuditAvailabilityState.BLOCKED,
                "AUDIT-INGESTION-POLICY-1.0.0",
                Set.of("AUDIT_LEDGER_ENTRY_HASH_MISMATCH"),
                now,
                now.plusSeconds(45),
                TRACE_ID);
        assertFalse(blocked.allowsHighRiskAt(now));
        assertFalse(healthy.allowsHighRiskAt(now.plusSeconds(46)));
    }

    @Test
    void productionIngressFacadeEmitsLowCardinalityAppendAndRejectionOutcomes() {
        var emitted = new ArrayList<String>();
        var metrics = new LowCardinalityAuditMetrics((name, labels) ->
                emitted.add(name + labels));
        var ingress = new AuditLedgerIngress(
                ignored -> new AuditAppendResult(
                        AuditAppendOutcome.APPENDED, 1L, "a".repeat(64), null, TRACE_ID),
                ignored -> {},
                metrics);

        ingress.ingest(outbox());
        ingress.rejectContract(new AuditContractRejection(
                "identity-access", "b".repeat(64), TRACE_ID, NOW));

        assertEquals(List.of(
                "audit.ingestion.outcome" + Map.of("outcome", "appended"),
                "audit.ingestion.outcome" + Map.of("outcome", "rejected")), emitted);
    }
}
