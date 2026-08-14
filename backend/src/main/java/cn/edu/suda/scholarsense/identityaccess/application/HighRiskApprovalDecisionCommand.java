package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.UUID;

public record HighRiskApprovalDecisionCommand(
        UUID approvalId, long expectedApprovalVersion, Decision decision,
        String actorPrincipalDigest, String currentCheckerSetDigest,
        long currentAuthorizationGeneration, Instant trustedNow, String traceId,
        String idempotencyKey) {
    public enum Decision { APPROVE, REJECT, CANCEL }
}
