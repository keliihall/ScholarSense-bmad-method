package cn.edu.suda.scholarsense.shared.observability;

import java.util.Objects;

/** Prevents collector/exporter degradation from participating in business correctness. */
public final class FailSafeObservationPort implements ObservationPort {
    private final ObservationPort delegate;
    private final W3cTraceContextCodec codec;

    public FailSafeObservationPort(
            ObservationPort delegate, W3cTraceContextCodec codec) {
        this.delegate = Objects.requireNonNull(delegate);
        this.codec = Objects.requireNonNull(codec);
    }

    @Override
    public ObservationScope start(
            String operation,
            ObservationKind kind,
            SafeObservationAttributes attributes,
            W3cTraceContext parent) {
        ObservationScope scope;
        try {
            scope = delegate.start(operation, kind, attributes, parent);
        } catch (RuntimeException unavailable) {
            return noop(codec.child(parent));
        }
        W3cTraceContext fallback = codec.child(parent);
        return new ObservationScope() {
            @Override
            public W3cTraceContext context() {
                try {
                    return scope.context();
                } catch (RuntimeException unavailable) {
                    return fallback;
                }
            }

            @Override
            public void outcome(String outcome) {
                try {
                    scope.outcome(outcome);
                } catch (RuntimeException ignored) {
                    // Telemetry degradation cannot replace the business result.
                }
            }

            @Override
            public void error(Throwable error) {
                try {
                    scope.error(error);
                } catch (RuntimeException ignored) {
                    // Telemetry degradation cannot replace the business failure.
                }
            }

            @Override
            public void close() {
                try {
                    scope.close();
                } catch (RuntimeException ignored) {
                    // Export completion is never part of the owner transaction.
                }
            }
        };
    }

    private static ObservationScope noop(W3cTraceContext context) {
        return new ObservationScope() {
            @Override public W3cTraceContext context() { return context; }
            @Override public void error(Throwable error) {}
            @Override public void close() {}
        };
    }
}
