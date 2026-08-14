package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.shared.outbox.DeliveryRecordKey;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** One database-fenced public quality-task delivery attempt. */
public record QualityTaskRelayClaim(
        UUID eventId,
        UUID taskId,
        long routeSequence,
        String payload,
        String payloadDigest,
        long attempt,
        long leaseGeneration) {
    public QualityTaskRelayClaim {
        eventId = uuidV7(eventId);
        taskId = uuidV7(taskId);
        if (routeSequence < 1 || attempt < 1 || leaseGeneration < 1) throw invalid();
        payload = Objects.requireNonNull(payload);
        if (payload.getBytes(StandardCharsets.UTF_8).length > 65_536) throw invalid();
        if (payloadDigest == null
                || !payloadDigest.matches("^sha256:[0-9a-f]{64}$")) throw invalid();
    }

    public DeliveryRecordKey deliveryKey() {
        return new DeliveryRecordKey(
                "RecoveryTask", taskId.toString(),
                "pic.quality-recovery-task.v1", "PIC-1.1.0");
    }

    private static UUID uuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_TASK_RELAY_CLAIM_INVALID");
    }
}
