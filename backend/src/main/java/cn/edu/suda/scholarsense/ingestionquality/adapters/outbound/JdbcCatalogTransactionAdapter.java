package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.CatalogTransactionPort;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcCatalogTransactionAdapter implements CatalogTransactionPort {
    private final TransactionTemplate transactions;

    public JdbcCatalogTransactionAdapter(TransactionTemplate transactions) {
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public Object execute(Supplier<?> work) {
        return transactions.execute(status -> work.get());
    }
}
