package cn.edu.suda.scholarsense.identityaccess.adapters;

import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityAccessStore;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityEstablishmentTransactionAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcRefreshTransactionAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcSessionTransactionAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcSensitiveReadTransactionAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityAuditAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HttpRemoteIdentityProviderClient;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.KmsEnvelopeClient;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.KmsEnvelopeDecryptClient;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.KmsEnvelopeDecryptionAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.KmsEnvelopeEncryptionAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.RemoteLogoutScheduler;
import cn.edu.suda.scholarsense.identityaccess.application.AuthorizationRecalculationPort;
import cn.edu.suda.scholarsense.identityaccess.application.ContinuationService;
import cn.edu.suda.scholarsense.identityaccess.application.CurrentSessionService;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditFactFactory;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditTokenPort;
import cn.edu.suda.scholarsense.identityaccess.application.HostBootstrapService;
import cn.edu.suda.scholarsense.identityaccess.application.OidcSessionEstablishmentService;
import cn.edu.suda.scholarsense.identityaccess.application.PseudonymizationPort;
import cn.edu.suda.scholarsense.identityaccess.application.RemoteIdentityProviderClient;
import cn.edu.suda.scholarsense.identityaccess.application.RemoteLogoutProcessor;
import cn.edu.suda.scholarsense.identityaccess.application.SecureOpaqueCodeGenerator;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandService;
import cn.edu.suda.scholarsense.identityaccess.application.SessionRefreshService;
import cn.edu.suda.scholarsense.identityaccess.application.TokenCustodyService;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import cn.edu.suda.scholarsense.shared.time.EvidenceBoundTrustedTimeSource;
import cn.edu.suda.scholarsense.shared.time.TimeSynchronizationStatusProvider;
import cn.edu.suda.scholarsense.shared.time.TrustedClockConstraints;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import java.time.Clock;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
@EnableScheduling
public class IdentityAccessConfiguration {
    @Bean
    @ConditionalOnMissingBean(TrustedClockConstraints.class)
    TrustedClockConstraints identityTrustedClockConstraints() {
        return new TrustedClockConstraints("PP-1.0.0", 100);
    }

    @Bean
    @ConditionalOnMissingBean(TimeSynchronizationStatusProvider.class)
    TimeSynchronizationStatusProvider unavailableTimeSynchronizationStatusProvider() {
        return Optional::empty;
    }

    @Bean
    @ConditionalOnMissingBean(IdentityAuditTokenPort.class)
    IdentityAuditTokenPort unavailableIdentityAuditTokenPort() {
        return (domain, value) -> {
            throw new IllegalStateException("AUDIT_TOKENIZATION_BINDING_UNAVAILABLE");
        };
    }

    @Bean
    Clock identityClock() {
        return Clock.systemUTC();
    }

    @Bean
    TrustedTimeSource identityTrustedTimeSource(
            RuntimeConfiguration runtime,
            Clock clock,
            TrustedClockConstraints policy,
            TimeSynchronizationStatusProvider synchronizationStatus) {
        URI binding = URI.create(runtime.clockSourceReference());
        String sourceId = binding.getPath().substring(1);
        return new EvidenceBoundTrustedTimeSource(clock, sourceId, policy, synchronizationStatus);
    }

    @Bean
    JdbcIdentityAccessStore identityAccessStore(JdbcTemplate jdbc) {
        return new JdbcIdentityAccessStore(jdbc);
    }

    @Bean
    JdbcSessionTransactionAdapter identitySessionTransactions(PlatformTransactionManager manager) {
        return new JdbcSessionTransactionAdapter(new TransactionTemplate(manager));
    }

    @Bean
    JdbcIdentityEstablishmentTransactionAdapter identityEstablishmentTransactions(
            PlatformTransactionManager manager) {
        return new JdbcIdentityEstablishmentTransactionAdapter(new TransactionTemplate(manager));
    }

    @Bean
    JdbcRefreshTransactionAdapter identityRefreshTransactions(PlatformTransactionManager manager) {
        return new JdbcRefreshTransactionAdapter(new TransactionTemplate(manager));
    }

    @Bean
    JdbcSensitiveReadTransactionAdapter identitySensitiveReadTransactions(
            PlatformTransactionManager manager) {
        return new JdbcSensitiveReadTransactionAdapter(new TransactionTemplate(manager));
    }

    @Bean
    JdbcIdentityAuditAdapter identityAuditAdapter(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper json) {
        return new JdbcIdentityAuditAdapter(jdbc, new TransactionTemplate(manager), json);
    }

