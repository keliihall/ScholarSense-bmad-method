package cn.edu.suda.scholarsense.ingestionquality.adapters;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenRuleDependencyRegistryLoader;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityEligibilityEventTransactionAdapter;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityEligibilitySnapshotLookupStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.HmacQualityFuseWorkItemKeyProvider;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityEventConsumer;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseWorkItemKeyPort;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseWorkloadAuthorizationGuard;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchWorkloadAuthorizationPort;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import cn.edu.suda.scholarsense.shared.observability.ObservationPort;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContextCodec;
import java.util.Arrays;
import javax.crypto.spec.SecretKeySpec;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyRegistry;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Independently activatable upstream quality-event consumer pool and startup gate. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "scholarsense.ingestion-quality.eligibility-consumer-enabled",
        havingValue = "true")
public class IngestionQualityEligibilityConsumerConfiguration {
    @Bean
    @ConfigurationProperties("scholarsense.ingestion-quality.eligibility-consumer-datasource")
    DataSourceProperties ingestionQualityEligibilityConsumerDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("ingestionQualityEligibilityConsumerDataSource")
    DataSource ingestionQualityEligibilityConsumerDataSource(
            @Qualifier("ingestionQualityEligibilityConsumerDataSourceProperties")
                    DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    PostgreSqlConnectionProfile ingestionQualityEligibilityConsumerPostgreSqlConnectionProfile(
            @Qualifier("ingestionQualityEligibilityConsumerDataSource") DataSource dataSource,
            @Qualifier("ingestionQualityEligibilityConsumerDataSourceProperties")
                    DataSourceProperties properties,
            RuntimeConfiguration runtime) {
        return PostgreSqlDataSourceStartupGate.verifyEligibilityConsumer(
                dataSource, runtime.environment().wireName(), properties.getUsername());
    }

    @Bean("ingestionQualityEligibilityConsumerJdbc")
    JdbcTemplate ingestionQualityEligibilityConsumerJdbc(
            @Qualifier("ingestionQualityEligibilityConsumerDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean("ingestionQualityEligibilityConsumerTransactions")
    TransactionOperations ingestionQualityEligibilityConsumerTransactions(
            @Qualifier("ingestionQualityEligibilityConsumerDataSource") DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Bean
    RuleDependencyRegistry ingestionQualityRuleDependencyRegistry(
            @Value("${scholarsense.ingestion-quality.contract-root}") String contractRoot,
            ObjectMapper json,
            @Qualifier("ingestionQualityEligibilityConsumerPostgreSqlConnectionProfile")
                    PostgreSqlConnectionProfile connectionProfile) {
        return FrozenRuleDependencyRegistryLoader.load(Path.of(contractRoot), json);
    }

    @Bean
    QualityFuseWorkloadAuthorizationGuard qualityFuseWorkloadAuthorizationGuard(
            DataBatchWorkloadAuthorizationPort authorization,
            RuntimeConfiguration runtime) {
        return new QualityFuseWorkloadAuthorizationGuard(
                authorization, runtime.environment().wireName());
    }

    @Bean
    QualityFuseWorkItemKeyPort qualityFuseWorkItemKeyPort(
            @Value("${scholarsense.ingestion-quality.fuse-work-item-hmac-key-path}")
                    String keyPath,
            @Value("${scholarsense.ingestion-quality.fuse-work-item-hmac-key-version}")
                    String keyVersion,
            @Qualifier("ingestionQualityEligibilityConsumerPostgreSqlConnectionProfile")
                    PostgreSqlConnectionProfile connectionProfile) {
        byte[] key = ProtectedTargetSigningKey.load(Path.of(keyPath));
        try {
            return new HmacQualityFuseWorkItemKeyProvider(
                    new SecretKeySpec(key, "HmacSHA256"), keyVersion);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Bean
    QualityEligibilityEventConsumer ingestionQualityEligibilityEventConsumer(
            @Qualifier("ingestionQualityEligibilityConsumerJdbc") JdbcTemplate jdbc,
            @Qualifier("ingestionQualityEligibilityConsumerTransactions")
                    TransactionOperations transactions,
            ObjectMapper json,
            RuleDependencyRegistry registry,
            QualityFuseWorkItemKeyPort workItemKeys,
            QualityFuseWorkloadAuthorizationGuard authorization,
            TrustedTimeSource trustedTime,
            ObservationPort observations,
            W3cTraceContextCodec traceCodec,
            @Qualifier("ingestionQualityEligibilityConsumerPostgreSqlConnectionProfile")
                    PostgreSqlConnectionProfile connectionProfile) {
        return new QualityEligibilityEventConsumer(
                new JdbcQualityEligibilitySnapshotLookupStore(jdbc, json),
                new JdbcQualityEligibilityEventTransactionAdapter(
                        jdbc, transactions, json, authorization, trustedTime),
                registry, workItemKeys, observations, traceCodec);
    }
}
