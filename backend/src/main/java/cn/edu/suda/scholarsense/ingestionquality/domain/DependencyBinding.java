package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.Objects;

public record DependencyBinding(
        String sourceId,
        String dependencyId,
        DependencyRequirement requirement,
        DependencyOperator operator) {

    public DependencyBinding {
        if (sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z-]+-[0-9]{3}$")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_SOURCE_ID_INVALID");
        }
        if (dependencyId == null || !dependencyId.matches("^DEP-P[01]-[A-Z-]+-[0-9]{3}$")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_DEPENDENCY_ID_INVALID");
        }
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(operator, "operator");
    }
}
