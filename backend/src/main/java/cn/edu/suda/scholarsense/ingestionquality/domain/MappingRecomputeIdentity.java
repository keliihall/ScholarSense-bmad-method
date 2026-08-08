package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.List;
import java.util.UUID;

public record MappingRecomputeIdentity(
        UUID correctionLineageId,
        String studentRef,
        String ruleId,
        String ruleVersion,
        String scenarioId,
        String windowId,
        String inputWatermarksDigest) {

    public static final List<String> IDENTITY_FIELDS = List.of(
            "correctionLineageId", "studentRef", "ruleId", "ruleVersion",
            "scenarioId", "windowId", "inputWatermarksDigest");

    public MappingRecomputeIdentity {
        correctionLineageId = IngestionQualityDomainRules.requireUuidV7(correctionLineageId);
        studentRef = IngestionQualityDomainRules.requireUuidV7(studentRef);
        ruleId = IngestionQualityDomainRules.requireText(ruleId, 128);
        ruleVersion = IngestionQualityDomainRules.requireText(ruleVersion, 64);
        scenarioId = IngestionQualityDomainRules.requireText(scenarioId, 128);
        windowId = IngestionQualityDomainRules.requireText(windowId, 128);
        inputWatermarksDigest = IngestionQualityDomainRules.requireSha256(inputWatermarksDigest);
    }
}
