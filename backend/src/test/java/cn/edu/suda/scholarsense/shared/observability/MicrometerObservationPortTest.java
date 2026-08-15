package cn.edu.suda.scholarsense.shared.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MicrometerObservationPortTest {
    @Test
    void recordsOnlyLowCardinalityMetricLabelsAndReturnsChildContext() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(
                new DefaultMeterObservationHandler(meters));
        ScopedCurrentTraceSource current = new ScopedCurrentTraceSource();
        W3cTraceContext parent = new W3cTraceContext(
                "11111111111111111111111111111111", "2222222222222222", true);
        MicrometerObservationPort port = new MicrometerObservationPort(
                observations, current, new W3cTraceContextCodec());

        try (CurrentTraceSource.Scope ignored = current.open(parent);
                ObservationPort.ObservationScope scope = port.start(
                        "scholarsense.operation",
                        ObservationPort.ObservationKind.INTERNAL,
                        SafeObservationAttributes.create()
                                .low("module", "ingestion-quality")
                                .low("outcome", "success")
                                .high("aggregateVersion", "7"),
                        parent)) {
            assertEquals(parent.traceId(), scope.context().traceId());
            assertNotEquals(parent.spanId(), scope.context().spanId());
        }

        assertNotNull(meters.find("scholarsense.operation.duration")
                .tags("service", "scholarsense", "module", "ingestion-quality",
                        "operation", "scholarsense.operation", "outcome", "success")
                .timer());
        assertEquals(Set.of("service", "module", "operation", "outcome", "error"),
                meters.find("scholarsense.operation.duration")
                .timer().getId().getTags().stream().map(tag -> tag.getKey())
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void productionAdapterBuildsAnExactRemoteParentAndDeclaredSpanKind() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Tracer tracer = mock(Tracer.class);
        TraceContext.Builder parentBuilder = mock(TraceContext.Builder.class);
        TraceContext micrometerParent = mock(TraceContext.class);
        Span.Builder spanBuilder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        TraceContext child = mock(TraceContext.class);
        Tracer.SpanInScope spanScope = mock(Tracer.SpanInScope.class);
        when(tracer.traceContextBuilder()).thenReturn(parentBuilder);
        when(parentBuilder.traceId(anyString())).thenReturn(parentBuilder);
        when(parentBuilder.spanId(anyString())).thenReturn(parentBuilder);
        when(parentBuilder.sampled(org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(parentBuilder);
        when(parentBuilder.build()).thenReturn(micrometerParent);
        when(tracer.spanBuilder()).thenReturn(spanBuilder);
        when(spanBuilder.setParent(micrometerParent)).thenReturn(spanBuilder);
        when(spanBuilder.name(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.kind(org.mockito.ArgumentMatchers.any())).thenReturn(spanBuilder);
        when(spanBuilder.tag(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(spanScope);
        when(span.context()).thenReturn(child);
        when(child.traceId()).thenReturn("11111111111111111111111111111111");
        when(child.spanId()).thenReturn("3333333333333333");
        when(child.sampled()).thenReturn(true);
        W3cTraceContext parent = new W3cTraceContext(
                "11111111111111111111111111111111", "2222222222222222", true);
        MicrometerObservationPort port = new MicrometerObservationPort(
                meters, tracer, java.util.Optional::empty, new W3cTraceContextCodec());

        IllegalStateException sensitive = new IllegalStateException(
                "jdbc:postgresql://secret-host/student?certificate=-----BEGIN CERTIFICATE-----");
        try (ObservationPort.ObservationScope scope = port.start(
                "event.consume", ObservationPort.ObservationKind.CONSUMER,
                SafeObservationAttributes.create()
                        .low("module", "ingestion-quality")
                        .low("operation", "event.consume")
                        .low("outcome", "success"),
                parent)) {
            assertEquals(parent.traceId(), scope.context().traceId());
            assertEquals("3333333333333333", scope.context().spanId());
            scope.outcome("duplicate");
            scope.error(sensitive);
        }

        verify(parentBuilder).traceId(parent.traceId());
        verify(parentBuilder).spanId(parent.spanId());
        verify(spanBuilder).kind(Span.Kind.CONSUMER);
        verify(spanBuilder).tag("service.name", "scholarsense");
        verify(spanBuilder).tag("scholarsense.module", "ingestion-quality");
        verify(spanBuilder).tag("scholarsense.operation", "event.consume");
        verify(spanBuilder).tag("scholarsense.outcome", "success");
        verify(span).tag("scholarsense.outcome", "duplicate");
        org.mockito.ArgumentCaptor<Throwable> exported =
                org.mockito.ArgumentCaptor.forClass(Throwable.class);
        verify(span).error(exported.capture());
        assertNotSame(sensitive, exported.getValue());
        assertEquals("OPERATION_FAILURE", exported.getValue().getMessage());
        verify(span).tag("error.type", "operation.failure");
        verify(spanScope).close();
        verify(span).end();
        assertNotNull(meters.find("scholarsense.operation.duration")
                .tags("service", "scholarsense", "module", "ingestion-quality",
                        "operation", "event.consume",
                        "outcome", "duplicate", "error", "error")
                .timer());
    }
}
