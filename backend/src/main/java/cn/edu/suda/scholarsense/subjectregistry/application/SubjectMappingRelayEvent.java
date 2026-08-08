package cn.edu.suda.scholarsense.subjectregistry.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Module-internal verified outbox projection. */
public record SubjectMappingRelayEvent(
        UUID eventId,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correctionLineageId,
        String sourceId,
        Set<String> affectedStudentRefs,
        String sourceWatermark,
        String traceId) {
    public SubjectMappingRelayEvent {
        affectedStudentRefs = Set.copyOf(affectedStudentRefs);
    }
}
