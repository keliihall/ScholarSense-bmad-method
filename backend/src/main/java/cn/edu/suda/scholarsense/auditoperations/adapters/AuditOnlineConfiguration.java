package cn.edu.suda.scholarsense.auditoperations.adapters;

import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditAvailabilityPort;
import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityPort;
import cn.edu.suda.scholarsense.auditoperations.application.AuditClock;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

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
}
