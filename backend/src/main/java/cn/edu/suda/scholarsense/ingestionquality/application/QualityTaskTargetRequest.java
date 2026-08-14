package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.shared.outbox.DeliveryRecordKey;
import java.util.Objects;
import java.util.UUID;

/** Transport request that keeps PIC route ordering separate from task business state. */
public record QualityTaskTargetRequest(
        UUID eventId,
        DeliveryRecordKey deliveryKey,
        long routeSequence,
        String payload,
        String payloadDigest) {
    public QualityTaskTargetRequest {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(deliveryKey);
        if (routeSequence < 1) throw new IllegalArgumentException(
                "INGESTION_QUALITY_TASK_TARGET_REQUEST_INVALID");
        Objects.requireNonNull(payload);
        Objects.requireNonNull(payloadDigest);
    }
}
