package cn.edu.suda.scholarsense.subjectregistry.application;

import java.util.Objects;
import java.util.Optional;

public record SubjectResolutionLookupResult(String outcome, Optional<String> subjectRef) {
    public SubjectResolutionLookupResult {
        Objects.requireNonNull(outcome);
        subjectRef = Objects.requireNonNull(subjectRef);
    }
}
