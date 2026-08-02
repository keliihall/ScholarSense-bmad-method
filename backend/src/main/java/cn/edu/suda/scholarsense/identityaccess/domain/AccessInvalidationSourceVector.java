package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.HashSet;
import java.util.List;

public record AccessInvalidationSourceVector(
        String sourceId,
        long sourceVersion,
        long sourceWatermark,
        List<AccessInvalidationDependencyWatermark> dependencyVector) {
    public AccessInvalidationSourceVector {
        if (!"SRC-P0-RESPONSIBILITY-001".equals(sourceId)) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_SOURCE_UNTRUSTED");
        }
        AccessInvalidationValidation.positive(
                sourceVersion, "ACCESS_INVALIDATION_SOURCE_VERSION");
        AccessInvalidationValidation.nonNegative(
                sourceWatermark, "ACCESS_INVALIDATION_SOURCE_WATERMARK");
        dependencyVector = List.copyOf(
                AccessInvalidationValidation.required(
                        dependencyVector, "dependencyVector"));
        if (dependencyVector.size() > 32) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_DEPENDENCY_VECTOR_TOO_LARGE");
        }
        var routes = new HashSet<String>();
        if (!dependencyVector.stream()
                .map(AccessInvalidationDependencyWatermark::route)
                .allMatch(routes::add)) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_DEPENDENCY_ROUTE_DUPLICATE");
        }
    }
}
