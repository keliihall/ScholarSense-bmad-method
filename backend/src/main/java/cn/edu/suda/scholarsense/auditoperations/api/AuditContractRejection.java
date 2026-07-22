package cn.edu.suda.scholarsense.auditoperations.api;

import java.time.Instant;
import java.util.Objects;

/** Privacy-safe report for an outbox envelope that cannot be decoded as a local audit fact. */
public record AuditContractRejection(
        String producerModule,
        String safeDigest,
        String traceId,
        Instant occurredAt) {
    public AuditContractRejection {
        if (producerModule == null || !producerModule.matches("[a-z][a-z0-9-]{2,63}")
                || safeDigest == null || !safeDigest.matches("[0-9a-f]{64}")
                || traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("AUDIT_CONTRACT_REJECTION_INVALID");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
