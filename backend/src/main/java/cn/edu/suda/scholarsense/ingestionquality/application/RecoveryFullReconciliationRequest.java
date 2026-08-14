package cn.edu.suda.scholarsense.ingestionquality.application;

public record RecoveryFullReconciliationRequest(
        String recoveryRequestId,
        String episodeId,
        String taskId,
        String ruleVersionsDigest,
        String memberSetDigest,
        String watermarksDigest,
        String backfillSummaryDigest,
        String traceId) {
    public RecoveryFullReconciliationRequest {
        RecoveryValidationPortRules.idsAndDigests(
                recoveryRequestId, episodeId, taskId, traceId,
                ruleVersionsDigest, memberSetDigest, watermarksDigest,
                backfillSummaryDigest);
    }
}
