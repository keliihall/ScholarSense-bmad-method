package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Closed owner commands; callers have no table-level write access. */
public interface QualityRecoveryFinalizationStorePort {
    Optional<QualityRecoveryFinalizationContext> load(UUID recoveryId);

    QualityRecoveryFinalizationContext bindApproval(Map<String, Object> binding);

    Optional<QualityRecoveryFinalizationCommit> findReplay(
            String idempotencyKeyDigest,
            String clientCommandDigest,
            UUID recoveryId,
            String finalObservationWatermark);

    QualityRecoveryFinalizationCommit execute(
            String idempotencyKeyDigest, Map<String, Object> command);
}
