package cn.edu.suda.scholarsense.shared.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

/** Exact seven-field JSON formatter; message bodies and arbitrary MDC values are never emitted. */
public final class ScholarSenseStructuredLogFormatter
        implements StructuredLogFormatter<ILoggingEvent> {
    private static final Set<String> MODULES = Set.of(
            "shared", "web-api", "worker", "identity-access", "audit-operations",
            "ingestion-quality", "subject-registry", "public-integration");
    private static final Set<String> EVENTS = Set.of(
            "runtime.lifecycle", "http.request", "job.attempt", "batch.evaluate",
            "outbox.publish", "event.consume", "external.call", "audit.write");
    private static final Set<String> CODES = Set.of(
            "ok", "invalid", "unauthorized", "forbidden", "conflict", "duplicate",
            "old", "gap", "poison", "timeout", "unavailable", "error");
    private static final String EMPTY_TRACE = "";

    @Override
    public String format(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        String timestamp = event.getInstant().toString();
        String level = event.getLevel().toString();
        String module = member(mdc, "module", MODULES, "shared");
        String traceId = traceId(mdc == null ? null : mdc.get("traceId"));
        String eventName = member(mdc, "event", EVENTS, "runtime.lifecycle");
        String code = member(mdc, "code", CODES, "ok");
        return "{"
                + json("timestamp", timestamp) + ","
                + json("level", level) + ","
                + json("service", "scholarsense") + ","
                + json("module", module) + ","
                + json("traceId", traceId) + ","
                + json("event", eventName) + ","
                + json("code", code)
                + "}\n";
    }

    private static String member(
            Map<String, String> mdc, String name, Set<String> allowed, String fallback) {
        String value = mdc == null ? null : mdc.get(name);
        if (value == null || !allowed.contains(value)) {
            return fallback;
        }
        return value;
    }

    private static String traceId(String value) {
        if (value == null || !value.matches("[0-9a-f]{32}")
                || value.equals("0".repeat(32))) {
            return EMPTY_TRACE;
        }
        return value;
    }

    private static String json(String name, String value) {
        return "\"" + name + "\":\"" + escaped(value) + "\"";
    }

    private static String escaped(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (current < 0x20) {
                        result.append(String.format("\\u%04x", (int) current));
                    } else {
                        result.append(current);
                    }
                }
            }
        }
        return result.toString();
    }
}
