package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.UUID;

public record HighRiskExecutionReconciliation(
        UUID leaseId,
        long leaseVersion,
        String leaseDigest,
        UUID executionJti,
        String requestDigest,
        String ownerCommitId,
        Instant ownerCommittedAt,
        String ownerResultDigest,
        UUID outboxEventId,
        String reconciliationDigest,
        String traceId) {}
