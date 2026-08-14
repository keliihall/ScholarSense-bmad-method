package cn.edu.suda.scholarsense.identityaccess.api;

public interface HighRiskExecutionAuthorizationPort {
    HighRiskExecutionAuthorization issueOrReplay(HighRiskExecutionAuthorizationRequest request);

    HighRiskExecutionAuthorization reserve(
            HighRiskExecutionAuthorization authorization, java.time.Instant trustedNow);

    default boolean verify(HighRiskExecutionAuthorization authorization) {
        return false;
    }

    HighRiskExecutionAuthorization reconcile(HighRiskExecutionReconciliation reconciliation);
}
