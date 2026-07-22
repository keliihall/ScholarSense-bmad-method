package cn.edu.suda.scholarsense.auditoperations.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.runtime.RuntimeConfigurationTest;
import cn.edu.suda.scholarsense.runtime.AuditRuntimeProfile;
import cn.edu.suda.scholarsense.shared.time.TimeSynchronizationStatus;
import cn.edu.suda.scholarsense.shared.time.TimeSynchronizationStatusProvider;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.scheduling.annotation.EnableScheduling;

class AuditRuntimeConfigurationTest {

    @Test
    void workerOwnsSchedulersAndLoadsPinnedPolicyFromControlledReferences() {
        var worker = new AuditWorkerConfiguration();
        Map<String, String> values = new HashMap<>(RuntimeConfigurationTest.validEnvironment("worker"));
        values.put("SCHOLARSENSE_AUDIT_LEDGER_ENABLED", "true");
        RuntimeConfigurationTest.addAuditRuntimeReferences(values, "test");
        RuntimeConfiguration runtime = RuntimeConfiguration.from(values);
        AuditRuntimeProfile profile = AuditRuntimeProfile.from(runtime);

        assertEquals(
                "AUDIT-INGESTION-POLICY-1.0.0",
                worker.auditPolicy(profile).ingestionPolicyVersion());
        assertEquals(
                "AUDIT-LEDGER-HASH-1.0.0",
                worker.auditPolicy(profile).hashProfileVersion());
        assertNotNull(AuditWorkerConfiguration.class.getAnnotation(EnableScheduling.class));
        assertNull(AuditOnlineConfiguration.class.getAnnotation(EnableScheduling.class));
        assertEquals(
                java.util.List.of("unavailableTimeSynchronizationStatusProvider"),
                java.util.Arrays.stream(AuditWorkerConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(ConditionalOnMissingBean.class) != null)
                .map(java.lang.reflect.Method::getName)
                .filter(name -> name.contains("unavailable") || name.contains("noOp"))
                .sorted()
                .toList());
    }

    @Test
    void staleClockEvidenceFailsDuringWorkerAssembly() {
        var worker = new AuditWorkerConfiguration();
        Map<String, String> values = new HashMap<>(RuntimeConfigurationTest.validEnvironment("worker"));
        values.put("SCHOLARSENSE_AUDIT_LEDGER_ENABLED", "true");
        RuntimeConfigurationTest.addAuditRuntimeReferences(values, "test");
        RuntimeConfiguration runtime = RuntimeConfiguration.from(values);
        Clock clock = Clock.fixed(Instant.parse("2026-07-22T10:00:00Z"), ZoneOffset.UTC);

        var source = worker.auditTrustedTimeSource(
                runtime,
                clock,
                worker.auditTrustedClockConstraints(),
                () -> java.util.Optional.of(new TimeSynchronizationStatus(
                        "campus-ntp-a",
                        "PP-1.0.0",
                        0,
                        Instant.parse("2026-07-22T09:50:00Z"),
                        Instant.parse("2026-07-22T09:59:59Z"),
                "evidence://signed/clock/campus-ntp-a.json")));
        assertThrows(TrustedTimeException.class, source::now);
    }

    @Test
    void auditWorkerProvidesAFailClosedDefaultButAcceptsFreshDeploymentClockEvidence()
            throws Exception {
        var worker = new AuditWorkerConfiguration();
        TimeSynchronizationStatusProvider unavailable =
                worker.unavailableTimeSynchronizationStatusProvider();
        assertTrue(unavailable.current().isEmpty());
        assertNotNull(AuditWorkerConfiguration.class
                .getDeclaredMethod("unavailableTimeSynchronizationStatusProvider")
                .getAnnotation(ConditionalOnMissingBean.class));

        Map<String, String> values = new HashMap<>(RuntimeConfigurationTest.validEnvironment("worker"));
        values.put("SCHOLARSENSE_AUDIT_LEDGER_ENABLED", "true");
        RuntimeConfigurationTest.addAuditRuntimeReferences(values, "test");
        RuntimeConfiguration runtime = RuntimeConfiguration.from(values);
        Clock clock = Clock.fixed(Instant.parse("2026-07-22T10:00:00Z"), ZoneOffset.UTC);

        var failClosedSource = worker.auditTrustedTimeSource(
                runtime, clock, worker.auditTrustedClockConstraints(), unavailable);
        TrustedTimeException unavailableFailure = assertThrows(
                TrustedTimeException.class, failClosedSource::now);
        assertEquals("AUDIT_TIME_SOURCE_UNAVAILABLE", unavailableFailure.code());

        assertNotNull(worker.auditTrustedTimeSource(
                runtime,
                clock,
                worker.auditTrustedClockConstraints(),
                () -> java.util.Optional.of(new TimeSynchronizationStatus(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        12,
                        Instant.parse("2026-07-22T09:59:30Z"),
                        Instant.parse("2026-07-22T10:00:30Z"),
                        "evidence://signed/clock/campus-ntp-a.json"))).now());
    }
}
