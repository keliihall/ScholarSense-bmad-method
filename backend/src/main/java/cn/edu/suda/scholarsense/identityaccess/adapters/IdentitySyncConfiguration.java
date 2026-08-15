package cn.edu.suda.scholarsense.identityaccess.adapters;

import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.IdentitySyncScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.AccessInvalidationScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.IdentitySloCompensationScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.ResponsibilityReconciliationScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.ResponsibilityReconciliationWorkerScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.ResponsibilitySloCompensationScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.ResponsibilitySyncScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.ResponsibilityV2CutoverScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HttpIdentityAuthoritySourceAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.AccessInvalidationEventJsonCodec;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.AccessInvalidationAuditAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.AccessInvalidationConsumerDatabase;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.AccessInvalidationDatabaseRoleVerifier;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HttpResponsibilityAuthoritySourceAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HttpResponsibilityFullSnapshotSourceAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.IdentitySyncAuditAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAuthoritativeIdentityContextAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityAuditAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityCascadeScopeReadBackAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationRepository;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationAppliedFactObserver;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationBackfillProcessor;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationConsumer;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationDeliveryRepository;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationFenceQueryAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationImpactResolver;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationJobRepository;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationReconciler;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationReconciliationChangePublisher;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.LocalAccessInvalidationTransportAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityReconciliationAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityReplayAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityProjectionRebuildAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentitySyncJobAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentitySyncRepository;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentitySyncTransactionAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcResponsibilityRecipientEvidenceAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcResponsibilityReconciliationAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcResponsibilitySloEvidenceAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcResponsibilitySyncRepository;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.MicrometerIdentitySyncObservabilityAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.MountedIdentitySyncSecurityBindings;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.ResponsibilityScopeQueryAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.ResponsibilityV2CutoverDatabase;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.ResponsibilityV2CutoverDatabaseRoleVerifier;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.UuidV7AccessInvalidationIdAdapter;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationPublisherService;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationExpiryWorker;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationImpactWorker;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationOutboxRelayProcessor;
import cn.edu.suda.scholarsense.identityaccess.application.AuthorizationEffectiveContext;
import cn.edu.suda.scholarsense.identityaccess.application.AuthorizationEffectivenessProbePort;
import cn.edu.suda.scholarsense.identityaccess.application.AuthorizationFreshness;
import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.EnvelopeEncryptionPort;
import cn.edu.suda.scholarsense.identityaccess.application.EnvelopeDecryptionPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditFactFactory;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditTokenPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuthoritySourcePort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityReconciliationService;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityProjectionRebuildService;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourceSignaturePort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncAuditPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncJobService;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncObservabilityPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySloCompensationService;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncService;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncWorker;
import cn.edu.suda.scholarsense.identityaccess.application.PseudonymizationPort;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityAuthoritySourcePort;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityFullSnapshotSourcePort;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityReconciliationService;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityReconciliationWorker;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySloCompensationService;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySloService;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySyncService;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2CutoverService;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2CutoverCommandSignaturePort;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySyncWorker;
import cn.edu.suda.scholarsense.identityaccess.application.WorkloadIdentityAuthenticationPort;
import cn.edu.suda.scholarsense.runtime.IdentityAuthorityRuntimeProfile;
import cn.edu.suda.scholarsense.runtime.RuntimeEnvironment;
import cn.edu.suda.scholarsense.runtime.ResponsibilityAuthorityRuntimeProfile;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.shared.time.EvidenceBoundTrustedTimeSource;
import cn.edu.suda.scholarsense.shared.time.TimeSynchronizationStatusProvider;
import cn.edu.suda.scholarsense.shared.time.TrustedClockConstraints;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import cn.edu.suda.scholarsense.shared.observability.TrustedHttpClientFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scholarsense.identity-sync.enabled", havingValue = "true")
@EnableScheduling
public class IdentitySyncConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock identitySyncClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(TrustedClockConstraints.class)
    TrustedClockConstraints identitySyncClockConstraints() {
        return new TrustedClockConstraints("PP-1.0.0", 100);
    }

    @Bean
    @ConditionalOnMissingBean(TimeSynchronizationStatusProvider.class)
    TimeSynchronizationStatusProvider unavailableIdentitySyncTimeStatus() {
        return Optional::empty;
    }

    @Bean
    @ConditionalOnMissingBean(TrustedTimeSource.class)
    TrustedTimeSource identitySyncTrustedTime(
            RuntimeConfiguration runtime,
            Clock clock,
            TrustedClockConstraints constraints,
            TimeSynchronizationStatusProvider status) {
        URI binding = URI.create(runtime.clockSourceReference());
        return new EvidenceBoundTrustedTimeSource(
                clock, binding.getPath().substring(1), constraints, status);
    }

    @Bean
    IdentityAuthorityRuntimeProfile identityAuthorityRuntimeProfile(
            RuntimeConfiguration runtime) {
        return IdentityAuthorityRuntimeProfile.from(runtime);
    }

    @Bean
    ResponsibilityAuthorityRuntimeProfile responsibilityAuthorityRuntimeProfile(
            RuntimeConfiguration runtime) {
        return ResponsibilityAuthorityRuntimeProfile.from(runtime);
    }

    @Bean
    @ConditionalOnMissingBean(HttpClient.class)
    HttpClient identityAuthorityHttpClient(
            IdentityAuthorityRuntimeProfile profile) {
        return HttpClient.newBuilder()
                .connectTimeout(profile.connectTimeout())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "scholarsense.identity-sync.security-directory")
    MountedIdentitySyncSecurityBindings mountedIdentitySyncSecurityBindings(
            @Value("${scholarsense.identity-sync.security-directory}") String directory,
            IdentityAuthorityRuntimeProfile profile,
            ResponsibilityAuthorityRuntimeProfile responsibilityProfile) {
        return new MountedIdentitySyncSecurityBindings(
                java.nio.file.Path.of(directory),
                profile,
                responsibilityProfile);
    }

    @Bean
    @ConditionalOnMissingBean(IdentityAuthoritySourcePort.class)
    HttpIdentityAuthoritySourceAdapter identityAuthoritySource(
            HttpClient http,
            ObjectMapper json,
            IdentityAuthorityRuntimeProfile profile,
            WorkloadIdentityAuthenticationPort workloadIdentity,
            IdentitySourceSignaturePort signatures,
            EnvelopeEncryptionPort encryption,
            PseudonymizationPort pseudonyms,
            TrustedTimeSource time,
            JdbcIdentitySyncRepository references,
            TrustedHttpClientFactory trustedHttp) {
        return new HttpIdentityAuthoritySourceAdapter(
                trustedHttp.wrap(http, profile), json, profile, workloadIdentity, signatures,
                encryption, pseudonyms, time, references);
    }

    @Bean
    @ConditionalOnMissingBean(ResponsibilityAuthoritySourcePort.class)
    HttpResponsibilityAuthoritySourceAdapter responsibilityAuthoritySource(
            HttpClient http,
            ObjectMapper json,
            ResponsibilityAuthorityRuntimeProfile profile,
            WorkloadIdentityAuthenticationPort workloadIdentity,
            IdentitySourceSignaturePort signatures,
            EnvelopeEncryptionPort encryption,
            PseudonymizationPort pseudonyms,
            TrustedTimeSource time,
            TrustedHttpClientFactory trustedHttp) {
        return new HttpResponsibilityAuthoritySourceAdapter(
                trustedHttp.wrap(http, profile),
                json,
                profile,
                workloadIdentity,
                signatures,
                encryption,
                pseudonyms,
                time);
    }

    @Bean
    @ConditionalOnMissingBean(ResponsibilityFullSnapshotSourcePort.class)
    HttpResponsibilityFullSnapshotSourceAdapter responsibilityFullSnapshotSource(
            HttpClient http,
            ObjectMapper json,
            ResponsibilityAuthorityRuntimeProfile profile,
            WorkloadIdentityAuthenticationPort workloadIdentity,
            IdentitySourceSignaturePort signatures,
            TrustedHttpClientFactory trustedHttp) {
        return new HttpResponsibilityFullSnapshotSourceAdapter(
                trustedHttp.wrap(http, profile),
                json, profile, workloadIdentity, signatures);
    }

    @Bean
    JdbcIdentitySyncRepository identitySyncRepository(JdbcTemplate jdbc) {
        return new JdbcIdentitySyncRepository(jdbc);
    }

    @Bean
    JdbcIdentitySyncJobAdapter identitySyncJobs(
            JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcIdentitySyncJobAdapter(
                jdbc, new TransactionTemplate(manager));
    }

    @Bean
    JdbcIdentitySyncTransactionAdapter identitySyncTransactions(
            PlatformTransactionManager manager) {
        return new JdbcIdentitySyncTransactionAdapter(
                new TransactionTemplate(manager));
    }

    @Bean
    JdbcAccessInvalidationRepository accessInvalidationRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper json) {
        return new JdbcAccessInvalidationRepository(
                jdbc, new TransactionTemplate(manager), json);
    }

    @Bean
    AccessInvalidationEventJsonCodec accessInvalidationEvents(
            ObjectMapper json) {
        return new AccessInvalidationEventJsonCodec(json);
    }

    @Bean
    UuidV7AccessInvalidationIdAdapter accessInvalidationIds() {
        return new UuidV7AccessInvalidationIdAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(AccessInvalidationConsumerDatabase.class)
    AccessInvalidationConsumerDatabase accessInvalidationConsumerDatabase(
            @Value("${scholarsense.identity-sync.access-invalidation-consumer.jdbc-url}")
                    String jdbcUrl,
            @Value("${scholarsense.identity-sync.access-invalidation-consumer.username}")
                    String username,
            @Value("${scholarsense.identity-sync.access-invalidation-consumer.password}")
                    String password) {
        return AccessInvalidationConsumerDatabase.connect(
                jdbcUrl, username, password);
    }

    @Bean
    @ConditionalOnMissingBean(ResponsibilityV2CutoverDatabase.class)
    ResponsibilityV2CutoverDatabase responsibilityV2CutoverDatabase(
            @Value("${scholarsense.identity-sync.responsibility-v2-cutover.jdbc-url}")
                    String jdbcUrl,
            @Value("${scholarsense.identity-sync.responsibility-v2-cutover.username}")
                    String username,
            @Value("${scholarsense.identity-sync.responsibility-v2-cutover.password}")
                    String password) {
        return ResponsibilityV2CutoverDatabase.connect(
                jdbcUrl, username, password);
    }

    @Bean
    SmartInitializingSingleton accessInvalidationDatabaseRoleIsolation(
            JdbcTemplate producerJdbc,
            PlatformTransactionManager producerManager,
            AccessInvalidationConsumerDatabase consumerDatabase,
            RuntimeConfiguration runtime,
            @Value("${scholarsense.identity-sync.access-invalidation-consumer.verify-role-isolation:true}")
                    boolean verifyRoleIsolation) {
        return () -> {
            var producerTransactions =
                    new TransactionTemplate(producerManager);
            AccessInvalidationDatabaseRoleVerifier
                    .verifyProducerTransactionBoundary(
                            producerJdbc, producerTransactions);
            if (!verifyRoleIsolation) {
                if (runtime.environment() != RuntimeEnvironment.TEST) {
                    throw new IllegalStateException(
                            "ACCESS_INVALIDATION_DATABASE_ROLE_VERIFICATION_REQUIRED");
                }
                return;
            }
            AccessInvalidationDatabaseRoleVerifier.verify(
                    producerJdbc,
                    producerTransactions,
                    consumerDatabase);
        };
    }

    @Bean
    SmartInitializingSingleton responsibilityV2CutoverDatabaseRoleIsolation(
            JdbcTemplate producerJdbc,
            PlatformTransactionManager producerManager,
            AccessInvalidationConsumerDatabase consumerDatabase,
            ResponsibilityV2CutoverDatabase cutoverDatabase,
            RuntimeConfiguration runtime,
            @Value("${scholarsense.identity-sync.responsibility-v2-cutover.verify-role-isolation:true}")
                    boolean verifyRoleIsolation) {
        return () -> {
            if (!verifyRoleIsolation) {
                if (runtime.environment() != RuntimeEnvironment.TEST) {
                    throw new IllegalStateException(
                            "RESPONSIBILITY_V2_CUTOVER_DATABASE_ROLE_VERIFICATION_REQUIRED");
                }
                return;
            }
            AccessInvalidationDatabaseRoleVerifier
                    .verifyResponsibilityV2Cutover(
                            cutoverDatabase.jdbc(),
                            cutoverDatabase.transactions());
            ResponsibilityV2CutoverDatabaseRoleVerifier.verify(
                    producerJdbc,
                    new TransactionTemplate(producerManager),
                    consumerDatabase,
                    cutoverDatabase);
        };
    }

    @Bean
    JdbcAccessInvalidationJobRepository accessInvalidationJobs(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        return new JdbcAccessInvalidationJobRepository(
                jdbc, new TransactionTemplate(manager));
    }

    @Bean
    AccessInvalidationPublisherService accessInvalidationPublisher(
            JdbcAccessInvalidationRepository repository,
            JdbcAccessInvalidationJobRepository jobs,
            AccessInvalidationEventJsonCodec events,
            UuidV7AccessInvalidationIdAdapter identifiers,
            AccessInvalidationAuditAdapter audit) {
        return new AccessInvalidationPublisherService(
                repository,
                repository,
                jobs,
                events,
                identifiers,
                audit);
    }

    @Bean
    JdbcAccessInvalidationConsumer accessInvalidationConsumer(
            AccessInvalidationConsumerDatabase consumerDatabase,
            UuidV7AccessInvalidationIdAdapter identifiers,
            ObjectMapper json) {
        return new JdbcAccessInvalidationConsumer(
                consumerDatabase.jdbc(),
                consumerDatabase.transactions(),
                identifiers,
                json);
    }

    @Bean
    JdbcAccessInvalidationBackfillProcessor accessInvalidationBackfills(
            AccessInvalidationConsumerDatabase consumerDatabase,
            JdbcAccessInvalidationRepository repository,
            JdbcAccessInvalidationConsumer consumer,
            AccessInvalidationEventJsonCodec events) {
        return new JdbcAccessInvalidationBackfillProcessor(
                consumerDatabase.jdbc(),
                consumerDatabase.transactions(),
                repository,
                consumer,
                events);
    }

    @Bean
    JdbcAccessInvalidationDeliveryRepository
            accessInvalidationDeliveryRepository(
                    JdbcTemplate jdbc,
                    PlatformTransactionManager manager) {
        return new JdbcAccessInvalidationDeliveryRepository(
                jdbc, new TransactionTemplate(manager));
    }

    @Bean
    LocalAccessInvalidationTransportAdapter
            accessInvalidationTransport(
                    JdbcAccessInvalidationRepository repository,
                    JdbcAccessInvalidationConsumer consumer,
                    TrustedTimeSource time) {
        return new LocalAccessInvalidationTransportAdapter(
                repository, consumer, time);
    }

    @Bean
    AccessInvalidationOutboxRelayProcessor accessInvalidationRelay(
            JdbcAccessInvalidationDeliveryRepository delivery,
            LocalAccessInvalidationTransportAdapter transport,
            UuidV7AccessInvalidationIdAdapter identifiers) {
        return new AccessInvalidationOutboxRelayProcessor(
                delivery,
                transport,
                identifiers,
                "access-invalidation-relay");
    }

    @Bean
    JdbcAccessInvalidationImpactResolver
            accessInvalidationImpactResolver(
                    JdbcTemplate jdbc,
                    JdbcAccessInvalidationRepository repository,
                    UuidV7AccessInvalidationIdAdapter identifiers) {
        return new JdbcAccessInvalidationImpactResolver(
                jdbc, repository, identifiers);
    }

    @Bean
    AccessInvalidationImpactWorker accessInvalidationImpactWorker(
            JdbcAccessInvalidationJobRepository jobs,
            JdbcAccessInvalidationRepository repository,
            JdbcAccessInvalidationImpactResolver resolver,
            AccessInvalidationEventJsonCodec events,
            UuidV7AccessInvalidationIdAdapter identifiers,
            JdbcIdentitySyncTransactionAdapter transactions,
            AccessInvalidationAuditAdapter audit) {
        return new AccessInvalidationImpactWorker(
                jobs,
                repository,
                resolver,
                events,
                identifiers,
                transactions,
                audit);
    }

    @Bean
    AccessInvalidationExpiryWorker accessInvalidationExpiryWorker(
            JdbcAccessInvalidationJobRepository jobs,
            JdbcAccessInvalidationRepository repository,
            AccessInvalidationEventJsonCodec events,
            UuidV7AccessInvalidationIdAdapter identifiers,
            JdbcIdentitySyncTransactionAdapter transactions,
            AccessInvalidationAuditAdapter audit) {
        return new AccessInvalidationExpiryWorker(
                jobs,
                repository,
                events,
                identifiers,
                transactions,
                audit);
    }

    @Bean
    JdbcAccessInvalidationAppliedFactObserver
            accessInvalidationAppliedFactObserver(
                    JdbcTemplate jdbc,
                    PlatformTransactionManager manager) {
        return new JdbcAccessInvalidationAppliedFactObserver(
                jdbc, new TransactionTemplate(manager));
    }

    @Bean
    JdbcAccessInvalidationReconciler accessInvalidationReconciler(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            UuidV7AccessInvalidationIdAdapter identifiers,
            ObjectMapper json) {
        return new JdbcAccessInvalidationReconciler(
                jdbc,
                new TransactionTemplate(manager),
                identifiers,
                json);
    }

    @Bean
    JdbcAccessInvalidationReconciliationChangePublisher
            accessInvalidationReconciliationChanges(
                    JdbcTemplate jdbc,
                    JdbcAccessInvalidationRepository repository,
                    AccessInvalidationEventJsonCodec events,
                    UuidV7AccessInvalidationIdAdapter identifiers,
                    AccessInvalidationAuditAdapter audit) {
        return new JdbcAccessInvalidationReconciliationChangePublisher(
                jdbc, repository, events, identifiers, audit);
    }

    @Bean
    AccessInvalidationScheduler accessInvalidationScheduler(
            AccessInvalidationOutboxRelayProcessor relay,
            AccessInvalidationImpactWorker impacts,
            AccessInvalidationExpiryWorker expiry,
            JdbcAccessInvalidationBackfillProcessor backfills,
            JdbcAccessInvalidationAppliedFactObserver appliedFacts,
            JdbcAccessInvalidationReconciler reconciliation,
            TrustedTimeSource time) {
        return new AccessInvalidationScheduler(
                relay,
                impacts,
                expiry,
                backfills,
                appliedFacts,
                reconciliation,
                time);
    }

    @Bean
    JdbcIdentityReplayAdapter identityReplay(
            JdbcTemplate jdbc, TrustedTimeSource time) {
        return new JdbcIdentityReplayAdapter(jdbc, time);
    }

    @Bean
    JdbcIdentityProjectionRebuildAdapter identityProjectionRebuildStore(
            JdbcTemplate jdbc,
            JdbcIdentitySyncRepository repository,
            JdbcIdentitySyncJobAdapter jobs) {
        return new JdbcIdentityProjectionRebuildAdapter(
                jdbc, repository, jobs);
    }

    @Bean
    IdentityProjectionRebuildService identityProjectionRebuildService(
            JdbcIdentityProjectionRebuildAdapter rebuild,
            HttpIdentityAuthoritySourceAdapter normalization,
            EnvelopeDecryptionPort decryption,
            JdbcIdentitySyncTransactionAdapter transactions,
            TrustedTimeSource time) {
        return new IdentityProjectionRebuildService(
                rebuild, normalization, decryption, transactions, time);
    }

    @Bean
    @ConditionalOnMissingBean(IdentityAuditPort.class)
    IdentityAuditPort identitySyncLocalAudit(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper json) {
        return new JdbcIdentityAuditAdapter(
                jdbc, new TransactionTemplate(manager), json);
    }

    @Bean
    @ConditionalOnMissingBean(IdentityAuditFactFactory.class)
    IdentityAuditFactFactory identitySyncAuditFacts(
            TrustedTimeSource time, IdentityAuditTokenPort tokens) {
        return new IdentityAuditFactFactory(time, tokens);
    }

    @Bean
    IdentitySyncAuditPort identitySyncAudit(
            IdentityAuditFactFactory facts, IdentityAuditPort audit) {
        return new IdentitySyncAuditAdapter(facts, audit);
    }

    @Bean
    AccessInvalidationAuditAdapter accessInvalidationAudit(
            IdentityAuditFactFactory facts, IdentityAuditPort audit) {
        return new AccessInvalidationAuditAdapter(facts, audit);
    }

    @Bean
    IdentitySyncObservabilityPort identitySyncObservability(
            MeterRegistry registry, JdbcTemplate jdbc) {
        return new MicrometerIdentitySyncObservabilityAdapter(registry, jdbc);
    }

    @Bean
    JdbcAuthoritativeIdentityContextAdapter identitySyncContextReader(
            JdbcTemplate jdbc, Clock clock) {
        return new JdbcAuthoritativeIdentityContextAdapter(
                jdbc, clock, Duration.ofMinutes(15));
    }

    @Bean
    AuthorizationEffectivenessProbePort identityAuthorizationProbe(
            JdbcAuthoritativeIdentityContextAdapter contexts) {
        return subject -> contexts.findCurrent(subject).map(context ->
                new AuthorizationEffectiveContext(
                        context.accountId(),
                        context.sourceVersion(),
                        context.sourceWatermark(),
                        switch (context.freshness()) {
                            case FRESH -> AuthorizationFreshness.FRESH;
                            case DEGRADED -> AuthorizationFreshness.DEGRADED;
                            case STALE -> AuthorizationFreshness.STALE;
                        }));
    }

    @Bean
    JdbcIdentityCascadeScopeReadBackAdapter identityCascadeScopeReadBack(
            JdbcTemplate jdbc,
            ResponsibilityScopeQueryAdapter responsibilityScopes) {
        return new JdbcIdentityCascadeScopeReadBackAdapter(
                jdbc, responsibilityScopes);
    }

    @Bean
    IdentitySyncService identitySyncService(
            JdbcIdentitySyncRepository repository,
            JdbcIdentityReplayAdapter replay,
            JdbcIdentitySyncTransactionAdapter transactions,
            IdentitySyncAuditPort audit,
            IdentitySyncObservabilityPort observability,
            AuthorizationEffectivenessProbePort contexts,
            TrustedTimeSource time,
            AccessInvalidationPublisherService invalidations,
            JdbcIdentityCascadeScopeReadBackAdapter cascadeReadBack) {
        return new IdentitySyncService(
                repository, replay, transactions, audit, observability,
                contexts, repository, time, invalidations, cascadeReadBack);
    }

    @Bean
    IdentitySyncJobService identitySyncJobService(
            JdbcIdentitySyncJobAdapter jobs, TrustedTimeSource time) {
        return new IdentitySyncJobService(jobs, time);
    }

    @Bean
    IdentitySyncWorker identitySyncWorker(
            JdbcIdentitySyncJobAdapter jobs,
            IdentityAuthoritySourcePort source,
            IdentitySyncService sync,
            IdentitySyncAuditPort audit,
            IdentitySyncObservabilityPort observability,
            JdbcIdentitySyncTransactionAdapter transactions,
            TrustedTimeSource time,
            JdbcIdentityReplayAdapter replay,
            JdbcIdentitySyncRepository repository,
            IdentityAuthorityRuntimeProfile profile) {
        return new IdentitySyncWorker(
                jobs, source, sync::process, audit, observability,
                transactions, time, replay, repository,
                new CheckpointKey(
                        profile.sourceId(),
                        profile.feedId(),
                        profile.partitionId(),
                        profile.consumerProjection()));
    }

    @Bean
    IdentityReconciliationService identityReconciliationService(
            JdbcTemplate jdbc,
            IdentitySyncAuditPort audit,
            TrustedTimeSource time,
            JdbcIdentitySyncTransactionAdapter transactions) {
        return new IdentityReconciliationService(
                new JdbcIdentityReconciliationAdapter(jdbc), audit, time,
                transactions);
    }

    @Bean
    IdentitySloCompensationService identitySloCompensationService(
            JdbcIdentitySyncRepository repository, TrustedTimeSource time) {
        return new IdentitySloCompensationService(repository, time);
    }

    @Bean
    IdentitySloCompensationScheduler identitySloCompensationScheduler(
            IdentitySloCompensationService service) {
        return new IdentitySloCompensationScheduler(service);
    }

    @Bean
    IdentitySyncScheduler identitySyncScheduler(
            IdentitySyncJobService jobs,
            IdentitySyncWorker worker,
            IdentityAuthorityRuntimeProfile profile) {
        return new IdentitySyncScheduler(
                jobs,
                worker,
                new CheckpointKey(
                        profile.sourceId(),
                        profile.feedId(),
                        profile.partitionId(),
                        profile.consumerProjection()),
                profile.retryBudget());
    }

    @Bean
    JdbcResponsibilitySyncRepository responsibilitySyncRepository(
            JdbcTemplate jdbc) {
        return new JdbcResponsibilitySyncRepository(jdbc);
    }

    @Bean
    ResponsibilityV2CutoverService responsibilityV2CutoverService(
            ResponsibilityV2CutoverDatabase database,
            TrustedTimeSource time,
            AccessInvalidationEventJsonCodec events,
            UuidV7AccessInvalidationIdAdapter identifiers,
            ResponsibilityFullSnapshotSourcePort snapshots,
            IdentityAuditFactFactory auditFacts,
            ObjectMapper json,
            ResponsibilityAuthorityRuntimeProfile profile,
            ResponsibilityV2CutoverCommandSignaturePort signatures) {
        var repository = new JdbcResponsibilitySyncRepository(
                database.jdbc());
        var transactions = new JdbcIdentitySyncTransactionAdapter(
                database.transactions());
        var invalidations = new JdbcAccessInvalidationRepository(
                database.jdbc(), database.transactions(), json);
        var expiryJobs = new JdbcAccessInvalidationJobRepository(
                database.jdbc(), database.transactions());
        var auditStore = new JdbcIdentityAuditAdapter(
                database.jdbc(), database.transactions(), json);
        return new ResponsibilityV2CutoverService(
                repository,
                transactions,
                time,
                invalidations,
                events,
                identifiers,
                new AccessInvalidationAuditAdapter(
                        auditFacts, auditStore),
                expiryJobs,
                snapshots,
                new IdentitySyncAuditAdapter(
                        auditFacts, auditStore),
                profile.contractProfileDigest(),
                signatures);
    }

    @Bean
    JdbcResponsibilityRecipientEvidenceAdapter
            responsibilityRecipientEvidence(JdbcTemplate jdbc) {
        return new JdbcResponsibilityRecipientEvidenceAdapter(jdbc);
    }

    @Bean
    JdbcAccessInvalidationFenceQueryAdapter accessInvalidationFenceQuery(
            JdbcTemplate jdbc) {
        return new JdbcAccessInvalidationFenceQueryAdapter(jdbc);
    }

    @Bean
    ResponsibilityScopeQueryAdapter responsibilityScopeQuery(
            JdbcResponsibilitySyncRepository repository,
            JdbcResponsibilityRecipientEvidenceAdapter evidence,
            JdbcAccessInvalidationFenceQueryAdapter invalidationFence,
            TrustedTimeSource time,
            ResponsibilityAuthorityRuntimeProfile profile) {
        return new ResponsibilityScopeQueryAdapter(
                repository,
                evidence,
                time,
                responsibilityKey(profile),
                invalidationFence);
    }

    @Bean
    JdbcResponsibilitySloEvidenceAdapter responsibilitySloEvidence(
            JdbcTemplate jdbc) {
        return new JdbcResponsibilitySloEvidenceAdapter(jdbc);
    }

    @Bean
    ResponsibilitySloService responsibilitySloService(
            ResponsibilityScopeQueryAdapter readBack,
            JdbcResponsibilitySloEvidenceAdapter evidence,
            IdentitySyncObservabilityPort observability,
            TrustedTimeSource time) {
        return new ResponsibilitySloService(
                readBack, evidence, observability, time);
    }

    @Bean
    ResponsibilitySloCompensationService
            responsibilitySloCompensationService(
                    JdbcResponsibilitySloEvidenceAdapter evidence,
                    TrustedTimeSource time) {
        return new ResponsibilitySloCompensationService(evidence, time);
    }

    @Bean
    ResponsibilitySloCompensationScheduler
            responsibilitySloCompensationScheduler(
                    ResponsibilitySloCompensationService service) {
        return new ResponsibilitySloCompensationScheduler(service);
    }

    @Bean
    ResponsibilitySyncService responsibilitySyncService(
            JdbcResponsibilitySyncRepository repository,
            JdbcResponsibilityRecipientEvidenceAdapter evidence,
            JdbcIdentityReplayAdapter replay,
            JdbcIdentitySyncTransactionAdapter transactions,
            IdentitySyncAuditPort audit,
            IdentitySyncObservabilityPort observability,
            TrustedTimeSource time,
            AccessInvalidationPublisherService invalidations,
            ResponsibilityAuthorityRuntimeProfile profile) {
        return new ResponsibilitySyncService(
                repository,
                evidence,
                replay,
                transactions,
                audit,
                observability,
                time,
                invalidations,
                profile.effectiveAt());
    }

    @Bean
    ResponsibilitySyncWorker responsibilitySyncWorker(
            JdbcIdentitySyncJobAdapter jobs,
            ResponsibilityAuthoritySourcePort source,
            ResponsibilitySyncService sync,
            JdbcResponsibilitySyncRepository repository,
            IdentitySyncAuditPort audit,
            IdentitySyncObservabilityPort observability,
            JdbcIdentitySyncTransactionAdapter transactions,
            TrustedTimeSource time,
            JdbcIdentityReplayAdapter replay,
            ResponsibilitySloService slo,
            ResponsibilityAuthorityRuntimeProfile profile) {
        return new ResponsibilitySyncWorker(
                jobs,
                source,
                sync,
                repository,
                audit,
                observability,
                transactions,
                time,
                replay,
                slo,
                responsibilityKey(profile),
                profile.effectiveAt());
    }

    @Bean
    ResponsibilityV2CutoverScheduler responsibilityV2CutoverScheduler(
            ResponsibilityV2CutoverService cutover,
            ResponsibilityV2CutoverDatabase database,
            TrustedTimeSource time,
            ResponsibilityAuthorityRuntimeProfile profile,
            ResponsibilityV2CutoverCommandSignaturePort signatures) {
        return new ResponsibilityV2CutoverScheduler(
                cutover,
                new JdbcResponsibilitySyncRepository(database.jdbc()),
                time,
                profile,
                responsibilityKey(profile),
                signatures);
    }

    @Bean
    ResponsibilitySyncScheduler responsibilitySyncScheduler(
            IdentitySyncJobService jobs,
            ResponsibilitySyncWorker worker,
            ResponsibilityAuthorityRuntimeProfile profile) {
        return new ResponsibilitySyncScheduler(
                jobs,
                worker,
                responsibilityKey(profile),
                profile.retryBudget());
    }

    @Bean
    JdbcResponsibilityReconciliationAdapter
            responsibilityReconciliationStore(
                    JdbcTemplate jdbc,
                    PlatformTransactionManager manager,
                    TrustedTimeSource time,
                    ResponsibilityAuthorityRuntimeProfile profile) {
        return new JdbcResponsibilityReconciliationAdapter(
                jdbc,
                new TransactionTemplate(manager),
                time,
                profile.retryBudget());
    }

    @Bean
    ResponsibilityReconciliationService responsibilityReconciliationService(
            ResponsibilityFullSnapshotSourcePort source,
            JdbcResponsibilityReconciliationAdapter store,
            IdentitySyncAuditPort audit,
            JdbcIdentitySyncTransactionAdapter transactions,
            TrustedTimeSource time,
            JdbcAccessInvalidationReconciliationChangePublisher
                    invalidations) {
        return new ResponsibilityReconciliationService(
                source,
                store,
                audit,
                transactions,
                time,
                invalidations);
    }

    @Bean
    ResponsibilityReconciliationWorker responsibilityReconciliationWorker(
            JdbcResponsibilityReconciliationAdapter jobs,
            ResponsibilityReconciliationService service,
            JdbcIdentitySyncTransactionAdapter transactions,
            TrustedTimeSource time,
            IdentitySyncAuditPort audit,
            ResponsibilityAuthorityRuntimeProfile profile) {
        return new ResponsibilityReconciliationWorker(
                jobs,
                service,
                transactions,
                time,
                audit,
                responsibilityKey(profile));
    }

    @Bean
    ResponsibilityReconciliationScheduler
            responsibilityReconciliationScheduler(
                    JdbcResponsibilityReconciliationAdapter jobs,
                    TrustedTimeSource time,
                    ResponsibilityAuthorityRuntimeProfile profile) {
        return new ResponsibilityReconciliationScheduler(
                jobs, responsibilityKey(profile), time);
    }

    @Bean
    ResponsibilityReconciliationWorkerScheduler
            responsibilityReconciliationWorkerScheduler(
                    ResponsibilityReconciliationWorker worker) {
        return new ResponsibilityReconciliationWorkerScheduler(
                worker);
    }

    private static CheckpointKey responsibilityKey(
            ResponsibilityAuthorityRuntimeProfile profile) {
        return new CheckpointKey(
                profile.sourceId(),
                profile.feedId(),
                profile.partitionId(),
                profile.consumerProjection());
    }
}
