package cn.edu.suda.scholarsense.shared.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;

/** One request-scoped source of trace truth for filters, MVC handlers and audit adapters. */
public final class HttpTraceContext {
    static final String ATTRIBUTE = HttpTraceContext.class.getName() + ".context";
    private static final W3cTraceContextCodec FALLBACK_CODEC = new W3cTraceContextCodec();

    private HttpTraceContext() {}

    public static String traceId(HttpServletRequest request) {
        return context(request).traceId();
    }

    public static W3cTraceContext context(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof W3cTraceContext context) {
            return context;
        }
        synchronized (request) {
            existing = request.getAttribute(ATTRIBUTE);
            if (existing instanceof W3cTraceContext context) {
                return context;
            }
            W3cTraceContext context = FALLBACK_CODEC
                    .extract(request.getHeader("traceparent"), true)
                    .context();
            request.setAttribute(ATTRIBUTE, context);
            return context;
        }
    }

    static void bind(HttpServletRequest request, W3cTraceContext context) {
        request.setAttribute(ATTRIBUTE, Objects.requireNonNull(context, "context"));
    }
}
