package cn.edu.suda.scholarsense.shared.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Micrometer adapter that keeps metric tags low-cardinality and trace attributes allowlisted. */
public final class MicrometerObservationPort implements ObservationPort {
    private static final String DURATION_METRIC = "scholarsense.operation.duration";
    private static final Throwable SAFE_OPERATION_FAILURE =
            new TelemetryOperationFailure();
    private final ObservationRegistry registry;
    private final MeterRegistry meters;
    private final Tracer tracer;
    private final CurrentTraceSource currentTrace;
    private final W3cTraceContextCodec codec;

    public MicrometerObservationPort(
            ObservationRegistry registry,
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec codec) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.meters = null;
        this.tracer = null;
        this.currentTrace = Objects.requireNonNull(currentTrace, "currentTrace");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public MicrometerObservationPort(
            MeterRegistry meters,
            Tracer tracer,
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec codec) {
        this.registry = null;
        this.meters = Objects.requireNonNull(meters, "meters");
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.currentTrace = Objects.requireNonNull(currentTrace, "currentTrace");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public ObservationScope start(
            String operation,
            ObservationKind kind,
            SafeObservationAttributes attributes,
            W3cTraceContext parent) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(parent, "parent");
        if (!operation.matches("[a-z][a-z0-9.-]{1,63}")) {
            throw new IllegalArgumentException("operation must be a stable low-cardinality name");
        }
        if (tracer != null) {
            return startTraced(operation, kind, attributes, parent);
        }
        return startObservation(operation, attributes, parent);
    }

    private ObservationScope startObservation(
            String operation,
            SafeObservationAttributes attributes,
            W3cTraceContext parent) {
        Observation observation = Observation.createNotStarted(DURATION_METRIC, registry)
                .contextualName(operation);
        for (Map.Entry<String, String> field : metricLabels(operation, attributes).entrySet()) {
            observation.lowCardinalityKeyValue(field.getKey(), field.getValue());
        }
        for (Map.Entry<String, String> field : attributes.spanAttributes().entrySet()) {
            if (!attributes.metricLabels().containsKey(field.getKey())) {
                observation.highCardinalityKeyValue(field.getKey(), field.getValue());
            }
        }
        observation.start();
        Observation.Scope scope = observation.openScope();
        W3cTraceContext operationContext = currentTrace.current()
                .filter(value -> value.traceId().equals(parent.traceId())
                        && !value.spanId().equals(parent.spanId()))
                .orElseGet(() -> codec.child(parent));
        return new ObservationScope() {
            private boolean closed;

            @Override
            public W3cTraceContext context() {
                return operationContext;
            }

            @Override
            public void outcome(String outcome) {
                attributes.low("outcome", outcome);
                observation.lowCardinalityKeyValue("outcome", outcome);
            }

            @Override
            public void error(Throwable error) {
                Objects.requireNonNull(error, "error");
                observation.error(SAFE_OPERATION_FAILURE);
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                scope.close();
                observation.stop();
            }
        };
    }

    private ObservationScope startTraced(
            String operation,
            ObservationKind kind,
            SafeObservationAttributes attributes,
            W3cTraceContext parent) {
        TraceContext parentContext = tracer.traceContextBuilder()
                .traceId(parent.traceId())
                .spanId(parent.spanId())
                .sampled(parent.sampled())
                .build();
        Span.Builder builder = tracer.spanBuilder()
                .setParent(parentContext)
                .name(operation);
        Span.Kind spanKind = spanKind(kind);
        if (spanKind != null) builder.kind(spanKind);
        Map<String, String> spanAttributes = new TreeMap<>(attributes.spanAttributes());
        spanAttributes.putIfAbsent("service.name", "scholarsense");
        spanAttributes.put("scholarsense.operation", operation);
        for (Map.Entry<String, String> field : spanAttributes.entrySet()) {
            builder.tag(field.getKey(), field.getValue());
        }
        Span span = builder.start();
        Tracer.SpanInScope spanScope = tracer.withSpan(span);
        Timer.Sample sample = Timer.start(meters);
        W3cTraceContext operationContext = context(span, parent);
        return new ObservationScope() {
            private boolean closed;
            private boolean failed;

            @Override
            public W3cTraceContext context() {
                return operationContext;
            }

            @Override
            public void outcome(String outcome) {
                attributes.low("outcome", outcome);
                span.tag("scholarsense.outcome", outcome);
            }

            @Override
            public void error(Throwable error) {
                Objects.requireNonNull(error, "error");
                failed = true;
                span.tag("error.type", "operation.failure");
                span.error(SAFE_OPERATION_FAILURE);
            }

            @Override
            public void close() {
                if (closed) return;
                closed = true;
                spanScope.close();
                span.end();
                Timer.Builder timer = Timer.builder(DURATION_METRIC);
                for (Map.Entry<String, String> field : metricLabels(operation, attributes).entrySet()) {
                    timer.tag(field.getKey(), field.getValue());
                }
                timer.tag("error", failed ? "error" : "none");
                sample.stop(timer.register(meters));
            }
        };
    }

    private static Map<String, String> metricLabels(
            String operation, SafeObservationAttributes attributes) {
        Map<String, String> labels = new TreeMap<>(attributes.metricLabels());
        labels.put("service", "scholarsense");
        labels.put("operation", operation);
        return labels;
    }

    private W3cTraceContext context(Span span, W3cTraceContext parent) {
        TraceContext context = span.context();
        if (context != null
                && context.traceId() != null && context.spanId() != null) {
            try {
                return new W3cTraceContext(
                        context.traceId(), context.spanId(), Boolean.TRUE.equals(context.sampled()));
            } catch (IllegalArgumentException ignored) {
                // A no-op tracer has no exportable context; retain causal semantics locally.
            }
        }
        return codec.child(parent);
    }

    private static Span.Kind spanKind(ObservationKind kind) {
        return switch (kind) {
            case SERVER -> Span.Kind.SERVER;
            case PRODUCER -> Span.Kind.PRODUCER;
            case CONSUMER -> Span.Kind.CONSUMER;
            case CLIENT -> Span.Kind.CLIENT;
            case INTERNAL -> null;
        };
    }

    private static final class TelemetryOperationFailure extends RuntimeException {
        private TelemetryOperationFailure() {
            super("OPERATION_FAILURE", null, false, false);
        }
    }
}
