package cn.edu.suda.scholarsense.shared.observability;

import java.util.Optional;

/** Explicit, nest-safe context carrier for boundaries not instrumented by a framework. */
public final class ScopedCurrentTraceSource implements CurrentTraceSource {
    private final ThreadLocal<W3cTraceContext> local = new ThreadLocal<>();

    @Override
    public Optional<W3cTraceContext> current() {
        return Optional.ofNullable(local.get());
    }

    public Scope open(W3cTraceContext context) {
        W3cTraceContext previous = local.get();
        local.set(context);
        return new Scope() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                if (previous == null) {
                    local.remove();
                } else {
                    local.set(previous);
                }
            }
        };
    }
}
