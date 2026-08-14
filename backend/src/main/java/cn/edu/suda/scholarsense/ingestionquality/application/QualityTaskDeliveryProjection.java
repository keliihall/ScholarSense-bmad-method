package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;

/** Read-only transport sidecar; it carries no recovery or quality business meaning. */
public record QualityTaskDeliveryProjection(
        String target,
        String status,
        long attempt,
        Instant nextAttemptAt,
        Long routeSequence) {
    public QualityTaskDeliveryProjection {
        if (!"public-task-platform".equals(target)
                || status == null
                || !status.matches("pending|retrying|confirmed|failed")
                || attempt < 0
                || routeSequence != null && routeSequence < 1
                || ("retrying".equals(status)) != (nextAttemptAt != null)) {
            throw new IllegalArgumentException(
                    "INGESTION_QUALITY_RECOVERY_TASK_INVALID");
        }
    }
}
