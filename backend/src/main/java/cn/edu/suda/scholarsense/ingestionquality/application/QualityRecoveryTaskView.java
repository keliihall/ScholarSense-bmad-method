package cn.edu.suda.scholarsense.ingestionquality.application;

import com.fasterxml.jackson.annotation.JsonIgnore;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleVersionIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** QFRTPROJ-1.1.0 allow-list; taskVersion is the only additive command fence. */
public record QualityRecoveryTaskView(
        UUID taskId,
        long taskVersion,
        UUID episodeId,
        long episodeGeneration,
        String sourceId,
        String dependencyId,
        List<RuleVersionIdentity> affectedRules,
        String ownerRef,
        String priority,
        Instant dueAt,
        String status,
        String watermark,
        Map<String, String> trigger,
        Map<String, String> currentEvidence,
        Instant closedAt,
        String closureReason,
        String ownerResultDigest,
        @JsonIgnore long aggregateVersion,
        @JsonIgnore Instant occurredAt,
        Delivery taskDelivery) {
    /** Predecessor open-task constructor retained for controller/service fixtures. */
    public QualityRecoveryTaskView(
            UUID taskId, long taskVersion, UUID episodeId, long episodeGeneration,
            String sourceId, String dependencyId, List<RuleVersionIdentity> affectedRules,
            String ownerRef, String priority, Instant dueAt, String status, String watermark,
            Map<String, String> trigger, Map<String, String> currentEvidence,
            long aggregateVersion, Instant occurredAt, Delivery taskDelivery) {
        this(taskId, taskVersion, episodeId, episodeGeneration, sourceId, dependencyId,
                affectedRules, ownerRef, priority, dueAt, status, watermark, trigger,
                currentEvidence, null, null, null, aggregateVersion, occurredAt, taskDelivery);
    }

    public static QualityRecoveryTaskView from(QualityRecoveryTask value) {
        return new QualityRecoveryTaskView(
                value.taskId(), value.aggregateVersion(), value.episodeId(), value.episodeGeneration(),
                value.sourceId(), value.dependencyId(), value.affectedRules(),
                value.ownerRef(), value.priority(), value.dueAt(), value.status(),
                value.watermark(), value.trigger(), value.currentEvidence(),
                value.closedAt(), value.closureReason(), value.ownerResultDigest(),
                value.aggregateVersion(), value.occurredAt(),
                Delivery.from(value.taskDelivery()));
    }

    /** QFRTPROJ-1.0.0 R6 delivery allow-list; receipt, route and errors stay hidden. */
    public record Delivery(
            String target,
            String status,
            long attempt,
            Instant nextAttemptAt) {
        private static Delivery from(QualityTaskDeliveryProjection value) {
            return new Delivery(
                    value.target(), value.status(), value.attempt(), value.nextAttemptAt());
        }
    }
}
