package cn.edu.suda.scholarsense.ingestionquality.adapters;

import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.QualityTaskRelayScheduler;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.DeferredQualityTaskTargetAdapter;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityTaskRelayWork;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityTaskRelayProcessor;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityTaskTargetPort;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

/** Independently activated quality-task relay with its own exclusive physical login. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "scholarsense.ingestion-quality.task-relay-enabled",
        havingValue = "true")
@EnableScheduling
public class IngestionQualityTaskRelayConfiguration {
    @Bean
    @ConfigurationProperties("scholarsense.ingestion-quality.task-relay-datasource")
    DataSourceProperties ingestionQualityTaskRelayDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("ingestionQualityTaskRelayDataSource")
    DataSource ingestionQualityTaskRelayDataSource(
            @Qualifier("ingestionQualityTaskRelayDataSourceProperties")
                    DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    PostgreSqlConnectionProfile ingestionQualityTaskRelayPostgreSqlConnectionProfile(
            @Qualifier("ingestionQualityTaskRelayDataSource") DataSource dataSource,
            @Qualifier("ingestionQualityTaskRelayDataSourceProperties")
                    DataSourceProperties properties,
            RuntimeConfiguration runtime) {
        return PostgreSqlDataSourceStartupGate.verifyTaskRelay(
                dataSource, runtime.environment().wireName(), properties.getUsername());
    }

    @Bean("ingestionQualityTaskRelayJdbc")
    JdbcTemplate ingestionQualityTaskRelayJdbc(
            @Qualifier("ingestionQualityTaskRelayDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    JdbcQualityTaskRelayWork ingestionQualityTaskRelayWork(
            @Qualifier("ingestionQualityTaskRelayJdbc") JdbcTemplate jdbc,
            ObjectMapper json,
            PostgreSqlConnectionProfile ingestionQualityTaskRelayPostgreSqlConnectionProfile) {
        return new JdbcQualityTaskRelayWork(jdbc, json);
    }

    @Bean
    QualityTaskTargetPort ingestionQualityTaskTarget() {
        return new DeferredQualityTaskTargetAdapter();
    }

    @Bean
    QualityTaskRelayProcessor ingestionQualityTaskRelayProcessor(
            JdbcQualityTaskRelayWork work, QualityTaskTargetPort target) {
        return new QualityTaskRelayProcessor(work, target);
    }

    @Bean
    QualityTaskRelayScheduler ingestionQualityTaskRelayScheduler(
            QualityTaskRelayProcessor processor) {
        return new QualityTaskRelayScheduler(processor);
    }
}
