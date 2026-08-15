package cn.edu.suda.scholarsense.shared.observability;

import java.net.URI;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Single W3C extraction/injection implementation for explicit application boundaries. */
public final class W3cTraceContextCodec {
    private static final Pattern TRACEPARENT = Pattern.compile(
            "^(00)-([0-9a-f]{32})-([0-9a-f]{16})-(00|01)$");
    private static final String ZERO_TRACE = "0".repeat(32);
    private static final String ZERO_SPAN = "0".repeat(16);

    private final Supplier<String> traceIds;
    private final Supplier<String> spanIds;

    public W3cTraceContextCodec() {
        SecureRandom random = new SecureRandom();
        this.traceIds = () -> randomHex(random, 16);
        this.spanIds = () -> randomHex(random, 8);
    }

    W3cTraceContextCodec(Supplier<String> traceIds, Supplier<String> spanIds) {
        this.traceIds = Objects.requireNonNull(traceIds, "traceIds");
        this.spanIds = Objects.requireNonNull(spanIds, "spanIds");
    }

    public TraceExtraction extract(String traceparent, boolean trusted) {
        if (!trusted) {
            return cleanRoot(TraceExtractionReason.UNTRUSTED);
        }
        if (traceparent == null || traceparent.isBlank()) {
            return cleanRoot(TraceExtractionReason.MISSING);
        }
        Matcher match = TRACEPARENT.matcher(traceparent.strip());
        if (!match.matches()) {
            return cleanRoot(TraceExtractionReason.INVALID);
        }
        String traceId = match.group(2).toLowerCase(Locale.ROOT);
        String spanId = match.group(3).toLowerCase(Locale.ROOT);
        if (ZERO_TRACE.equals(traceId) || ZERO_SPAN.equals(spanId)) {
            return cleanRoot(TraceExtractionReason.ALL_ZERO);
        }
        int flags = Integer.parseInt(match.group(4), 16);
        return new TraceExtraction(
                new W3cTraceContext(traceId, spanId, (flags & 1) == 1),
                TraceExtractionReason.INHERITED,
                true);
    }

    public W3cTraceContext newRoot(boolean sampled) {
        return new W3cTraceContext(nextTraceId(), nextSpanId(), sampled);
    }

    /** Restores a durable causal chain when the owner record persists only its trace id. */
    public W3cTraceContext resume(String persistedTraceId, boolean sampled) {
        return new W3cTraceContext(persistedTraceId, nextSpanId(), sampled);
    }

    public W3cTraceContext child(W3cTraceContext parent) {
        Objects.requireNonNull(parent, "parent");
        return new W3cTraceContext(parent.traceId(), nextSpanId(), parent.sampled());
    }

    public String format(W3cTraceContext context) {
        Objects.requireNonNull(context, "context");
        return "00-" + context.traceId() + "-" + context.spanId()
                + (context.sampled() ? "-01" : "-00");
    }

    public Map<String, String> inject(
            W3cTraceContext context, URI target, TrustedTargetPolicy policy) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(policy, "policy");
        if (!policy.allows(target)) {
            return Map.of();
        }
        return Map.of("traceparent", format(context));
    }

    private TraceExtraction cleanRoot(TraceExtractionReason reason) {
        return new TraceExtraction(newRoot(false), reason, false);
    }

    private String nextTraceId() {
        return new W3cTraceContext(traceIds.get(), "1".repeat(16), true).traceId();
    }

    private String nextSpanId() {
        String value = spanIds.get();
        return new W3cTraceContext("1".repeat(32), value, true).spanId();
    }

    private static String randomHex(SecureRandom random, int bytes) {
        byte[] value = new byte[bytes];
        do {
            random.nextBytes(value);
        } while (allZero(value));
        return HexFormat.of().formatHex(value);
    }

    private static boolean allZero(byte[] value) {
        for (byte current : value) {
            if (current != 0) {
                return false;
            }
        }
        return true;
    }
}
