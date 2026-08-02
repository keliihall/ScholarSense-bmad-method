package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record AccessInvalidationPropagationStatus(
        AccessInvalidationLineageId lineageId,
        long targetVersion,
        Set<String> requiredConsumerIds,
        Set<String> appliedRequiredConsumerIds,
        Set<String> visiblePlannedConsumerIds,
        boolean reconciliationHealthy,
        boolean complete) {
    public AccessInvalidationPropagationStatus {
        AccessInvalidationValidation.required(lineageId, "lineageId");
        AccessInvalidationValidation.positive(
                targetVersion,
                "ACCESS_INVALIDATION_PROPAGATION_TARGET");
        requiredConsumerIds = Set.copyOf(requiredConsumerIds);
        appliedRequiredConsumerIds =
                Set.copyOf(appliedRequiredConsumerIds);
        visiblePlannedConsumerIds =
                Set.copyOf(visiblePlannedConsumerIds);
        if (!requiredConsumerIds.containsAll(
                appliedRequiredConsumerIds)) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_PROPAGATION_APPLIED_SET_INVALID");
        }
        if (complete
                != (reconciliationHealthy
                        && appliedRequiredConsumerIds.containsAll(
                                requiredConsumerIds))) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_PROPAGATION_COMPLETION_INVALID");
        }
    }

    public static AccessInvalidationPropagationStatus assess(
            AccessInvalidationLineageId lineageId,
            long targetVersion,
            List<AccessInvalidationConsumerRegistration> registrations,
            Set<String> appliedConsumerIds,
            boolean reconciliationHealthy) {
        var requiredIds = new HashSet<String>();
        var plannedIds = new HashSet<String>();
        for (var registration : List.copyOf(registrations)) {
            if (registration.lifecycle()
                            == AccessInvalidationConsumerLifecycle.ACTIVE
                    && registration.required()) {
                requiredIds.add(registration.consumerId());
            } else if (registration.lifecycle()
                    == AccessInvalidationConsumerLifecycle
                            .PLANNED_NOT_INSTALLED) {
                plannedIds.add(registration.consumerId());
            }
        }
        var appliedRequired = new HashSet<>(appliedConsumerIds);
        appliedRequired.retainAll(requiredIds);
        boolean complete =
                reconciliationHealthy
                        && appliedRequired.containsAll(requiredIds);
        return new AccessInvalidationPropagationStatus(
                lineageId,
                targetVersion,
                requiredIds,
                appliedRequired,
                plannedIds,
                reconciliationHealthy,
                complete);
    }
}
