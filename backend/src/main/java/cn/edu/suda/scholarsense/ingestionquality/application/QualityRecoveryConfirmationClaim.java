package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.UUID;

/** Self-contained owner-commit evidence claimed from the IQ confirmation outbox. */
public record QualityRecoveryConfirmationClaim(
        UUID outboxEventId,
        UUID leaseId,
        long leaseVersion,
        String leaseDigest,
        UUID executionJti,
        String requestDigest,
        String ownerCommitId,
        Instant ownerCommittedAt,
        String ownerResultDigest,
        String traceId,
        String payloadDigest) {}
