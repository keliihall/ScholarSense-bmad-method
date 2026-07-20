package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.IdentityEstablishmentTransactionPort;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcIdentityEstablishmentTransactionAdapter
        implements IdentityEstablishmentTransactionPort {
    private final TransactionTemplate transactions;

    public JdbcIdentityEstablishmentTransactionAdapter(TransactionTemplate transactions) {
        this.transactions = transactions;
    }

    @Override
    public void execute(Runnable work) {
        transactions.executeWithoutResult(status -> work.run());
    }
}
