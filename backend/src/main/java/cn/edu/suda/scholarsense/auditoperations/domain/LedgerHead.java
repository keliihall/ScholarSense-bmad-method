package cn.edu.suda.scholarsense.auditoperations.domain;

public record LedgerHead(long ledgerSequence, LedgerHash entryHash) {
    public LedgerHead {
        if (ledgerSequence < 0) {
            throw new IllegalArgumentException("AUDIT_LEDGER_HEAD_SEQUENCE_INVALID");
        }
        if (entryHash == null || ledgerSequence == 0 && !entryHash.constantTimeEquals(LedgerHash.genesis())) {
            throw new IllegalArgumentException("AUDIT_LEDGER_HEAD_HASH_INVALID");
        }
    }

    public static LedgerHead genesis() {
        return new LedgerHead(0, LedgerHash.genesis());
    }
}
