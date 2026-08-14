package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.Objects;
import java.util.UUID;

public record QualityEligibilityMemberEvidence(
        String sourceId,
        long sourceVersion,
        String dependencyId,
        long dependencyVersion,
        DependencyRequirement requirement,
        QualityEligibilityStatus state,
        boolean versionContinuous,
        String sourceWatermark,
        String dependencyWatermark,
        UUID snapshotId,
        String snapshotImmutableHash,
        String qualityMetricDecisionProfileVersion,
        String qualityMetricDecisionProfileDigest,
        String qualitySnapshotHashProfileVersion,
        String qualitySnapshotHashProfileDigest,
        UUID lineageId) {

    public QualityEligibilityMemberEvidence {
        if (sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$")
                || dependencyId == null
                || !dependencyId.matches("^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$")) {
            throw invalid();
        }
        sourceVersion = IngestionQualityDomainRules.requireVersion(sourceVersion);
        dependencyVersion = IngestionQualityDomainRules.requireVersion(dependencyVersion);
        requirement = Objects.requireNonNull(requirement);
        state = Objects.requireNonNull(state);
        sourceWatermark = IngestionQualityDomainRules.requireText(sourceWatermark, 512);
        dependencyWatermark = IngestionQualityDomainRules.requireText(
                dependencyWatermark, 512);
        if (snapshotId != null) snapshotId = IngestionQualityDomainRules.requireUuidV7(snapshotId);
        if (snapshotImmutableHash != null) {
            snapshotImmutableHash = IngestionQualityDomainRules.requireSha256(
                    snapshotImmutableHash);
        }
        if (snapshotId == null != (snapshotImmutableHash == null)) throw invalid();
        if (!"QMDP-1.0.0".equals(qualityMetricDecisionProfileVersion)
                || !"QSHM-1.0.0".equals(qualitySnapshotHashProfileVersion)) {
            throw invalid();
        }
        qualityMetricDecisionProfileDigest = IngestionQualityDomainRules.requireSha256(
                qualityMetricDecisionProfileDigest);
        qualitySnapshotHashProfileDigest = IngestionQualityDomainRules.requireSha256(
                qualitySnapshotHashProfileDigest);
        if (lineageId != null) lineageId = IngestionQualityDomainRules.requireUuidV7(lineageId);
        if (state != QualityEligibilityStatus.MISSING
                && (snapshotId == null || lineageId == null)) {
            throw invalid();
        }
    }

    private static IngestionQualityException invalid() {
        return new IngestionQualityException(
                IngestionQualityErrorCode.INGESTION_QUALITY_ELIGIBILITY_INVALID);
    }
}
