package cn.edu.suda.scholarsense.shared.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class ScholarSenseStructuredLogFormatterTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void emitsExactUtcPrivacySafeAllowlistedFields() throws Exception {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getInstant()).thenReturn(Instant.parse("2026-08-15T00:00:00Z"));
        when(event.getLevel()).thenReturn(Level.WARN);
        when(event.getFormattedMessage()).thenReturn("张三 20260001 Authorization: Bearer secret-token");
        when(event.getMDCPropertyMap()).thenReturn(Map.of(
                "traceId", "11111111111111111111111111111111",
                "module", "ingestion-quality",
                "event", "batch.evaluate",
                "code", "conflict",
                "studentId", "20260001"));

        String output = new ScholarSenseStructuredLogFormatter().format(event);
        Map<String, Object> value = json.readValue(output, new TypeReference<>() {});

        assertEquals(
                Map.of(
                        "timestamp", "2026-08-15T00:00:00Z",
                        "level", "WARN",
                        "service", "scholarsense",
                        "module", "ingestion-quality",
                        "traceId", "11111111111111111111111111111111",
                        "event", "batch.evaluate",
                        "code", "conflict"),
                value);
        assertFalse(output.contains("张三"));
        assertFalse(output.contains("20260001"));
        assertFalse(output.contains("Authorization"));
    }

    @Test
    void frameworkLogsWithoutMdcUseSafeFixedDefaults() throws Exception {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getInstant()).thenReturn(Instant.parse("2026-08-15T00:00:00Z"));
        when(event.getLevel()).thenReturn(Level.INFO);
        when(event.getMDCPropertyMap()).thenReturn(Map.of());

        Map<String, Object> value = json.readValue(
                new ScholarSenseStructuredLogFormatter().format(event),
                new TypeReference<>() {});

        assertEquals("shared", value.get("module"));
        assertEquals("", value.get("traceId"));
        assertEquals("runtime.lifecycle", value.get("event"));
        assertEquals("ok", value.get("code"));
    }

    @Test
    void arbitraryMdcTokensCannotBecomeContractFieldsOrLeakStudentIdentifiers()
            throws Exception {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getInstant()).thenReturn(Instant.parse("2026-08-15T00:00:00Z"));
        when(event.getLevel()).thenReturn(Level.INFO);
        when(event.getMDCPropertyMap()).thenReturn(Map.of(
                "event", "student.20260001",
                "code", "student-20260001"));

        String output = new ScholarSenseStructuredLogFormatter().format(event);
        Map<String, Object> value = json.readValue(output, new TypeReference<>() {});

        assertEquals("runtime.lifecycle", value.get("event"));
        assertEquals("ok", value.get("code"));
        assertFalse(output.contains("20260001"));
    }
}
