package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.subjectregistry.domain.MappingCorrectionEvent;
import java.util.Objects;

public record RepairCommit(
        SubjectMappingExceptionRecord exceptionRecord,
        MappingCorrectionEvent correctionEvent,
        MappingRecomputeRequestIntent recomputeRequest,
        RepairIdempotencyScope idempotencyScope,
        String requestDigest,
        SubjectRegistryAuditEvent auditEvent) {
    public RepairCommit {
        Objects.requireNonNull(exceptionRecord);
        Objects.requireNonNull(correctionEvent);
        Objects.requireNonNull(recomputeRequest);
        Objects.requireNonNull(idempotencyScope);
        if (requestDigest == null || !requestDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_REQUEST_DIGEST_INVALID");
        }
        Objects.requireNonNull(auditEvent);
    }
}
