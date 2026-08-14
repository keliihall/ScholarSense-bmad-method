package cn.edu.suda.scholarsense.ingestionquality.adapters;

import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.RecoveryValidationScheduler;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcRecoveryValidationExternalWork;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcRecoveryBackfillAndReconciliationAdapter;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcRecoveryValidationWork;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenQualityRecoveryPolicyLoader;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenRecoverySourceClassRegistryLoader;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.SignalEvaluationRecoverySampleRecomputeAdapter;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.TrustedTimeRecomputeIds;
import cn.edu.suda.scholarsense.ingestionquality.application.MappingRecomputeIdPort;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoverySampleRecomputePort;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryValidationJobProcessor;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryValidationTrustedTimePort;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleNormalizedInputPort;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleRecomputeProviderPort;
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
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

/** Recovery-only worker login, exact privilege gate and real signal-evaluation provider. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "scholarsense.ingestion-quality.recovery-runtime-enabled",
        havingValue = "true")
@EnableScheduling
public class IngestionQualityRecoveryWorkerConfiguration {
    @Bean
    @ConfigurationProperties("scholarsense.ingestion-quality.recovery-worker-datasource")
    DataSourceProperties ingestionQualityRecoveryWorkerDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("ingestionQualityRecoveryWorkerDataSource")
    DataSource ingestionQualityRecoveryWorkerDataSource(
            @Qualifier("ingestionQualityRecoveryWorkerDataSourceProperties")
                    DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    PostgreSqlConnectionProfile ingestionQualityRecoveryWorkerPostgreSqlConnectionProfile(
            @Qualifier("ingestionQualityRecoveryWorkerDataSource") DataSource dataSource,
            @Qualifier("ingestionQualityRecoveryWorkerDataSourceProperties")
                    DataSourceProperties properties,
            RuntimeConfiguration runtime) {
        return PostgreSqlDataSourceStartupGate.verifyRecoveryWorker(
                dataSource, runtime.environment().wireName(), properties.getUsername());
    }

    @Bean("ingestionQualityRecoveryWorkerJdbc")
    JdbcTemplate ingestionQualityRecoveryWorkerJdbc(
            @Qualifier("ingestionQualityRecoveryWorkerDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    RecoveryValidationJobProcessor recoveryValidationJobProcessor(
            @Qualifier("ingestionQualityRecoveryWorkerJdbc") JdbcTemplate jdbc,
            ObjectMapper json,
            RecoverySampleNormalizedInputPort sampleInputs,
            RecoverySampleRecomputeProviderPort sampleProvider,
            TrustedTimeSource trustedTime,
            @Value("${scholarsense.ingestion-quality.recovery-worker-digest}")
                    String workerDigest,
            @Value("${scholarsense.ingestion-quality.contract-root}")
                    String contractRoot,
            PostgreSqlConnectionProfile ingestionQualityRecoveryWorkerPostgreSqlConnectionProfile) {
        JdbcRecoveryValidationWork work = new JdbcRecoveryValidationWork(jdbc, json);
        RecoverySampleRecomputePort samples =
                new SignalEvaluationRecoverySampleRecomputeAdapter(sampleProvider);
        MappingRecomputeIdPort ids = new TrustedTimeRecomputeIds(trustedTime);
        RecoveryValidationTrustedTimePort time = () -> trustedTime.now().instant();
        var policy = FrozenQualityRecoveryPolicyLoader.load(Path.of(contractRoot), json);
        var sourceClasses = FrozenRecoverySourceClassRegistryLoader.load(
                Path.of(contractRoot), json);
        var historicalWork = new JdbcRecoveryBackfillAndReconciliationAdapter(jdbc, json);
        return new RecoveryValidationJobProcessor(
                work, new JdbcRecoveryValidationExternalWork(
                        jdbc, json, sampleInputs, samples, historicalWork, historicalWork,
                        policy, sourceClasses,
                        time::now, ids::nextId),
                time, workerDigest);
    }

    @Bean
    RecoveryValidationScheduler recoveryValidationScheduler(
            RecoveryValidationJobProcessor processor) {
        return new RecoveryValidationScheduler(processor);
    }
}
