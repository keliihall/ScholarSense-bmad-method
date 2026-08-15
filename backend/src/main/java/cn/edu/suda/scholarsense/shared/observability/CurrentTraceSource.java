package cn.edu.suda.scholarsense.shared.observability;

import java.util.Optional;

/** Business-facing port for reading the current technical trace context. */
public interface CurrentTraceSource {
    Optional<W3cTraceContext> current();

    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
