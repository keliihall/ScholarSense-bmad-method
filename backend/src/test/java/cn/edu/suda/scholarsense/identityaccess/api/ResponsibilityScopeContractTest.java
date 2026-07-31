package cn.edu.suda.scholarsense.identityaccess.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResponsibilityScopeContractTest {
    private static final UUID ACCOUNT_ID =
            UUID.fromString("019c1234-0000-7000-8000-000000000211");
    private static final UUID COLLEGE_ID =
            UUID.fromString("019c1234-0000-7000-8000-000000000212");
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Test
    void validScopeCarriesOnlyControlledRecipientReferencesAndVersions() {
        var view = new ResponsibilityScopeView(
                "a".repeat(64),
                ACCOUNT_ID,
                COLLEGE_ID,
                7,
                7,
                1,
                NOW.minusSeconds(60),
                null,
                ResponsibilityScopeValidity.VALID,
                ResponsibilityScopeFreshness.FRESH,
                "RESPONSIBILITY_VALID",
                "RESPONSIBILITY-AUTHORITY-1.0.0",
                NOW);

        assertEquals(ResponsibilityScopeValidity.VALID, view.validity());
        assertEquals(ACCOUNT_ID, view.counselorAccountId());
    }

    @Test
    void invalidAndDependencyUnavailableScopesCannotLeakRecipientReferences() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResponsibilityScopeView(
                        "a".repeat(64),
                        ACCOUNT_ID,
                        COLLEGE_ID,
                        7,
                        7,
                        1,
                        NOW.minusSeconds(60),
                        null,
                        ResponsibilityScopeValidity.INVALID,
                        ResponsibilityScopeFreshness.STALE,
                        "RESPONSIBILITY_MULTIPLE_RECIPIENTS",
                        "RESPONSIBILITY-AUTHORITY-1.0.0",
                        NOW));

        var unavailable = ResponsibilityScopeView.unavailable(
                "a".repeat(64),
                "RESPONSIBILITY_DEPENDENCY_WATERMARK_BEHIND",
                NOW);
        assertEquals(
                ResponsibilityScopeValidity.DEPENDENCY_UNAVAILABLE,
                unavailable.validity());
        assertEquals(null, unavailable.counselorAccountId());
    }
}
