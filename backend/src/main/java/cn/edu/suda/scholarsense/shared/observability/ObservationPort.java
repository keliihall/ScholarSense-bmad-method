package cn.edu.suda.scholarsense.shared.observability;

/** Business-facing port; exporter and tracing SDK types never cross this boundary. */
public interface ObservationPort {
    ObservationScope start(
            String operation,
            ObservationKind kind,
            SafeObservationAttributes attributes,
            W3cTraceContext parent);

    enum ObservationKind {
        SERVER,
        INTERNAL,
        PRODUCER,
        CONSUMER,
        CLIENT
    }

    interface ObservationScope extends AutoCloseable {
        W3cTraceContext context();

        /** Replaces the provisional outcome once the operation has a real result. */
        default void outcome(String outcome) {}

        void error(Throwable error);

        @Override
        void close();
    }
}
