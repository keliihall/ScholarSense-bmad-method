package cn.edu.suda.scholarsense.signalevaluation.application;

/** Trusted resolver input copied from the already validated public request. */
public record RecoverySampleResolutionCommand(
        String opaqueSubjectWindowSelectionRef,
        String ruleVersionsDigest,
        String memberSetDigest,
        String watermarksDigest,
        String qualityRecoveryPolicyDigest,
        String selectionSeed,
        int requestedSampleCount,
        String traceId) {

    static RecoverySampleResolutionCommand from(RecoverySampleComputationRequest request) {
        return new RecoverySampleResolutionCommand(
                request.opaqueSubjectWindowSelectionRef(), request.ruleVersionsDigest(),
                request.memberSetDigest(), request.watermarksDigest(),
                request.qualityRecoveryPolicyDigest(), request.selectionSeed(),
                request.requestedSampleCount(), request.traceId());
    }
}