    @Bean
    IdentityAuditFactFactory identityAuditFactFactory(
            TrustedTimeSource timeSource,
            IdentityAuditTokenPort tokens) {
        return new IdentityAuditFactFactory(timeSource, tokens);
    }

    @Bean
    AuthorizationRecalculationPort authorizationRecalculationPort() {
        // Story 1.2 recalculates the active identity boundary. Object/role decisions arrive in 1.6/1.7.
        return (actorPseudonym, sessionPseudonym) -> true;
    }

    @Bean
    CurrentSessionService currentSessionService(
            JdbcIdentityAccessStore store,
            AuthorizationRecalculationPort authorization,
            IdentityAuditFactFactory auditFacts,
            IdentityAuditPort audit,
            JdbcSensitiveReadTransactionAdapter transactions,
            Clock clock) {
        return new CurrentSessionService(
                store, authorization, auditFacts, audit, transactions, clock);
    }

    @Bean
    ContinuationService continuationService(JdbcIdentityAccessStore store, Clock clock) {
        return new ContinuationService(store, new SecureOpaqueCodeGenerator("ct_"), clock);
    }

    @Bean
    HostBootstrapService hostBootstrapService(JdbcIdentityAccessStore store, Clock clock) {
        return new HostBootstrapService(store, new SecureOpaqueCodeGenerator("hb_"), clock);
    }

    @Bean
    SessionCommandService sessionCommandService(
            JdbcIdentityAccessStore store,
            IdentityAuditFactFactory auditFacts,
            IdentityAuditPort audit,
            JdbcSessionTransactionAdapter transactions,
            Clock clock,
            @Value("${scholarsense.identity.registration-id}") String registrationId) {
        return new SessionCommandService(
                store, store, auditFacts, audit, store, transactions, clock, registrationId);
    }

    @Bean
    OidcSessionEstablishmentService oidcSessionEstablishmentService(
            JdbcIdentityAccessStore store,
            JdbcIdentityEstablishmentTransactionAdapter transactions,
            TokenCustodyService custody,
            IdentityAuditFactFactory auditFacts,
            IdentityAuditPort audit,
            PseudonymizationPort pseudonyms,
            Clock clock) {
        return new OidcSessionEstablishmentService(
                store, custody, auditFacts, audit, transactions, pseudonyms,
                new SecureOpaqueCodeGenerator("rf_"), clock);
    }

    @Bean
    TokenCustodyService tokenCustodyService(
            KmsEnvelopeClient encryption,
            KmsEnvelopeDecryptClient decryption,
            JdbcIdentityAccessStore store) {
        return new TokenCustodyService(
                new KmsEnvelopeEncryptionAdapter(encryption),
                new KmsEnvelopeDecryptionAdapter(decryption),
                store);
    }

    @Bean
    SessionRefreshService sessionRefreshService(
            JdbcIdentityAccessStore store,
            TokenCustodyService custody,
            RemoteIdentityProviderClient identityProvider,
            IdentityAuditFactFactory auditFacts,
            IdentityAuditPort audit,
            JdbcRefreshTransactionAdapter transactions,
            Clock clock) {
        return new SessionRefreshService(
                store, custody, identityProvider, auditFacts, audit, transactions, clock);
    }

    @Bean
    RemoteLogoutProcessor remoteLogoutProcessor(
            JdbcIdentityAccessStore store,
            TokenCustodyService custody,
            RemoteIdentityProviderClient identityProvider,
            Clock clock) {
        return new RemoteLogoutProcessor(store, custody, identityProvider, clock);
    }

    @Bean
    RemoteLogoutScheduler remoteLogoutScheduler(RemoteLogoutProcessor processor) {
        return new RemoteLogoutScheduler(processor);
    }

    @Bean
    @ConditionalOnMissingBean(RemoteIdentityProviderClient.class)
    RemoteIdentityProviderClient remoteIdentityProviderClient(
            ClientRegistrationRepository registrations,
            ObjectMapper json,
            Clock clock,
            @Value("${scholarsense.identity.revocation-endpoint}") URI revocationEndpoint,
            @Value("${scholarsense.identity.end-session-endpoint}") URI endSessionEndpoint,
            @Value("${scholarsense.identity.post-logout-redirect-uri}") URI postLogoutRedirectUri) {
        return new HttpRemoteIdentityProviderClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                json, registrations, revocationEndpoint, endSessionEndpoint,
                postLogoutRedirectUri, clock);
    }
}
