package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandResult;
import cn.edu.suda.scholarsense.identityaccess.application.SessionTransactionPort;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcSessionTransactionAdapter implements SessionTransactionPort {
    private final TransactionTemplate transactions;

    public JdbcSessionTransactionAdapter(TransactionTemplate transactions) {
        this.transactions = transactions;
    }

    @Override
    public SessionCommandResult execute(Supplier<SessionCommandResult> work) {
        return transactions.execute(status -> work.get());
    }
}
