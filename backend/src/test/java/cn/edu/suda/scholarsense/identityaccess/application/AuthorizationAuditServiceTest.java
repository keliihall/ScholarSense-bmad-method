package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.domain.FieldClass;
import cn.edu.suda.scholarsense.identityaccess.domain.RolePackage;
import cn.edu.suda.scholarsense.identityaccess.domain.ScopeAnchor;
import cn.edu.suda.scholarsense.identityaccess.domain.Visibility;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthorizationAuditServiceTest {
    @Test
    void keepsTheFrozenV1FactAndRetainsTheSuccessorEvidenceBesideIt() {
        List<IdentityAuditRecord> records = new ArrayList<>();
        var service = new AuthorizationAuditService(AuditTestSupport.factory(), records::add);

        service.record(new AuthorizationAuditRequest(
                AuthorizationAuditKind.OBJECT_DECISION,
                "actor-sensitive-pseudonym",
                Set.of(RolePackage.R1),
                "care.read",
                "a".repeat(64),
                null,
                Set.of(ScopeAnchor.CURRENT_RESPONSIBILITY),
                7L,
                fields(),
                "ALLOW",
                Instant.parse("2026-07-20T02:00:00Z"),
                "0123456789abcdef0123456789abcdef",
                null));

        var fact = records.getFirst().fact();
        assertEquals("authorization.object.decided", fact.action());
        assertEquals(List.of("R1"), fact.roleIds());
        assertEquals("RFP-1.0.0", fact.policyVersions().get("roleFieldPolicy"));
        assertEquals(List.of("CURRENT_RESPONSIBILITY"),
                fact.authorizationContext().get("scopeCodes"));
        assertEquals(Set.of(
                        "decision", "policyVersion", "scopeCodes",
                        "grantSearchTokens", "notApplicableReason"),
                fact.authorizationContext().keySet());
        var successor = records.getFirst().authorizationDecisionContext().orElseThrow();
        assertEquals("care.read", successor.actionId());
        assertEquals("a".repeat(64), successor.objectTokenDigest());
        assertEquals(7L, successor.objectVersion());
        assertEquals("ALLOW", successor.result());
        assertEquals(Set.of(
                        "actorPseudonym", "rolePackages", "actionId", "objectTokenDigest",
                        "policyVersion", "scopeAnchorSummary", "objectVersion",
                        "fieldProjectionSummary", "result", "trustedAt", "traceId"),
                successor.asMap().keySet());
        assertTrue(successor.actorPseudonym().startsWith("ast_v1_k1_"));
        assertFalse(records.getFirst().toString().contains("actor-sensitive-pseudonym"));
    }

    @Test
    void auditFailurePropagatesBeforeCallerCanReleaseContent() {
        var service = new AuthorizationAuditService(
                AuditTestSupport.factory(),
                ignored -> { throw new IllegalStateException("injected audit failure"); });

        assertThrows(RuntimeException.class, () -> service.record(new AuthorizationAuditRequest(
                AuthorizationAuditKind.SHELL_VIEW,
                "actor-sensitive-pseudonym",
                Set.of(RolePackage.R3),
                null,
                "authorized-shell",
                "sp_" + "a".repeat(32),
                Set.of(),
                3L,
                fields(),
                "ALLOW",
                Instant.parse("2026-07-20T02:00:00Z"),
                "0123456789abcdef0123456789abcdef",
                null)));
    }

    private static EnumMap<FieldClass, Visibility> fields() {
        EnumMap<FieldClass, Visibility> fields = new EnumMap<>(FieldClass.class);
        for (FieldClass field : FieldClass.values()) {
            fields.put(field, Visibility.HIDDEN);
        }
        return fields;
    }
}
