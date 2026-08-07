package cn.edu.suda.scholarsense.subjectregistry.adapters.outbound;

import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryTransactionPort;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcSubjectRegistryTransactionAdapter implements SubjectRegistryTransactionPort {
    private final TransactionTemplate transactions;

    public JdbcSubjectRegistryTransactionAdapter(TransactionTemplate transactions) {
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public Object execute(Supplier<?> work) {
        return transactions.execute(status -> work.get());
    }
}
