package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.UUID;

/** Immutable, digest-only input binding for one recovery validation job. */
public record RecoveryValidationJobBinding(
        UUID recoveryRequestId,
        UUID episodeId,
        UUID taskId,
        String inputDigest,
        String qualityRecoveryPolicyVersion,
        String qualityRecoveryPolicyDigest,
        String ruleVersionsDigest,
        String memberSetDigest,
        String watermarksDigest,
        String selectionSeed,
        String traceId) {

    public RecoveryValidationJobBinding {
        IngestionQualityDomainRules.requireUuidV7(recoveryRequestId);
        IngestionQualityDomainRules.requireUuidV7(episodeId);
        IngestionQualityDomainRules.requireUuidV7(taskId);
        IngestionQualityDomainRules.requireSha256(inputDigest);
        if (!"QRP-1.0.0".equals(qualityRecoveryPolicyVersion)) {
            throw IngestionQualityDomainRules.invalid();
        }
        IngestionQualityDomainRules.requireSha256(qualityRecoveryPolicyDigest);
        IngestionQualityDomainRules.requireSha256(ruleVersionsDigest);
        IngestionQualityDomainRules.requireSha256(memberSetDigest);
        IngestionQualityDomainRules.requireSha256(watermarksDigest);
        IngestionQualityDomainRules.requireSha256(selectionSeed);
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw IngestionQualityDomainRules.invalid();
        }
    }
}
