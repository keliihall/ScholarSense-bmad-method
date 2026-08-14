package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.Objects;
import java.util.UUID;

public record DependencyQualityState(
        String sourceId,
        long sourceVersion,
        UUID lineageId,
        long lineageRevision,
        String dependencyId,
        long dependencyVersion,
        QualityEligibilityStatus status,
        boolean versionContinuous,
        String watermark) {

    public DependencyQualityState {
        if (sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$")
                || dependencyId == null
                || !dependencyId.matches("^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$")) {
            throw invalid();
        }
        sourceVersion = IngestionQualityDomainRules.requireVersion(sourceVersion);
        lineageId = IngestionQualityDomainRules.requireUuidV7(lineageId);
        if (lineageRevision < 0 || lineageRevision > IngestionQualityDomainRules.MAX_SAFE_VERSION) {
            throw invalid();
        }
        dependencyVersion = IngestionQualityDomainRules.requireVersion(dependencyVersion);
        status = Objects.requireNonNull(status);
        watermark = IngestionQualityDomainRules.requireText(watermark, 512);
    }

    private static IngestionQualityException invalid() {
        return new IngestionQualityException(
                IngestionQualityErrorCode.INGESTION_QUALITY_ELIGIBILITY_INVALID);
    }
}
