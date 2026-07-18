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

class RuntimeConfigurationTest {

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

    static Map<String, String> validEnvironment(String role) {
        return validEnvironment("test", role);
    }

    static Map<String, String> validEnvironment(String environment, String role) {
        return Map.of(
                "SCHOLARSENSE_ENV", environment,
                "SCHOLARSENSE_ROLE", role,
                "SCHOLARSENSE_ACCOUNT_REF", "account://" + environment + "/scholarsense",
                "SCHOLARSENSE_DATABASE_REF", "database://" + environment + "/scholarsense",
                "SCHOLARSENSE_SECRET_REF", "secret://" + environment + "/scholarsense",
                "SCHOLARSENSE_STORAGE_NAMESPACE", "scholarsense-" + environment,
                "SCHOLARSENSE_EXTERNAL_BASE_URI", "https://" + environment + ".invalid");
    }

    private static Stream<Arguments> controlledEnvironmentAndRolePairs() {
        return Stream.of("dev", "test", "stage", "prod")
                .flatMap(environment -> Stream.of("web-api", "worker")
                        .map(role -> Arguments.of(environment, role)));
    }
}
