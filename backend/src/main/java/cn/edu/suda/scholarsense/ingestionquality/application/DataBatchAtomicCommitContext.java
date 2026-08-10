package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.util.Objects;
import java.util.UUID;

/** Idempotency, authorized trace and trusted-time envelope for a compound owner command. */
public record DataBatchAtomicCommitContext(
        UUID commandId,
        DataBatchIdempotencyScope idempotencyScope,
        String scopeDigest,
        String requestDigest,
        String traceId,
        TrustedTime occurredAt) {
    public DataBatchAtomicCommitContext {
        commandId = DataBatchCommandRules.uuidV7(commandId);
        Objects.requireNonNull(idempotencyScope);
        if (scopeDigest == null || !scopeDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
        requestDigest = DataBatchCommandRules.digest(requestDigest);
        traceId = DataBatchCommandRules.traceId(traceId);
        Objects.requireNonNull(occurredAt);
        if (occurredAt.instant().getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
    }
}
