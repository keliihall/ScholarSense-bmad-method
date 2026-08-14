package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.UUID;

public record QualityRecoveryExecutionCommit(
        UUID recoveryRequestId,
        UUID taskId,
        UUID episodeId,
        String state,
        boolean transitionApplied,
        UUID executionJti,
        String ownerCommitId,
        String ownerResultDigest,
        UUID confirmationOutboxEventId,
        Instant ownerCommittedAt,
        String traceId) {}
