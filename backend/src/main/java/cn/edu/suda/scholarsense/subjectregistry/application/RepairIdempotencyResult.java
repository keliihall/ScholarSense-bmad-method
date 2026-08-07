package cn.edu.suda.scholarsense.subjectregistry.application;

import java.time.Instant;
import java.util.Objects;

public record RepairIdempotencyResult(
        RepairIdempotencyScope scope,
        String requestDigest,
        RepairSubjectMappingResult response,
        Instant completedAt,
        Instant expiresAt) {
    public RepairIdempotencyResult {
        Objects.requireNonNull(scope);
        if (requestDigest == null || !requestDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_REQUEST_DIGEST_INVALID");
        }
        Objects.requireNonNull(response);
        Objects.requireNonNull(completedAt);
        Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(completedAt)) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_IDEMPOTENCY_RETENTION_INVALID");
        }
    }
}
