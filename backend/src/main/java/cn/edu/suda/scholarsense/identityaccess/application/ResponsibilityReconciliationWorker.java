package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Claims only full-reconciliation jobs and persists retry/terminal truth. */
public final class ResponsibilityReconciliationWorker {
    private static final Set<String> RETRYABLE = Set.of(
            "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE",
            "RESPONSIBILITY_DEPENDENCY_WATERMARK_BEHIND",
            "RESPONSIBILITY_SNAPSHOT_PARTITION_MISSING",
            "RESPONSIBILITY_RECONCILIATION_FENCING_STALE",
            "RESPONSIBILITY_RECONCILIATION_PERSISTENCE_UNAVAILABLE");

    private final ResponsibilityReconciliationJobPort jobs;
    private final ResponsibilityReconciliationService service;
    private final IdentitySyncTransactionPort transactions;
    private final TrustedTimeSource time;
    private final CheckpointKey key;
    private final IdentitySyncAuditPort audit;

    public ResponsibilityReconciliationWorker(
            ResponsibilityReconciliationJobPort jobs,
            ResponsibilityReconciliationService service,
            IdentitySyncTransactionPort transactions,
            TrustedTimeSource time,
            CheckpointKey key) {
        this(
                jobs,
                service,
                transactions,
                time,
                ignored -> {},
                key);
    }

    public ResponsibilityReconciliationWorker(
            ResponsibilityReconciliationJobPort jobs,
            ResponsibilityReconciliationService service,
            IdentitySyncTransactionPort transactions,
            TrustedTimeSource time,
            IdentitySyncAuditPort audit,
            CheckpointKey key) {
        this.jobs = java.util.Objects.requireNonNull(jobs);
        this.service = java.util.Objects.requireNonNull(service);
        this.transactions = java.util.Objects.requireNonNull(transactions);
        this.time = java.util.Objects.requireNonNull(time);
        this.audit = java.util.Objects.requireNonNull(audit);
        if (key == null
                || !"responsibility".equals(
                        key.consumerProjection())) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_RECONCILIATION_ROUTE_INVALID");
        }
        this.key = key;
    }

    public Optional<ResponsibilityReconciliationResult> runNext(
            String leaseOwner) {
        Instant now = time.now().instant();
        Optional<UUID> due = jobs.nextDue(key, now);
        if (due.isEmpty()) {
            return Optional.empty();
        }
        Optional<RunningResponsibilityReconciliationAttempt> started =
                jobs.start(due.get(), leaseOwner, now);
        if (started.isEmpty()) {
            return Optional.empty();
        }
        RunningResponsibilityReconciliationAttempt attempt =
                started.get();
        try {
            return Optional.of(service.execute(
                    attempt,
                    result -> jobs.complete(
                            attempt,
                            result,
                            result.completedAt())));
        } catch (IdentitySyncException failure) {
            fail(attempt, failure.code(), now);
            return Optional.empty();
        } catch (RuntimeException unavailable) {
            fail(
                    attempt,
                    "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE",
                    now);
            return Optional.empty();
        }
    }

    private void fail(
            RunningResponsibilityReconciliationAttempt attempt,
            String reasonCode,
            Instant now) {
        boolean retry = RETRYABLE.contains(reasonCode)
                && attempt.attemptNo() < attempt.retryBudget();
        Instant nextAttemptAt = retry
                ? now.plusSeconds(backoffSeconds(attempt.attemptNo()))
                : null;
        transactions.execute(() -> {
            jobs.fail(
                    attempt,
                    reasonCode,
                    retry,
                    nextAttemptAt,
                    now);
            audit.append(new IdentitySyncAuditEvent(
                    "responsibility.sync.failed",
                    retry ? "retry_scheduled" : "failed",
                    reasonCode,
                    attempt.jobId(),
                    attempt.attemptNo(),
                    attempt.lease().fencingToken(),
                    0,
                    0,
                    0,
                    attempt.traceId(),
                    now,
                    Map.of(
                            "identitySessionPolicy", "ISP-1.0.0",
                            "roleFieldPolicy", "RFP-1.0.0",
                            "responsibilityContract",
                            "RESPONSIBILITY-AUTHORITY-1.0.0",
                            "retentionSchedule", "RS-1.0.0")));
            return null;
        });
    }

    private static long backoffSeconds(int attemptNo) {
        int shift = Math.min(5, Math.max(0, attemptNo - 1));
        return Math.min(900, 30L << shift);
    }
}
