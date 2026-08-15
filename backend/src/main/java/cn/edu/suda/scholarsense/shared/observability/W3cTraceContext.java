package cn.edu.suda.scholarsense.shared.observability;

import java.util.Locale;
import java.util.regex.Pattern;

/** Minimal privacy-safe W3C trace context used at explicit async and client boundaries. */
public record W3cTraceContext(String traceId, String spanId, boolean sampled) {
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SPAN_ID = Pattern.compile("^[0-9a-f]{16}$");

    public W3cTraceContext {
        traceId = normalized(traceId, 32, TRACE_ID, "traceId");
        spanId = normalized(spanId, 16, SPAN_ID, "spanId");
    }

    private static String normalized(
            String value, int width, Pattern pattern, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!pattern.matcher(normalized).matches() || normalized.equals("0".repeat(width))) {
            throw new IllegalArgumentException(field + " must be lowercase non-zero W3C hex");
        }
        return normalized;
    }
}
