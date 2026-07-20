package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.SensitiveReadTransactionPort;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcSensitiveReadTransactionAdapter implements SensitiveReadTransactionPort {
    private final TransactionTemplate transactions;

    public JdbcSensitiveReadTransactionAdapter(TransactionTemplate transactions) {
        this.transactions = transactions;
    }

    @Override
    public <T> T execute(Supplier<T> work) {
        try {
            return transactions.execute(status -> work.get());
        } catch (IdentityAccessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IdentityAccessException(
                    "IDENTITY_DEPENDENCY_UNAVAILABLE", "identity persistence is unavailable");
        }
    }
}
