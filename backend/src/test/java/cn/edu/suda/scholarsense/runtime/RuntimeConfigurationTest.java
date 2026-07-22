package cn.edu.suda.scholarsense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class RuntimeConfigurationTest {

    @Test
    void acceptsExplicitNonSecretConfigurationAndSecretReference() {
        RuntimeConfiguration configuration = RuntimeConfiguration.from(validEnvironment("worker"));

        assertEquals(RuntimeEnvironment.TEST, configuration.environment());
        assertEquals(RuntimeRole.WORKER, configuration.role());
        assertEquals("secret://test/scholarsense", configuration.secretReference());
    }

    @Test
    void missingRequiredConfigurationFailsWithStableNonSecretError() {
        Map<String, String> environment = new HashMap<>(validEnvironment("worker"));
        environment.remove("SCHOLARSENSE_SECRET_REF");

        ConfigurationException error = assertThrows(
                ConfigurationException.class,
                () -> RuntimeConfiguration.from(environment));

        assertEquals("CONFIG_REQUIRED", error.code());
        assertEquals("SCHOLARSENSE_SECRET_REF", error.field());
        assertFalse(error.getMessage().contains("secret://test/scholarsense"));
    }

    @Test
    void unknownRoleAndEnvironmentFailFast() {
        Map<String, String> roleEnvironment = new HashMap<>(validEnvironment("business-api"));
        ConfigurationException roleError = assertThrows(
                ConfigurationException.class,
                () -> RuntimeConfiguration.from(roleEnvironment));
        assertEquals("CONFIG_UNKNOWN_ROLE", roleError.code());

        Map<String, String> profileEnvironment = new HashMap<>(validEnvironment("worker"));
        profileEnvironment.put("SCHOLARSENSE_ENV", "qa");
        ConfigurationException profileError = assertThrows(
                ConfigurationException.class,
                () -> RuntimeConfiguration.from(profileEnvironment));
        assertEquals("CONFIG_UNKNOWN_ENVIRONMENT", profileError.code());
    }

    @ParameterizedTest
    @MethodSource("controlledEnvironmentAndRolePairs")
    void acceptsEveryControlledEnvironmentAndRole(String environmentName, String role) {
        RuntimeConfiguration configuration = RuntimeConfiguration.from(validEnvironment(environmentName, role));

        assertEquals(environmentName, configuration.environment().wireName());
        assertEquals(role, configuration.role().wireName());
    }

    @Test
    void rejectsCrossEnvironmentReferencesAndInvalidPortWithoutEchoingValues() {
        Map<String, String> crossEnvironment = new HashMap<>(validEnvironment("stage", "web-api"));
        crossEnvironment.put("SCHOLARSENSE_DATABASE_REF", "database://prod/do-not-echo");
        ConfigurationException crossError = assertThrows(
                ConfigurationException.class, () -> RuntimeConfiguration.from(crossEnvironment));
        assertEquals("CONFIG_CROSS_ENVIRONMENT", crossError.code());
        assertEquals("SCHOLARSENSE_DATABASE_REF", crossError.field());
        assertFalse(crossError.getMessage().contains("do-not-echo"));

        Map<String, String> invalidPort = new HashMap<>(validEnvironment("dev", "worker"));
        invalidPort.put("SCHOLARSENSE_HTTP_PORT", "not-a-port");
        ConfigurationException portError = assertThrows(
                ConfigurationException.class, () -> RuntimeConfiguration.from(invalidPort));
        assertEquals("CONFIG_INVALID", portError.code());
        assertEquals("SCHOLARSENSE_HTTP_PORT", portError.field());
    }

    @Test
    void rejectsReferenceWithoutResourcePathAndNonReservedProductionEndpoint() {
        Map<String, String> emptyReferencePath = new HashMap<>(validEnvironment("test", "worker"));
        emptyReferencePath.put("SCHOLARSENSE_ACCOUNT_REF", "account://test/");
        ConfigurationException referenceError = assertThrows(
                ConfigurationException.class, () -> RuntimeConfiguration.from(emptyReferencePath));
        assertEquals("CONFIG_INVALID", referenceError.code());
        assertEquals("SCHOLARSENSE_ACCOUNT_REF", referenceError.field());

        Map<String, String> unreservedProductionEndpoint = new HashMap<>(validEnvironment("prod", "web-api"));
        unreservedProductionEndpoint.put("SCHOLARSENSE_EXTERNAL_BASE_URI", "https://prod.external.example");
        ConfigurationException endpointError = assertThrows(
                ConfigurationException.class, () -> RuntimeConfiguration.from(unreservedProductionEndpoint));
        assertEquals("CONFIG_CROSS_ENVIRONMENT", endpointError.code());
        assertEquals("SCHOLARSENSE_EXTERNAL_BASE_URI", endpointError.field());
    }

    @Test
    void rejectsPercentEncodedReferencePaths() {
        Map<String, String> encodedReference = new HashMap<>(validEnvironment("test", "worker"));
        encodedReference.put("SCHOLARSENSE_ACCOUNT_REF", "account://test/%73cholarsense");

        ConfigurationException error = assertThrows(
                ConfigurationException.class, () -> RuntimeConfiguration.from(encodedReference));

        assertEquals("CONFIG_INVALID", error.code());
        assertEquals("SCHOLARSENSE_ACCOUNT_REF", error.field());
    }

    @Test
    void identityRuntimeRequiresAControlledClockSourceBindingAndRejectsStaticSyncClaims() {
        Map<String, String> missing = new HashMap<>(validEnvironment("web-api"));
        missing.put("SCHOLARSENSE_IDENTITY_ENABLED", "true");
        missing.put("SCHOLARSENSE_CLOCK_SYNCHRONIZED", "true");

        ConfigurationException error = assertThrows(
                ConfigurationException.class, () -> RuntimeConfiguration.from(missing));
        assertEquals("CONFIG_REQUIRED", error.code());
        assertEquals("SCHOLARSENSE_CLOCK_SOURCE_REF", error.field());

        missing.put("SCHOLARSENSE_CLOCK_SOURCE_REF", "config://test/campus-ntp-a");
        RuntimeConfiguration configuration = RuntimeConfiguration.from(missing);
        assertEquals("config://test/campus-ntp-a", configuration.clockSourceReference());
    }

    @Test
    void auditLedgerCapabilityIsWorkerOnlyAndRequiresTheSameControlledClockBinding() {
        Map<String, String> worker = new HashMap<>(validEnvironment("worker"));
        worker.put("SCHOLARSENSE_AUDIT_LEDGER_ENABLED", "true");

        ConfigurationException missingClock = assertThrows(
                ConfigurationException.class, () -> RuntimeConfiguration.from(worker));
        assertEquals("CONFIG_REQUIRED", missingClock.code());
        assertEquals("SCHOLARSENSE_CLOCK_SOURCE_REF", missingClock.field());

        addAuditRuntimeReferences(worker, "test");
        RuntimeConfiguration configured = RuntimeConfiguration.from(worker);
        assertEquals(true, configured.auditLedgerEnabled());
        assertEquals(
                "config://test/audit-ingestion-policy-1-0-0",
                configured.auditIngestionPolicyReference());
        assertEquals(
                "config://test/audit-verifier-1-0-0",
                configured.auditVerifierReference());

        Map<String, String> web = new HashMap<>(worker);
        web.put("SCHOLARSENSE_ROLE", "web-api");
        ConfigurationException mismatch = assertThrows(
                ConfigurationException.class, () -> RuntimeConfiguration.from(web));
        assertEquals("CONFIG_ROLE_CAPABILITY_MISMATCH", mismatch.code());
        assertEquals("SCHOLARSENSE_AUDIT_LEDGER_ENABLED", mismatch.field());
    }

    @Test
    void auditRuntimeRejectsMissingCrossEnvironmentAndStaleControlledReferencesWithoutEchoingValues() {
        Map<String, String> missing = new HashMap<>(validEnvironment("worker"));
        missing.put("SCHOLARSENSE_AUDIT_LEDGER_ENABLED", "true");
        missing.put("SCHOLARSENSE_CLOCK_SOURCE_REF", "config://test/campus-ntp-a");

        ConfigurationException missingError = assertThrows(
                ConfigurationException.class, () -> RuntimeConfiguration.from(missing));
        assertEquals("CONFIG_REQUIRED", missingError.code());
        assertEquals("SCHOLARSENSE_AUDIT_INGESTION_POLICY_REF", missingError.field());

        Map<String, String> crossEnvironment = new HashMap<>(missing);
        addAuditRuntimeReferences(crossEnvironment, "test");
        crossEnvironment.put(
                "SCHOLARSENSE_AUDIT_COLLECTOR_REF",
                "config://prod/do-not-echo");
        ConfigurationException crossError = assertThrows(
                ConfigurationException.class, () -> RuntimeConfiguration.from(crossEnvironment));
        assertEquals("CONFIG_CROSS_ENVIRONMENT", crossError.code());
        assertEquals("SCHOLARSENSE_AUDIT_COLLECTOR_REF", crossError.field());
        assertFalse(crossError.getMessage().contains("do-not-echo"));

        Map<String, String> stale = new HashMap<>(missing);
        addAuditRuntimeReferences(stale, "test");
        stale.put(
                "SCHOLARSENSE_AUDIT_VERIFIER_REF",
                "config://test/audit-verifier-0-9-0");
        ConfigurationException staleError = assertThrows(
                ConfigurationException.class, () -> RuntimeConfiguration.from(stale));
        assertEquals("CONFIG_STALE_REFERENCE", staleError.code());
        assertEquals("SCHOLARSENSE_AUDIT_VERIFIER_REF", staleError.field());
        assertFalse(staleError.getMessage().contains("0-9-0"));
    }

    public static Map<String, String> validEnvironment(String role) {
        return validEnvironment("test", role);
    }

    public static Map<String, String> validEnvironment(String environment, String role) {
        return Map.ofEntries(
                Map.entry("SCHOLARSENSE_ENV", environment),
                Map.entry("SCHOLARSENSE_ROLE", role),
                Map.entry("SCHOLARSENSE_ACCOUNT_REF", "account://" + environment + "/scholarsense"),
                Map.entry("SCHOLARSENSE_DATABASE_REF", "database://" + environment + "/scholarsense"),
                Map.entry("SCHOLARSENSE_SECRET_REF", "secret://" + environment + "/scholarsense"),
                Map.entry("SCHOLARSENSE_STORAGE_NAMESPACE", "scholarsense-" + environment),
                Map.entry("SCHOLARSENSE_EXTERNAL_BASE_URI", "https://" + environment + ".invalid"),
                Map.entry("SCHOLARSENSE_IDENTITY_ENABLED", "false"),
                Map.entry("SCHOLARSENSE_AUDIT_LEDGER_ENABLED", "false"));
    }

    public static void addAuditRuntimeReferences(Map<String, String> values, String environment) {
        values.put("SCHOLARSENSE_CLOCK_SOURCE_REF", "config://" + environment + "/campus-ntp-a");
        values.put("SCHOLARSENSE_AUDIT_INGESTION_POLICY_REF",
                "config://" + environment + "/audit-ingestion-policy-1-0-0");
        values.put("SCHOLARSENSE_AUDIT_HASH_PROFILE_REF",
                "config://" + environment + "/audit-ledger-hash-1-0-0");
        values.put("SCHOLARSENSE_AUDIT_COLLECTOR_REF",
                "config://" + environment + "/audit-collector-1-0-0");
        values.put("SCHOLARSENSE_AUDIT_VERIFIER_REF",
                "config://" + environment + "/audit-verifier-1-0-0");
        values.put("SCHOLARSENSE_AUDIT_ALERT_TRANSPORT_REF",
                "config://" + environment + "/audit-alert-structured-log-1-0-0");
        values.put("SCHOLARSENSE_AUDIT_METRIC_BINDING_REF",
                "config://" + environment + "/audit-micrometer-1-0-0");
    }

    private static Stream<Arguments> controlledEnvironmentAndRolePairs() {
        return Stream.of("dev", "test", "stage", "prod")
                .flatMap(environment -> Stream.of("web-api", "worker")
                        .map(role -> Arguments.of(environment, role)));
    }
}
