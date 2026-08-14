package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.UUID;

/** Counts/status projection deliberately omits checker principal details. */
public record HighRiskApprovalView(
        UUID approvalId,
        long approvalVersion,
        UUID requestId,
        String status,
        int requiredCheckerCount,
        int approvedCheckerCount,
        Instant requestedAt,
        Instant expiresAt,
        String receiptDigest,
        String traceId) {}
