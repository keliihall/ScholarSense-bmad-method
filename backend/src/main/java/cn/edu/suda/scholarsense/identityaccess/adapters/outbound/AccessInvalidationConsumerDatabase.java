package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dedicated database boundary for consumer-owned invalidation evidence.
 *
 * <p>The producer datasource is deliberately not accepted here: deployments
 * must provide a distinct login that is a member only of
 * {@code scholarsense_identity_invalidation_consumer}.
 */
public final class AccessInvalidationConsumerDatabase {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public AccessInvalidationConsumerDatabase(
            JdbcTemplate jdbc, TransactionTemplate transactions) {
        AccessInvalidationDatabaseTransactionBoundary.require(
                jdbc,
                transactions,
                "ACCESS_INVALIDATION_CONSUMER_TRANSACTION_BOUNDARY_INVALID");
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public static AccessInvalidationConsumerDatabase connect(
            String jdbcUrl, String username, String password) {
        if (jdbcUrl == null
                || jdbcUrl.isBlank()
                || username == null
                || username.isBlank()
                || password == null
                || password.isBlank()) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_CONSUMER_DATABASE_CONFIG_INVALID");
        }
        var dataSource = new DriverManagerDataSource(
                jdbcUrl, username, password);
        return new AccessInvalidationConsumerDatabase(
                new JdbcTemplate(dataSource),
                new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)));
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public TransactionTemplate transactions() {
        return transactions;
    }
}
