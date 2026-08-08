package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.subjectregistry.domain.ResolutionOutcome;
import cn.edu.suda.scholarsense.subjectregistry.domain.StudentRef;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record IngestSubjectIdentifierResult(
        ResolutionOutcome outcome,
        Optional<StudentRef> studentRef,
        Optional<UUID> exceptionId) {
    public IngestSubjectIdentifierResult {
        Objects.requireNonNull(outcome);
        studentRef = Objects.requireNonNull(studentRef);
        exceptionId = Objects.requireNonNull(exceptionId);
    }
}
