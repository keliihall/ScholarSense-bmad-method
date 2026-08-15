package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import cn.edu.suda.scholarsense.shared.observability.MicrometerCurrentTraceSource;
import cn.edu.suda.scholarsense.shared.observability.MicrometerObservationPort;
import cn.edu.suda.scholarsense.shared.observability.ObservationPort;
import cn.edu.suda.scholarsense.shared.observability.SafeObservationAttributes;
import cn.edu.suda.scholarsense.shared.observability.ScholarSenseStructuredLogFormatter;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContext;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContextCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class FullTraceBundleConformanceTest {
    private static final String TRACE = "00112233445566778899aabbccddeeff";
    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
    private static final UUID JOB = UUID.fromString("019fb91c-8400-7000-8000-000000000001");
    private static final UUID EVENT = UUID.fromString("019fb91c-8400-7000-8000-000000000002");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void generatesDeterministicSdkTopologyBundleForPrivacyScanning() throws Exception {
        CollectingExporter exporter = new CollectingExporter();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            OtelCurrentTraceContext traceContext = new OtelCurrentTraceContext();
            OtelTracer tracer = new OtelTracer(
                    provider.get("scholarsense-conformance"), traceContext, ignored -> {});
            var current = new MicrometerCurrentTraceSource(tracer);
            W3cTraceContextCodec codec = new W3cTraceContextCodec();
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            ObservationPort observations = new MicrometerObservationPort(
                    meters, tracer, current, codec);
            TopologyOnlyWorkProbe work = new TopologyOnlyWorkProbe(current, codec);

            W3cTraceContext ingressParent = new W3cTraceContext(
                    TRACE, "aaaaaaaaaaaaaaaa", true);
            try (ObservationPort.ObservationScope server = observations.start(
                    "http.server", ObservationPort.ObservationKind.SERVER,
                    dimensions("web-api", "http.server"), ingressParent)) {
                assertEquals(
                        new SubjectWindowRecomputeWorkerResult(1, 1, 0, 0),
                        new SubjectWindowRecomputeProcessor(
                                work, () -> EVENT, Clock.fixed(NOW, ZoneOffset.UTC),
                                observations, codec).runBatch());

            }
            CompletableResultCode flush = provider.forceFlush();
            flush.join(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(flush.isSuccess());

            Map<String, SpanData> spans = exporter.spans.stream().collect(
                    java.util.stream.Collectors.toMap(SpanData::getName, value -> value));
            Set<String> expected = Set.of(
                    "http.server", "job.attempt", "batch.evaluate", "job.finalize",
                    "outbox.publish");
            assertEquals(expected, spans.keySet());
            assertEquals(Set.of(TRACE), spans.values().stream()
                    .map(SpanData::getTraceId).collect(java.util.stream.Collectors.toSet()));
            assertEquals(spans.size(), spans.values().stream()
                    .map(SpanData::getSpanId).distinct().count());
            assertParent(spans, "batch.evaluate", "job.attempt");
            assertParent(spans, "job.attempt", "http.server");
            assertParent(spans, "job.finalize", "job.attempt");
            assertParent(spans, "outbox.publish", "job.finalize");
            assertEquals(spans.get("outbox.publish").getSpanId(),
                    work.traceparent.substring(36, 52));
            assertEquals(5, meters.getMeters().size());
            assertEquals(Set.of("scholarsense.operation.duration"), meters.getMeters().stream()
                    .map(meter -> meter.getId().getName())
                    .collect(java.util.stream.Collectors.toSet()));

            ObjectNode bundle = bundle(spans, work.traceparent, meters);
            String encoded = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(bundle);
            for (String denied : List.of(
                    "张三", "20260001", "证据正文-不可导出", "api-key-secret",
                    "Bearer secret-token", "session=cookie-secret",
                    "-----BEGIN CERTIFICATE-----")) {
                assertFalse(encoded.contains(denied), denied);
            }
            Path output = Path.of(
                    "target", "observability", "sdk-topology-privacy-bundle-1.0.0.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, encoded + System.lineSeparator());
        }
    }

    private static ObjectNode bundle(
            Map<String, SpanData> spans,
            String traceparent,
            SimpleMeterRegistry meters) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("bundleVersion", "OBS-TRACE-BUNDLE-1.0.0");
        root.put("traceId", TRACE);
        ArrayNode spanValues = root.putArray("spans");
        spans.values().stream().sorted(Comparator.comparing(SpanData::getName)).forEach(span -> {
            ObjectNode value = spanValues.addObject();
            value.put("name", span.getName());
            value.put("traceId", span.getTraceId());
            value.put("spanId", span.getSpanId());
            value.put("parentSpanId", span.getParentSpanId());
            value.put("kind", span.getKind().name().toLowerCase(java.util.Locale.ROOT));
        });
        root.putObject("event")
                .put("traceId", TRACE)
                .put("traceparent", traceparent);

        LoggingEvent event = new LoggingEvent();
        event.setInstant(NOW);
        event.setLevel(Level.INFO);
        event.setMessage("张三 20260001 Authorization: Bearer secret-token");
        event.setMDCPropertyMap(Map.of(
                "module", "ingestion-quality", "traceId", TRACE,
                "event", "batch.evaluate", "code", "ok",
                "studentId", "20260001"));
        root.set("log", JSON.readTree(new ScholarSenseStructuredLogFormatter().format(event)));

        ArrayNode metricValues = root.putArray("metrics");
        meters.getMeters().stream()
                .sorted(Comparator.comparing(meter -> meter.getId().getName()))
                .forEach(meter -> {
                    ObjectNode value = metricValues.addObject();
                    value.put("name", meter.getId().getName());
                    ObjectNode tags = value.putObject("tags");
                    meter.getId().getTags().forEach(tag -> {
                        assertFalse(Set.of(
                                "traceId", "spanId", "studentId", "batchId",
                                "aggregateId", "url", "query").contains(tag.getKey()));
                        tags.put(tag.getKey(), tag.getValue());
                    });
                });
        return root;
    }

    private static SafeObservationAttributes dimensions(String module, String operation) {
        return SafeObservationAttributes.create()
                .low("module", module)
                .low("operation", operation)
                .low("outcome", "success");
    }

    private static void assertParent(
            Map<String, SpanData> spans, String child, String parent) {
        assertEquals(spans.get(parent).getSpanId(), spans.get(child).getParentSpanId(), child);
    }

    /** SDK-only topology probe; PostgreSQL production evidence lives in the dedicated PG ITs. */
    private static final class TopologyOnlyWorkProbe implements SubjectWindowRecomputeWorkPort {
        private final MicrometerCurrentTraceSource current;
        private final W3cTraceContextCodec codec;
        private String traceparent;

        private TopologyOnlyWorkProbe(
                MicrometerCurrentTraceSource current, W3cTraceContextCodec codec) {
            this.current = current;
            this.codec = codec;
        }

        @Override
        public List<SubjectWindowRecomputeCandidate> findClaimable(int batchSize, Instant now) {
            String durableParent = codec.format(current.current().orElseThrow());
            return List.of(new SubjectWindowRecomputeCandidate(
                    JOB, 0, TRACE, durableParent));
        }

        @Override
        public long claim(
                UUID jobId, String workerId, Instant now, Duration lease) {
            return 7;
        }

        @Override
        public boolean checkpoint(UUID jobId, long fence, long sequence, Instant now) {
            return true;
        }

        @Override
        public MappingRecomputeCompletion complete(
                UUID jobId, long fence, Instant now, UUID eventId) {
            traceparent = codec.format(current.current().orElseThrow());
            return new MappingRecomputeCompletion("RECOMPUTED", true, true);
        }

        @Override
        public boolean fail(UUID jobId, long fence, Instant now, String controlledCode) {
            return true;
        }
    }

    private static final class CollectingExporter implements SpanExporter {
        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> values) {
            spans.addAll(values);
            return CompletableResultCode.ofSuccess();
        }

        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
}
