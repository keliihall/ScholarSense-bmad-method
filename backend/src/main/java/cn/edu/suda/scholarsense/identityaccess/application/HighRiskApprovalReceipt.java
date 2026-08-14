package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApprovalStatus;
import java.time.Instant;
import java.util.UUID;

/** Immutable signed decision evidence persisted beside the approval aggregate. */
public record HighRiskApprovalReceipt(
        UUID approvalId,
        long approvalVersion,
        UUID requestId,
        String requestDigest,
        HighRiskApprovalStatus status,
        String decisionActorPrincipalDigest,
        Instant decidedAt,
        Instant expiresAt,
        String receiptDigest,
        String keyVersion,
        String signature,
        String traceId) {}
