package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchTransactionPort;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchView;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/** Executes all owner writes for one batch command in one local PostgreSQL transaction. */
public final class JdbcDataBatchTransactionAdapter implements DataBatchTransactionPort {
    private final TransactionTemplate transactions;

    public JdbcDataBatchTransactionAdapter(TransactionTemplate transactions) {
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public DataBatchView execute(Supplier<DataBatchView> work) {
        Objects.requireNonNull(work);
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }
}
