package cn.edu.suda.scholarsense.identityaccess.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthoritativeIdentityContextTest {
    @Test
    void exposesOnlyLocalIdsFrozenRolesVersionsAndFreshness() {
        var context = new AuthoritativeIdentityContext(
                UUID.fromString("019c1234-0000-7000-8000-000000000201"),
                List.of("R1-COUNSELOR", "R7-PLATFORM-OPS"),
                List.of(UUID.fromString("019c1234-0000-7000-8000-000000000202")),
                3,
                7,
                7,
                IdentityFreshness.FRESH,
                Map.of(
                        "identitySessionPolicy", "ISP-1.0.0",
                        "roleFieldPolicy", "RFP-1.0.0",
                        "roleMapping", "IDENTITY-ROLE-MAPPING-1.0.0",
                        "roleMappingDigest", "sha256:" + "a".repeat(64)),
                Instant.parse("2026-07-24T00:02:00Z"));

        assertEquals(2, context.roleIds().size());
        assertEquals(IdentityFreshness.FRESH, context.freshness());
        assertThrows(UnsupportedOperationException.class, () ->
                context.roleIds().add("R8-FORGED"));
    }

    @Test
    void rejectsUnknownRolesAndMissingPolicyVersions() {
        assertThrows(IllegalArgumentException.class, () -> new AuthoritativeIdentityContext(
                UUID.fromString("019c1234-0000-7000-8000-000000000201"),
                List.of("R8-FORGED"),
                List.of(UUID.fromString("019c1234-0000-7000-8000-000000000202")),
                3,
                7,
                7,
                IdentityFreshness.FRESH,
                Map.of(),
                Instant.parse("2026-07-24T00:02:00Z")));
    }
}
