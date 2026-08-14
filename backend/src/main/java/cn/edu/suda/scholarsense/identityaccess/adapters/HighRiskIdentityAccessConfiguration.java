package cn.edu.suda.scholarsense.identityaccess.adapters;

import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.HighRiskApprovalExpiryScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.HighRiskRetentionScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.HighRiskExecutionLeaseExpiryScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HmacHighRiskEvidenceSignatureAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcCurrentNaturalPersonBindingQueryAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcCurrentNaturalPersonPrincipalQueryAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcHighRiskApprovalEvidenceQueryAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcHighRiskApprovalRepository;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcHighRiskExecutionLeaseRepository;
import cn.edu.suda.scholarsense.identityaccess.api.CurrentNaturalPersonBindingQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.CurrentNaturalPersonPrincipalQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.RecoveryCheckerBindingResolver;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalEvidenceQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalPort;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalService;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskExecutionAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskExecutionAuthorizationService;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskApprovalUseCase;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskEvidenceSignaturePort;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskExecutionAuthorizationUseCase;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskIdentityFactoryPort;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskTrustedTimePort;
import cn.edu.suda.scholarsense.identityaccess.application.UuidV7;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import cn.edu.suda.scholarsense.rulegovernance.api.RuleVersionBusinessOwnerBindingQueryPort;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Production assembly for the identity-access-owned HRAP and durable lease protocol. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "scholarsense.identity.high-risk-enabled", havingValue = "true")
public class HighRiskIdentityAccessConfiguration {
    @Bean
    JdbcHighRiskApprovalRepository highRiskApprovalRepository(
            JdbcTemplate jdbc,
            ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        return new JdbcHighRiskApprovalRepository(jdbc, json, transactionManager);
    }

    @Bean
    JdbcHighRiskExecutionLeaseRepository highRiskExecutionLeaseRepository(
            JdbcTemplate jdbc,
            ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        return new JdbcHighRiskExecutionLeaseRepository(jdbc, json, transactionManager);
    }

    @Bean
    HighRiskApprovalEvidenceQueryPort highRiskApprovalEvidenceQueryPort(
            JdbcTemplate jdbc, ObjectMapper json) {
        return new JdbcHighRiskApprovalEvidenceQueryAdapter(jdbc, json);
    }

    @Bean
    CurrentNaturalPersonBindingQueryPort currentNaturalPersonBindingQueryPort(
            JdbcTemplate jdbc, ObjectMapper json) {
        return new JdbcCurrentNaturalPersonBindingQueryAdapter(jdbc, json);
    }

    @Bean
    CurrentNaturalPersonPrincipalQueryPort currentNaturalPersonPrincipalQueryPort(
            JdbcTemplate jdbc) {
        return new JdbcCurrentNaturalPersonPrincipalQueryAdapter(jdbc);
    }

    @Bean
    RecoveryCheckerBindingResolver recoveryCheckerBindingResolver(
            RuleVersionBusinessOwnerBindingQueryPort owners,
            CurrentNaturalPersonBindingQueryPort persons) {
        return new RecoveryCheckerBindingResolver(owners, persons);
    }

    @Bean
    HighRiskEvidenceSignaturePort highRiskEvidenceSignatures(
            @Value("${scholarsense.identity.high-risk-signing-key-path}") String keyPath,
            @Value("${scholarsense.identity.high-risk-signing-key-version}") String keyVersion) {
        return HmacHighRiskEvidenceSignatureAdapter.fromMountedKey(
                Path.of(keyPath).normalize(), keyVersion);
    }

    @Bean
    HighRiskTrustedTimePort highRiskTrustedTime(TrustedTimeSource time) {
        return () -> time.now().instant();
    }

    @Bean
    HighRiskIdentityFactoryPort highRiskIdentityFactory(HighRiskTrustedTimePort time) {
        return () -> UUID.fromString(UuidV7.generate(time.now()));
    }

    @Bean
    HighRiskApprovalUseCase highRiskApprovalUseCase(
            JdbcHighRiskApprovalRepository repository,
            HighRiskIdentityFactoryPort identities,
            HighRiskEvidenceSignaturePort signatures) {
        return new HighRiskApprovalUseCase(repository, identities, signatures);
    }

    @Bean
    HighRiskApprovalPort highRiskApprovalPort(HighRiskApprovalUseCase useCase) {
        return new HighRiskApprovalService(useCase);
    }

    @Bean
    HighRiskExecutionAuthorizationUseCase highRiskExecutionAuthorizationUseCase(
            JdbcHighRiskApprovalRepository approvals,
            JdbcHighRiskExecutionLeaseRepository leases,
            HighRiskIdentityFactoryPort identities,
            HighRiskEvidenceSignaturePort signatures,
            HighRiskTrustedTimePort time) {
        return new HighRiskExecutionAuthorizationUseCase(
                approvals, leases, identities, signatures, time);
    }

    @Bean
    HighRiskExecutionAuthorizationPort highRiskExecutionAuthorizationPort(
            HighRiskExecutionAuthorizationUseCase useCase) {
        return new HighRiskExecutionAuthorizationService(useCase);
    }

    @Bean
    HighRiskApprovalExpiryScheduler highRiskApprovalExpiryScheduler(
            HighRiskApprovalUseCase approvals, HighRiskTrustedTimePort time) {
        return new HighRiskApprovalExpiryScheduler(approvals, time);
    }

    @Bean
    HighRiskExecutionLeaseExpiryScheduler highRiskExecutionLeaseExpiryScheduler(
            HighRiskExecutionAuthorizationUseCase authorizations,
            HighRiskTrustedTimePort time) {
        return new HighRiskExecutionLeaseExpiryScheduler(authorizations, time);
    }

    @Bean
    HighRiskRetentionScheduler highRiskRetentionScheduler(
            JdbcTemplate jdbc, HighRiskTrustedTimePort time) {
        return new HighRiskRetentionScheduler(jdbc, time);
    }
}
