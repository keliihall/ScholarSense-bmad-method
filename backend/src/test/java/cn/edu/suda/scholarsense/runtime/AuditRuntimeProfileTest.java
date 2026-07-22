package cn.edu.suda.scholarsense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class AuditRuntimeProfileTest {

    @Test
    void loadsEveryRuntimeValueFromThePinnedClasspathResources() {
        var values = new HashMap<>(RuntimeConfigurationTest.validEnvironment("worker"));
        values.put("SCHOLARSENSE_AUDIT_LEDGER_ENABLED", "true");
        RuntimeConfigurationTest.addAuditRuntimeReferences(values, "test");

        AuditRuntimeProfile profile = AuditRuntimeProfile.from(RuntimeConfiguration.from(values));

        assertEquals("AUDIT-INGESTION-POLICY-1.0.0", profile.ingestionPolicyVersion());
        assertEquals("AUDIT-LEDGER-HASH-1.0.0", profile.hashProfileVersion());
        assertEquals(100, profile.collectorBatchSize());
        assertEquals(Duration.ofSeconds(60), profile.collectorClaimLease());
        assertEquals(Duration.ofSeconds(5), profile.collectorInterval());
        assertEquals(1_000, profile.verifierBatchSize());
        assertEquals(Duration.ofSeconds(15), profile.verifierInterval());
        assertEquals(100, profile.alertBatchSize());
        assertEquals(Duration.ofSeconds(5), profile.alertInterval());
        assertEquals(45, profile.measurementStaleAfterSeconds());
        assertEquals(60, profile.degradedAgeSeconds());
        assertEquals(10_000, profile.degradedCount());
        assertEquals(300, profile.blockedAgeSeconds());
        assertEquals(50_000, profile.blockedCount());
        assertEquals(2, profile.recoveryHealthyObservations());
    }

    @Test
    void missingControlledResourceFailsClosedWithoutEchoingItsReference() {
        var values = new HashMap<>(RuntimeConfigurationTest.validEnvironment("worker"));
        values.put("SCHOLARSENSE_AUDIT_LEDGER_ENABLED", "true");
        RuntimeConfigurationTest.addAuditRuntimeReferences(values, "test");
        RuntimeConfiguration runtime = RuntimeConfiguration.from(values);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> AuditRuntimeProfile.from(runtime, ignored -> null));

        assertEquals("AUDIT_RUNTIME_RESOURCE_MISSING", failure.getMessage());
    }
}
