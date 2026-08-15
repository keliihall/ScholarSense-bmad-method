package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HighRiskApprovalCommand(
        UUID requestId, String requestDigest, String actionType, String makerPrincipalDigest,
        String authorizationContextDigest, String authenticationStateDigest, String objectType,
        String objectRefDigest, long objectVersion, String scopeDigest, String impactScopeDigest,
        String dataSensitivity, String currentState, String targetState, String reasonCode,
        String matrixVersion, String matrixDigest, String policyVersion, String policyDigest,
        String roleFieldPolicyVersion, String roleFieldPolicyDigest, String previewDigest,
        String checkerSetDigest, List<String> requiredCheckerPrincipalDigests,
        long authorizationGeneration, Instant requestedAt, String traceId, String idempotencyKey,
        String observationDecisionDigest, String memberSetDigest, String watermarksDigest,
        String qualityRecoveryPolicyVersion, String qualityRecoveryPolicyDigest) {
    public HighRiskApprovalCommand(
            UUID requestId, String requestDigest, String actionType,
            String makerPrincipalDigest, String authorizationContextDigest,
            String authenticationStateDigest, String objectType, String objectRefDigest,
            long objectVersion, String scopeDigest, String impactScopeDigest,
            String dataSensitivity, String currentState, String targetState, String reasonCode,
            String matrixVersion, String matrixDigest, String policyVersion,
            String policyDigest, String roleFieldPolicyVersion, String roleFieldPolicyDigest,
            String previewDigest, String checkerSetDigest,
            List<String> requiredCheckerPrincipalDigests, long authorizationGeneration,
            Instant requestedAt, String traceId, String idempotencyKey) {
        this(requestId, requestDigest, actionType, makerPrincipalDigest,
                authorizationContextDigest, authenticationStateDigest, objectType,
                objectRefDigest, objectVersion, scopeDigest, impactScopeDigest,
                dataSensitivity, currentState, targetState, reasonCode, matrixVersion,
                matrixDigest, policyVersion, policyDigest, roleFieldPolicyVersion,
                roleFieldPolicyDigest, previewDigest, checkerSetDigest,
                requiredCheckerPrincipalDigests, authorizationGeneration, requestedAt,
                traceId, idempotencyKey, null, null, null, null, null);
    }
}
