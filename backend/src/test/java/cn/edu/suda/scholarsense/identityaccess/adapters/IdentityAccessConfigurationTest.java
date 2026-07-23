package cn.edu.suda.scholarsense.identityaccess.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.application.AuditTokenDomain;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchAuthorizationRequest;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchTokenDomain;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchTokenQuery;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchView;
import java.time.Instant;
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
}
