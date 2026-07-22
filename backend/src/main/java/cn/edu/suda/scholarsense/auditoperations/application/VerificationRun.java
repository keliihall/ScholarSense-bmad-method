package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHead;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record VerificationRun(
        UUID runId,
        String mode,
        long startSequence,
        long endSequence,
        LedgerHead verifiedHead,
        boolean healthy,
        Instant startedAt,
        Instant completedAt,
        String traceId) {
    public VerificationRun {
        Objects.requireNonNull(runId);
        if (runId.version() != 7 || runId.variant() != 2
                || !("incremental".equals(mode) || "full-chain".equals(mode))
                || startSequence < 0 || endSequence < startSequence) {
            throw new IllegalArgumentException("AUDIT_VERIFICATION_RUN_INVALID");
        }
        Objects.requireNonNull(verifiedHead);
        Objects.requireNonNull(startedAt);
        Objects.requireNonNull(completedAt);
        if (completedAt.isBefore(startedAt) || traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("AUDIT_VERIFICATION_RUN_INVALID");
        }
    }
}
