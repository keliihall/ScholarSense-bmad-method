package cn.edu.suda.scholarsense.shared.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Binds the framework server span to HTTP handlers, response headers and safe log MDC. */
public final class HttpTraceCorrelationFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(HttpTraceCorrelationFilter.class);
    private static final String LEGACY_HEADER = "X-ScholarSense-Trace-Id";
    private static final String TRACEPARENT_HEADER = "traceparent";
    private static final java.util.List<String> MDC_KEYS =
            java.util.List.of("traceId", "module", "event", "code");

    private final CurrentTraceSource currentTrace;
    private final W3cTraceContextCodec codec;
    private final String module;

    public HttpTraceCorrelationFilter(
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec codec,
            String module) {
        this.currentTrace = Objects.requireNonNull(currentTrace, "currentTrace");
        this.codec = Objects.requireNonNull(codec, "codec");
        if (!"web-api".equals(module)) {
            throw new IllegalArgumentException("HTTP correlation is only valid for web-api");
        }
        this.module = module;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        W3cTraceContext context = currentTrace.current().orElseGet(
                () -> codec.extract(request.getHeader(TRACEPARENT_HEADER), true).context());
        HttpTraceContext.bind(request, context);
        response.setHeader(LEGACY_HEADER, context.traceId());
        response.setHeader(TRACEPARENT_HEADER, codec.format(context));

        Map<String, String> previous = captureMdc();
        MDC.put("traceId", context.traceId());
        MDC.put("module", module);
        MDC.put("event", "http.request");
        MDC.put("code", "ok");
        String code = "error";
        try {
            chain.doFilter(request, response);
            code = responseCode(response.getStatus());
        } finally {
            MDC.put("code", code);
            LOG.info("HTTP request completed");
            restoreMdc(previous);
        }
    }

    private static String responseCode(int status) {
        if (status >= 200 && status < 400) return "ok";
        return switch (status) {
            case 400, 404, 405, 415, 422 -> "invalid";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 409 -> "conflict";
            case 408, 504 -> "timeout";
            case 502, 503 -> "unavailable";
            default -> "error";
        };
    }

    private static Map<String, String> captureMdc() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : MDC_KEYS) {
            values.put(key, MDC.get(key));
        }
        return values;
    }

    private static void restoreMdc(Map<String, String> previous) {
        previous.forEach((key, value) -> {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
    }
}
