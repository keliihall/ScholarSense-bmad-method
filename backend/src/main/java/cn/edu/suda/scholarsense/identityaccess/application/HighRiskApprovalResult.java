package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.UUID;

public record HighRiskApprovalResult(
        UUID approvalId, long approvalVersion, UUID requestId, String status,
        int requiredCheckerCount, int approvedCheckerCount, Instant requestedAt,
        Instant expiresAt, String receiptDigest, String traceId) {}
