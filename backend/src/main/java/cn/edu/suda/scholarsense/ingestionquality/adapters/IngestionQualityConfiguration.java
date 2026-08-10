package cn.edu.suda.scholarsense.ingestionquality.adapters;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapabilityProvider;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapabilityState;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceProvider;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationPort;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.CompositeCatalogAuthorizationAdapter;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.CatalogOwnerEvidenceProvider;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenDataCatalogLoader;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenDataCatalogBuildSubject;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenDataCatalogSubject;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogAuditBacklog;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogTransactionAdapter;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualitySnapshotQueryStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualitySnapshotReadAudit;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcSubjectWindowRecomputeStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.QualitySnapshotOwnerEvidenceProvider;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.SharedAuditPublicationGuard;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.TrustedTimeRecomputeIds;
import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.FrozenDataCatalogBootstrapRunner;
import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.TransactionalSubjectMappingChangedConsumer;
import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogBootstrap;
import cn.edu.suda.scholarsense.ingestionquality.application.DataSourceCatalogService;
import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogPolicy;
import cn.edu.suda.scholarsense.ingestionquality.application.MappingRecomputeIdPort;
import cn.edu.suda.scholarsense.ingestionquality.application.MappingRecomputePlanner;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotQueryService;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectRecomputeJobQueryService;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectMappingCorrectionCoordinator;
import cn.edu.suda.scholarsense.subjectregistry.api.SubjectMappingChangedConsumerPort;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.shared.time.AuditAvailabilityPort;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import cn.edu.suda.scholarsense.subjectregistry.api.PendingSubjectRecomputeRequestPort;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
public class IngestionQualityConfiguration {
    @Bean("ingestionQualityTransactionManager")
    PlatformTransactionManager ingestionQualityTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile(
            DataSource dataSource,
            DataSourceProperties properties,
            RuntimeConfiguration runtime) {
        return PostgreSqlDataSourceStartupGate.verifyOnline(
                dataSource, runtime.environment().wireName(), properties.getUsername());
    }

    @Bean
    CatalogOwnerEvidenceProvider dataSourceCatalogOwnerEvidenceProvider(
            ObjectMapper json,
            @Value("${scholarsense.ingestion-quality.owner-bindings-path}") String path,
            @Value("${scholarsense.ingestion-quality.owner-bindings-digest}") String digest) {
        return CatalogOwnerEvidenceProvider.load(Path.of(path), digest, json);
    }

    @Bean
    AuthorizationObjectEvidenceProvider qualitySnapshotOwnerEvidenceProvider(
            JdbcTemplate jdbc,
            CatalogOwnerEvidenceProvider dataSourceCatalogOwnerEvidenceProvider,
            PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile) {
        return new QualitySnapshotOwnerEvidenceProvider(
                jdbc, dataSourceCatalogOwnerEvidenceProvider);
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
            @Qualifier("ingestionQualityTransactionManager")
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
    JdbcQualitySnapshotQueryStore jdbcQualitySnapshotQueryStore(
            JdbcTemplate jdbc,
            PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile) {
        return new JdbcQualitySnapshotQueryStore(jdbc);
    }

    @Bean
    JdbcQualitySnapshotReadAudit jdbcQualitySnapshotReadAudit(
            JdbcTemplate jdbc,
            ObjectMapper json,
            AuditTokenizationPort tokenization,
            TrustedTimeSource time,
            PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile) {
        return new JdbcQualitySnapshotReadAudit(jdbc, json, tokenization, time);
    }

    @Bean
    QualitySnapshotQueryService qualitySnapshotQueryService(
            JdbcQualitySnapshotQueryStore snapshots,
            CompositeAuthorizationPort authorization,
            CompositeAuthorizationRecheckPort recheck,
            JdbcQualitySnapshotReadAudit readAudit) {
        return new QualitySnapshotQueryService(snapshots, authorization, recheck, readAudit);
    }

    @Bean
    AuthorizedShellCapabilityProvider dataSourceCatalogShellCapability() {
        return () -> List.of(
                new AuthorizedShellCapability(
                        "data-source-catalogs", "数据源目录", "data-quality.catalogs",
                        AuthorizedShellCapabilityState.AVAILABLE, Set.of("R6-DATA-OWNER")),
                new AuthorizedShellCapability(
                        "quality-snapshots", "批次与质量快照",
                        "data-quality.quality-snapshots",
                        AuthorizedShellCapabilityState.AVAILABLE, Set.of("R6-DATA-OWNER")));
    }

    @Bean
    JdbcSubjectWindowRecomputeStore jdbcSubjectWindowRecomputeStore(
            JdbcTemplate jdbc, ObjectMapper json,
            PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile) {
        return new JdbcSubjectWindowRecomputeStore(jdbc, json);
    }

    @Bean
    MappingRecomputeIdPort mappingRecomputeIds(TrustedTimeSource time) {
        return new TrustedTimeRecomputeIds(time);
    }

    @Bean
    MappingRecomputePlanner mappingRecomputePlanner(
            JdbcSubjectWindowRecomputeStore store, MappingRecomputeIdPort ids) {
        return new MappingRecomputePlanner(store, store, store, ids);
    }

    @Bean
    SubjectRecomputeJobQueryService subjectRecomputeJobQueryService(
            JdbcSubjectWindowRecomputeStore store,
            CompositeAuthorizationPort authorization,
            CompositeAuthorizationRecheckPort recheck,
            ObjectProvider<PendingSubjectRecomputeRequestPort> pendingRequests) {
        return new SubjectRecomputeJobQueryService(
                store, authorization, recheck,
                pendingRequests.getIfAvailable(() -> ignored -> java.util.Optional.empty()));
    }

    @Bean
    SubjectMappingCorrectionCoordinator subjectMappingCorrectionCoordinator(
            JdbcSubjectWindowRecomputeStore store, MappingRecomputePlanner planner) {
        return new SubjectMappingCorrectionCoordinator(store, planner, store);
    }

    @Bean
    SubjectMappingChangedConsumerPort subjectMappingChangedConsumer(
            SubjectMappingCorrectionCoordinator coordinator,
            @Qualifier("ingestionQualityTransactionManager")
            PlatformTransactionManager manager,
            TrustedTimeSource time) {
        return new TransactionalSubjectMappingChangedConsumer(
                coordinator, new TransactionTemplate(manager), new TrustedTimeClock(time));
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
