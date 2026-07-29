package cn.edu.suda.scholarsense.identityaccess.application;

public record IdentityReconciliationEntry(
        String stableKeyDigest,
        IdentityRecordKind recordKind,
        long sourceVersion,
        String structureDigest) {
    public IdentityReconciliationEntry {
        requireDigest(stableKeyDigest);
        java.util.Objects.requireNonNull(recordKind, "recordKind");
        if (sourceVersion < 1) {
            throw new IllegalArgumentException("IDENTITY_RECONCILIATION_VERSION_INVALID");
        }
        requireDigest(structureDigest);
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("IDENTITY_RECONCILIATION_DIGEST_INVALID");
        }
    }
}
