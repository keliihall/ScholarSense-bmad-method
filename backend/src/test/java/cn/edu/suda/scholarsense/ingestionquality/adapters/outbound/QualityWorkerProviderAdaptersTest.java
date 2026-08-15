package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchAuthorizationDecision;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchAuthorizationRequest;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchCommandContext;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchCommandType;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchWorkloadAuthorizationRequest;
import cn.edu.suda.scholarsense.runtime.RuntimeEnvironment;
import cn.edu.suda.scholarsense.shared.observability.ObservationPort;
import cn.edu.suda.scholarsense.shared.observability.TrustedHttpClient;
import cn.edu.suda.scholarsense.shared.observability.TrustedHttpClientFactory;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContext;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContextCodec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class QualityWorkerProviderAdaptersTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-10T08:00:00Z");

    @Test
    void bindsAllFourTargetManagedProvidersAndRevalidatesWorkloadEvidence() {
        QualityWorkerProviderAdapters adapters = adapters((endpoint, request) -> {
            String operation = request.path("operation").asText();
            return switch (operation) {
                case "authorize-data-batch" -> object("""
                        {"decision":"ALLOW"}
                        """);
                case "capture-workload", "revalidate-workload" -> object("""
                        {"status":"ALLOW","currentAuthorizationGeneration":7,"evidence":{
                          "environment":"test","principalRef":"quality-worker-a",
                          "mtlsSanUriRef":"spiffe://test.invalid/scholarsense/ingestion-quality/quality-worker",
                          "audience":"ingestion-quality:data-batch-command",
                          "capabilities":["data-batch.receive"],"authorizationGeneration":7,
                          "policyVersion":"WORKLOAD-AUTH-1.0.0",
                          "policyDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "effectiveAt":"2026-08-10T07:00:00Z","expiresAt":"2026-08-10T09:00:00Z",
                          "revokedAt":null}}
                        """);
                case "next-quality-snapshot-id" -> object("""
                        {"snapshotId":"019fa0d6-cc00-7000-8000-000000000001"}
                        """);
                case "trusted-time-status" -> object("""
                        {"sourceId":"school-clock.test","profileVersion":"AUDIT-CLOCK-BINDING-1.0.0",
                         "offsetMs":2,"observedAt":"2026-08-10T07:59:00Z",
                         "freshUntil":"2026-08-10T08:01:00Z",
                         "evidenceRef":"evidence://signed/clock/test-1"}
                        """);
                default -> throw new AssertionError(operation);
            };
        });

        assertEquals(DataBatchAuthorizationDecision.ALLOW, adapters.authorize(
                new DataBatchAuthorizationRequest(
                        new DataBatchCommandContext(
                                "tenant-a", "ignored", "idem-a",
                                "00112233445566778899aabbccddeeff"),
                        DataBatchCommandType.RECEIVE, "SRC-P0-STUDENT-001",
                        UUID.fromString("019fa0d6-cc00-7000-8000-000000000002"), 0)));
        var request = new DataBatchWorkloadAuthorizationRequest(
                DataBatchCommandType.RECEIVE, "ingestion-quality:data-batch-command",
                Set.of("data-batch.receive"), NOW);
        var captured = adapters.capture(request);
        assertEquals("quality-worker-a", captured.evidence().principalRef());
        assertEquals(7, adapters.revalidate(captured.evidence(), request)
                .currentAuthorizationGeneration());
        assertEquals(7, adapters.nextId(NOW).version());
        assertEquals("school-clock.test", adapters.current().orElseThrow().sourceId());
    }

    @Test
    void malformedOrNonV7ProviderEvidenceFailsClosed() {
        QualityWorkerProviderAdapters adapters = adapters((endpoint, request) ->
                object("{\"snapshotId\":\"00000000-0000-4000-8000-000000000000\"}"));
        assertThrows(IllegalStateException.class, () -> adapters.nextId(NOW));
    }

    @Test
    void productionMtlsAdapterUsesTheGovernedClientForOrdinaryJsonCalls() throws Exception {
        HttpClient raw = mock(HttpClient.class);
        when(raw.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(
                "{\"snapshotId\":\"019fa0d6-cc00-7000-8000-000000000001\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        when(raw.send(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return response;
        });
        W3cTraceContext parent = new W3cTraceContext(
                "00112233445566778899aabbccddeeff", "1111111111111111", true);
        W3cTraceContext child = new W3cTraceContext(
                parent.traceId(), "2222222222222222", true);
        ObservationPort observations = mock(ObservationPort.class);
        ObservationPort.ObservationScope scope = mock(ObservationPort.ObservationScope.class);
        when(observations.start(any(), any(), any(), any())).thenReturn(scope);
        when(scope.context()).thenReturn(child);
        URI base = URI.create("https://localhost:43192");
        TrustedHttpClient governed = new TrustedHttpClientFactory(
                () -> java.util.Optional.of(parent), new W3cTraceContextCodec(),
                RuntimeEnvironment.TEST, observations).wrapSandboxQualityWorker(
                        raw,
                        base.resolve("/data-batches"),
                        base.resolve("/workloads"),
                        base.resolve("/quality-snapshots"),
                        base.resolve("/time"));
        QualityWorkerProviderAdapters adapters = new QualityWorkerProviderAdapters(
                base.resolve("/data-batches"),
                base.resolve("/workloads"),
                base.resolve("/quality-snapshots"),
                base.resolve("/time"), JSON, governed);

        assertEquals(7, adapters.nextId(NOW).version());

        assertTrue(sent.get().headers().firstValue("Authorization").isEmpty());
        assertEquals("00-" + child.traceId() + "-" + child.spanId() + "-01",
                sent.get().headers().firstValue("traceparent").orElseThrow());
    }

    private static QualityWorkerProviderAdapters adapters(
            QualityWorkerProviderAdapters.Exchange exchange) {
        return new QualityWorkerProviderAdapters(
                URI.create("https://authorization.test.invalid/data-batches"),
                URI.create("https://authorization.test.invalid/workloads"),
                URI.create("https://identity.test.invalid/quality-snapshots"),
                URI.create("https://clock.test.invalid/status"), JSON, exchange);
    }

    private static tools.jackson.databind.JsonNode object(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
