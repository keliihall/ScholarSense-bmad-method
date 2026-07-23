package cn.edu.suda.scholarsense.auditoperations.application;

public record AuditArchiveRecord(long ledgerSequence, String previousHash, String entryHash, byte[] canonicalBytes) {
    public AuditArchiveRecord {
        if (ledgerSequence < 1 || !hash(previousHash) || !hash(entryHash) || canonicalBytes == null) {
            throw new IllegalArgumentException("AUDIT_ARCHIVE_RECORD_INVALID");
        }
        canonicalBytes = canonicalBytes.clone();
    }

    @Override
    public byte[] canonicalBytes() {
        return canonicalBytes.clone();
    }

    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
