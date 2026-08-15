package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.RuleVersionIdentity;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owner-local RecoveryTask fact and its separately owned delivery projection. */
public record QualityRecoveryTask(
        UUID taskId,
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
        long aggregateVersion,
        Instant occurredAt,
        Instant closedAt,
        String closureReason,
        String ownerResultDigest,
        QualityTaskDeliveryProjection taskDelivery,
        String traceId) {
    public QualityRecoveryTask {
        taskId = uuidV7(taskId);
        episodeId = uuidV7(episodeId);
        if (episodeGeneration < 1 || aggregateVersion < 1) throw invalid();
        if (sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$")
                || dependencyId == null
                || !dependencyId.matches("^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$")) throw invalid();
        affectedRules = List.copyOf(Objects.requireNonNull(affectedRules)).stream()
                .sorted(Comparator.comparing(RuleVersionIdentity::ruleId)
                        .thenComparing(RuleVersionIdentity::ruleVersion)).toList();
        if (affectedRules.isEmpty()) throw invalid();
        ownerRef = text(ownerRef);
        if (!List.of("P0", "P1", "P2").contains(priority)
                || !List.of("open", "closed").contains(status)) {
            throw invalid();
        }
        Objects.requireNonNull(dueAt);
        watermark = text(watermark);
        trigger = Map.copyOf(Objects.requireNonNull(trigger));
        currentEvidence = Map.copyOf(Objects.requireNonNull(currentEvidence));
        Objects.requireNonNull(occurredAt);
        if (("open".equals(status)
                    && (closedAt != null || closureReason != null || ownerResultDigest != null))
                || ("closed".equals(status)
                    && (closedAt == null || !"RECOVERY_FINALIZED".equals(closureReason)
                        || ownerResultDigest == null
                        || !ownerResultDigest.matches("^sha256:[0-9a-f]{64}$")))) {
            throw invalid();
        }
        Objects.requireNonNull(taskDelivery);
        if (traceId == null || !traceId.matches("^(?!0{32}$)[0-9a-f]{32}$")) throw invalid();
    }

    /** Predecessor open-task constructor retained for source compatibility. */
    public QualityRecoveryTask(
            UUID taskId, UUID episodeId, long episodeGeneration, String sourceId,
            String dependencyId, List<RuleVersionIdentity> affectedRules, String ownerRef,
            String priority, Instant dueAt, String status, String watermark,
            Map<String, String> trigger, Map<String, String> currentEvidence,
            long aggregateVersion, Instant occurredAt,
            QualityTaskDeliveryProjection taskDelivery, String traceId) {
        this(taskId, episodeId, episodeGeneration, sourceId, dependencyId, affectedRules,
                ownerRef, priority, dueAt, status, watermark, trigger, currentEvidence,
                aggregateVersion, occurredAt, null, null, null, taskDelivery, traceId);
    }

    private static UUID uuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) throw invalid();
        return value;
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_RECOVERY_TASK_INVALID");
    }
}
