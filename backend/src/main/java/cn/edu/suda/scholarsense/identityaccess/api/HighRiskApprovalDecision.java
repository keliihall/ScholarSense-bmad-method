package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.UUID;

public record HighRiskApprovalDecision(
        UUID approvalId,
        long expectedApprovalVersion,
        Decision decision,
        String actorPrincipalDigest,
        String currentCheckerSetDigest,
        long currentAuthorizationGeneration,
        Instant trustedNow,
        String traceId,
        String idempotencyKey) {
    public enum Decision { APPROVE, REJECT, CANCEL }
}
