package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QualityRecoveryAuthorizationGuardTest {
    @Test
    void allowsOnlyCompositeAllowFollowedByCurrentRecheck() {
        CompositeAuthorizationRequest request = request("quality-fuse.recover");
        CompositeAuthorizationDecision allow = mock(CompositeAuthorizationDecision.class);
        when(allow.outcome()).thenReturn(CompositeAuthorizationOutcome.ALLOW);
        var token = mock(cn.edu.suda.scholarsense.identityaccess.api
                .CompositeAuthorizationDecisionToken.class);
        when(allow.decisionToken()).thenReturn(token);
        CompositeAuthorizationRecheckPort recheck = ignored ->
                new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT, "DECISION_CURRENT");
        var guard = new QualityRecoveryAuthorizationGuard(ignored -> allow, recheck);

        assertDoesNotThrow(() -> guard.authorize(request));
        assertDoesNotThrow(() -> guard.recheck(request, allow));
    }

    @Test
    void denyUnavailableStaleAndWrongActionAreConcealed() {
        CompositeAuthorizationDecision deny = mock(CompositeAuthorizationDecision.class);
        when(deny.outcome()).thenReturn(CompositeAuthorizationOutcome.DENY);
        assertThrows(IllegalStateException.class, () ->
                new QualityRecoveryAuthorizationGuard(
                        ignored -> deny, ignored -> null).authorize(request("quality-fuse.recover")));
        assertThrows(IllegalStateException.class, () ->
                new QualityRecoveryAuthorizationGuard(
                        ignored -> deny, ignored -> null).authorize(request("data-quality.read")));
    }

    private static CompositeAuthorizationRequest request(String action) {
        return new CompositeAuthorizationRequest(
                "actor", "RECOVERY_TASK", action, "a".repeat(64), 7,
                Optional.empty(), Optional.empty(), "00112233445566778899aabbccddeeff");
    }
}
