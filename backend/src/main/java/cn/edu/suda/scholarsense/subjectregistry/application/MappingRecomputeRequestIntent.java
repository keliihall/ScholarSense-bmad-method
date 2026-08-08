package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.subjectregistry.domain.StudentRef;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record MappingRecomputeRequestIntent(
        UUID requestId,
        UUID correctionLineageId,
        List<StudentRef> affectedStudentRefs,
        String sourceWatermark,
        Instant createdAt,
        String traceId) {
    public MappingRecomputeRequestIntent {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(correctionLineageId);
        affectedStudentRefs = List.copyOf(Objects.requireNonNull(affectedStudentRefs));
        if (affectedStudentRefs.isEmpty() || sourceWatermark == null || sourceWatermark.isBlank()
                || sourceWatermark.length() > 128 || createdAt == null
                || traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_RECOMPUTE_INTENT_INVALID");
        }
    }
}
