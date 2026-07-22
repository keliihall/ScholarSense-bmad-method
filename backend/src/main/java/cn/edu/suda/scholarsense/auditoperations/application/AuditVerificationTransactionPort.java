package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.function.Supplier;

/** Runs one ledger verification against a stable database snapshot. */
@FunctionalInterface
public interface AuditVerificationTransactionPort {
    LedgerVerificationResult repeatableRead(Supplier<LedgerVerificationResult> work);
}
