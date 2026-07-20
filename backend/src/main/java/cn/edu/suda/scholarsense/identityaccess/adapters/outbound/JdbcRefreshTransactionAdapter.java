package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.RefreshTransactionPort;
import cn.edu.suda.scholarsense.identityaccess.domain.RefreshRotation;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcRefreshTransactionAdapter implements RefreshTransactionPort {
    private final TransactionTemplate transactions;

    public JdbcRefreshTransactionAdapter(TransactionTemplate transactions) {
        this.transactions = transactions;
    }

    @Override
    public RefreshRotation execute(Supplier<RefreshRotation> work) {
        return transactions.execute(status -> work.get());
    }
}
