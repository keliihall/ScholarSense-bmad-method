package cn.edu.suda.scholarsense.shared.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.Objects;
import java.util.Optional;

/** Reads the framework-instrumented current span without exposing Micrometer to business modules. */
public final class MicrometerCurrentTraceSource implements CurrentTraceSource {
    private final Tracer tracer;

    public MicrometerCurrentTraceSource(Tracer tracer) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    @Override
    public Optional<W3cTraceContext> current() {
        Span span = tracer.currentSpan();
        if (span == null || span.isNoop()) {
            return Optional.empty();
        }
        TraceContext context = span.context();
        return Optional.of(new W3cTraceContext(
                context.traceId(), context.spanId(), Boolean.TRUE.equals(context.sampled())));
    }
}
