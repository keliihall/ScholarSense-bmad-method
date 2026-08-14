package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Transport-neutral D4 request; clients cannot supply approval evidence or checker decisions. */
public record HighRiskApprovalRequest(
        UUID requestId,
        String requestDigest,
        String actionType,
        String makerPrincipalDigest,
        String authorizationContextDigest,
        String authenticationStateDigest,
        String objectType,
        String objectRefDigest,
        long objectVersion,
        String scopeDigest,
        String impactScopeDigest,
        String dataSensitivity,
        String currentState,
        String targetState,
        String reasonCode,
        String matrixVersion,
        String matrixDigest,
        String policyVersion,
        String policyDigest,
        String roleFieldPolicyVersion,
        String roleFieldPolicyDigest,
        String previewDigest,
        String checkerSetDigest,
        List<String> requiredCheckerPrincipalDigests,
        long authorizationGeneration,
        Instant requestedAt,
        String traceId,
        String idempotencyKey) {
    public HighRiskApprovalRequest {
        requiredCheckerPrincipalDigests = List.copyOf(requiredCheckerPrincipalDigests);
        if (idempotencyKey == null || idempotencyKey.length() < 16
                || idempotencyKey.length() > 128 || requestedAt == null) {
            throw new IllegalArgumentException("HIGH_RISK_APPROVAL_REQUEST_INVALID");
        }
    }
}
