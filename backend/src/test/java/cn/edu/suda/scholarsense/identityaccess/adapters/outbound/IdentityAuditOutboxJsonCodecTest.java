package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class IdentityAuditOutboxJsonCodecTest {
    private final ObjectMapper json = new ObjectMapper();
    private final IdentityAuditOutboxJsonCodec codec = new IdentityAuditOutboxJsonCodec(json);

    @Test
    void rejectsUnknownFieldsWrongSourceContentTypeAndJsonTypeCoercion() throws Exception {
        Fixture fixture = fixture();

        assertThrows(IllegalArgumentException.class,
                () -> read(fixture, fixture.envelope().replaceFirst("\\{", "{\"forged\":true,")));
        assertThrows(IllegalArgumentException.class,
                () -> read(fixture, fixture.envelope().replace(
                        "\"data\":{", "\"data\":{\"forged\":true,")));
        assertThrows(IllegalArgumentException.class,
                () -> read(fixture, fixture.envelope().replace(
                        "urn:scholarsense:identity-access", "urn:scholarsense:reporting")));
        assertThrows(IllegalArgumentException.class,
                () -> read(fixture, fixture.envelope().replace(
                        "\"datacontenttype\":\"application/json\"",
                        "\"datacontenttype\":\"text/plain\"")));
        assertThrows(IllegalArgumentException.class,
                () -> read(fixture, fixture.envelope().replace(
                        "\"roleIds\":[]", "\"roleIds\":\"COUNSELOR\"")));
    }

    @Test
    void acceptsEnvelopeNanosecondsLostByPostgresqlMicrosecondTimestampRoundTrip() throws Exception {
        Fixture fixture = fixture();
        String nanosecondEnvelope = fixture.envelope().replace(
                "\"time\":\"2026-07-20T02:00:00Z\"",
                "\"time\":\"2026-07-20T02:00:00.000000789Z\"");

        assertDoesNotThrow(() -> read(fixture, nanosecondEnvelope));
    }

    @Test
    void rejectsClockOffsetOutsideTheFrozenFactContractBoundary() throws Exception {
        Fixture fixture = fixture();

        assertThrows(IllegalArgumentException.class,
                () -> read(fixture, fixture.envelope().replace("\"offsetMs\":12", "\"offsetMs\":101")));
        assertThrows(IllegalArgumentException.class,
                () -> read(fixture, fixture.envelope().replace("\"offsetMs\":12", "\"offsetMs\":-101")));
        assertDoesNotThrow(
                () -> read(fixture, fixture.envelope().replace("\"offsetMs\":12", "\"offsetMs\":100")));
        assertDoesNotThrow(
                () -> read(fixture, fixture.envelope().replace("\"offsetMs\":12", "\"offsetMs\":-100")));
    }

    private void read(Fixture fixture, String value) {
        codec.read(
                fixture.eventId(), fixture.auditId(), fixture.eventType(), fixture.schemaVersion(),
                fixture.createdAt(), value);
    }

    private Fixture fixture() throws Exception {
        JsonNode root = json.readTree(Files.readString(Path.of(
                "..", "contracts", "audit", "fixtures", "valid", "local-audit-outbox.json")));
        return new Fixture(
                UUID.fromString(root.required("eventId").asText()),
                UUID.fromString(root.required("auditId").asText()),
                root.required("eventType").asText(),
                root.required("schemaVersion").asText(),
                Instant.parse(root.required("createdAt").asText()),
                json.writeValueAsString(root.required("envelope")));
    }

    private record Fixture(
            UUID eventId,
            UUID auditId,
            String eventType,
            String schemaVersion,
            Instant createdAt,
            String envelope) {}
}
