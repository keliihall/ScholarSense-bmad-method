package cn.edu.suda.scholarsense.shared.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class FailSafeObservationPortTest {
    private static final W3cTraceContext PARENT = new W3cTraceContext(
            "11111111111111111111111111111111", "2222222222222222", true);

    @Test
    void startAndExportFailuresReturnAUsableSameTraceScope() {
        ObservationPort failingStart = (operation, kind, attributes, parent) -> {
            throw new IllegalStateException("collector unavailable");
        };
        FailSafeObservationPort safe = new FailSafeObservationPort(
                failingStart, new W3cTraceContextCodec());

        try (ObservationPort.ObservationScope scope = safe.start(
                "job.attempt", ObservationPort.ObservationKind.INTERNAL,
                SafeObservationAttributes.create()
                        .low("module", "ingestion-quality")
                        .low("operation", "job.attempt")
                        .low("outcome", "success"), PARENT)) {
            assertEquals(PARENT.traceId(), scope.context().traceId());
            assertDoesNotThrow(() -> scope.error(new IllegalStateException("business failure")));
        }
    }

    @Test
    void repeatedContextFailureReturnsOneStableFallbackChild() {
        ObservationPort failingContext = (operation, kind, attributes, parent) ->
                new ObservationPort.ObservationScope() {
                    @Override public W3cTraceContext context() {
                        throw new IllegalStateException("telemetry context unavailable");
                    }
                    @Override public void error(Throwable error) {}
                    @Override public void close() {}
                };
        FailSafeObservationPort safe = new FailSafeObservationPort(
                failingContext, new W3cTraceContextCodec());

        try (ObservationPort.ObservationScope scope = safe.start(
                "job.attempt", ObservationPort.ObservationKind.INTERNAL,
                SafeObservationAttributes.create()
                        .low("module", "ingestion-quality")
                        .low("operation", "job.attempt")
                        .low("outcome", "success"), PARENT)) {
            W3cTraceContext first = scope.context();
            assertSame(first, scope.context());
            assertEquals(PARENT.traceId(), first.traceId());
        }
    }
}
