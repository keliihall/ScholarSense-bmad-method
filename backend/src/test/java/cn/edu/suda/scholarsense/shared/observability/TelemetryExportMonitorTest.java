package cn.edu.suda.scholarsense.shared.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TelemetryExportMonitorTest {
    @Test
    void actualExporterFailureChangesHealthAndCounterWithoutThrowingIntoBusinessCode() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TelemetryExportMonitor monitor = new TelemetryExportMonitor(meters);
        AtomicBoolean fail = new AtomicBoolean(true);
        SpanExporter exporter = new MonitoringSpanExporter(
                new ControlledExporter(fail), monitor);

        CompletableResultCode failure = exporter.export(List.of());
        failure.join(1, java.util.concurrent.TimeUnit.SECONDS);

        assertFalse(failure.isSuccess());
        assertEquals("DEGRADED", monitor.health().getStatus().getCode());
        assertEquals(1L, monitor.failureCount());
        assertEquals(1.0, meters.get("scholarsense.telemetry.export.failures")
                .counter().count());

        exporter.flush().join(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("DEGRADED", monitor.health().getStatus().getCode());

        fail.set(false);
        exporter.export(List.of()).join(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("DEGRADED", monitor.health().getStatus().getCode());

        exporter.export(List.of(mock(SpanData.class)))
                .join(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("UP", monitor.health().getStatus().getCode());
        assertEquals(1L, monitor.failureCount());
    }

    @Test
    void olderConcurrentSuccessCannotClearANewerExportFailure() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TelemetryExportMonitor monitor = new TelemetryExportMonitor(meters);
        CompletableResultCode delayedSuccess = new CompletableResultCode();
        SpanExporter exporter = new MonitoringSpanExporter(
                new SequencedExporter(new AtomicInteger(), delayedSuccess), monitor);
        SpanData span = mock(SpanData.class);

        CompletableResultCode first = exporter.export(List.of(span));
        exporter.export(List.of(span)).join(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("DEGRADED", monitor.health().getStatus().getCode());

        delayedSuccess.succeed();
        first.join(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("DEGRADED", monitor.health().getStatus().getCode());

        exporter.export(List.of(span)).join(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("UP", monitor.health().getStatus().getCode());
    }

    @Test
    void olderConcurrentSuccessCannotClearANewerFlushFailure() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TelemetryExportMonitor monitor = new TelemetryExportMonitor(meters);
        CompletableResultCode delayedSuccess = new CompletableResultCode();
        SpanExporter exporter = new MonitoringSpanExporter(
                new FlushFailingExporter(delayedSuccess), monitor);
        SpanData span = mock(SpanData.class);

        CompletableResultCode export = exporter.export(List.of(span));
        exporter.flush().join(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("DEGRADED", monitor.health().getStatus().getCode());

        delayedSuccess.succeed();
        export.join(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("DEGRADED", monitor.health().getStatus().getCode());
    }

    private record ControlledExporter(AtomicBoolean fail) implements SpanExporter {
        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return fail.get()
                    ? CompletableResultCode.ofFailure()
                    : CompletableResultCode.ofSuccess();
        }

        @Override public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private record SequencedExporter(
            AtomicInteger calls,
            CompletableResultCode delayedSuccess) implements SpanExporter {
        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return switch (calls.incrementAndGet()) {
                case 1 -> delayedSuccess;
                case 2 -> CompletableResultCode.ofFailure();
                default -> CompletableResultCode.ofSuccess();
            };
        }

        @Override public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private record FlushFailingExporter(
            CompletableResultCode delayedSuccess) implements SpanExporter {
        @Override public CompletableResultCode export(Collection<SpanData> spans) {
            return delayedSuccess;
        }

        @Override public CompletableResultCode flush() {
            return CompletableResultCode.ofFailure();
        }

        @Override public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
