package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.domain.CompositeAuthorizationEvaluator;
import cn.edu.suda.scholarsense.identityaccess.domain.CompositeAuthorizationRequest;
import cn.edu.suda.scholarsense.identityaccess.domain.RoleFieldPolicyCatalog;

/** Application use case over the pure authorization domain kernel. */
public final class CompositeAuthorizationService {
    private final CompositeAuthorizationEvaluator evaluator;
    private final RoleFieldPolicyCatalog policy;

    public CompositeAuthorizationService(
            CompositeAuthorizationEvaluator evaluator,
            RoleFieldPolicyCatalog policy) {
        this.evaluator = java.util.Objects.requireNonNull(evaluator);
        this.policy = java.util.Objects.requireNonNull(policy);
    }

    public CompositeAuthorizationDecision authorize(CompositeAuthorizationRequest request) {
        return evaluator.evaluate(request, policy);
    }
}
