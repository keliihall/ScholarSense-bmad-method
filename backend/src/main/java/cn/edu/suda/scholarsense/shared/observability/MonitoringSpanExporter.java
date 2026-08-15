package cn.edu.suda.scholarsense.shared.observability;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Observes the actual asynchronous exporter result without changing it. */
final class MonitoringSpanExporter implements SpanExporter {
    private final SpanExporter delegate;
    private final TelemetryExportMonitor monitor;
    private final AtomicLong exportSequence = new AtomicLong();
    private final Object completionLock = new Object();
    private long latestFailedExport;

    MonitoringSpanExporter(SpanExporter delegate, TelemetryExportMonitor monitor) {
        this.delegate = Objects.requireNonNull(delegate);
        this.monitor = Objects.requireNonNull(monitor);
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        Objects.requireNonNull(spans);
        long sequence = exportSequence.incrementAndGet();
        return observe(delegate.export(spans), !spans.isEmpty(), sequence);
    }

    @Override
    public CompletableResultCode flush() {
        return observe(delegate.flush(), false, exportSequence.incrementAndGet());
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    private CompletableResultCode observe(
            CompletableResultCode result,
            boolean recoveryEvidence,
            long exportAttempt) {
        result.whenComplete(() -> {
            synchronized (completionLock) {
                if (result.isSuccess()) {
                    if (recoveryEvidence && exportAttempt > latestFailedExport) {
                        monitor.succeeded();
                    }
                } else {
                    latestFailedExport = Math.max(latestFailedExport, exportAttempt);
                    monitor.failed();
                }
            }
        });
        return result;
    }
}
