package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface ResponsibilityReconciliationJobPort
        extends ResponsibilityReconciliationPort {
    default Optional<LocalDate> latestScheduledBusinessDate(
            CheckpointKey key) {
        return Optional.empty();
    }

    Optional<UUID> nextDue(CheckpointKey key, Instant now);

    Optional<RunningResponsibilityReconciliationAttempt> start(
            UUID jobId, String leaseOwner, Instant now);

    void complete(
            RunningResponsibilityReconciliationAttempt attempt,
            ResponsibilityReconciliationResult result,
            Instant completedAt);

    void fail(
            RunningResponsibilityReconciliationAttempt attempt,
            String reasonCode,
            boolean retry,
            Instant nextAttemptAt,
            Instant failedAt);

    void miss(
            CheckpointKey key,
            LocalDate businessDate,
            String traceId,
            String reasonCode,
            Instant detectedAt);
}
