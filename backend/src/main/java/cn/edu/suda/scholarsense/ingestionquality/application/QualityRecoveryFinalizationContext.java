package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.List;
import java.util.UUID;

/** Owner-current facts used to construct and later recheck a fresh final D4 binding. */
public record QualityRecoveryFinalizationContext(
        UUID recoveryId,
        long recoveryVersion,
        UUID taskId,
        long taskVersion,
        UUID episodeId,
        long episodeVersion,
        long generation,
        String sourceId,
        String dependencyId,
        String taskStatus,
        boolean episodeActive,
        String observationStatus,
        long observationVersion,
        String observationDecisionDigest,
        String finalObservationWatermark,
        String policyVersion,
        String policyDigest,
        String memberSetDigest,
        String watermarksDigest,
        String finalPreviewDigest,
        String finalizationState,
        UUID approvalId,
        Long approvalVersion,
        String approvalReceiptDigest,
        String checkerSetDigest,
        String ownerBindingSetDigest,
        String checkerPersonSetDigest,
        String authorizationContextDigest,
        String authenticationStateDigest,
        long authorizationGeneration,
        String makerPrincipalDigest,
        String requestDigest,
        String scopeDigest,
        String impactScopeDigest,
        List<AffectedRule> affectedRules,
        String traceId) {
    public QualityRecoveryFinalizationContext {
        affectedRules = List.copyOf(affectedRules);
        if (recoveryId == null || taskId == null || episodeId == null
                || recoveryVersion < 1 || taskVersion < 1 || episodeVersion < 1
                || generation < 1 || affectedRules.isEmpty()) {
            throw new IllegalArgumentException("QUALITY_FINALIZATION_CONTEXT_INVALID");
        }
    }

    public record AffectedRule(String ruleId, String ruleVersion, String ruleVersionDigest) {}
}
