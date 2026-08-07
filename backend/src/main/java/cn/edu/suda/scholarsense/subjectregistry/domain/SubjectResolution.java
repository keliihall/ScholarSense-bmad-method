package cn.edu.suda.scholarsense.subjectregistry.domain;

import java.util.Objects;
import java.util.Optional;

public record SubjectResolution(ResolutionOutcome outcome, Optional<StudentRef> studentRef) {
    public SubjectResolution {
        Objects.requireNonNull(outcome);
        studentRef = Objects.requireNonNull(studentRef);
        if ((outcome == ResolutionOutcome.UNIQUE) != studentRef.isPresent()) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_RESOLUTION_SHAPE_INVALID");
        }
    }

    public static SubjectResolution noMatch() {
        return new SubjectResolution(ResolutionOutcome.NO_MATCH, Optional.empty());
    }

    public static SubjectResolution unique(StudentRef studentRef) {
        return new SubjectResolution(ResolutionOutcome.UNIQUE, Optional.of(studentRef));
    }

    public static SubjectResolution ambiguous() {
        return new SubjectResolution(ResolutionOutcome.AMBIGUOUS, Optional.empty());
    }
}
