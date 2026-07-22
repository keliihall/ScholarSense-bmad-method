package cn.edu.suda.scholarsense.auditoperations.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import cn.edu.suda.scholarsense.auditoperations.adapters.inbound.AuditWorkerScheduler;
import cn.edu.suda.scholarsense.auditoperations.api.AuditProducerBacklogPort;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.runtime.RuntimeConfigurationTest;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeException;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class AuditWorkerSpringAssemblyTest {

    @Test
    void auditEnabledWorkerAssemblesWithoutClockEvidenceAndFailsClosedWhenTimeIsUsed() {
        Map<String, String> values = new HashMap<>(RuntimeConfigurationTest.validEnvironment("worker"));
        values.put("SCHOLARSENSE_AUDIT_LEDGER_ENABLED", "true");
        RuntimeConfigurationTest.addAuditRuntimeReferences(values, "test");
        RuntimeConfiguration runtime = RuntimeConfiguration.from(values);

        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "audit-worker-test",
                    Map.of(
                            "scholarsense.audit-ledger.enabled", "true",
                            "scholarsense.audit.verifier.initial-delay", "86400000",
                            "scholarsense.audit.verifier.interval", "86400000",
                            "scholarsense.audit.alert.initial-delay", "86400000",
                            "scholarsense.audit.alert.interval", "86400000")));
            context.registerBean(RuntimeConfiguration.class, () -> runtime);
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(
                    PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(SimpleMeterRegistry.class, () -> new SimpleMeterRegistry());
            context.registerBean(
                    AuditProducerBacklogPort.class,
                    () -> mock(AuditProducerBacklogPort.class));
            context.register(AuditWorkerConfiguration.class);

            context.refresh();

            assertNotNull(context.getBean(AuditWorkerScheduler.class));
            TrustedTimeSource time = context.getBean(TrustedTimeSource.class);
            TrustedTimeException failure = assertThrows(TrustedTimeException.class, time::now);
            assertEquals("AUDIT_TIME_SOURCE_UNAVAILABLE", failure.code());
        }
    }
}
