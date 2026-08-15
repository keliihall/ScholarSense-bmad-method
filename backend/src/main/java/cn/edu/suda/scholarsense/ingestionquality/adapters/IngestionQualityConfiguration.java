package cn.edu.suda.scholarsense.ingestionquality.adapters;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapabilityProvider;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapabilityState;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellActionCapability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellActionCapabilityProvider;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceProvider;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalEvidenceQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalPort;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskExecutionAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.RecoveryCheckerBindingResolver;
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
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityEligibilityQueryStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityEligibilityReadAudit;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityRecoveryTaskQueryStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityRecoveryTaskReadAudit;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityRecoveryCommandStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityRecoveryFinalizationStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcRecoveryObservationQueryStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcSubjectWindowRecomputeStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.QualitySnapshotOwnerEvidenceProvider;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.QualityRecoveryTaskOwnerEvidenceProvider;
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
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityQueryService;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryTaskQueryService;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseRecoveryService;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryFinalizationService;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryAuthorizationGuard;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryObservationQueryService;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryValidationTrustedTimePort;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectRecomputeJobQueryService;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectMappingCorrectionCoordinator;
import cn.edu.suda.scholarsense.subjectregistry.api.SubjectMappingChangedConsumerPort;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.shared.observability.CurrentTraceSource;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContextCodec;
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
    AuthorizationObjectEvidenceProvider qualityRecoveryTaskOwnerEvidenceProvider(
            JdbcTemplate jdbc,
            CatalogOwnerEvidenceProvider dataSourceCatalogOwnerEvidenceProvider,
            ObjectProvider<HighRiskApprovalEvidenceQueryPort> approvalEvidence,
            PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile) {
        return new QualityRecoveryTaskOwnerEvidenceProvider(
                jdbc, dataSourceCatalogOwnerEvidenceProvider,
                approvalEvidence.getIfAvailable(HighRiskApprovalEvidenceQueryPort::notInstalled));
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
    JdbcQualityEligibilityQueryStore jdbcQualityEligibilityQueryStore(
            JdbcTemplate jdbc,
            PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile) {
        return new JdbcQualityEligibilityQueryStore(jdbc);
    }

    @Bean
    JdbcQualityEligibilityReadAudit jdbcQualityEligibilityReadAudit(
            JdbcTemplate jdbc,
            ObjectMapper json,
            TrustedTimeSource time,
            AuditTokenizationPort tokenization,
            @Qualifier("ingestionQualityTransactionManager")
                    PlatformTransactionManager manager,
            PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile) {
        return new JdbcQualityEligibilityReadAudit(
                jdbc, json, time, new TransactionTemplate(manager), tokenization);
    }

    @Bean
    QualityEligibilityQueryService qualityEligibilityQueryService(
            JdbcQualityEligibilityQueryStore eligibilities,
            CompositeAuthorizationPort authorization,
            CompositeAuthorizationRecheckPort recheck,
            JdbcQualityEligibilityReadAudit readAudit) {
        return new QualityEligibilityQueryService(
                eligibilities, authorization, recheck, readAudit);
    }

    @Bean
    JdbcQualityRecoveryTaskQueryStore jdbcQualityRecoveryTaskQueryStore(
            JdbcTemplate jdbc,
            ObjectMapper json,
            PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile) {
        return new JdbcQualityRecoveryTaskQueryStore(jdbc, json);
    }

    @Bean
    JdbcQualityRecoveryTaskReadAudit jdbcQualityRecoveryTaskReadAudit(
            JdbcTemplate jdbc,
            ObjectMapper json,
            TrustedTimeSource time,
            AuditTokenizationPort tokenization,
            @Qualifier("ingestionQualityTransactionManager")
                    PlatformTransactionManager manager,
            PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile) {
        return new JdbcQualityRecoveryTaskReadAudit(
                jdbc, json, time, new TransactionTemplate(manager), tokenization);
    }

    @Bean
    QualityRecoveryTaskQueryService qualityRecoveryTaskQueryService(
            JdbcQualityRecoveryTaskQueryStore tasks,
            CompositeAuthorizationPort authorization,
            CompositeAuthorizationRecheckPort recheck,
            JdbcQualityRecoveryTaskReadAudit readAudit) {
        return new QualityRecoveryTaskQueryService(tasks, authorization, recheck, readAudit);
    }

    @Bean
    JdbcRecoveryObservationQueryStore jdbcRecoveryObservationQueryStore(
            JdbcTemplate jdbc, ObjectMapper json,
            PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile) {
        return new JdbcRecoveryObservationQueryStore(jdbc, json);
    }

    @Bean
    RecoveryObservationQueryService recoveryObservationQueryService(
            QualityRecoveryTaskQueryService tasks,
            JdbcRecoveryObservationQueryStore observations) {
        return new RecoveryObservationQueryService(tasks, observations);
    }

    @Bean
    @ConditionalOnProperty(
            name = "scholarsense.ingestion-quality.recovery-runtime-enabled",
            havingValue = "true")
    JdbcQualityRecoveryCommandStore jdbcQualityRecoveryCommandStore(
            JdbcTemplate jdbc, ObjectMapper json,
            PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile) {
        return new JdbcQualityRecoveryCommandStore(jdbc, json);
    }

    @Bean
    @ConditionalOnProperty(
            name = "scholarsense.ingestion-quality.recovery-runtime-enabled",
            havingValue = "true")
    QualityFuseRecoveryService qualityFuseRecoveryService(
            JdbcQualityRecoveryCommandStore store,
            CompositeAuthorizationPort authorization,
            CompositeAuthorizationRecheckPort recheck,
            RecoveryCheckerBindingResolver checkers,
            HighRiskApprovalPort approvals,
            HighRiskExecutionAuthorizationPort executionAuthorizations,
            TrustedTimeSource time,
            MappingRecomputeIdPort ids) {
        RecoveryValidationTrustedTimePort trustedTime = () -> time.now().instant();
        return new QualityFuseRecoveryService(
                store, authorization,
                new QualityRecoveryAuthorizationGuard(authorization, recheck),
                checkers, approvals, executionAuthorizations, trustedTime, ids::nextId);
    }

    @Bean
    @ConditionalOnProperty(
            name = "scholarsense.ingestion-quality.recovery-runtime-enabled",
            havingValue = "true")
    JdbcQualityRecoveryFinalizationStore jdbcQualityRecoveryFinalizationStore(
            JdbcTemplate jdbc, ObjectMapper json,
            PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile) {
        return new JdbcQualityRecoveryFinalizationStore(jdbc, json);
    }

    @Bean
    @ConditionalOnProperty(
            name = "scholarsense.ingestion-quality.recovery-runtime-enabled",
            havingValue = "true")
    QualityRecoveryFinalizationService qualityRecoveryFinalizationService(
            JdbcQualityRecoveryFinalizationStore store,
            CompositeAuthorizationPort authorization,
            CompositeAuthorizationRecheckPort recheck,
            RecoveryCheckerBindingResolver checkers,
            HighRiskApprovalPort approvals,
            HighRiskExecutionAuthorizationPort executionAuthorizations,
            TrustedTimeSource time,
            MappingRecomputeIdPort ids) {
        return new QualityRecoveryFinalizationService(
                store, new QualityRecoveryAuthorizationGuard(authorization, recheck),
                checkers, approvals, executionAuthorizations,
                () -> time.now().instant(), ids::nextId);
    }

    @Bean
    @ConditionalOnProperty(
            name = "scholarsense.ingestion-quality.recovery-runtime-enabled",
            havingValue = "true")
    cn.edu.suda.scholarsense.ingestionquality.application
            .QualityRecoveryConfirmationRelayProcessor qualityRecoveryConfirmationRelayProcessor(
                    JdbcQualityRecoveryCommandStore store,
                    HighRiskExecutionAuthorizationPort authorizations,
                    TrustedTimeSource time) {
        return new cn.edu.suda.scholarsense.ingestionquality.application
                .QualityRecoveryConfirmationRelayProcessor(
                        store, authorizations, () -> time.now().instant());
    }

    @Bean
    @ConditionalOnProperty(
            name = "scholarsense.ingestion-quality.recovery-runtime-enabled",
            havingValue = "true")
    cn.edu.suda.scholarsense.ingestionquality.adapters.inbound
            .QualityRecoveryConfirmationRelayScheduler qualityRecoveryConfirmationRelayScheduler(
                    cn.edu.suda.scholarsense.ingestionquality.application
                            .QualityRecoveryConfirmationRelayProcessor processor) {
        return new cn.edu.suda.scholarsense.ingestionquality.adapters.inbound
                .QualityRecoveryConfirmationRelayScheduler(processor);
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
    AuthorizedShellActionCapabilityProvider qualityFuseRecoveryShellActionCapability(
            @Value("${scholarsense.ingestion-quality.recovery-runtime-enabled:false}")
                    boolean recoveryRuntimeEnabled) {
        AuthorizedShellCapabilityState state = recoveryRuntimeEnabled
                ? AuthorizedShellCapabilityState.AVAILABLE
                : AuthorizedShellCapabilityState.NOT_INSTALLED;
        return () -> List.of(new AuthorizedShellActionCapability(
                "quality-fuse.recover", state, Set.of("R6-DATA-OWNER")));
    }

    @Bean
    JdbcSubjectWindowRecomputeStore jdbcSubjectWindowRecomputeStore(
            JdbcTemplate jdbc, ObjectMapper json,
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec traceCodec,
            PostgreSqlConnectionProfile ingestionQualityOnlinePostgreSqlConnectionProfile) {
        return new JdbcSubjectWindowRecomputeStore(jdbc, json, currentTrace, traceCodec);
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
