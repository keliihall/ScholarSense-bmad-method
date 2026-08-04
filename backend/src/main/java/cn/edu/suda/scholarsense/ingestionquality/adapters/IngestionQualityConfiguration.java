package cn.edu.suda.scholarsense.ingestionquality.adapters;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapabilityProvider;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapabilityState;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.CompositeCatalogAuthorizationAdapter;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogAuditBacklog;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogAuditRelayWork;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogTransactionAdapter;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.SharedAuditPublicationGuard;
import cn.edu.suda.scholarsense.ingestionquality.application.DataSourceCatalogService;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditRelayProcessor;
import cn.edu.suda.scholarsense.shared.outbox.AuditLedgerIngressPort;
import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogPolicy;
import cn.edu.suda.scholarsense.shared.time.AuditAvailabilityPort;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
public class IngestionQualityConfiguration {
    @Bean
    JdbcCatalogStore jdbcDataSourceCatalogStore(JdbcTemplate jdbc, ObjectMapper json) {
        return new JdbcCatalogStore(jdbc, json);
    }

    @Bean
    DataSourceCatalogService dataSourceCatalogService(
            JdbcCatalogStore store, CompositeAuthorizationPort authorization,
            AuditAvailabilityPort availability, JdbcCatalogAuditBacklog backlog,
            PlatformTransactionManager manager) {
        TransactionTemplate transactions = new TransactionTemplate(manager);
        return new DataSourceCatalogService(
                store, store, new FrozenDataCatalogPolicy(),
                new CompositeCatalogAuthorizationAdapter(authorization),
                new JdbcCatalogTransactionAdapter(transactions), store,
                new SharedAuditPublicationGuard(availability, backlog, Clock.systemUTC()));
    }

    @Bean
    JdbcCatalogAuditBacklog dataSourceCatalogAuditBacklog(JdbcTemplate jdbc) {
        return new JdbcCatalogAuditBacklog(jdbc, Clock.systemUTC());
    }

    @Bean
    JdbcCatalogAuditRelayWork dataSourceCatalogAuditRelayWork(
            JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json) {
        return new JdbcCatalogAuditRelayWork(jdbc, new TransactionTemplate(manager), json);
    }

    @Bean
    @ConditionalOnBean(AuditLedgerIngressPort.class)
    CatalogAuditRelayProcessor dataSourceCatalogAuditRelayProcessor(
            JdbcCatalogAuditRelayWork work, AuditLedgerIngressPort center) {
        return new CatalogAuditRelayProcessor(work, center, Clock.systemUTC());
    }

    @Bean
    AuthorizedShellCapabilityProvider dataSourceCatalogShellCapability() {
        return () -> List.of(new AuthorizedShellCapability(
                "data-source-catalogs", "数据源目录", "data-quality.catalogs",
                AuthorizedShellCapabilityState.AVAILABLE, Set.of("R6-DATA-OWNER")));
    }

}
