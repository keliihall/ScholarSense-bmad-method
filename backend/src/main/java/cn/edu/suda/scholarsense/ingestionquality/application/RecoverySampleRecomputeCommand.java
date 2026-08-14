package cn.edu.suda.scholarsense.ingestionquality.application;

/** Closed IQ-owned request mapped into the signal-evaluation public API by one adapter. */
public record RecoverySampleRecomputeCommand(
        String providerVersion,
        String recoveryRequestId,
        String episodeId,
        String taskId,
        String ruleVersionsDigest,
        String memberSetDigest,
        String watermarksDigest,
        String qualityRecoveryPolicyVersion,
        String qualityRecoveryPolicyDigest,
        String opaqueSubjectWindowSelectionRef,
        String selectionSeed,
        int requestedSampleCount,
        String requestDigest,
        String traceId) {

    public RecoverySampleRecomputeCommand {
        RecoveryValidationPortRules.idsAndDigests(
                recoveryRequestId, episodeId, taskId, traceId,
                ruleVersionsDigest, memberSetDigest, watermarksDigest,
                qualityRecoveryPolicyDigest, selectionSeed, requestDigest);
        if (!"RECOVERY-SAMPLE-PROVIDER-1.0.0".equals(providerVersion)
                || !"QRP-1.0.0".equals(qualityRecoveryPolicyVersion)
                || !"sha256:195a22553b13ac35da5923e704ef8385f85d17574b8a753cc1afc16c2055f366"
                        .equals(qualityRecoveryPolicyDigest)
                || opaqueSubjectWindowSelectionRef == null
                || !opaqueSubjectWindowSelectionRef.matches("oswref:v1:[0-9a-f]{64}")
                || requestedSampleCount < 1 || requestedSampleCount > 10_000) {
            throw new IllegalArgumentException("RECOVERY_SAMPLE_COMMAND_INVALID");
        }
    }
}
