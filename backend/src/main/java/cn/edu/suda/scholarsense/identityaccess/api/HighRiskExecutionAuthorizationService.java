package cn.edu.suda.scholarsense.identityaccess.api;

import cn.edu.suda.scholarsense.identityaccess.application.HighRiskExecutionAuthorizationCommand;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskExecutionAuthorizationResult;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskExecutionAuthorizationUseCase;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskExecutionReconciliationCommand;
import java.time.Instant;
import java.util.Objects;

/** Public facade for the identity-access-owned durable lease protocol. */
public final class HighRiskExecutionAuthorizationService
        implements HighRiskExecutionAuthorizationPort {
    private final HighRiskExecutionAuthorizationUseCase useCase;

    public HighRiskExecutionAuthorizationService(
            HighRiskExecutionAuthorizationUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase);
    }

    @Override
    public HighRiskExecutionAuthorization issueOrReplay(
            HighRiskExecutionAuthorizationRequest value) {
        return view(useCase.issueOrReplay(new HighRiskExecutionAuthorizationCommand(
                value.approvalId(), value.approvalVersion(), value.approvalReceiptDigest(),
                value.requestDigest(), value.actionType(), value.objectType(),
                value.objectRefDigest(), value.objectVersion(), value.scopeDigest(),
                value.impactScopeDigest(), value.previewDigest(),
                value.authorizationContextDigest(), value.authenticationStateDigest(),
                value.checkerSetDigest(), value.authorizationGeneration(), value.audience(),
                value.idempotencyKeyDigest(), value.issuanceRequestDigest(),
                value.trustedNow(), value.traceId())));
    }

    @Override
    public HighRiskExecutionAuthorization reserve(
            HighRiskExecutionAuthorization value, Instant trustedNow) {
        return view(useCase.reserve(result(value), trustedNow));
    }

    @Override
    public boolean verify(HighRiskExecutionAuthorization value) {
        return useCase.verify(result(value));
    }

    @Override
    public HighRiskExecutionAuthorization reconcile(
            HighRiskExecutionReconciliation value) {
        return view(useCase.reconcile(new HighRiskExecutionReconciliationCommand(
                value.leaseId(), value.leaseVersion(), value.leaseDigest(),
                value.executionJti(), value.requestDigest(), value.ownerCommitId(),
                value.ownerCommittedAt(), value.ownerResultDigest(), value.outboxEventId(),
                value.reconciliationDigest(), value.traceId())));
    }

    private static HighRiskExecutionAuthorization view(
            HighRiskExecutionAuthorizationResult value) {
        return new HighRiskExecutionAuthorization(
                value.leaseId(), value.leaseVersion(), value.leaseDigest(), value.executionJti(),
                value.approvalId(), value.approvalVersion(), value.approvalReceiptDigest(),
                value.requestDigest(), value.actionType(), value.objectType(),
                value.objectRefDigest(), value.objectVersion(), value.scopeDigest(),
                value.impactScopeDigest(), value.previewDigest(), value.authorizationGeneration(),
                value.state(), value.issuedAt(), value.authorizedUntil(), value.issuer(),
                value.audience(), value.keyVersion(), value.signature(), value.traceId());
    }

    private static HighRiskExecutionAuthorizationResult result(
            HighRiskExecutionAuthorization value) {
        return new HighRiskExecutionAuthorizationResult(
                value.leaseId(), value.leaseVersion(), value.leaseDigest(), value.executionJti(),
                value.approvalId(), value.approvalVersion(), value.approvalReceiptDigest(),
                value.requestDigest(), value.actionType(), value.objectType(),
                value.objectRefDigest(), value.objectVersion(), value.scopeDigest(),
                value.impactScopeDigest(), value.previewDigest(), value.authorizationGeneration(),
                value.state(), value.issuedAt(), value.authorizedUntil(), value.issuer(),
                value.audience(), value.keyVersion(), value.signature(), value.traceId());
    }
}
