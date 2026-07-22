package cn.edu.suda.scholarsense.identityaccess.adapters;

import cn.edu.suda.scholarsense.auditoperations.api.AuditLedgerIngressPort;
import cn.edu.suda.scholarsense.auditoperations.api.AuditProducerBacklogPort;
import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.IdentityAuditRelayScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityAuditBacklogSource;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcIdentityAuditRelayRepository;
import cn.edu.suda.scholarsense.identityaccess.application.AuditOutboxRelayProcessor;
import cn.edu.suda.scholarsense.identityaccess.application.AuditRelayClock;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import cn.edu.suda.scholarsense.runtime.AuditRuntimeProfile;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Producer-owned worker wiring kept separate from the identity web configuration. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scholarsense.audit-ledger.enabled", havingValue = "true")
@EnableScheduling
public class IdentityAuditRelayConfiguration {
    @Bean
    AuditRelayClock identityAuditRelayClock(TrustedTimeSource time) {
        return () -> time.now().instant();
    }

    @Bean
    JdbcIdentityAuditRelayRepository identityAuditRelayWork(
            JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json) {
        return new JdbcIdentityAuditRelayRepository(
                jdbc, new TransactionTemplate(manager), json);
    }

    @Bean
    AuditProducerBacklogPort identityAuditBacklogSource(
            JdbcTemplate jdbc, AuditRelayClock clock) {
        return new JdbcIdentityAuditBacklogSource(jdbc, clock);
    }

    @Bean
    AuditOutboxRelayProcessor identityAuditRelayProcessor(
            JdbcIdentityAuditRelayRepository work,
            AuditLedgerIngressPort center,
            AuditRelayClock clock,
            AuditRuntimeProfile runtime) {
        return new AuditOutboxRelayProcessor(
                work, center, clock,
                () -> ThreadLocalRandom.current().nextDouble(0.5, 1.0),
                runtime.collectorBatchSize(), runtime.collectorClaimLease());
    }

    @Bean
    IdentityAuditRelayScheduler identityAuditRelayScheduler(AuditOutboxRelayProcessor processor) {
        return new IdentityAuditRelayScheduler(processor);
    }
}
