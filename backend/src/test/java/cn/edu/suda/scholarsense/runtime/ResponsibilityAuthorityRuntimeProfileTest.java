package cn.edu.suda.scholarsense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponsibilityAuthorityRuntimeProfileTest {
    @Test
    void resolvesThePinnedNonProductionProfile() {
        ResponsibilityAuthorityRuntimeProfile profile =
                ResponsibilityAuthorityRuntimeProfile.from(runtime("test"));

        assertEquals(
                "RESPONSIBILITY-AUTHORITY-PROFILE-1.0.0",
                profile.profileVersion());
        assertEquals("responsibility", profile.consumerProjection());
        assertEquals(
                "https://test.responsibility-authority.sandbox.invalid/api/v1/incremental",
                profile.incrementalEndpoint().toString());
        assertEquals(
                "https://test.responsibility-authority.sandbox.invalid/api/v1/snapshots/2026-07-30",
                profile.snapshotEndpoint(
                        java.time.LocalDate.of(2026, 7, 30)).toString());
        assertEquals(ZoneId.of("Asia/Shanghai"), profile.scheduleTimeZone());
        assertEquals(Duration.ofHours(24), profile.catchUpWindow());
        assertEquals(8, profile.retryBudget());
        assertFalse(profile.productionEligible());
    }

    @Test
    void productionAndMissingResourcesFailClosed() {
        IllegalStateException production = assertThrows(
                IllegalStateException.class,
                () -> ResponsibilityAuthorityRuntimeProfile.from(
                        runtime("prod")));
        assertEquals(
                "RESPONSIBILITY_AUTHORITY_PROFILE_NOT_PRODUCTION_ELIGIBLE",
                production.getMessage());

        IllegalStateException missing = assertThrows(
                IllegalStateException.class,
                () -> ResponsibilityAuthorityRuntimeProfile.from(
                        runtime("test"), ignored -> null));
        assertEquals(
                "RESPONSIBILITY_AUTHORITY_PROFILE_RESOURCE_MISSING",
                missing.getMessage());
    }

    private static RuntimeConfiguration runtime(String environment) {
        Map<String, String> values = new HashMap<>(
                RuntimeConfigurationTest.validEnvironment(
                        environment, "worker"));
        values.put("SCHOLARSENSE_IDENTITY_SYNC_ENABLED", "true");
        values.put(
                "SCHOLARSENSE_CLOCK_SOURCE_REF",
                "config://" + environment + "/campus-ntp-a");
        values.put(
                "SCHOLARSENSE_IDENTITY_AUTHORITY_PROFILE_REF",
                "config://" + environment
                        + "/identity-authority-profile-1-0-0");
        values.put(
                "SCHOLARSENSE_RESPONSIBILITY_AUTHORITY_PROFILE_REF",
                "config://" + environment
                        + "/responsibility-authority-profile-1-0-0");
        return RuntimeConfiguration.from(values);
    }
}
