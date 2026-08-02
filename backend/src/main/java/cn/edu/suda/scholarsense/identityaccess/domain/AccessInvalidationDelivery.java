package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Instant;
import java.util.UUID;

public record AccessInvalidationDelivery(
        UUID deliveryId,
        AccessInvalidationConsumerRoute route,
        UUID eventId,
        long aggregateVersion,
        AccessInvalidationDeliveryDecision decision,
        int attempts,
        Instant nextAttemptAt,
        String traceId) {
    public AccessInvalidationDelivery {
        AccessInvalidationValidation.uuidV7(
                deliveryId, "ACCESS_INVALIDATION_DELIVERY");
        AccessInvalidationValidation.required(route, "route");
        AccessInvalidationValidation.uuidV7(
                eventId, "ACCESS_INVALIDATION_EVENT");
        AccessInvalidationValidation.positive(
                aggregateVersion,
                "ACCESS_INVALIDATION_DELIVERY_VERSION");
        AccessInvalidationValidation.required(decision, "decision");
        AccessInvalidationValidation.nonNegative(
                attempts, "ACCESS_INVALIDATION_DELIVERY_ATTEMPTS");
        AccessInvalidationValidation.required(
                nextAttemptAt, "nextAttemptAt");
        AccessInvalidationValidation.traceId(traceId);
    }
}
