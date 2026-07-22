package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.application.AuditVerificationTransactionPort;
import cn.edu.suda.scholarsense.auditoperations.application.LedgerVerificationResult;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Keeps ledger batches, head and evidence writes in one repeatable-read transaction. */
public final class SpringAuditVerificationTransactionAdapter
        implements AuditVerificationTransactionPort {
    private final TransactionTemplate transactions;

    public SpringAuditVerificationTransactionAdapter(PlatformTransactionManager manager) {
        transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public LedgerVerificationResult repeatableRead(Supplier<LedgerVerificationResult> work) {
        return transactions.execute(status -> work.get());
    }
}
