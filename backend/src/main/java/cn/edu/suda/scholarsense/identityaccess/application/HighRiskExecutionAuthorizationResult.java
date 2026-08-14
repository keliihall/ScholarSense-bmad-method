package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.UUID;

public record HighRiskExecutionAuthorizationResult(
        UUID leaseId, long leaseVersion, String leaseDigest, UUID executionJti,
        UUID approvalId, long approvalVersion, String approvalReceiptDigest,
        String requestDigest, String actionType, String objectType, String objectRefDigest,
        long objectVersion, String scopeDigest, String impactScopeDigest, String previewDigest,
        long authorizationGeneration, String state, Instant issuedAt, Instant authorizedUntil,
        String issuer, String audience, String keyVersion, String signature, String traceId) {}
