package cn.edu.suda.scholarsense.auditoperations.application;

import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.NOW;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.TRACE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CurrentAuditAvailabilityServiceTest {
    @Test
    void observationFindingAndAlertRunInsideOneRequiredTransaction() {
        int[] transactions = {0};
        AuditTransactionPort transaction = new AuditTransactionPort() {
            @Override
            public <T> T required(java.util.function.Supplier<T> work) {
                transactions[0]++;
                return work.get();
            }
        };
        AuditBacklogMeasurement measurement = new AuditBacklogMeasurement(
                10_000, 59, false, true, NOW, true);
        CurrentAuditAvailabilityService service = new CurrentAuditAvailabilityService(
                () -> measurement,
                new AuditBacklogEvaluator(),
                () -> NOW,
                (availability, measured) -> {},
                transaction);

        service.current(TRACE_ID);

        assertEquals(1, transactions[0]);
    }
}
