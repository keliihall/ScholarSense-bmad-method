package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.UUID;

/** Self-contained result committed by the ingestion-quality owner transaction. */
public record QualityRecoveryFinalizationCommit(
        UUID recoveryId,
        long generation,
        String eligibilityStatus,
        String episodeStatus,
        UUID taskId,
        String taskStatus,
        long taskVersion,
        Instant recoveryCompletedAt,
        String windowOutcomesDigest,
        String ownerResultDigest,
        String deliveryStatus,
        UUID executionJti,
        UUID confirmationOutboxEventId,
        String traceId) {}
