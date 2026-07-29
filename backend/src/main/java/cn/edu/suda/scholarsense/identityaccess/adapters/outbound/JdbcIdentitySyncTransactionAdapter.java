package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncTransactionPort;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcIdentitySyncTransactionAdapter implements IdentitySyncTransactionPort {
    private final TransactionTemplate transactions;

    public JdbcIdentitySyncTransactionAdapter(TransactionTemplate transactions) {
        this.transactions = transactions;
    }

    @Override
    public <T> T execute(Supplier<T> work) {
        try {
            return transactions.execute(status -> work.get());
        } catch (IdentitySyncException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IdentitySyncException("IDENTITY_SYNC_PERSISTENCE_UNAVAILABLE");
        }
    }
}
