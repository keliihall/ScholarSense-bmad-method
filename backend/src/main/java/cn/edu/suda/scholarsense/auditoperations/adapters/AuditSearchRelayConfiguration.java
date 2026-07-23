package cn.edu.suda.scholarsense.auditoperations.adapters;

import cn.edu.suda.scholarsense.auditoperations.adapters.inbound.AuditSearchRelayScheduler;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditOperationsBacklogSource;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditSearchRelayRepository;
import cn.edu.suda.scholarsense.auditoperations.api.AuditLedgerIngressPort;
import cn.edu.suda.scholarsense.auditoperations.api.AuditProducerBacklogPort;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchAuditRelayProcessor;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchRelayClock;
import cn.edu.suda.scholarsense.auditoperations.application.AuditCenterIngressOutcome;
import cn.edu.suda.scholarsense.auditoperations.application.AuditCenterIngressResult;
import cn.edu.suda.scholarsense.runtime.AuditRuntimeProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scholarsense.audit-ledger.enabled", havingValue = "true")
@EnableScheduling
public class AuditSearchRelayConfiguration {
    @Bean
    AuditSearchRelayClock auditSearchRelayClock(TrustedTimeSource time) {
        return () -> time.now().instant();
    }

    @Bean
    JdbcAuditSearchRelayRepository auditSearchRelayWork(
            JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json) {
        return new JdbcAuditSearchRelayRepository(jdbc, new TransactionTemplate(manager), json);
    }

    @Bean
    AuditProducerBacklogPort auditOperationsSearchBacklog(
            JdbcTemplate jdbc, AuditSearchRelayClock clock) {
        return new JdbcAuditOperationsBacklogSource(jdbc, clock);
    }

    @Bean
    AuditSearchAuditRelayProcessor auditSearchRelayProcessor(
            JdbcAuditSearchRelayRepository work,
            AuditLedgerIngressPort center,
            AuditSearchRelayClock clock,
            AuditRuntimeProfile runtime) {
        return new AuditSearchAuditRelayProcessor(
                work, source -> {
                    var result = center.ingest(source);
                    return new AuditCenterIngressResult(
                            AuditCenterIngressOutcome.valueOf(result.outcome().name()),
                            result.errorCode(), result.retryable());
                }, clock,
                () -> ThreadLocalRandom.current().nextDouble(0.5, 1.0),
                runtime.collectorBatchSize(), runtime.collectorClaimLease());
    }

    @Bean
    AuditSearchRelayScheduler auditSearchRelayScheduler(AuditSearchAuditRelayProcessor processor) {
        return new AuditSearchRelayScheduler(processor);
    }
}
