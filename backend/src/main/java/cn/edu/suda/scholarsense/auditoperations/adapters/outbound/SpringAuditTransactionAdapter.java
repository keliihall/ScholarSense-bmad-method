package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.application.AuditTransactionPort;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

public final class SpringAuditTransactionAdapter implements AuditTransactionPort {
    private final TransactionTemplate transactions;

    public SpringAuditTransactionAdapter(TransactionTemplate transactions) {
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public <T> T required(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }
}
