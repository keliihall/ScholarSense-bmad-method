package cn.edu.suda.scholarsense.ingestionquality.adapters;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualitySnapshotRetentionAuthorityIngest;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Independently activatable pool for the sole production authority-ingest capability. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "scholarsense.ingestion-quality.consumer-registry-authority-enabled",
        havingValue = "true")
public class IngestionQualityConsumerRegistryAuthorityConfiguration {
    @Bean
    @ConfigurationProperties(
            "scholarsense.ingestion-quality.consumer-registry-authority-datasource")
    DataSourceProperties ingestionQualityConsumerRegistryAuthorityDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("ingestionQualityConsumerRegistryAuthorityDataSource")
    DataSource ingestionQualityConsumerRegistryAuthorityDataSource(
            @Qualifier("ingestionQualityConsumerRegistryAuthorityDataSourceProperties")
                    DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    PostgreSqlConnectionProfile ingestionQualityConsumerRegistryAuthorityPostgreSqlConnectionProfile(
            @Qualifier("ingestionQualityConsumerRegistryAuthorityDataSource")
                    DataSource dataSource,
            @Qualifier("ingestionQualityConsumerRegistryAuthorityDataSourceProperties")
                    DataSourceProperties properties,
            RuntimeConfiguration runtime) {
        return PostgreSqlDataSourceStartupGate.verifyConsumerRegistryAuthority(
                dataSource, runtime.environment().wireName(), properties.getUsername());
    }

    @Bean("ingestionQualityConsumerRegistryAuthorityJdbc")
    JdbcTemplate ingestionQualityConsumerRegistryAuthorityJdbc(
            @Qualifier("ingestionQualityConsumerRegistryAuthorityDataSource")
                    DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    JdbcQualitySnapshotRetentionAuthorityIngest
            ingestionQualityQualitySnapshotRetentionAuthorityIngest(
                    @Qualifier("ingestionQualityConsumerRegistryAuthorityJdbc") JdbcTemplate jdbc,
                    @Qualifier(
                            "ingestionQualityConsumerRegistryAuthorityPostgreSqlConnectionProfile")
                            PostgreSqlConnectionProfile connectionProfile) {
        return new JdbcQualitySnapshotRetentionAuthorityIngest(jdbc);
    }
}
