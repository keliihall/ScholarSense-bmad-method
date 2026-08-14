package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.UUID;

public record HighRiskExecutionReconciliationCommand(
        UUID leaseId, long leaseVersion, String leaseDigest, UUID executionJti,
        String requestDigest, String ownerCommitId, Instant ownerCommittedAt,
        String ownerResultDigest, UUID outboxEventId, String reconciliationDigest,
        String traceId) {}
