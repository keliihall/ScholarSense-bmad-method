package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchAuthorizationRequest;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchView;
import cn.edu.suda.scholarsense.identityaccess.api.AuthoritativeIdentityContext;
import cn.edu.suda.scholarsense.identityaccess.api.IdentityFreshness;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import cn.edu.suda.scholarsense.identityaccess.domain.RoleFieldPolicyCatalog;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompositeAuditSearchAuthorizationAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final String SESSION = "sp_" + "a".repeat(32);
    private static final UUID ACCOUNT =
            UUID.fromString("019c1234-0000-7000-8000-000000000701");
    private static final UUID COLLEGE =
            UUID.fromString("019c1234-0000-7000-8000-000000000702");

    @Test
    void r3BusinessAndR7TechnicalUseTheCompleteEvaluator() {
        var r3 = adapter(List.of("R3-STUDENT-AFFAIRS"));
        var business = r3.authorize(request(AuditSearchView.BUSINESS));
        assertTrue(business.allowed());
        assertEquals("audit.search-business-metadata", business.action());
        assertEquals(Set.of("audit-domain"), business.scopes());
        assertTrue(r3.capabilityManifest().productionAuthorizationEnabled());

        var r7 = adapter(List.of("R7-PLATFORM-OPS"));
        var technical = r7.authorize(request(AuditSearchView.TECHNICAL));
        assertTrue(technical.allowed());
        assertEquals("audit.search-technical-metadata", technical.action());
        assertEquals(cn.edu.suda.scholarsense.identityaccess.api.FieldVisibility.CLEAR,
                technical.fieldProjection().get("T"));
    }

    @Test
    void multiRoleNoLongerUsesTheConformanceAdaptersBlanketDeny() {
        var adapter = adapter(List.of("R3-STUDENT-AFFAIRS", "R7-PLATFORM-OPS"));

        assertTrue(adapter.authorize(request(AuditSearchView.BUSINESS)).allowed());
        assertTrue(adapter.authorize(request(AuditSearchView.TECHNICAL)).allowed());
    }

    @Test
    void wrongRoleExpiredSessionAndMissingAuthorityFailClosed() {
        assertFalse(adapter(List.of("R1-COUNSELOR"))
                .authorize(request(AuditSearchView.BUSINESS)).allowed());

        var session = IdentitySession.authenticate(
                "session-id",
                SESSION,
                "actor-pseudonym",
                "browser-binding",
                "https://app.test.invalid",
                "refresh-family",
                "refresh-digest",
                NOW.minusSeconds(IdentitySession.IDLE_WINDOW.toSeconds() + 1));
        var expired = new CompositeAuditSearchAuthorizationAdapter(
                pseudonym -> Optional.of(session),
                actor -> Optional.of(context(List.of("R3-STUDENT-AFFAIRS"))),
                CompositeAuditSearchAuthorizationAdapterTest::trustedTime,
                RoleFieldPolicyCatalog.approved());
        assertFalse(expired.authorize(request(AuditSearchView.BUSINESS)).allowed());

        var missing = new CompositeAuditSearchAuthorizationAdapter(
                pseudonym -> Optional.of(activeSession()),
                actor -> Optional.empty(),
                CompositeAuditSearchAuthorizationAdapterTest::trustedTime,
                RoleFieldPolicyCatalog.approved());
        assertEquals("AUDIT_SEARCH_AUTHORITY_UNAVAILABLE",
                missing.authorize(request(AuditSearchView.BUSINESS)).reasonCode());
    }

    private static CompositeAuditSearchAuthorizationAdapter adapter(List<String> roles) {
        return new CompositeAuditSearchAuthorizationAdapter(
                pseudonym -> Optional.of(activeSession()),
                actor -> Optional.of(context(roles)),
                CompositeAuditSearchAuthorizationAdapterTest::trustedTime,
                RoleFieldPolicyCatalog.approved());
    }

    private static IdentitySession activeSession() {
        return IdentitySession.authenticate(
                "session-id",
                SESSION,
                "actor-pseudonym",
                "browser-binding",
                "https://app.test.invalid",
                "refresh-family",
                "refresh-digest",
                NOW);
    }

    private static AuthoritativeIdentityContext context(List<String> roles) {
        return new AuthoritativeIdentityContext(
                ACCOUNT,
                roles,
                List.of(COLLEGE),
                3,
                7,
                7,
                IdentityFreshness.FRESH,
                Map.of(
                        "identitySessionPolicy", "ISP-1.0.0",
                        "roleFieldPolicy", "RFP-1.0.0",
                        "roleMapping", "IDENTITY-ROLE-MAPPING-1.0.0",
                        "roleMappingDigest", "sha256:" + "a".repeat(64)),
                NOW);
    }

    private static TrustedTime trustedTime() {
        return new TrustedTime(
                NOW,
                new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(10),
                        "evidence://signed/clock/campus-ntp-a.json"));
    }

    private static AuditSearchAuthorizationRequest request(AuditSearchView view) {
        return new AuditSearchAuthorizationRequest(
                SESSION, view, "audit-record", "audit-domain", "trace-search-test-001");
    }
}
