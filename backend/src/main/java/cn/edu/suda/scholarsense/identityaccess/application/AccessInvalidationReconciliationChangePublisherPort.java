package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.List;

@FunctionalInterface
public interface AccessInvalidationReconciliationChangePublisherPort {
    void publish(
            ResponsibilityReconciliationResult result,
            List<ResponsibilityExceptionAuditTransition> transitions);
}
