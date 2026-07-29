package cn.edu.suda.scholarsense.identityaccess.adapters;

import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.IdentitySyncScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.IdentitySloCompensationScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HttpIdentityAuthoritySourceAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.IdentitySyncAuditAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAuthoritativeIdentityContextAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityAuditAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityReconciliationAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityReplayAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityProjectionRebuildAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentitySyncJobAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentitySyncRepository;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentitySyncTransactionAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.MicrometerIdentitySyncObservabilityAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.MountedIdentitySyncSecurityBindings;
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
import cn.edu.suda.scholarsense.identityaccess.application.WorkloadIdentityAuthenticationPort;
import cn.edu.suda.scholarsense.runtime.IdentityAuthorityRuntimeProfile;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.shared.time.EvidenceBoundTrustedTimeSource;
import cn.edu.suda.scholarsense.shared.time.TimeSynchronizationStatusProvider;
import cn.edu.suda.scholarsense.shared.time.TrustedClockConstraints;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
            IdentityAuthorityRuntimeProfile profile) {
        return new MountedIdentitySyncSecurityBindings(
                java.nio.file.Path.of(directory), profile);
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
            JdbcIdentitySyncRepository references) {
        return new HttpIdentityAuthoritySourceAdapter(
                http, json, profile, workloadIdentity, signatures,
                encryption, pseudonyms, time, references);
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
    IdentitySyncService identitySyncService(
            JdbcIdentitySyncRepository repository,
            JdbcIdentityReplayAdapter replay,
            JdbcIdentitySyncTransactionAdapter transactions,
            IdentitySyncAuditPort audit,
            IdentitySyncObservabilityPort observability,
            AuthorizationEffectivenessProbePort contexts,
            TrustedTimeSource time) {
        return new IdentitySyncService(
                repository, replay, transactions, audit, observability,
                contexts, repository, time);
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
            JdbcIdentitySyncRepository repository) {
        return new IdentitySyncWorker(
                jobs, source, sync::process, audit, observability,
                transactions, time, replay, repository);
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
}
