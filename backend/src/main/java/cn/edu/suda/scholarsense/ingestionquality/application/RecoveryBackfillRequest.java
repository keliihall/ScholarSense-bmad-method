package cn.edu.suda.scholarsense.ingestionquality.application;

public record RecoveryBackfillRequest(
        String recoveryRequestId,
        String episodeId,
        String taskId,
        String ruleVersionsDigest,
        String memberSetDigest,
        String startWatermarkDigest,
        String targetWatermarkDigest,
        String traceId) {
    public RecoveryBackfillRequest {
        RecoveryValidationPortRules.idsAndDigests(
                recoveryRequestId, episodeId, taskId, traceId,
                ruleVersionsDigest, memberSetDigest, startWatermarkDigest,
                targetWatermarkDigest);
    }
}
