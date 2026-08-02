package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Repository-derived evidence bound to one authenticated complete V2 snapshot. */
public record ResponsibilityV2ReconciliationEvidence(
        CheckpointKey key,
        UUID snapshotId,
        long snapshotSourceVersion,
        long throughWatermark,
        long expectedCount,
        long actualCount,
        String expectedDigest,
        String actualDigest,
        long expectedLineageCount,
        long actualLineageCount,
        String expectedLineageDigest,
        String actualLineageDigest,
        long lineageConflictCount,
        String envelopeDigest,
        String signatureDigest,
        Instant reconciledAt,
        String traceId) {
    public ResponsibilityV2ReconciliationEvidence {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(reconciledAt, "reconciledAt");
        if (!"responsibility".equals(key.consumerProjection())
                || snapshotId == null
                || snapshotId.version() != 7
                || snapshotId.variant() != 2
                || snapshotSourceVersion < 1
                || throughWatermark < 1
                || expectedCount < 0
                || actualCount < 0
                || expectedLineageCount < 0
                || actualLineageCount < 0
                || lineageConflictCount < 0
                || !digest(expectedDigest)
                || !digest(actualDigest)
                || !digest(expectedLineageDigest)
                || !digest(actualLineageDigest)
                || !digest(envelopeDigest)
                || !digest(signatureDigest)
                || traceId == null
                || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_V2_RECONCILIATION_EVIDENCE_INVALID");
        }
    }

    public boolean matched() {
        return expectedCount == actualCount
                && expectedDigest.equals(actualDigest)
                && expectedLineageCount == actualLineageCount
                && expectedLineageDigest.equals(actualLineageDigest)
                && lineageConflictCount == 0;
    }

    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
