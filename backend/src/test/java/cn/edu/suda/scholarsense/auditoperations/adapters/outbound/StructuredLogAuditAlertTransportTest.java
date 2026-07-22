package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StructuredLogAuditAlertTransportTest {
    @Test
    void sendsTheAlreadyMinimizedAlertPayloadToTheBoundStructuredChannel() {
        AtomicReference<String> delivered = new AtomicReference<>();
        var transport = new StructuredLogAuditAlertTransport(delivered::set);

        transport.send("{\"schemaVersion\":\"AUDIT-INTEGRITY-ALERT-1.0.0\"}");

        assertEquals(
                "{\"schemaVersion\":\"AUDIT-INTEGRITY-ALERT-1.0.0\"}",
                delivered.get());
    }
}
