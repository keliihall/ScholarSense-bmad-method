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
        long authorizationGeneration, Instant requestedAt, String traceId, String idempotencyKey) {}
