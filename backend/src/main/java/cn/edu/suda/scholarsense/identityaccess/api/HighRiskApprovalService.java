package cn.edu.suda.scholarsense.identityaccess.api;

import cn.edu.suda.scholarsense.identityaccess.application.HighRiskApprovalCommand;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskApprovalDecisionCommand;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskApprovalResult;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskApprovalUseCase;
import java.util.Objects;

/** Public facade maps API values into the inward application boundary. */
public final class HighRiskApprovalService implements HighRiskApprovalPort {
    private final HighRiskApprovalUseCase useCase;

    public HighRiskApprovalService(HighRiskApprovalUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase);
    }

    @Override
    public HighRiskApprovalView request(HighRiskApprovalRequest value) {
        return view(useCase.request(new HighRiskApprovalCommand(
                value.requestId(), value.requestDigest(), value.actionType(),
                value.makerPrincipalDigest(), value.authorizationContextDigest(),
                value.authenticationStateDigest(), value.objectType(), value.objectRefDigest(),
                value.objectVersion(), value.scopeDigest(), value.impactScopeDigest(),
                value.dataSensitivity(), value.currentState(), value.targetState(),
                value.reasonCode(), value.matrixVersion(), value.matrixDigest(),
                value.policyVersion(), value.policyDigest(), value.roleFieldPolicyVersion(),
                value.roleFieldPolicyDigest(), value.previewDigest(), value.checkerSetDigest(),
                value.requiredCheckerPrincipalDigests(), value.authorizationGeneration(),
                value.requestedAt(), value.traceId(), value.idempotencyKey(),
                value.observationDecisionDigest(), value.memberSetDigest(),
                value.watermarksDigest(), value.qualityRecoveryPolicyVersion(),
                value.qualityRecoveryPolicyDigest())));
    }

    @Override
    public HighRiskApprovalView decide(HighRiskApprovalDecision value) {
        return view(useCase.decide(new HighRiskApprovalDecisionCommand(
                value.approvalId(), value.expectedApprovalVersion(),
                HighRiskApprovalDecisionCommand.Decision.valueOf(value.decision().name()),
                value.actorPrincipalDigest(), value.currentCheckerSetDigest(),
                value.currentAuthorizationGeneration(), value.trustedNow(), value.traceId(),
                value.idempotencyKey())));
    }

    private static HighRiskApprovalView view(HighRiskApprovalResult value) {
        return new HighRiskApprovalView(
                value.approvalId(), value.approvalVersion(), value.requestId(), value.status(),
                value.requiredCheckerCount(), value.approvedCheckerCount(), value.requestedAt(),
                value.expiresAt(), value.receiptDigest(), value.traceId());
    }
}
