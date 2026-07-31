package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RunningResponsibilityReconciliationAttempt(
        UUID jobId,
        CheckpointKey key,
        LocalDate businessDate,
        int attemptNo,
        int retryBudget,
        String traceId,
        Instant startedAt,
        ResponsibilityReconciliationLease lease) {}
