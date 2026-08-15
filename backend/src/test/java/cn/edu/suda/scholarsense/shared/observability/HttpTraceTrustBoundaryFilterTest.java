package cn.edu.suda.scholarsense.shared.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Collections;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpTraceTrustBoundaryFilterTest {
    private static final String TRACE_ID = "11111111111111111111111111111111";
    private static final String TRACEPARENT =
            "00-" + TRACE_ID + "-2222222222222222-01";

    private final W3cTraceContextCodec codec = new W3cTraceContextCodec(
            () -> "33333333333333333333333333333333",
            () -> "4444444444444444");
    private final HttpTraceTrustBoundaryFilter filter = new HttpTraceTrustBoundaryFilter(
            new TrustedIngressAllowlist(
                    "TRUSTED-INGRESS-1.0.0",
                    Map.of("10.10.0.10", "portal-proxy-test-v1")),
            codec);

    @Test
    void preservesW3cOnlyForExactTrustedIngressAndAlwaysDropsBaggage() throws Exception {
        MockHttpServletRequest request = trustedProxyRequest("api.suda.edu.cn");
        request.addHeader("traceparent", TRACEPARENT);
        request.addHeader("tracestate", "vendor=value");
        request.addHeader("baggage", "studentId=20260001");

        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, ignored) -> {
            HttpServletRequest http = (HttpServletRequest) wrapped;
            assertEquals(TRACEPARENT, http.getHeader("traceparent"));
            assertNull(http.getHeader("baggage"));
        });
    }

    @Test
    void removesAllPropagationHeadersForUntrustedIngress() throws Exception {
        MockHttpServletRequest request = request("api.suda.edu.cn");
        request.addHeader("traceparent", TRACEPARENT);
        request.addHeader("tracestate", "vendor=value");
        request.addHeader("baggage", "safe=value");
        request.addHeader("X-ScholarSense-Trace-Id", TRACE_ID);

        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, ignored) -> {
            HttpServletRequest http = (HttpServletRequest) wrapped;
            assertNull(http.getHeader("traceparent"));
            assertNull(http.getHeader("tracestate"));
            assertNull(http.getHeader("baggage"));
            assertNull(http.getHeader("X-ScholarSense-Trace-Id"));
        });
    }

    @Test
    void trustedLegacyHeaderBridgesToRealW3cDuringObsOneCompatibility() throws Exception {
        MockHttpServletRequest request = trustedProxyRequest("portal.suda.edu.cn");
        request.addHeader("X-ScholarSense-Trace-Id", TRACE_ID);

        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, ignored) -> {
            HttpServletRequest http = (HttpServletRequest) wrapped;
            String bridged = http.getHeader("traceparent");
            assertTrue(bridged.matches("00-" + TRACE_ID + "-[0-9a-f]{16}-00"));
            assertEquals(TRACE_ID, http.getHeader("X-ScholarSense-Trace-Id"));
        });
    }

    @Test
    void requiresBothExactProxySocketAndProxyInjectedIdentity() throws Exception {
        for (MockHttpServletRequest request : java.util.List.of(
                request("api.suda.edu.cn"),
                request("api.suda.edu.cn", "10.10.0.10", null),
                request("api.suda.edu.cn", "203.0.113.9", "portal-proxy-test-v1"),
                request("api.suda.edu.cn", "10.10.0.10", "wrong-proxy-v1"))) {
            request.addHeader("traceparent", TRACEPARENT);
            filter.doFilter(request, new MockHttpServletResponse(), (wrapped, ignored) ->
                    assertNull(((HttpServletRequest) wrapped).getHeader("traceparent")));
        }
    }

    @Test
    void preservesAllValuesOfNonPropagationHeaders() throws Exception {
        MockHttpServletRequest request = trustedProxyRequest("api.suda.edu.cn");
        request.addHeader("X-Feature", "first");
        request.addHeader("X-Feature", "second");

        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, ignored) ->
                assertEquals(
                        java.util.List.of("first", "second"),
                        Collections.list(
                                ((HttpServletRequest) wrapped).getHeaders("X-Feature"))));
    }

    private static MockHttpServletRequest trustedProxyRequest(String host) {
        return request(host, "10.10.0.10", "portal-proxy-test-v1");
    }

    private static MockHttpServletRequest request(String host) {
        return request(host, "203.0.113.9", null);
    }

    private static MockHttpServletRequest request(
            String host, String remoteAddress, String proxyIdentity) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName(host);
        request.setRemoteAddr(remoteAddress);
        if (proxyIdentity != null) {
            request.addHeader("X-ScholarSense-Proxy-Identity", proxyIdentity);
        }
        request.setMethod("POST");
        request.setRequestURI("/api/v1/test");
        return request;
    }
}
