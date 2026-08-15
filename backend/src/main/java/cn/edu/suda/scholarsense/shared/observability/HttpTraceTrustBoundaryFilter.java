package cn.edu.suda.scholarsense.shared.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/** Sanitizes propagation headers before Spring creates the single server observation. */
public final class HttpTraceTrustBoundaryFilter extends OncePerRequestFilter {
    private static final String TRACEPARENT = "traceparent";
    private static final String TRACESTATE = "tracestate";
    private static final String BAGGAGE = "baggage";
    private static final String LEGACY = "x-scholarsense-trace-id";
    static final String PROXY_IDENTITY = "x-scholarsense-proxy-identity";
    private static final Set<String> PROPAGATION_HEADERS =
            Set.of(TRACEPARENT, TRACESTATE, BAGGAGE, LEGACY, PROXY_IDENTITY);

    private final TrustedIngressAllowlist trustedIngress;
    private final W3cTraceContextCodec codec;

    public HttpTraceTrustBoundaryFilter(
            TrustedIngressAllowlist trustedIngress,
            W3cTraceContextCodec codec) {
        this.trustedIngress = Objects.requireNonNull(trustedIngress, "trustedIngress");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        boolean trusted = trustedIngress.allows(
                request.getRemoteAddr(), request.getHeader(PROXY_IDENTITY));
        String traceparent = trusted ? request.getHeader(TRACEPARENT) : null;
        String legacy = trusted ? validLegacy(request.getHeader(LEGACY)) : null;
        if ((traceparent == null || traceparent.isBlank()) && legacy != null) {
            W3cTraceContext bridge = new W3cTraceContext(
                    legacy, codec.newRoot(false).spanId(), false);
            traceparent = codec.format(bridge);
        }
        chain.doFilter(
                new SanitizedPropagationRequest(request, trusted, traceparent, legacy),
                response);
    }

    private static String validLegacy(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return normalized.matches("(?!0{32})[0-9a-f]{32}") ? normalized : null;
    }

    private static final class SanitizedPropagationRequest extends HttpServletRequestWrapper {
        private final boolean trusted;
        private final String traceparent;
        private final String legacy;

        SanitizedPropagationRequest(
                HttpServletRequest request,
                boolean trusted,
                String traceparent,
                String legacy) {
            super(request);
            this.trusted = trusted;
            this.traceparent = traceparent;
            this.legacy = legacy;
        }

        @Override
        public String getHeader(String name) {
            String normalized = name.toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case TRACEPARENT -> traceparent;
                case TRACESTATE -> trusted && traceparent != null ? super.getHeader(name) : null;
                case BAGGAGE -> null;
                case LEGACY -> legacy;
                case PROXY_IDENTITY -> null;
                default -> super.getHeader(name);
            };
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (!PROPAGATION_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return super.getHeaders(name);
            }
            String value = getHeader(name);
            return value == null
                    ? Collections.emptyEnumeration()
                    : Collections.enumeration(java.util.List.of(value));
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            Enumeration<String> existing = super.getHeaderNames();
            while (existing.hasMoreElements()) {
                String name = existing.nextElement();
                if (!PROPAGATION_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    names.add(name);
                }
            }
            if (traceparent != null) {
                names.add(TRACEPARENT);
            }
            if (trusted && traceparent != null && super.getHeader(TRACESTATE) != null) {
                names.add(TRACESTATE);
            }
            if (legacy != null) {
                names.add("X-ScholarSense-Trace-Id");
            }
            return Collections.enumeration(new ArrayList<>(names));
        }
    }
}
