package cn.edu.suda.scholarsense.subjectregistry.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Validated, transport-neutral projection of the approved subject-mapping CloudEvent. */
public record SubjectMappingChangedEvent(
        UUID eventId,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correctionLineageId,
        String sourceId,
        Set<String> affectedStudentRefs,
        String sourceWatermark,
        String traceId) {
    public SubjectMappingChangedEvent {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(aggregateId);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(correctionLineageId);
        affectedStudentRefs = Set.copyOf(Objects.requireNonNull(affectedStudentRefs));
        if (aggregateVersion < 1
                || sourceId == null
                || !sourceId.matches("^SRC-P[01]-[A-Z-]+-[0-9]{3}$")
                || affectedStudentRefs.isEmpty()
                || affectedStudentRefs.stream().anyMatch(value -> !uuid(value))
                || sourceWatermark == null || sourceWatermark.isBlank()
                || sourceWatermark.length() > 128
                || traceId == null || !traceId.matches("^[0-9a-f]{32}$")) {
            throw new IllegalArgumentException("SUBJECT_MAPPING_CHANGED_EVENT_INVALID");
        }
    }

    private static boolean uuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
