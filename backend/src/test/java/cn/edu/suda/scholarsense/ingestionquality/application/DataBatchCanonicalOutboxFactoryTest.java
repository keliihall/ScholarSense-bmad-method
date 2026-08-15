package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.shared.observability.ObservationPort;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContext;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContextCodec;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DataBatchCanonicalOutboxFactoryTest {
    @Test
    void traceparentNeverDerivesAnAllZeroSpanIdFromAValidTraceId() {
        String traceId = "00000000000000001122334455667788";

        assertEquals("00-" + traceId + "-1122334455667788-01",
                DataBatchCanonicalOutboxFactory.traceparent(traceId));
        assertEquals(
                "00-00112233445566778899aabbccddeeff-0011223344556677-01",
                DataBatchCanonicalOutboxFactory.traceparent(
                        "00112233445566778899aabbccddeeff"));
    }

    @Test
    void successorProducerUsesARealChildSpanInsteadOfTraceIdDerivedPseudoSpan() {
        String traceId = "00112233445566778899aabbccddeeff";
        W3cTraceContext parent = new W3cTraceContext(
                traceId, "2222222222222222", true);
        W3cTraceContext producer = new W3cTraceContext(
                traceId, "3333333333333333", true);
        AtomicReference<ObservationPort.ObservationKind> kind = new AtomicReference<>();
        AtomicReference<W3cTraceContext> observedParent = new AtomicReference<>();
        AtomicReference<String> outcome = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        ObservationPort observations = (operation, observationKind, attributes, candidateParent) -> {
            kind.set(observationKind);
            observedParent.set(candidateParent);
            return new ObservationPort.ObservationScope() {
                @Override public W3cTraceContext context() { return producer; }
                @Override public void outcome(String value) { outcome.set(value); }
                @Override public void error(Throwable error) {}
                @Override public void close() { closed.set(true); }
            };
        };
        DataBatchCanonicalOutboxFactory factory = new DataBatchCanonicalOutboxFactory(
                "scholarsense_iq_test_worker",
                () -> Optional.of(parent),
                new W3cTraceContextCodec(), observations);

        String traceparent;
        try (DataBatchCanonicalOutboxFactory.PublicationScope publication =
                     factory.startPublication(traceId)) {
            traceparent = publication.traceparent();
            assertFalse(closed.get(), "producer span must remain open through owner commit");
            publication.complete(false);
        }

        org.junit.jupiter.api.Assertions.assertTrue(traceparent.matches(
                "00-" + traceId + "-[0-9a-f]{16}-01"));
        assertEquals("00-" + traceId + "-" + producer.spanId() + "-01", traceparent);
        assertEquals(ObservationPort.ObservationKind.PRODUCER, kind.get());
        assertEquals(parent, observedParent.get());
        assertEquals("success", outcome.get());
        assertTrue(closed.get());
        assertNotEquals(DataBatchCanonicalOutboxFactory.traceparent(traceId), traceparent);
        assertEquals("scholarsense.ingestion-quality.data-batch.quality-assessed.v2",
                factory.eventTypeFor(DataBatchCommandType.EVALUATE));
        assertEquals("DATA-BATCH-QUALITY-ASSESSED-2.0.0",
                factory.schemaFor(DataBatchCommandType.EVALUATE));
        assertEquals("scholarsense.ingestion-quality.data-batch.published.v2",
                factory.eventTypeFor(DataBatchCommandType.PUBLISH));
        assertEquals("DATA-BATCH-PUBLISHED-2.0.0",
                factory.schemaFor(DataBatchCommandType.PUBLISH));

        DataBatchCanonicalOutboxFactory predecessor =
                new DataBatchCanonicalOutboxFactory("scholarsense_iq_test_worker");
        assertEquals("scholarsense.ingestion-quality.data-batch.quality-assessed.v1",
                predecessor.eventTypeFor(DataBatchCommandType.EVALUATE));
    }
}
