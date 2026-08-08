package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.SubjectMappingChangedFact;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectMappingCorrectionCoordinator;
import cn.edu.suda.scholarsense.subjectregistry.api.SubjectMappingChangedConsumerPort;
import cn.edu.suda.scholarsense.subjectregistry.api.SubjectMappingConsumptionOutcome;
import cn.edu.suda.scholarsense.subjectregistry.api.SubjectMappingChangedEvent;
import java.time.Clock;
import java.util.Objects;
import org.springframework.transaction.support.TransactionOperations;

/** Applies the inbox cursor and request/job plan atomically in the IQ owner transaction. */
public final class TransactionalSubjectMappingChangedConsumer
        implements SubjectMappingChangedConsumerPort {
    private final SubjectMappingCorrectionCoordinator coordinator;
    private final TransactionOperations transactions;
    private final Clock clock;

    public TransactionalSubjectMappingChangedConsumer(
            SubjectMappingCorrectionCoordinator coordinator,
            TransactionOperations transactions,
            Clock clock) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public SubjectMappingConsumptionOutcome consume(SubjectMappingChangedEvent event) {
        return transactions.execute(status -> {
            var fact = new SubjectMappingChangedFact(
                    "urn:scholarsense:subject-registry", event.eventId(), event.aggregateId(),
                    event.aggregateVersion(), event.occurredAt(), event.correctionLineageId(),
                    event.sourceId(), event.affectedStudentRefs(), event.sourceWatermark(), true);
            var result = coordinator.consume(fact, false, clock.instant(), event.traceId());
            return SubjectMappingConsumptionOutcome.valueOf(result.outcome().name());
        });
    }
}
