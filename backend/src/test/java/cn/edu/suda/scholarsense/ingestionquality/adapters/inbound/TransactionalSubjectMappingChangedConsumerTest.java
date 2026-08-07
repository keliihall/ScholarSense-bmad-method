package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.ingestionquality.application.MappingRecomputePlan;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectMappingCorrectionCoordinator;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectMappingCorrectionResult;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectMappingEventOutcome;
import cn.edu.suda.scholarsense.subjectregistry.api.SubjectMappingConsumptionOutcome;
import cn.edu.suda.scholarsense.subjectregistry.api.SubjectMappingChangedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionCallback;

class TransactionalSubjectMappingChangedConsumerTest {
    private static final Instant NOW = Instant.parse("2026-08-06T08:00:00Z");

    @Test
    void eventAcceptanceAndPlanRunInsideOneIqTransaction() {
        SubjectMappingCorrectionCoordinator coordinator = mock(SubjectMappingCorrectionCoordinator.class);
        SubjectMappingChangedEvent event = event();
        when(coordinator.consume(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.eq(event.traceId())))
                .thenReturn(new SubjectMappingCorrectionResult(
                        SubjectMappingEventOutcome.APPLIED,
                        new MappingRecomputePlan(List.of(), 0)));
        AtomicBoolean transactionUsed = new AtomicBoolean();
        TransactionOperations transactions = new TransactionOperations() {
            @Override public <T> T execute(TransactionCallback<T> action) {
                transactionUsed.set(true);
                return action.doInTransaction(null);
            }
        };
        var consumer = new TransactionalSubjectMappingChangedConsumer(
                coordinator, transactions, Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(SubjectMappingConsumptionOutcome.APPLIED, consumer.consume(event));
        assertTrue(transactionUsed.get());
        verify(coordinator).consume(
                org.mockito.ArgumentMatchers.argThat(fact ->
                        fact.eventId().equals(event.eventId())
                                && fact.inputWatermark().equals("wm-42")
                                && fact.schemaValid()),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.eq(event.traceId()));
    }

    private static SubjectMappingChangedEvent event() {
        UUID lineage = UUID.fromString("019fcfea-6800-7000-8000-000000000002");
        return new SubjectMappingChangedEvent(
                UUID.fromString("019fcfea-6800-7000-8000-000000000001"),
                lineage, 1, NOW, lineage, "SRC-P0-CARD-001",
                Set.of("019fcfea-6800-7000-8000-000000000003"),
                "wm-42", "00112233445566778899aabbccddeeff");
    }
}
