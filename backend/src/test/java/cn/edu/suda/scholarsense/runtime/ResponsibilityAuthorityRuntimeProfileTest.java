package cn.edu.suda.scholarsense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
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
                "RESPONSIBILITY-AUTHORITY-PROFILE-2.0.0",
                profile.profileVersion());
        assertEquals("responsibility", profile.consumerProjection());
        assertEquals(
                "https://test.responsibility-authority.sandbox.invalid/api/v1/incremental",
                profile.incrementalEndpoint().toString());
        assertEquals(
                "https://test.responsibility-authority.sandbox.invalid/api/v1/snapshots/2026-07-30",
                profile.snapshotEndpoint(
                        java.time.LocalDate.of(2026, 7, 30)).toString());
        assertEquals(
                "https://test.responsibility-authority.sandbox.invalid/api/v2/incremental",
                profile.incrementalEndpoint(
                        "RESPONSIBILITY-AUTHORITY-2.0.0").toString());
        assertEquals(
                "https://test.responsibility-authority.sandbox.invalid/api/v2/snapshots/2026-07-30",
                profile.snapshotEndpoint(
                        "RESPONSIBILITY-AUTHORITY-2.0.0",
                        java.time.LocalDate.of(2026, 7, 30)).toString());
        assertEquals(
                "RESPONSIBILITY-AUTHORITY-2.0.0",
                profile.writeContractVersion());
        assertEquals(
                Instant.parse("2026-07-31T16:00:00Z"),
                profile.effectiveAt());
        assertEquals(Duration.ofHours(336), profile.dualReadWindow());
        assertEquals(
                Instant.parse("2026-08-14T16:00:00Z"),
                profile.dualReadWindowEndsAt());
        assertTrue(profile.cutoverEnabled());
        assertEquals("0 */15 * * * *", profile.cutoverCron());
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
                        + "/responsibility-authority-profile-2-0-0");
        return RuntimeConfiguration.from(values);
    }
}
