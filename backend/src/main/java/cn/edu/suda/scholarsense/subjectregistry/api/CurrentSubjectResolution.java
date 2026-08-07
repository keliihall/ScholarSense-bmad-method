package cn.edu.suda.scholarsense.subjectregistry.api;

import java.util.Objects;
import java.util.Optional;

public record CurrentSubjectResolution(String outcome, Optional<String> subjectRef) {
    public CurrentSubjectResolution {
        if (!java.util.Set.of("no-match", "unique", "ambiguous").contains(outcome)) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_RESOLUTION_OUTCOME_INVALID");
        }
        subjectRef = Objects.requireNonNull(subjectRef);
        if ("unique".equals(outcome) != subjectRef.isPresent()) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_RESOLUTION_SHAPE_INVALID");
        }
    }
}
