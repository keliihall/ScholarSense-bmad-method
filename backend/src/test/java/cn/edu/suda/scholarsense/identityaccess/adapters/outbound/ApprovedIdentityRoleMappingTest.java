package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.domain.OrganizationType;
import cn.edu.suda.scholarsense.identityaccess.domain.TargetRole;
import cn.edu.suda.scholarsense.runtime.IdentityAuthorityRuntimeProfile;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ApprovedIdentityRoleMappingTest {

    @Test
    void resolvesOnlySourceOwnerProvidedAndHeiApprovedCatalogEntries() {
        ApprovedIdentityRoleMapping mapping = ApprovedIdentityRoleMapping.from(
                profile(),
                new ObjectMapper(),
                ApprovedIdentityRoleMappingTest.class::getResourceAsStream);

        assertEquals(
                TargetRole.R1_COUNSELOR,
                mapping.resolve(
                        "SANDBOX_COUNSELOR",
                        "R1-COUNSELOR",
                        OrganizationType.DEPARTMENT));
        IdentitySyncException rejected = assertThrows(
                IdentitySyncException.class,
                () -> mapping.resolve(
                        "UNAPPROVED_COUNSELOR",
                        "R1-COUNSELOR",
                        OrganizationType.DEPARTMENT));
        assertEquals("IDENTITY_ROLE_UNKNOWN", rejected.code());
    }

    @Test
    void rejectsCatalogWhoseDigestDoesNotMatchRuntimeProfile() {
        String tampered = """
                {
                  "version": "IDENTITY-ROLE-MAPPING-1.0.0",
                  "sourceId": "SRC-P0-RESPONSIBILITY-001",
                  "environment": "sandbox",
                  "productionEligible": false,
                  "providedBy": "SRC-P0-RESPONSIBILITY-001 controlled sandbox source owner",
                  "approvedBy": "Hei",
                  "approvedAt": "2026-07-25T08:00:00+08:00",
                  "effectiveAt": "2026-07-25T08:00:00+08:00",
                  "digest": "sha256:%s",
                  "entries": []
                }
                """.formatted("0".repeat(64));

        IllegalStateException rejected = assertThrows(
                IllegalStateException.class,
                () -> ApprovedIdentityRoleMapping.from(
                        profile(),
                        new ObjectMapper(),
                        ignored -> new ByteArrayInputStream(
                                tampered.getBytes(StandardCharsets.UTF_8))));
        assertEquals("IDENTITY_ROLE_MAPPING_RESOURCE_INVALID", rejected.getMessage());
    }

    private static IdentityAuthorityRuntimeProfile profile() {
        return new IdentityAuthorityRuntimeProfile(
                "IDENTITY-AUTHORITY-PROFILE-1.0.0",
                "SRC-P0-RESPONSIBILITY-001",
                "identity-authority",
                "sandbox-0",
                "identity-org",
                URI.create("https://test.identity-authority.sandbox.invalid/api/v1/incremental"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                "account://test/identity-sync-worker",
                "secret://test/identity-authority-signature",
                "config://test/identity-authority-inbox",
                "config://test/identity-role-mapping-1-0-0",
                "IDENTITY-ROLE-MAPPING-1.0.0",
                "sha256:f09768f88cd6a595791ec6591e65758b85fd8402585c8ffaa053446214895e29",
                Duration.ofSeconds(30),
                5,
                false);
    }
}
