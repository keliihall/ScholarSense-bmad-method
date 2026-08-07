package cn.edu.suda.scholarsense.subjectregistry.adapters;

import cn.edu.suda.scholarsense.subjectregistry.api.SubjectMappingChangedConsumerPort;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import cn.edu.suda.scholarsense.subjectregistry.adapters.inbound.SubjectMappingRelayScheduler;
import cn.edu.suda.scholarsense.subjectregistry.adapters.outbound.JdbcSubjectMappingEventRelayStore;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Producer-owned worker wiring with a dedicated subject-registry relay principal. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "scholarsense.subject-registry.relay-enabled", havingValue = "true")
@EnableScheduling
public class SubjectMappingRelayConfiguration {
    @Bean
    @ConfigurationProperties("scholarsense.subject-registry.relay-datasource")
    DataSourceProperties subjectMappingRelayDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("subjectMappingRelayDataSource")
    DataSource subjectMappingRelayDataSource(
            @Qualifier("subjectMappingRelayDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    SubjectRegistryPostgreSqlConnectionProfile subjectMappingRelayConnectionProfile(
            @Qualifier("subjectMappingRelayDataSource") DataSource dataSource,
            @Qualifier("subjectMappingRelayDataSourceProperties") DataSourceProperties properties,
            RuntimeConfiguration runtime) {
        return SubjectRegistryPostgreSqlDataSourceStartupGate.verifyRelay(
                dataSource, runtime.environment().wireName(), properties.getUsername());
    }

    @Bean("subjectMappingRelayJdbc")
    JdbcTemplate subjectMappingRelayJdbc(
            @Qualifier("subjectMappingRelayDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean("subjectMappingRelayTransactionManager")
    PlatformTransactionManager subjectMappingRelayTransactionManager(
            @Qualifier("subjectMappingRelayDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    JdbcSubjectMappingEventRelayStore subjectMappingEventRelayStore(
            @Qualifier("subjectMappingRelayJdbc") JdbcTemplate jdbc,
            @Qualifier("subjectMappingRelayTransactionManager") PlatformTransactionManager manager,
            ObjectMapper json,
            SubjectRegistryPostgreSqlConnectionProfile subjectMappingRelayConnectionProfile) {
        return new JdbcSubjectMappingEventRelayStore(
                jdbc, new TransactionTemplate(manager), json);
    }

    @Bean
    SubjectMappingRelayProcessor subjectMappingRelayProcessor(
            JdbcSubjectMappingEventRelayStore work,
            SubjectMappingChangedConsumerPort consumer,
            TrustedTimeSource time) {
        Clock clock = new TrustedTimeSubjectRegistryClock(time);
        return new SubjectMappingRelayProcessor(work, consumer, clock);
    }

    @Bean
    SubjectMappingRelayScheduler subjectMappingRelayScheduler(
            SubjectMappingRelayProcessor processor) {
        return new SubjectMappingRelayScheduler(processor);
    }
}
