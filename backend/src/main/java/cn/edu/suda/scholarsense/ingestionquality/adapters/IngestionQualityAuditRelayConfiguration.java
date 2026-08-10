package cn.edu.suda.scholarsense.ingestionquality.adapters;

import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.CatalogAuditRelayScheduler;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogAuditBacklog;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogAuditRelayWork;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditRelayProcessor;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.shared.outbox.AuditLedgerIngressPort;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Producer-owned relay wiring in the non-HTTP audit-ledger worker runtime. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scholarsense.audit-ledger.enabled", havingValue = "true")
@EnableScheduling
public class IngestionQualityAuditRelayConfiguration {
    @Bean
    PostgreSqlConnectionProfile ingestionQualityRelayPostgreSqlConnectionProfile(
            DataSource dataSource,
            DataSourceProperties properties,
            RuntimeConfiguration runtime) {
        return PostgreSqlDataSourceStartupGate.verifyRelay(
                dataSource, runtime.environment().wireName(), properties.getUsername());
    }

    @Bean
    JdbcCatalogAuditRelayWork dataSourceCatalogAuditRelayWork(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper json,
            PostgreSqlConnectionProfile ingestionQualityRelayPostgreSqlConnectionProfile) {
        return new JdbcCatalogAuditRelayWork(jdbc, new TransactionTemplate(manager), json);
    }

    @Bean
    JdbcCatalogAuditBacklog dataSourceCatalogRelayBacklog(
            JdbcTemplate jdbc,
            TrustedTimeSource time,
            PostgreSqlConnectionProfile ingestionQualityRelayPostgreSqlConnectionProfile) {
        return new JdbcCatalogAuditBacklog(jdbc, new TrustedTimeClock(time));
    }

    @Bean
    CatalogAuditRelayProcessor dataSourceCatalogAuditRelayProcessor(
            JdbcCatalogAuditRelayWork work,
            AuditLedgerIngressPort center,
            TrustedTimeSource time) {
        return new CatalogAuditRelayProcessor(work, center, new TrustedTimeClock(time));
    }

    @Bean
    CatalogAuditRelayScheduler dataSourceCatalogAuditRelayScheduler(
            CatalogAuditRelayProcessor processor) {
        return new CatalogAuditRelayScheduler(processor);
    }
}
