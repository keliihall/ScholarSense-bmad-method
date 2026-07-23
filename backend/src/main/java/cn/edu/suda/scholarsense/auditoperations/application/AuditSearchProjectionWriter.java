package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.LedgerEntry;

/** Rebuildable read-model sink. The caller owns the ledger append transaction. */
@FunctionalInterface
public interface AuditSearchProjectionWriter {
    void project(LedgerEntry entry);
}
