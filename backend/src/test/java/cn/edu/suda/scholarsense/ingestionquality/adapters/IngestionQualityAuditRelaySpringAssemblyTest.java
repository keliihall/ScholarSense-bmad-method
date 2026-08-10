package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.CatalogAuditRelayScheduler;
import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.CatalogRetentionScheduler;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.runtime.RuntimeConfigurationTest;
import cn.edu.suda.scholarsense.shared.outbox.AuditLedgerIngressPort;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class IngestionQualityAuditRelaySpringAssemblyTest {
    @Test
    void auditWorkerAssemblesTheScheduledRelayBehindTheVerifiedDataSourceGate()
            throws Exception {
        Map<String, String> values = new HashMap<>(
                RuntimeConfigurationTest.validEnvironment("worker"));
        values.put("SCHOLARSENSE_AUDIT_LEDGER_ENABLED", "true");
        RuntimeConfigurationTest.addAuditRuntimeReferences(values, "test");
        RuntimeConfiguration runtime = RuntimeConfiguration.from(values);
        DataSource dataSource = dataSource();
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUsername("scholarsense_worker_test");

        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "ingestion-quality-relay-test",
                    Map.of(
                            "scholarsense.audit-ledger.enabled", "true",
                            "scholarsense.audit.collector.initial-delay", "86400000",
                            "scholarsense.audit.collector.interval", "86400000")));
            context.registerBean(RuntimeConfiguration.class, () -> runtime);
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(DataSourceProperties.class, () -> properties);
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(
                    PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(AuditLedgerIngressPort.class, () -> mock(
                    AuditLedgerIngressPort.class));
            context.registerBean(TrustedTimeSource.class, () -> () -> new TrustedTime(
                    Instant.parse("2026-08-05T00:00:00Z"),
                    new TimeSourceProfile(
                            "campus-ntp-a",
                            "AUDIT-CLOCK-BINDING-1.0.0",
                            5,
                            Instant.parse("2026-08-04T23:59:50Z"),
                            Instant.parse("2026-08-05T00:00:10Z"),
                            "evidence://signed/clock/campus-ntp-a.json")));
            context.register(IngestionQualityAuditRelayConfiguration.class);

            context.refresh();

            assertNotNull(context.getBean(CatalogAuditRelayScheduler.class));
            assertThrows(
                    NoSuchBeanDefinitionException.class,
                    () -> context.getBean(CatalogRetentionScheduler.class));
            assertNotNull(context.getBean(PostgreSqlConnectionProfile.class));
        }
    }

    private static DataSource dataSource() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        ResultSet principal = mock(ResultSet.class);
        ResultSet matrix = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn("jdbc:postgresql://localhost/scholarsense");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0).contains("expected_table")
                        ? matrix
                        : principal);
        when(principal.next()).thenReturn(true, false);
        when(principal.getString(1)).thenReturn("scholarsense_worker_test");
        when(principal.getString(2)).thenReturn("scholarsense_worker_test");
        when(principal.getString(3)).thenReturn("180004");
        when(principal.getBoolean(4)).thenReturn(true);
        when(principal.getBoolean(5)).thenReturn(false);
        when(principal.getBoolean(6)).thenReturn(false);
        when(principal.getBoolean(7)).thenReturn(false);
        when(principal.getBoolean(8)).thenReturn(false);
        when(principal.getBoolean(9)).thenReturn(false);
        when(principal.getBoolean(10)).thenReturn(false);
        when(principal.getBoolean(11)).thenReturn(true);
        when(principal.getBoolean(12)).thenReturn(true);
        when(principal.getBoolean(13)).thenReturn(false);
        when(principal.getBoolean(14)).thenReturn(false);
        when(principal.getBoolean(15)).thenReturn(false);
        when(principal.getBoolean(16)).thenReturn(false);
        when(principal.getBoolean(17)).thenReturn(false);
        when(principal.getBoolean(18)).thenReturn(false);
        when(principal.getBoolean(19)).thenReturn(false);
        when(principal.getBoolean(20)).thenReturn(false);
        when(principal.getBoolean(21)).thenReturn(false);
        when(principal.getBoolean(22)).thenReturn(false);
        when(principal.getBoolean(23)).thenReturn(true);
        when(principal.getBoolean(24)).thenReturn(true);
        when(principal.getBoolean(25)).thenReturn(true);
        when(matrix.next()).thenReturn(true, false);
        when(matrix.getBoolean(1)).thenReturn(true);
        return dataSource;
    }
}
