package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.outbox;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AuditLedgerJsonCodecTest {
    private final AuditLedgerJsonCodec codec = new AuditLedgerJsonCodec(new ObjectMapper());

    @Test
    void roundTripsTheFrozenFactShape() {
        assertEquals(outbox().fact(), codec.readFact(codec.writeFact(outbox().fact())));
    }

    @Test
    void rejectsUnknownTopLevelAndNestedFields() {
        String payload = codec.writeFact(outbox().fact());

        assertThrows(IllegalArgumentException.class,
                () -> codec.readFact(payload.replaceFirst("\\{", "{\"forged\":true,")));
        assertThrows(IllegalArgumentException.class,
                () -> codec.readFact(payload.replace(
                        "\"timeSourceProfile\":{",
                        "\"timeSourceProfile\":{\"forged\":true,")));
    }

    @Test
    void rejectsJsonTypeCoercion() {
        String payload = codec.writeFact(outbox().fact());

        assertThrows(IllegalArgumentException.class,
                () -> codec.readFact(payload.replaceFirst(
                        "\\\"roleIds\\\":\\[[^]]*]", "\\\"roleIds\\\":\\\"ignored\\\"")));
        assertThrows(IllegalArgumentException.class,
                () -> codec.readFact(payload.replaceFirst(
                        "\\\"offsetMs\\\":-?[0-9]+", "\\\"offsetMs\\\":\\\"0\\\"")));
        assertThrows(IllegalArgumentException.class,
                () -> codec.readFact(payload.replaceFirst(
                        "\\\"aggregateVersion\\\":[0-9]+", "\\\"aggregateVersion\\\":7.0")));
    }
}
