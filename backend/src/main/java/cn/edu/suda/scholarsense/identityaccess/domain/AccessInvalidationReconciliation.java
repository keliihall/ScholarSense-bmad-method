package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record AccessInvalidationReconciliation(
        UUID reconciliationId,
        AccessInvalidationLineageId lineageId,
        long targetVersion,
        Set<String> gapConsumerIds,
        Set<String> conflictConsumerIds,
        Instant checkedAt,
        String traceId) {
    public AccessInvalidationReconciliation {
        AccessInvalidationValidation.uuidV7(
                reconciliationId,
                "ACCESS_INVALIDATION_RECONCILIATION");
        AccessInvalidationValidation.required(lineageId, "lineageId");
        AccessInvalidationValidation.positive(
                targetVersion,
                "ACCESS_INVALIDATION_RECONCILIATION_TARGET");
        gapConsumerIds = Set.copyOf(gapConsumerIds);
        conflictConsumerIds = Set.copyOf(conflictConsumerIds);
        AccessInvalidationValidation.required(checkedAt, "checkedAt");
        AccessInvalidationValidation.traceId(traceId);
    }

    public boolean healthy() {
        return gapConsumerIds.isEmpty()
                && conflictConsumerIds.isEmpty();
    }
}
