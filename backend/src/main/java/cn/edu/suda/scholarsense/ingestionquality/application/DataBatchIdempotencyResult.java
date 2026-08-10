package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.Objects;

public record DataBatchIdempotencyResult(
        DataBatchIdempotencyScope scope,
        String requestDigest,
        DataBatchView response,
        Instant completedAt,
        Instant expiresAt) {
    public DataBatchIdempotencyResult {
        Objects.requireNonNull(scope);
        requestDigest = DataBatchCommandRules.digest(requestDigest);
        Objects.requireNonNull(response);
        Objects.requireNonNull(completedAt);
        Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(completedAt)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_IDEMPOTENCY_RETENTION_INVALID");
        }
    }
}
