package cn.edu.suda.scholarsense.shared.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import cn.edu.suda.scholarsense.runtime.RuntimeEnvironment;
import org.junit.jupiter.api.Test;

class ObservabilityKernelTest {
    private static final String TRACE_ID = "11111111111111111111111111111111";
    private static final String PARENT_ID = "2222222222222222";
    private static final String ROOT_TRACE = "33333333333333333333333333333333";
    private static final String ROOT_SPAN = "4444444444444444";

    private final W3cTraceContextCodec codec = new W3cTraceContextCodec(
            () -> ROOT_TRACE, () -> ROOT_SPAN);

    @Test
    void inheritsOnlyTrustedValidW3cContext() {
        TraceExtraction extraction = codec.extract(
                "00-" + TRACE_ID + "-" + PARENT_ID + "-01", true);

        assertEquals(TraceExtractionReason.INHERITED, extraction.reason());
        assertEquals(TRACE_ID, extraction.context().traceId());
        assertEquals(PARENT_ID, extraction.context().spanId());
        assertTrue(extraction.context().sampled());
        assertTrue(extraction.remoteParent());
    }

    @Test
    void missingInvalidAllZeroAndUntrustedContextsCreateCleanRoots() {
        assertCleanRoot(null, true, TraceExtractionReason.MISSING);
        assertCleanRoot("not-w3c", true, TraceExtractionReason.INVALID);
        assertCleanRoot(
                "FF-" + TRACE_ID + "-" + PARENT_ID + "-01",
                true,
                TraceExtractionReason.INVALID);
        assertCleanRoot(
                "01-" + TRACE_ID + "-" + PARENT_ID + "-01",
                true,
                TraceExtractionReason.INVALID);
        assertCleanRoot(
                "00-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA-BBBBBBBBBBBBBBBB-01",
                true,
                TraceExtractionReason.INVALID);
        assertCleanRoot(
                "00-00000000000000000000000000000000-" + PARENT_ID + "-01",
                true,
                TraceExtractionReason.ALL_ZERO);
        assertCleanRoot(
                "00-" + TRACE_ID + "-" + PARENT_ID + "-01",
                false,
                TraceExtractionReason.UNTRUSTED);
    }

    @Test
    void createsRealChildSpanAndFormatsLowercaseTraceparent() {
        W3cTraceContext parent = codec.extract(
                "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-00", true).context();
        W3cTraceContext child = codec.child(parent);

        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", child.traceId());
        assertNotEquals(parent.spanId(), child.spanId());
        assertEquals(
                "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-4444444444444444-00",
                codec.format(child));
    }

    @Test
    void trustedTargetPolicyIsExactHttpsAllowlistAndRejectsUnsafeUris() {
        TrustedTargetPolicy policy = new TrustedTargetPolicy(Set.of(
                "idp.suda.edu.cn", "quality-provider.suda.edu.cn"));

        assertTrue(policy.allows(URI.create("https://idp.suda.edu.cn/v1/sync")));
        assertFalse(policy.allows(URI.create("http://idp.suda.edu.cn/v1/sync")));
        assertFalse(policy.allows(URI.create("https://evil.idp.suda.edu.cn/v1/sync")));
        assertFalse(policy.allows(URI.create("https://user@idp.suda.edu.cn/v1/sync")));
        assertEquals(
                Map.of("traceparent", "00-" + ROOT_TRACE + "-" + ROOT_SPAN + "-01"),
                codec.inject(
                        new W3cTraceContext(ROOT_TRACE, ROOT_SPAN, true),
                        URI.create("https://idp.suda.edu.cn/v1/sync"),
                        policy));
        assertEquals(
                Map.of(),
                codec.inject(
                        new W3cTraceContext(ROOT_TRACE, ROOT_SPAN, true),
                        URI.create("https://untrusted.example/v1/sync"),
                        policy));
    }

