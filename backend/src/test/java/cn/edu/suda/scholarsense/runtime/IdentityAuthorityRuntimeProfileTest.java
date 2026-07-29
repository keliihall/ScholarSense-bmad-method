package cn.edu.suda.scholarsense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class IdentityAuthorityRuntimeProfileTest {

    @Test
    void resolvesOnlyEnvironmentScopedNonSecretSandboxBindings() {
        var values = enabled("test");

        IdentityAuthorityRuntimeProfile profile =
                IdentityAuthorityRuntimeProfile.from(RuntimeConfiguration.from(values));

        assertEquals(
                URI.create("https://test.identity-authority.sandbox.invalid/api/v1/incremental"),
                profile.endpoint());
        assertEquals(Duration.ofSeconds(5), profile.connectTimeout());
        assertEquals(Duration.ofSeconds(20), profile.requestTimeout());
        assertEquals("account://test/identity-sync-worker", profile.workloadIdentityReference());
        assertEquals("secret://test/identity-authority-signature", profile.signatureKeyReference());
        assertEquals(
                "config://test/identity-authority-inbox",
                profile.inboxEncryptionKeyReference());
        assertEquals(
                "config://test/identity-role-mapping-1-0-0",
                profile.roleMappingReference());
        assertEquals("IDENTITY-ROLE-MAPPING-1.0.0", profile.roleMappingVersion());
        assertEquals(5, profile.retryBudget());
        assertFalse(profile.productionEligible());
    }

    @Test
    void sandboxProfileCannotBeResolvedForProductionAndMissingResourceFailsClosed() {
        RuntimeConfiguration prod = RuntimeConfiguration.from(enabled("prod"));
        IllegalStateException production = assertThrows(
                IllegalStateException.class,
                () -> IdentityAuthorityRuntimeProfile.from(prod));
        assertEquals("IDENTITY_AUTHORITY_PROFILE_NOT_PRODUCTION_ELIGIBLE",
                production.getMessage());

        RuntimeConfiguration test = RuntimeConfiguration.from(enabled("test"));
        IllegalStateException missing = assertThrows(
                IllegalStateException.class,
                () -> IdentityAuthorityRuntimeProfile.from(test, ignored -> null));
        assertEquals("IDENTITY_AUTHORITY_PROFILE_RESOURCE_MISSING", missing.getMessage());
    }

    private static HashMap<String, String> enabled(String environment) {
        var values = new HashMap<>(
                RuntimeConfigurationTest.validEnvironment(environment, "worker"));
        values.put("SCHOLARSENSE_IDENTITY_SYNC_ENABLED", "true");
        values.put(
                "SCHOLARSENSE_CLOCK_SOURCE_REF",
                "config://" + environment + "/campus-ntp-a");
        values.put(
                "SCHOLARSENSE_IDENTITY_AUTHORITY_PROFILE_REF",
                "config://" + environment + "/identity-authority-profile-1-0-0");
        return values;
    }
}
