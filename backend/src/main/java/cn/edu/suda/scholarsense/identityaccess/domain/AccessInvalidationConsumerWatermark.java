package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Instant;
import java.util.UUID;

public record AccessInvalidationConsumerWatermark(
        AccessInvalidationConsumerRoute route,
        long currentWatermark,
        UUID lastEventId,
        String lastPayloadDigest,
        Instant appliedAt) {
    public AccessInvalidationConsumerWatermark {
        AccessInvalidationValidation.required(route, "route");
        AccessInvalidationValidation.nonNegative(
                currentWatermark,
                "ACCESS_INVALIDATION_CONSUMER_WATERMARK");
        AccessInvalidationValidation.uuidV7(
                lastEventId, "ACCESS_INVALIDATION_LAST_EVENT");
        AccessInvalidationValidation.digest(
                lastPayloadDigest,
                "ACCESS_INVALIDATION_LAST_PAYLOAD");
        AccessInvalidationValidation.required(appliedAt, "appliedAt");
    }

    public AccessInvalidationDeliveryDecision classify(
            long incomingVersion,
            UUID incomingEventId,
            UUID incomingSupersedesId,
            String incomingPayloadDigest) {
        AccessInvalidationValidation.nonNegative(
                incomingVersion,
                "ACCESS_INVALIDATION_INCOMING_VERSION");
        AccessInvalidationValidation.uuidV7(
                incomingEventId, "ACCESS_INVALIDATION_INCOMING_EVENT");
        AccessInvalidationValidation.digest(
                incomingPayloadDigest,
                "ACCESS_INVALIDATION_INCOMING_PAYLOAD");
        if (incomingVersion == currentWatermark) {
            return lastEventId.equals(incomingEventId)
                            && lastPayloadDigest.equals(
                                    incomingPayloadDigest)
                    ? AccessInvalidationDeliveryDecision.DUPLICATE
                    : AccessInvalidationDeliveryDecision.CONFLICT;
        }
        if (incomingVersion < currentWatermark) {
            return AccessInvalidationDeliveryDecision.OLD_IGNORED;
        }
        if (incomingVersion == currentWatermark + 1) {
            return lastEventId.equals(incomingSupersedesId)
                    ? AccessInvalidationDeliveryDecision.APPLIED
                    : AccessInvalidationDeliveryDecision.CONFLICT;
        }
        return AccessInvalidationDeliveryDecision
                .GAP_BACKFILL_REQUIRED;
    }
}
