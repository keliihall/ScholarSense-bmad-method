package cn.edu.suda.scholarsense.ingestionquality.adapters;

import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.CatalogRetentionScheduler;
import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.QualitySnapshotRetentionScheduler;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogRetentionCleanup;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualitySnapshotRetentionAuthorityIngest;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualitySnapshotRetentionCandidateFinder;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualitySnapshotRetentionExecutor;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.TrustedTimeQualitySnapshotRetentionIds;
import cn.edu.suda.scholarsense.ingestionquality.application.ConsumerRegistryAuthorityPort;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionOrchestrator;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Dedicated pool and startup gate for destructive retention work. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "scholarsense.ingestion-quality.retention-enabled", havingValue = "true")
@EnableScheduling
public class IngestionQualityRetentionConfiguration {
    @Bean
    @ConfigurationProperties("scholarsense.ingestion-quality.retention-datasource")
    DataSourceProperties ingestionQualityRetentionDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("ingestionQualityRetentionDataSource")
    DataSource ingestionQualityRetentionDataSource(
            @Qualifier("ingestionQualityRetentionDataSourceProperties")
                    DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    PostgreSqlConnectionProfile ingestionQualityRetentionPostgreSqlConnectionProfile(
            @Qualifier("ingestionQualityRetentionDataSource") DataSource dataSource,
            @Qualifier("ingestionQualityRetentionDataSourceProperties")
                    DataSourceProperties properties,
            RuntimeConfiguration runtime) {
        return PostgreSqlDataSourceStartupGate.verifyRetentionExecutor(
                dataSource, runtime.environment().wireName(), properties.getUsername());
    }

    @Bean("ingestionQualityRetentionJdbc")
    JdbcTemplate ingestionQualityRetentionJdbc(
            @Qualifier("ingestionQualityRetentionDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    JdbcCatalogRetentionCleanup dataSourceCatalogRetentionCleanup(
            @Qualifier("ingestionQualityRetentionJdbc") JdbcTemplate jdbc,
            TrustedTimeSource time,
            @Qualifier("ingestionQualityRetentionPostgreSqlConnectionProfile")
                    PostgreSqlConnectionProfile connectionProfile) {
        return new JdbcCatalogRetentionCleanup(jdbc, time);
    }

    @Bean
    CatalogRetentionScheduler dataSourceCatalogRetentionScheduler(
            JdbcCatalogRetentionCleanup cleanup,
            @Qualifier("ingestionQualityRetentionPostgreSqlConnectionProfile")
                    PostgreSqlConnectionProfile connectionProfile) {
        return new CatalogRetentionScheduler(cleanup);
    }

    @Bean
    JdbcQualitySnapshotRetentionExecutor ingestionQualityQualitySnapshotRetentionExecutor(
            @Qualifier("ingestionQualityRetentionJdbc") JdbcTemplate jdbc,
            @Qualifier("ingestionQualityRetentionPostgreSqlConnectionProfile")
                    PostgreSqlConnectionProfile connectionProfile) {
        return new JdbcQualitySnapshotRetentionExecutor(jdbc);
    }

    @Bean
    JdbcQualitySnapshotRetentionCandidateFinder
            ingestionQualityQualitySnapshotRetentionCandidateFinder(
                    @Qualifier("ingestionQualityRetentionJdbc") JdbcTemplate jdbc,
                    @Qualifier("ingestionQualityRetentionPostgreSqlConnectionProfile")
                            PostgreSqlConnectionProfile connectionProfile) {
        return new JdbcQualitySnapshotRetentionCandidateFinder(jdbc);
    }

    @Bean
    TrustedTimeQualitySnapshotRetentionIds ingestionQualityQualitySnapshotRetentionIds(
            TrustedTimeSource time) {
        return new TrustedTimeQualitySnapshotRetentionIds(time);
    }

    @Bean
    QualitySnapshotRetentionOrchestrator ingestionQualityQualitySnapshotRetentionOrchestrator(
            JdbcQualitySnapshotRetentionCandidateFinder candidates,
            ConsumerRegistryAuthorityPort authority,
            TrustedTimeQualitySnapshotRetentionIds ids,
            JdbcQualitySnapshotRetentionAuthorityIngest ingest,
            JdbcQualitySnapshotRetentionExecutor executor) {
        return new QualitySnapshotRetentionOrchestrator(
                candidates, authority, ids, ingest, executor);
    }

    @Bean
    QualitySnapshotRetentionScheduler ingestionQualityQualitySnapshotRetentionScheduler(
            QualitySnapshotRetentionOrchestrator orchestrator,
            @Qualifier("ingestionQualityRetentionPostgreSqlConnectionProfile")
                    PostgreSqlConnectionProfile connectionProfile) {
        return new QualitySnapshotRetentionScheduler(orchestrator);
    }
}
