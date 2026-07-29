package cn.edu.suda.scholarsense.identityaccess.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthoritativeIdentityDomainTest {
    private static final UUID ACCOUNT_ID =
            UUID.fromString("019c1234-0000-7000-8000-000000000101");
    private static final UUID ROOT_ID =
            UUID.fromString("019c1234-0000-7000-8000-000000000102");
    private static final UUID CHILD_ID =
            UUID.fromString("019c1234-0000-7000-8000-000000000103");
    private static final Instant START = Instant.parse("2026-07-24T00:00:00Z");

    @Test
    void acceptsUuidV7AccountAndOnlyFrozenR1ToR7Roles() {
        var account = new AuthoritativeAccount(
                ACCOUNT_ID,
                "SRC-P0-RESPONSIBILITY-001",
                digest("a"),
                "actor_v1_k1_" + "a".repeat(64),
                AuthoritativeStatus.ACTIVE,
                new EffectiveInterval(START, null),
                7,
                3);

        assertEquals(ACCOUNT_ID, account.accountId());
        assertEquals(TargetRole.R1_COUNSELOR, TargetRole.fromWire("R1-COUNSELOR"));
        assertThrows(IllegalArgumentException.class, () -> TargetRole.fromWire("R8-FORGED"));
        assertThrows(IllegalArgumentException.class, () -> new AuthoritativeAccount(
                UUID.fromString("00000000-0000-4000-8000-000000000000"),
                "SRC-P0-RESPONSIBILITY-001",
                digest("a"),
                "actor_v1_k1_" + "a".repeat(64),
                AuthoritativeStatus.ACTIVE,
                new EffectiveInterval(START, null),
                7,
                3));
    }

    @Test
    void rejectsInvalidEffectiveWindows() {
        assertThrows(IllegalArgumentException.class, () ->
                new EffectiveInterval(START, START));
        assertThrows(IllegalArgumentException.class, () ->
                new EffectiveInterval(START, START.minusSeconds(1)));
    }

    @Test
    void organizationGraphRejectsOrphanSelfParentCycleAndDuplicateExternalRef() {
        var root = organization(ROOT_ID, digest("root"), null);
        var child = organization(CHILD_ID, digest("child"), digest("root"));
        OrganizationGraph.validate(List.of(root, child));

        assertCode("IDENTITY_ORGANIZATION_ORPHAN", () ->
                OrganizationGraph.validate(List.of(organization(
                        CHILD_ID, digest("child"), digest("missing")))));
        assertCode("IDENTITY_ORGANIZATION_SELF_PARENT", () ->
                OrganizationGraph.validate(List.of(organization(
                        CHILD_ID, digest("child"), digest("child")))));
        assertCode("IDENTITY_ORGANIZATION_CYCLE", () ->
                OrganizationGraph.validate(List.of(
                        organization(ROOT_ID, digest("root"), digest("child")),
                        organization(CHILD_ID, digest("child"), digest("root")))));
        assertCode("IDENTITY_EXTERNAL_ID_DUPLICATE", () ->
                OrganizationGraph.validate(List.of(
                        root,
                        organization(CHILD_ID, digest("root"), null))));
    }

    @Test
    void roleBindingRequiresKnownAccountOrganizationAndApprovedMapping() {
        var binding = new EmploymentRoleBinding(
                UUID.fromString("019c1234-0000-7000-8000-000000000104"),
                ACCOUNT_ID,
                ROOT_ID,
                digest("employment"),
                "SANDBOX_COUNSELOR",
                TargetRole.R1_COUNSELOR,
                "IDENTITY-ROLE-MAPPING-1.0.0",
                AuthoritativeStatus.ACTIVE,
                new EffectiveInterval(START, null),
                7,
                2);
        assertEquals("R1-COUNSELOR", binding.targetRole().wireName());
        assertThrows(IllegalArgumentException.class, () -> new EmploymentRoleBinding(
                UUID.fromString("019c1234-0000-7000-8000-000000000104"),
                ACCOUNT_ID,
                ROOT_ID,
                digest("employment"),
                "COUNSELOR_BY_NAME",
                TargetRole.R1_COUNSELOR,
                "UNAPPROVED",
                AuthoritativeStatus.ACTIVE,
                new EffectiveInterval(START, null),
                7,
                2));
    }

    private static OrganizationNode organization(
            UUID id, String externalRefDigest, String parentExternalRefDigest) {
        return new OrganizationNode(
                id,
                "SRC-P0-RESPONSIBILITY-001",
                externalRefDigest,
                parentExternalRefDigest,
                "测试组织",
                OrganizationType.COLLEGE,
                AuthoritativeStatus.ACTIVE,
                new EffectiveInterval(START, null),
                7,
                1);
    }

    private static String digest(String prefix) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(prefix.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void assertCode(String code, Runnable operation) {
        IdentityAccessException error =
                assertThrows(IdentityAccessException.class, operation::run);
        assertEquals(code, error.code());
    }
}
