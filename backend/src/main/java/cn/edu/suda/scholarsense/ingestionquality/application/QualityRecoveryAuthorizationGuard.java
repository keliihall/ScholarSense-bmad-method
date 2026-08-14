package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckRequest;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest;
import java.util.Objects;

/** Composite authorization + current-evidence recheck guard for quality-fuse.recover only. */
public final class QualityRecoveryAuthorizationGuard {
    private final CompositeAuthorizationPort authorization;
    private final CompositeAuthorizationRecheckPort recheck;

    public QualityRecoveryAuthorizationGuard(
            CompositeAuthorizationPort authorization,
            CompositeAuthorizationRecheckPort recheck) {
        this.authorization = Objects.requireNonNull(authorization);
        this.recheck = Objects.requireNonNull(recheck);
    }

    public CompositeAuthorizationDecision authorize(CompositeAuthorizationRequest request) {
        requireRecoveryRequest(request);
        CompositeAuthorizationDecision decision = authorization.authorize(request);
        if (decision.outcome() != CompositeAuthorizationOutcome.ALLOW) throw denied();
        return decision;
    }

    public void recheck(
            CompositeAuthorizationRequest current,
            CompositeAuthorizationDecision prior) {
        requireRecoveryRequest(current);
        CompositeAuthorizationRecheckDecision decision = recheck.recheck(
                new CompositeAuthorizationRecheckRequest(current, prior.decisionToken()));
        if (decision.outcome() != CompositeAuthorizationRecheckOutcome.CURRENT) throw denied();
    }

    private static void requireRecoveryRequest(CompositeAuthorizationRequest request) {
        if (request == null || !"RECOVERY_TASK".equals(request.objectClass())
                || !"quality-fuse.recover".equals(request.actionId())) throw denied();
    }

    private static IllegalStateException denied() {
        return new IllegalStateException("QUALITY_RECOVERY_NOT_FOUND");
    }
}
