package cn.edu.suda.scholarsense.shared.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TrustedHttpClientTest {
    private static final W3cTraceContext PARENT = new W3cTraceContext(
            "11111111111111111111111111111111", "2222222222222222", true);
    private static final W3cTraceContext CLIENT = new W3cTraceContext(
            PARENT.traceId(), "3333333333333333", true);

    @Test
    void trustedTargetGetsClientContextAndLegacyHeaderWithoutChangingRequestSemantics()
            throws Exception {
        HttpClient delegate = mock(HttpClient.class);
        when(delegate.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        HttpResponse<String> response = mock(HttpResponse.class);
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        when(response.statusCode()).thenReturn(200);
        when(delegate.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    sent.set(invocation.getArgument(0));
                    return response;
                });
        ObservationPort observations = observations(CLIENT);
        TrustedHttpClient client = new TrustedHttpClient(
                delegate, () -> java.util.Optional.of(PARENT), new W3cTraceContextCodec(),
                new TrustedTargetPolicy(Set.of("identity-authority.suda.edu.cn")),
                observations);
        HttpRequest original = HttpRequest.newBuilder(
                        URI.create("https://identity-authority.suda.edu.cn/feed"))
                .timeout(Duration.ofSeconds(7))
                .header("Authorization", "Basic redacted")
                .header("X-Signature", "opaque-signature")
                .header("baggage", "studentId=20260001")
                .POST(HttpRequest.BodyPublishers.ofString("signed-material"))
                .build();

        client.send(original, HttpResponse.BodyHandlers.ofString(),
                "identity-access", PARENT.traceId());

        HttpRequest governed = sent.get();
        assertEquals("POST", governed.method());
        assertEquals(Duration.ofSeconds(7), governed.timeout().orElseThrow());
        assertTrue(governed.bodyPublisher().isPresent());
        assertEquals("Basic redacted",
                governed.headers().firstValue("Authorization").orElseThrow());
        assertEquals("opaque-signature",
                governed.headers().firstValue("X-Signature").orElseThrow());
        assertFalse(governed.headers().firstValue("baggage").isPresent());
        assertEquals("00-" + CLIENT.traceId() + "-" + CLIENT.spanId() + "-01",
                governed.headers().firstValue("traceparent").orElseThrow());
        assertEquals(CLIENT.traceId(), governed.headers()
                .firstValue(TrustedHttpClient.LEGACY_TRACE_HEADER).orElseThrow());
    }

    @Test
    void untrustedTargetStripsBothTraceHeadersAndStillExecutes() throws Exception {
        HttpClient delegate = mock(HttpClient.class);
        when(delegate.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        HttpResponse<String> response = mock(HttpResponse.class);
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        when(response.statusCode()).thenReturn(401);
        when(delegate.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    sent.set(invocation.getArgument(0));
                    return response;
                });
        ObservationPort observations = observations(CLIENT);
        TrustedHttpClient client = new TrustedHttpClient(
                delegate, () -> java.util.Optional.of(PARENT), new W3cTraceContextCodec(),
                new TrustedTargetPolicy(Set.of("identity-authority.suda.edu.cn")),
                observations);
        HttpRequest original = HttpRequest.newBuilder(URI.create("https://outside.example/api"))
                .header("traceparent", "00-" + PARENT.traceId() + "-" + PARENT.spanId() + "-01")
                .header("tracestate", "vendor=caller-controlled")
                .header(TrustedHttpClient.LEGACY_TRACE_HEADER, PARENT.traceId())
                .GET().build();

        assertEquals(401, client.send(
                original, HttpResponse.BodyHandlers.ofString(), "identity-access", null)
                .statusCode());
        when(response.statusCode()).thenReturn(503);
        assertEquals(503, client.send(
                original, HttpResponse.BodyHandlers.ofString(), "identity-access", null)
                .statusCode());

        assertFalse(sent.get().headers().firstValue("traceparent").isPresent());
        assertFalse(sent.get().headers().firstValue("tracestate").isPresent());
        assertFalse(sent.get().headers()
                .firstValue(TrustedHttpClient.LEGACY_TRACE_HEADER).isPresent());
        verify(observations.start(any(), any(), any(), any()))
                .outcome("unavailable");
        verify(observations.start(
                any(), any(), any(), any()), times(2)).error(any(Throwable.class));
    }

    @Test
    void timeoutKeepsTheLocalClientSpanAndOriginalExceptionSemantics() throws Exception {
        HttpClient delegate = mock(HttpClient.class);
        when(delegate.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        when(delegate.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    sent.set(invocation.getArgument(0));
                    throw new HttpTimeoutException("controlled timeout");
                });
        ObservationPort observations = observations(CLIENT);
        TrustedHttpClient client = new TrustedHttpClient(
                delegate, () -> java.util.Optional.of(PARENT), new W3cTraceContextCodec(),
                new TrustedTargetPolicy(Set.of("identity-authority.suda.edu.cn")),
                observations);

        assertThrows(HttpTimeoutException.class, () -> client.send(
                HttpRequest.newBuilder(
                        URI.create("https://identity-authority.suda.edu.cn/feed"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString(), "identity-access", PARENT.traceId()));

        assertEquals(CLIENT.traceId(), sent.get().headers()
                .firstValue(TrustedHttpClient.LEGACY_TRACE_HEADER).orElseThrow());
        verify(observations.start(any(), any(), any(), any())).outcome("timeout");
        verify(observations.start(
                any(), any(), any(), any())).error(any(HttpTimeoutException.class));
    }

    @Test
    void automaticRedirectDelegateIsRejectedBeforeTraceHeadersCanEscapeTheAllowlist() {
        HttpClient redirecting = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        assertThrows(IllegalArgumentException.class, () -> new TrustedHttpClient(
                redirecting,
                () -> java.util.Optional.of(PARENT),
                new W3cTraceContextCodec(),
                new TrustedTargetPolicy(Set.of("identity-authority.suda.edu.cn")),
                observations(CLIENT)));
    }

    private static ObservationPort observations(W3cTraceContext child) {
        ObservationPort observations = mock(ObservationPort.class);
        ObservationPort.ObservationScope scope = mock(ObservationPort.ObservationScope.class);
        when(observations.start(any(), any(), any(), any())).thenReturn(scope);
        when(scope.context()).thenReturn(child);
        return observations;
    }
}
