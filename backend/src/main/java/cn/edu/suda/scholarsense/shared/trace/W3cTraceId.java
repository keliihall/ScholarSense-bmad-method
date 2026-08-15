package cn.edu.suda.scholarsense.shared.trace;

import cn.edu.suda.scholarsense.shared.observability.W3cTraceContextCodec;

/** Parses W3C traceparent and exposes only the normalized 16-byte trace-id. */
public final class W3cTraceId {
    private static final W3cTraceContextCodec CODEC = new W3cTraceContextCodec();

    private W3cTraceId() {}

    public static String from(String traceparent, String fallbackSeed) {
        return CODEC.extract(traceparent, true).context().traceId();
    }
}
