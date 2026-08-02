package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Fail-closed validation for the isolated responsibility V2 cutover edge. */
final class ResponsibilityV2CutoverDatabaseTransactionBoundary {
    private ResponsibilityV2CutoverDatabaseTransactionBoundary() {}

    static void require(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            String errorCode) {
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(transactions, "transactions");
        var jdbcDataSource = Objects.requireNonNull(
                jdbc.getDataSource(), "jdbc.dataSource");
        int propagation = transactions.getPropagationBehavior();
        boolean startsTransaction =
                propagation == TransactionDefinition.PROPAGATION_REQUIRED
                        || propagation
                                == TransactionDefinition.PROPAGATION_REQUIRES_NEW;
        if (!(transactions.getTransactionManager()
                        instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbcDataSource
                || !startsTransaction
                || transactions.isReadOnly()) {
            throw new IllegalArgumentException(errorCode);
        }
    }
}
