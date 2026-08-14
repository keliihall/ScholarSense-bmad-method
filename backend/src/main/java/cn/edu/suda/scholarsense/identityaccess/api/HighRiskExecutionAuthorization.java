package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.UUID;

/** Signed lease projection consumed by the ingestion-quality owner transaction. */
public record HighRiskExecutionAuthorization(
        UUID leaseId,
        long leaseVersion,
        String leaseDigest,
        UUID executionJti,
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
        long authorizationGeneration,
        String state,
        Instant issuedAt,
        Instant authorizedUntil,
        String issuer,
        String audience,
        String keyVersion,
        String signature,
        String traceId) {}
