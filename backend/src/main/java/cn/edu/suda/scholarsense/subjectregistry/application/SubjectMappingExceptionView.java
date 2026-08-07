package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.subjectregistry.domain.MappingExceptionCode;
import cn.edu.suda.scholarsense.subjectregistry.domain.MappingExceptionStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record SubjectMappingExceptionView(
        UUID exceptionId,
        MappingExceptionStatus status,
        Optional<String> subjectOfficialRef,
        MappingExceptionCode exceptionCode,
        String sourceSystem,
        String sourceOwner,
        Instant detectedAt,
        long aggregateVersion) {
    public static final Set<String> FIELD_ALLOWLIST = Set.of(
            "exceptionId", "status", "subjectOfficialRef", "exceptionCode",
            "sourceSystem", "sourceOwner", "detectedAt");

    public SubjectMappingExceptionView {
        subjectOfficialRef = subjectOfficialRef == null ? Optional.empty() : subjectOfficialRef;
        if (aggregateVersion < 1 || aggregateVersion > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_VERSION_INVALID");
        }
    }
}
