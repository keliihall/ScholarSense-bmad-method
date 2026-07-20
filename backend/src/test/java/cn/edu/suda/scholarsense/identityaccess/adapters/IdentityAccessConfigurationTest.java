package cn.edu.suda.scholarsense.identityaccess.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.application.AuditTokenDomain;
import org.junit.jupiter.api.Test;

class IdentityAccessConfigurationTest {
    @Test
    void repositoryOwnedDefaultsStartFailClosedUntilDeploymentOverridesEvidenceAndKeyBindings() {
        var configuration = new IdentityAccessConfiguration();

        assertEquals(100, configuration.identityTrustedClockConstraints().maximumSkewMs());
        assertTrue(configuration.unavailableTimeSynchronizationStatusProvider().current().isEmpty());
        assertThrows(IllegalStateException.class, () -> configuration
                .unavailableIdentityAuditTokenPort().tokenize(AuditTokenDomain.ACTOR, "actor"));
    }
}
