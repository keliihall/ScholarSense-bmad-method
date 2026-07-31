package cn.edu.suda.scholarsense.identityaccess.application;

public record ResponsibilitySnapshotEntry(
        String relationRefToken,
        String studentSourceRefDigest,
        long recordVersion,
        String payloadDigest,
        boolean active,
        boolean recipientMapped) {
    public ResponsibilitySnapshotEntry {
        if (relationRefToken == null
                || !relationRefToken.matches(
                        "rtok_[A-Za-z0-9_-]{32,128}")
                || studentSourceRefDigest == null
                || !studentSourceRefDigest.matches("[0-9a-f]{64}")
                || recordVersion < 1
                || payloadDigest == null
                || !payloadDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SNAPSHOT_ENTRY_INVALID");
        }
        if (!active && recipientMapped) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SNAPSHOT_MAPPING_INVALID");
        }
    }
}
