package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record SubjectMappingChangedFact(
        String source,
        UUID eventId,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correctionLineageId,
        String sourceId,
        Set<String> affectedStudentRefs,
        String inputWatermark,
        boolean schemaValid) {

    public SubjectMappingChangedFact {
        affectedStudentRefs = affectedStudentRefs == null ? Set.of() : Set.copyOf(affectedStudentRefs);
    }
}
