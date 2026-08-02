package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationJob;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReason;
import java.time.Instant;
import java.util.UUID;

public interface AccessInvalidationExpiryPort {
    AccessInvalidationJob enqueue(
            UUID jobId,
            AccessInvalidationLineageId lineageId,
            Instant effectiveTo,
            String traceId);

    default AccessInvalidationJob enqueue(
            UUID jobId,
            AccessInvalidationLineageId lineageId,
            UUID scheduledEventId,
            long scheduledAggregateVersion,
            Instant effectiveTo,
            AccessInvalidationReason reasonCode,
            String traceId,
            Instant scheduledAt) {
        if (scheduledEventId == null
                || scheduledAggregateVersion < 1
                || reasonCode
                        != AccessInvalidationReason.RELATION_EXPIRED
                || scheduledAt == null) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_EXPIRY_BINDING_INVALID");
        }
        throw new IllegalStateException(
                "ACCESS_INVALIDATION_BOUND_EXPIRY_ENQUEUE_REQUIRED");
    }
}
