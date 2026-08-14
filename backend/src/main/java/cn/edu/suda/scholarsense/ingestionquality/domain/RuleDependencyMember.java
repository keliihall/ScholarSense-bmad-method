package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.Objects;

public record RuleDependencyMember(
        String sourceId,
        String sourceVersion,
        String dependencyId,
        String dependencyVersion,
        DependencyRequirement requirement,
        String compositionGroup) {

    public RuleDependencyMember {
        if (sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$")
                || dependencyId == null
                || !dependencyId.matches("^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$")) {
            throw invalid();
        }
        sourceVersion = IngestionQualityDomainRules.requireText(sourceVersion, 128);
        dependencyVersion = IngestionQualityDomainRules.requireText(dependencyVersion, 128);
        requirement = Objects.requireNonNull(requirement);
        if (compositionGroup == null || !compositionGroup.matches("^[a-z][a-z0-9-]{0,63}$")) {
            throw invalid();
        }
    }

    private static IngestionQualityException invalid() {
        return new IngestionQualityException(
                IngestionQualityErrorCode.INGESTION_QUALITY_REGISTRY_INVALID);
    }
}
