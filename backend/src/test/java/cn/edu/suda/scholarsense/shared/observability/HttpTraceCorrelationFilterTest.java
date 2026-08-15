package cn.edu.suda.scholarsense.shared.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpTraceCorrelationFilterTest {
    private static final W3cTraceContext CURRENT = new W3cTraceContext(
            "11111111111111111111111111111111", "2222222222222222", true);

    @Test
    void cachesOneCurrentContextForHandlersResponsesAndStructuredLogs() throws Exception {
        CurrentTraceSource current = () -> Optional.of(CURRENT);
        HttpTraceCorrelationFilter filter = new HttpTraceCorrelationFilter(
                current, new W3cTraceContextCodec(), "web-api");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> handlerTrace = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            handlerTrace.set(HttpTraceContext.traceId(request));
            assertEquals(CURRENT.traceId(), MDC.get("traceId"));
            assertEquals("web-api", MDC.get("module"));
            assertEquals("http.request", MDC.get("event"));
        });

        assertEquals(CURRENT.traceId(), handlerTrace.get());
        assertEquals(CURRENT.traceId(), response.getHeader("X-ScholarSense-Trace-Id"));
        assertEquals(
                "00-11111111111111111111111111111111-2222222222222222-01",
                response.getHeader("traceparent"));
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("module"));
        assertNull(MDC.get("event"));
    }

    @Test
    void centralFallbackCachesOneCleanContextWhenFrameworkSpanIsUnavailable() throws Exception {
        W3cTraceContextCodec codec = new W3cTraceContextCodec(
                () -> "33333333333333333333333333333333",
                () -> "4444444444444444");
        HttpTraceCorrelationFilter filter = new HttpTraceCorrelationFilter(
                Optional::empty, codec, "web-api");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            assertEquals("33333333333333333333333333333333", HttpTraceContext.traceId(request));
            assertEquals(HttpTraceContext.traceId(request), HttpTraceContext.traceId(request));
        });

        assertEquals(
                "33333333333333333333333333333333",
                response.getHeader("X-ScholarSense-Trace-Id"));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400, 401, 403, 409, 500})
    void successAndAd12ErrorOutcomesShareTraceAcrossResponseLogAndAudit(int status)
            throws Exception {
        HttpTraceCorrelationFilter filter = new HttpTraceCorrelationFilter(
                () -> Optional.of(CURRENT), new W3cTraceContextCodec(), "web-api");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> auditTrace = new AtomicReference<>();
        Logger logger = (Logger) LoggerFactory.getLogger(HttpTraceCorrelationFilter.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            filter.doFilter(request, response, (ignoredRequest, rawResponse) -> {
                String traceId = HttpTraceContext.traceId(request);
                auditTrace.set(traceId);
                HttpServletResponse http = (HttpServletResponse) rawResponse;
                http.setStatus(status);
                http.getWriter().write("{\"code\":\"result\",\"message\":\"safe\","
                        + "\"traceId\":\"" + traceId + "\",\"fieldErrors\":[]}");
            });
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }

        assertEquals(CURRENT.traceId(), auditTrace.get());
        assertEquals(CURRENT.traceId(), response.getHeader("X-ScholarSense-Trace-Id"));
        assertTrue(response.getContentAsString().contains(
                "\"traceId\":\"" + CURRENT.traceId() + "\""));
        assertEquals(CURRENT.traceId(), logs.list.getLast().getMDCPropertyMap().get("traceId"));
        assertEquals(expectedCode(status), logs.list.getLast().getMDCPropertyMap().get("code"));
    }

    private static String expectedCode(int status) {
        return switch (status) {
            case 200 -> "ok";
            case 400 -> "invalid";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 409 -> "conflict";
            default -> "error";
        };
    }
}
