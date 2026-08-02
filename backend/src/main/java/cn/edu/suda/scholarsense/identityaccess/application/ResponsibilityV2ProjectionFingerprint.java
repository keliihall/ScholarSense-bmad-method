package cn.edu.suda.scholarsense.identityaccess.application;

/** Repository-derived V2 shadow state used to reject caller-forged reconciliation evidence. */
public record ResponsibilityV2ProjectionFingerprint(
        long recordCount,
        String canonicalDigest,
        long lineageCount,
        String canonicalLineageDigest,
        long lineageConflictCount) {
    public ResponsibilityV2ProjectionFingerprint {
        if (recordCount < 0
                || canonicalDigest == null
                || !canonicalDigest.matches("[0-9a-f]{64}")
                || lineageCount < 0
                || canonicalLineageDigest == null
                || !canonicalLineageDigest.matches("[0-9a-f]{64}")
                || lineageConflictCount < 0) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_V2_PROJECTION_FINGERPRINT_INVALID");
        }
    }

    public ResponsibilityV2ProjectionFingerprint(
            long recordCount,
            String canonicalDigest,
            long lineageConflictCount) {
        this(
                recordCount,
                canonicalDigest,
                recordCount,
                canonicalDigest,
                lineageConflictCount);
    }
}
