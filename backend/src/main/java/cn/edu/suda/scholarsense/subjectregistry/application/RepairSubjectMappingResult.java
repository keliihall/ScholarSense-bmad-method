package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.subjectregistry.domain.MappingExceptionStatus;
import java.util.Objects;
import java.util.UUID;

public record RepairSubjectMappingResult(
        UUID exceptionId,
        MappingExceptionStatus status,
        long aggregateVersion,
        UUID correctionEventId,
        UUID recomputeRequestId) {
    public RepairSubjectMappingResult {
        Objects.requireNonNull(exceptionId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(correctionEventId);
        Objects.requireNonNull(recomputeRequestId);
    }
}
