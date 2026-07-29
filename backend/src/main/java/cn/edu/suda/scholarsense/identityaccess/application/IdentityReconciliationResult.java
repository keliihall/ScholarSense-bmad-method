package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.UUID;

public record IdentityReconciliationResult(
        UUID reconciliationId,
        String scope,
        long sourceVersion,
        long watermark,
        String expectedDigest,
        String actualDigest,
        long expectedCount,
        long actualCount,
        long matched,
        long missing,
        long unexpected,
        long versionDrift,
        Instant generatedAt,
        String traceId,
        boolean mutationApplied) {
    public IdentityReconciliationResult {
        if (reconciliationId == null
                || reconciliationId.version() != 7
                || reconciliationId.variant() != 2
                || mutationApplied
                || traceId == null
                || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("IDENTITY_RECONCILIATION_RESULT_INVALID");
        }
    }

    public boolean matchedCompletely() {
        return missing == 0 && unexpected == 0 && versionDrift == 0;
    }
}
