package cn.edu.suda.scholarsense.ingestionquality.adapters;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapabilityProvider;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapabilityState;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceProvider;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationPort;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.CompositeCatalogAuthorizationAdapter;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.CatalogOwnerEvidenceProvider;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenDataCatalogLoader;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenDataCatalogBuildSubject;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenDataCatalogSubject;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogAuditBacklog;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogTransactionAdapter;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.SharedAuditPublicationGuard;
import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.FrozenDataCatalogBootstrapRunner;
import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogBootstrap;
import cn.edu.suda.scholarsense.ingestionquality.application.DataSourceCatalogService;
import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogPolicy;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.shared.time.AuditAvailabilityPort;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
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
    PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile(
            DataSource dataSource,
            DataSourceProperties properties,
            RuntimeConfiguration runtime) {
        return PostgreSqlDataSourceStartupGate.verifyOnline(
                dataSource, runtime.environment().wireName(), properties.getUsername());
    }

    @Bean
    AuthorizationObjectEvidenceProvider dataSourceCatalogOwnerEvidenceProvider(
            ObjectMapper json,
            @Value("${scholarsense.ingestion-quality.owner-bindings-path}") String path,
            @Value("${scholarsense.ingestion-quality.owner-bindings-digest}") String digest) {
        return CatalogOwnerEvidenceProvider.load(Path.of(path), digest, json);
    }

    @Bean
    FrozenDataCatalogLoader frozenDataCatalogLoader(
            ObjectMapper json,
            @Value("${scholarsense.ingestion-quality.contract-root}") String contractRoot,
            @Value("${scholarsense.ingestion-quality.target-report-path}") String targetReport,
            @Value("${scholarsense.ingestion-quality.target-report-uri}") String targetReportUri,
            @Value("${scholarsense.ingestion-quality.target-authority}") String targetAuthority,
            @Value("${scholarsense.ingestion-quality.target-environment}") String targetEnvironment,
            @Value("${scholarsense.ingestion-quality.minimum-handoff-revision}")
                    long minimumHandoffRevision,
            @Value("${DATA_CATALOG_TARGET_MINIMUM_HANDOFF_REVISION}")
                    long releaseMinimumHandoffRevision,
            @Value("${scholarsense.ingestion-quality.target-signing-key-path}")
                    String targetSigningKeyPath,
            RuntimeConfiguration runtime) {
        Path root = requiredAbsolutePath(contractRoot);
        Path report = requiredAbsolutePath(targetReport);
        byte[] targetSigningKey = ProtectedTargetSigningKey.load(
                requiredAbsolutePath(targetSigningKeyPath));
        FrozenDataCatalogBuildSubject build = FrozenDataCatalogBuildSubject.load(
                IngestionQualityConfiguration.class.getClassLoader());
        try {
            return new FrozenDataCatalogLoader(
                    json, root, report, targetReportUri, targetSigningKey,
                    FrozenDataCatalogSubject.boundToRuntime(
                            targetAuthority, targetEnvironment,
                            runtime.environment().wireName(), build,
                            minimumHandoffRevision,
                            releaseMinimumHandoffRevision));
        } finally {
            Arrays.fill(targetSigningKey, (byte) 0);
        }
    }

    @Bean
    JdbcCatalogStore jdbcDataSourceCatalogStore(
            JdbcTemplate jdbc,
            ObjectMapper json,
            AuditTokenizationPort tokenization,
            TrustedTimeSource time,
            PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile) {
        return new JdbcCatalogStore(jdbc, json, tokenization, time);
    }

    @Bean
    FrozenDataCatalogPolicy frozenDataCatalogPolicy() {
        return new FrozenDataCatalogPolicy();
    }

    @Bean
    JdbcCatalogTransactionAdapter dataSourceCatalogTransactions(
            PlatformTransactionManager manager) {
        return new JdbcCatalogTransactionAdapter(new TransactionTemplate(manager));
    }

    @Bean
    FrozenDataCatalogBootstrap frozenDataCatalogBootstrap(
            FrozenDataCatalogLoader frozenCatalog,
            JdbcCatalogStore store,
            FrozenDataCatalogPolicy policy,
            JdbcCatalogTransactionAdapter transactions) {
        return new FrozenDataCatalogBootstrap(
                frozenCatalog, store, policy, transactions);
    }

    @Bean
    FrozenDataCatalogBootstrapRunner frozenDataCatalogBootstrapRunner(
            FrozenDataCatalogBootstrap bootstrap) {
        return new FrozenDataCatalogBootstrapRunner(bootstrap);
    }

    @Bean
    DataSourceCatalogService dataSourceCatalogService(
            JdbcCatalogStore store, CompositeAuthorizationPort authorization,
            AuditAvailabilityPort availability, JdbcCatalogAuditBacklog backlog,
            FrozenDataCatalogPolicy policy,
            JdbcCatalogTransactionAdapter transactions,
            FrozenDataCatalogLoader frozenCatalog,
            TrustedTimeSource time) {
        TrustedTimeClock clock = new TrustedTimeClock(time);
        return new DataSourceCatalogService(
                store, store, policy,
                new CompositeCatalogAuthorizationAdapter(authorization),
                transactions, store,
                new SharedAuditPublicationGuard(availability, backlog, clock),
                frozenCatalog,
                frozenCatalog,
                time);
    }

    @Bean
    JdbcCatalogAuditBacklog dataSourceCatalogAuditBacklog(
            JdbcTemplate jdbc,
            TrustedTimeSource time,
            PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile) {
        return new JdbcCatalogAuditBacklog(jdbc, new TrustedTimeClock(time));
    }

    @Bean
    AuthorizedShellCapabilityProvider dataSourceCatalogShellCapability() {
        return () -> List.of(new AuthorizedShellCapability(
                "data-source-catalogs", "数据源目录", "data-quality.catalogs",
                AuthorizedShellCapabilityState.AVAILABLE, Set.of("R6-DATA-OWNER")));
    }

    private static Path requiredAbsolutePath(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(
                    "INGESTION_QUALITY_CONTROLLED_PATH_ABSOLUTE_REQUIRED");
        }
        return path.normalize();
    }

}