    @Test
    void approvedAuthorityProfilesDriveEnvironmentSpecificEgressTrust() {
        TrustedTargetPolicy sandbox = TrustedTargetPolicy.fromSandboxProfile(
                RuntimeEnvironment.TEST,
                "QUALITY-WORKER-PROVIDER-PROFILE-1.0.0",
                Set.of(URI.create("http://127.0.0.1:43199/authority")));
        assertTrue(sandbox.allows(URI.create("http://127.0.0.1:43199/other")));
        assertFalse(sandbox.allows(URI.create("http://127.0.0.1:43200/authority")));

        assertThrows(IllegalArgumentException.class, () ->
                TrustedTargetPolicy.fromSandboxProfile(
                        RuntimeEnvironment.PROD,
                        "IDENTITY-RUNTIME-PROFILE-1.0.0",
                        Set.of(URI.create("https://identity-authority.suda.edu.cn/v1/feed"))));
        assertThrows(IllegalArgumentException.class, () ->
                TrustedTargetPolicy.fromSandboxProfile(
                        RuntimeEnvironment.TEST,
                        "CALLER-INVENTED-PROFILE-1.0.0",
                        Set.of(URI.create("http://127.0.0.1:43199/authority"))));
        assertThrows(IllegalArgumentException.class, () ->
                TrustedTargetPolicy.fromSandboxProfile(
                        RuntimeEnvironment.TEST,
                        "QUALITY-WORKER-PROVIDER-PROFILE-1.0.0",
                        Set.of(URI.create("https://caller-chosen.example/authority"))));
    }

    @Test
    void privacySafeAttributesSeparateMetricAndTraceCardinality() {
        SafeObservationAttributes attributes = SafeObservationAttributes.create()
                .low("module", "ingestion-quality")
                .low("outcome", "success")
                .high("aggregateVersion", "7");

        assertEquals(
                Map.of("module", "ingestion-quality", "outcome", "success"),
                attributes.metricLabels());
        assertEquals("ingestion-quality",
                attributes.spanAttributes().get("scholarsense.module"));
        assertEquals("success",
                attributes.spanAttributes().get("scholarsense.outcome"));
        assertEquals("7",
                attributes.spanAttributes().get("scholarsense.aggregate.version"));
        assertFalse(attributes.spanAttributes().containsKey("module"));
        assertFalse(attributes.spanAttributes().containsKey("aggregateVersion"));
        assertThrows(IllegalArgumentException.class,
                () -> SafeObservationAttributes.create().low("traceId", TRACE_ID));
        assertThrows(IllegalArgumentException.class,
                () -> SafeObservationAttributes.create().high("studentId", "20260001"));
        assertThrows(IllegalArgumentException.class,
                () -> SafeObservationAttributes.create().low("outcome", "张三"));
    }

    @Test
    void currentTraceSourceScopesAndRestoresWithoutLeaking() {
        ScopedCurrentTraceSource source = new ScopedCurrentTraceSource();
        W3cTraceContext first = new W3cTraceContext(TRACE_ID, PARENT_ID, true);
        W3cTraceContext second = new W3cTraceContext(ROOT_TRACE, ROOT_SPAN, false);

        assertTrue(source.current().isEmpty());
        try (CurrentTraceSource.Scope ignored = source.open(first)) {
            assertEquals(first, source.current().orElseThrow());
            try (CurrentTraceSource.Scope nested = source.open(second)) {
                assertEquals(second, source.current().orElseThrow());
            }
            assertEquals(first, source.current().orElseThrow());
        }
        assertTrue(source.current().isEmpty());
    }

    private void assertCleanRoot(
            String header, boolean trusted, TraceExtractionReason expectedReason) {
        TraceExtraction extraction = codec.extract(header, trusted);
        assertEquals(expectedReason, extraction.reason());
        assertEquals(ROOT_TRACE, extraction.context().traceId());
        assertEquals(ROOT_SPAN, extraction.context().spanId());
        assertFalse(extraction.context().sampled());
        assertFalse(extraction.remoteParent());
    }
}
