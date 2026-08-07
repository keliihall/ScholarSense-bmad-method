package cn.edu.suda.scholarsense.subjectregistry.api;

/** Event-only callback implemented by an active downstream consumer. */
@FunctionalInterface
public interface SubjectMappingChangedConsumerPort {
    SubjectMappingConsumptionOutcome consume(SubjectMappingChangedEvent event);
}
