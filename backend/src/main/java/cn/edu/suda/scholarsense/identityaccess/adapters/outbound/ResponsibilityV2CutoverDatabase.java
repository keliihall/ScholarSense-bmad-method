package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Dedicated JDBC boundary for authenticated responsibility V2 cutover. */
public final class ResponsibilityV2CutoverDatabase {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ResponsibilityV2CutoverDatabase(
            JdbcTemplate jdbc, TransactionTemplate transactions) {
        ResponsibilityV2CutoverDatabaseTransactionBoundary.require(
                jdbc,
                transactions,
                "RESPONSIBILITY_V2_CUTOVER_TRANSACTION_BOUNDARY_INVALID");
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public static ResponsibilityV2CutoverDatabase connect(
            String jdbcUrl, String username, String password) {
        if (jdbcUrl == null
                || jdbcUrl.isBlank()
                || username == null
                || username.isBlank()
                || password == null
                || password.isBlank()) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_V2_CUTOVER_DATABASE_CONFIG_INVALID");
        }
        var dataSource = new DriverManagerDataSource(
                jdbcUrl, username, password);
        return new ResponsibilityV2CutoverDatabase(
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
