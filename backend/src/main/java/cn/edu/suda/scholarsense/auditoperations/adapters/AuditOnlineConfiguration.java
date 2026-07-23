package cn.edu.suda.scholarsense.auditoperations.adapters;

import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditAvailabilityPort;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditSearchQueryRepository;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcSearchAuditRepository;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcRetentionExecutionQueryRepository;
import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityPort;
import cn.edu.suda.scholarsense.auditoperations.application.AuditClock;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchService;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchAuthorizationGateway;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchTokenGateway;
import cn.edu.suda.scholarsense.auditoperations.application.RetentionExecutionReadService;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Web capability: a read-only availability gate, never collector or verifier schedulers. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
public class AuditOnlineConfiguration {
    @Bean
    AuditClock auditOnlineClock(TrustedTimeSource trustedTime) {
        return () -> trustedTime.now().instant();
    }

    @Bean
    AuditAvailabilityPort auditOnlineAvailability(
            JdbcTemplate jdbc, ObjectMapper json, AuditClock clock) {
        return new JdbcAuditAvailabilityPort(jdbc, json, clock);
    }

    @Bean
    JdbcAuditSearchQueryRepository auditSearchQueries(JdbcTemplate jdbc) {
        return new JdbcAuditSearchQueryRepository(jdbc);
    }

    @Bean
    JdbcSearchAuditRepository auditSearchAudit(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper json,
            AuditSearchTokenGateway tokens,
            TrustedTimeSource time) {
        return new JdbcSearchAuditRepository(
                jdbc, new TransactionTemplate(manager), json, tokens, time);
    }

    @Bean
    AuditSearchService auditSearchService(
            JdbcAuditSearchQueryRepository queries,
            AuditSearchAuthorizationGateway authorization,
            AuditSearchTokenGateway tokens,
            JdbcSearchAuditRepository audit,
            AuditClock clock) {
        return new AuditSearchService(
                queries, authorization, tokens, audit, clock, requesterKey -> requesterKey);
    }

    @Bean
    JdbcRetentionExecutionQueryRepository retentionExecutionQueries(
            JdbcTemplate jdbc, ObjectMapper json) {
        return new JdbcRetentionExecutionQueryRepository(jdbc, json);
    }

    @Bean
    RetentionExecutionReadService retentionExecutionReadService(
            JdbcRetentionExecutionQueryRepository executions,
            AuditSearchAuthorizationGateway authorization,
            JdbcSearchAuditRepository audit,
            AuditClock clock) {
        return new RetentionExecutionReadService(executions, authorization, audit, clock);
    }

}
