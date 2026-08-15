package cn.edu.suda.scholarsense.ingestionquality.adapters;

import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationPort;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcDataBatchAtomicCommandAdapter;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcDataBatchStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.QualityWorkerMtlsHttpClientFactory;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.QualityWorkerProviderAdapters;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchAuthorizationPort;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchCanonicalOutboxFactory;
import cn.edu.suda.scholarsense.shared.observability.CurrentTraceSource;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContextCodec;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchCommandService;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchQualityEvaluationService;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchWorkloadAuthorizationGuard;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchWorkloadAuthorizationPort;
import cn.edu.suda.scholarsense.ingestionquality.application.ExecutableQualityPolicyGuard;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotIdPort;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.shared.time.EvidenceBoundTrustedTimeSource;
import cn.edu.suda.scholarsense.shared.time.TrustedClockConstraints;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import cn.edu.suda.scholarsense.shared.observability.TrustedHttpClientFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
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
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Dedicated pool and startup gate for the batch quality command worker. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "scholarsense.ingestion-quality.quality-worker-enabled", havingValue = "true")
public class IngestionQualityQualityWorkerConfiguration {
    @Bean("ingestionQualityQualityWorkerProviderHttpClient")
    HttpClient ingestionQualityQualityWorkerProviderHttpClient(
            @Value("${scholarsense.ingestion-quality.workload-mtls-cert-path}") String certificate,
            @Value("${scholarsense.ingestion-quality.workload-mtls-key-path}") String privateKey,
            @Value("${scholarsense.ingestion-quality.workload-trust-bundle-path}") String trust) {
        return QualityWorkerMtlsHttpClientFactory.create(
                Path.of(certificate), Path.of(privateKey), Path.of(trust));
    }

    @Bean
    QualityWorkerProviderAdapters ingestionQualityQualityWorkerProviderAdapters(
            @Value("${scholarsense.ingestion-quality.data-batch-authorization-ref}")
                    URI dataAuthorization,
            @Value("${scholarsense.ingestion-quality.workload-authorization-ref}")
                    URI workloadAuthorization,
            @Value("${scholarsense.ingestion-quality.quality-snapshot-id-provider-ref}")
                    URI snapshotIds,
            @Value("${scholarsense.ingestion-quality.trusted-time-provider-ref}") URI trustedTime,
            ObjectMapper json,
            @Qualifier("ingestionQualityQualityWorkerProviderHttpClient") HttpClient http,
            TrustedHttpClientFactory trustedHttp) {
        return new QualityWorkerProviderAdapters(
                dataAuthorization, workloadAuthorization, snapshotIds, trustedTime, json,
                trustedHttp.wrapSandboxQualityWorker(http, dataAuthorization, workloadAuthorization,
                        snapshotIds, trustedTime));
    }

    @Bean
    TrustedTimeSource ingestionQualityQualityWorkerTrustedTimeSource(
            RuntimeConfiguration runtime,
            QualityWorkerProviderAdapters providers) {
        URI reference = URI.create(java.util.Objects.requireNonNull(
                runtime.clockSourceReference(), "SCHOLARSENSE_CLOCK_SOURCE_REF"));
        String sourceId = reference.getPath().substring(1);
        return new EvidenceBoundTrustedTimeSource(
                Clock.systemUTC(), sourceId,
                new TrustedClockConstraints("PP-1.0.0", 100), providers);
    }

    @Bean
    @ConfigurationProperties("scholarsense.ingestion-quality.quality-worker-datasource")
    DataSourceProperties ingestionQualityQualityWorkerDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("ingestionQualityQualityWorkerDataSource")
    DataSource ingestionQualityQualityWorkerDataSource(
            @Qualifier("ingestionQualityQualityWorkerDataSourceProperties")
                    DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    PostgreSqlConnectionProfile ingestionQualityQualityWorkerPostgreSqlConnectionProfile(
            @Qualifier("ingestionQualityQualityWorkerDataSource") DataSource dataSource,
            @Qualifier("ingestionQualityQualityWorkerDataSourceProperties")
                    DataSourceProperties properties,
            RuntimeConfiguration runtime) {
        return PostgreSqlDataSourceStartupGate.verifyQualityWorker(
                dataSource, runtime.environment().wireName(), properties.getUsername());
    }

    @Bean("ingestionQualityQualityWorkerJdbc")
    JdbcTemplate ingestionQualityQualityWorkerJdbc(
            @Qualifier("ingestionQualityQualityWorkerDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean("ingestionQualityQualityWorkerTransactionManager")
    PlatformTransactionManager ingestionQualityQualityWorkerTransactionManager(
            @Qualifier("ingestionQualityQualityWorkerDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    JdbcDataBatchStore ingestionQualityQualityWorkerStore(
            @Qualifier("ingestionQualityQualityWorkerJdbc") JdbcTemplate jdbc,
            ObjectMapper json,
            @Qualifier("ingestionQualityQualityWorkerPostgreSqlConnectionProfile")
                    PostgreSqlConnectionProfile connectionProfile) {
        return new JdbcDataBatchStore(jdbc, json);
    }

    @Bean
    JdbcDataBatchAtomicCommandAdapter ingestionQualityDataBatchAtomicCommands(
            @Qualifier("ingestionQualityQualityWorkerJdbc") JdbcTemplate jdbc,
            ObjectMapper json,
            JdbcDataBatchStore store,
            AuditTokenizationPort tokenization,
            @Qualifier("ingestionQualityQualityWorkerPostgreSqlConnectionProfile")
                    PostgreSqlConnectionProfile connectionProfile) {
        return new JdbcDataBatchAtomicCommandAdapter(jdbc, json, store, tokenization);
    }

    @Bean
    DataBatchCanonicalOutboxFactory ingestionQualityDataBatchCanonicalOutboxFactory(
            @Qualifier("ingestionQualityQualityWorkerPostgreSqlConnectionProfile")
                    PostgreSqlConnectionProfile connectionProfile,
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec traceCodec,
            cn.edu.suda.scholarsense.shared.observability.ObservationPort observations) {
        return new DataBatchCanonicalOutboxFactory(
                connectionProfile.expectedWorkloadIdentity(), currentTrace, traceCodec,
                observations);
    }

    @Bean
    DataBatchWorkloadAuthorizationGuard ingestionQualityDataBatchWorkloadAuthorizationGuard(
            DataBatchWorkloadAuthorizationPort authorization) {
        return new DataBatchWorkloadAuthorizationGuard(authorization);
    }

    @Bean
    DataBatchQualityEvaluationService ingestionQualityDataBatchQualityEvaluationService(
            ExecutableQualityPolicyGuard guard,
            JdbcDataBatchStore store,
            QualitySnapshotIdPort snapshotIds) {
        return new DataBatchQualityEvaluationService(
                guard, store, store, store, snapshotIds);
    }

    @Bean
    DataBatchCommandService ingestionQualityDataBatchCommandService(
            JdbcDataBatchStore store,
            JdbcDataBatchAtomicCommandAdapter atomic,
            DataBatchQualityEvaluationService evaluation,
            ExecutableQualityPolicyGuard guard,
            DataBatchAuthorizationPort authorization,
            DataBatchWorkloadAuthorizationGuard workloadAuthorization,
            DataBatchCanonicalOutboxFactory payloads,
            TrustedTimeSource time) {
        return new DataBatchCommandService(
                store, store, store, atomic, evaluation, guard, authorization,
                workloadAuthorization, payloads, time);
    }
}
