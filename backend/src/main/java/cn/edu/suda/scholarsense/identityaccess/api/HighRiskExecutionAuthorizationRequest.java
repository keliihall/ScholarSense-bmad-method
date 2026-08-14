package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.UUID;

public record HighRiskExecutionAuthorizationRequest(
        UUID approvalId,
        long approvalVersion,
        String approvalReceiptDigest,
        String requestDigest,
        String actionType,
        String objectType,
        String objectRefDigest,
        long objectVersion,
        String scopeDigest,
        String impactScopeDigest,
        String previewDigest,
        String authorizationContextDigest,
        String authenticationStateDigest,
        String checkerSetDigest,
        long authorizationGeneration,
        String audience,
        String idempotencyKeyDigest,
        String issuanceRequestDigest,
        Instant trustedNow,
        String traceId) {}
