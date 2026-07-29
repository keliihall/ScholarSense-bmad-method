package cn.edu.suda.scholarsense.identityaccess.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.application.AuditTokenDomain;
import cn.edu.suda.scholarsense.identityaccess.application.AuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.AuthoritativeIdentityContext;
import cn.edu.suda.scholarsense.identityaccess.api.IdentityFreshness;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchAuthorizationRequest;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchTokenDomain;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchTokenQuery;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityAccessConfigurationTest {
    @Test
    void repositoryOwnedDefaultsStartFailClosedUntilDeploymentOverridesEvidenceAndKeyBindings() {
        var configuration = new IdentityAccessConfiguration();

        assertEquals(100, configuration.identityTrustedClockConstraints().maximumSkewMs());
        assertTrue(configuration.unavailableTimeSynchronizationStatusProvider().current().isEmpty());
        assertThrows(IllegalStateException.class, () -> configuration
                .unavailableIdentityAuditTokenPort().tokenize(AuditTokenDomain.ACTOR, "actor"));
        var authorization = configuration.unavailableAuditSearchAuthorizationPort();
        var decision = authorization.authorize(new AuditSearchAuthorizationRequest(
                "session", AuditSearchView.BUSINESS, "fixture", "scope", "trace-test-001"));
        assertEquals("AUDIT_SEARCH_AUTHORITY_UNAVAILABLE", decision.reasonCode());
        assertTrue(authorization.capabilityManifest().conformanceVerified());
        assertEquals(false, authorization.capabilityManifest().productionAuthorizationEnabled());
        assertThrows(IllegalStateException.class, () -> configuration
                .unavailableAuditSearchTokenQueryPort()
                .query(new AuditSearchTokenQuery(AuditSearchTokenDomain.ACTOR, "actor", Instant.EPOCH)));
    }

    @Test
    void currentAuthorizationFailsClosedForUnavailableRfpOrRoleMappingConfiguration() {
        var configuration = new IdentityAccessConfiguration();
        var context = new AuthoritativeIdentityContext(
                UUID.fromString("019c1234-0000-7000-8000-000000000701"),
                List.of("R3-STUDENT-AFFAIRS"),
                List.of(UUID.fromString("019c1234-0000-7000-8000-000000000702")),
                3,
                7,
                7,
                IdentityFreshness.FRESH,
                Map.of(
                        "identitySessionPolicy", "ISP-1.0.0",
                        "roleFieldPolicy", "RFP-0.9.0",
                        "roleMapping", "IDENTITY-ROLE-MAPPING-0.9.0",
                        "roleMappingDigest", "sha256:" + "a".repeat(64)),
                Instant.parse("2026-07-24T01:00:00Z"));
        var decision = configuration.authorizationRecalculationPort(
                        actor -> Optional.of(context),
                        () -> new TrustedTime(
                                Instant.parse("2026-07-24T01:00:00Z"),
                                new TimeSourceProfile(
                                        "campus-ntp-a",
                                        "AUDIT-CLOCK-BINDING-1.0.0",
                                        5,
                                        Instant.parse("2026-07-24T00:59:50Z"),
                                        Instant.parse("2026-07-24T01:00:50Z"),
                                        "evidence://signed/clock/campus-ntp-a.json")))
                .decide("actor", "session");

        assertEquals(AuthorizationOutcome.DEPENDENCY_UNAVAILABLE, decision.outcome());
        assertEquals("IDENTITY_AUTHORIZATION_POLICY_UNAVAILABLE", decision.reasonCode());
    }
}
