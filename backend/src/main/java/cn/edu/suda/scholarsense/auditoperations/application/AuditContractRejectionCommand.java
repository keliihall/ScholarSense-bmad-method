package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;
import java.util.Objects;

public record AuditContractRejectionCommand(
        String producerModule,
        String safeDigest,
        String traceId,
        Instant occurredAt) {
    public AuditContractRejectionCommand {
        if (producerModule == null || !producerModule.matches("[a-z][a-z0-9-]{2,63}")
                || safeDigest == null || !safeDigest.matches("[0-9a-f]{64}")
                || traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("AUDIT_CONTRACT_REJECTION_INVALID");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
