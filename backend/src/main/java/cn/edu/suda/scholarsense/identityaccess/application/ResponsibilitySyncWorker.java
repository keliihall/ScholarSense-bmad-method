package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Runs one persisted responsibility attempt on its projection-scoped route. */
public final class ResponsibilitySyncWorker {
    private static final Set<String> RETRYABLE_CODES = Set.of(
            "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE",
            "RESPONSIBILITY_DEPENDENCY_WATERMARK_BEHIND",
            "RESPONSIBILITY_IDENTITY_PROJECTION_UNAVAILABLE",
            "RESPONSIBILITY_SLO_COMPENSATION_PERSISTENCE_UNAVAILABLE",
            "RESPONSIBILITY_WATERMARK_GAP",
            "IDENTITY_SYNC_FENCING_STALE",
            "IDENTITY_SYNC_PERSISTENCE_UNAVAILABLE");

    private final IdentitySyncJobPort jobs;
    private final ResponsibilityAuthoritySourcePort source;
    private final ResponsibilitySyncService sync;
    private final ResponsibilitySyncRepository repository;
    private final IdentitySyncAuditPort audit;
    private final IdentitySyncObservabilityPort observability;
    private final IdentitySyncTransactionPort transactions;
    private final TrustedTimeSource time;
    private final IdentityReplayPort replay;
    private final CheckpointKey routeKey;
    private final ResponsibilitySloRecorder slo;

    public ResponsibilitySyncWorker(
            IdentitySyncJobPort jobs,
            ResponsibilityAuthoritySourcePort source,
            ResponsibilitySyncService sync,
            ResponsibilitySyncRepository repository,
            IdentitySyncAuditPort audit,
            IdentitySyncObservabilityPort observability,
            IdentitySyncTransactionPort transactions,
            TrustedTimeSource time,
            IdentityReplayPort replay,
            CheckpointKey routeKey) {
        this(
                jobs,
                source,
                sync,
                repository,
                audit,
                observability,
                transactions,
                time,
                replay,
                (batch, appliedAt, result) -> {},
                routeKey);
    }

    public ResponsibilitySyncWorker(
            IdentitySyncJobPort jobs,
            ResponsibilityAuthoritySourcePort source,
            ResponsibilitySyncService sync,
            ResponsibilitySyncRepository repository,
            IdentitySyncAuditPort audit,
            IdentitySyncObservabilityPort observability,
            IdentitySyncTransactionPort transactions,
            TrustedTimeSource time,
            IdentityReplayPort replay,
            ResponsibilitySloRecorder slo,
            CheckpointKey routeKey) {
        this.jobs = java.util.Objects.requireNonNull(jobs);
        this.source = java.util.Objects.requireNonNull(source);
        this.sync = java.util.Objects.requireNonNull(sync);
        this.repository = java.util.Objects.requireNonNull(repository);
        this.audit = java.util.Objects.requireNonNull(audit);
        this.observability = java.util.Objects.requireNonNull(observability);
        this.transactions = java.util.Objects.requireNonNull(transactions);
        this.time = java.util.Objects.requireNonNull(time);
        this.replay = java.util.Objects.requireNonNull(replay);
        this.slo = java.util.Objects.requireNonNull(slo);
        if (routeKey == null
                || !"responsibility".equals(
                        routeKey.consumerProjection())) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SYNC_ROUTE_INVALID");
        }
        this.routeKey = routeKey;
    }

    public Optional<IdentitySyncWorkerRun> runNext(String leaseOwner) {
        Instant now = time.now().instant();
        Optional<IdentitySyncJob> due = jobs.nextDue(routeKey, now);
        if (due.isEmpty()) {
            return Optional.empty();
        }
        Optional<RunningIdentitySyncAttempt> started =
                jobs.start(due.get().jobId(), leaseOwner, now);
        if (started.isEmpty()) {
            return Optional.empty();
        }
        RunningIdentitySyncAttempt attempt = started.get();
        java.util.concurrent.atomic.AtomicReference<IdentitySyncJob> saved =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            Optional<IdentityReplayRange> requested =
                    replay.nextRequested(attempt.job().key());
            NormalizedResponsibilityBatch batch = requested.isPresent()
                    ? source.fetchRange(
                            attempt.job().key(),
                            requested.get().fromInclusive(),
                            requested.get().toInclusive(),
                            attempt.job().traceId())
                    : source.fetch(
                            attempt.job().key(),
                            attempt.inputWatermark(),
                            attempt.job().traceId());
            IdentitySyncResult result = sync.process(
                    batch,
                    attempt.lease(),
                    applied -> {
                        IdentitySyncJob completed =
                                attempt.job().transitionTo(
                                        IdentitySyncJobStatus.SUCCEEDED,
                                        IdentitySourceHealth.HEALTHY,
                                        IdentityProjectionFreshness.FRESH,
                                        now,
                                        null,
                                        null,
                                        applied.sourceWatermark());
                        jobs.save(attempt, completed, now);
                        saved.set(completed);
                    });
            IdentitySyncJob completed = saved.get();
            if (completed == null) {
                throw new IdentitySyncException(
                        "IDENTITY_SYNC_PERSISTENCE_UNAVAILABLE");
            }
            Instant committedAt = time.now().instant();
            try {
                slo.record(batch, committedAt, result);
            } catch (IdentitySyncException failure) {
                if (!"RESPONSIBILITY_SLO_COMPENSATION_PERSISTENCE_UNAVAILABLE"
                        .equals(failure.code())) {
                    throw failure;
                }
                scheduleSloRetry(
                        attempt,
                        completed,
                        batch,
                        result,
                        committedAt);
                record(
                        "responsibility_sync_slo_retry_total",
                        "scheduled",
                        attempt,
                        committedAt);
                return Optional.of(result(attempt, completed));
            }
            record(
                    "responsibility_sync_job_total",
                    result.reasonCode(),
                    attempt,
                    now);
            return Optional.of(result(attempt, completed));
        } catch (IdentitySourcePoisonException poison) {
            quarantine(attempt, poison, now);
            return Optional.of(fail(
                    attempt, poison.code(), false, now));
        } catch (IdentitySyncException failure) {
            if (saved.get() != null) {
                throw failure;
            }
            return Optional.of(fail(
                    attempt,
                    failure.code(),
                    RETRYABLE_CODES.contains(failure.code()),
                    now));
        } catch (RuntimeException unavailable) {
            if (saved.get() != null) {
                throw unavailable;
            }
            return Optional.of(fail(
                    attempt,
                    "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE",
                    true,
                    now));
        }
    }

    private void scheduleSloRetry(
            RunningIdentitySyncAttempt attempt,
            IdentitySyncJob completed,
            NormalizedResponsibilityBatch batch,
            IdentitySyncResult result,
            Instant requestedAt) {
        IdentitySyncJob retry = new IdentitySyncJob(
                UUID.fromString(UuidV7.generate(requestedAt)),
                attempt.job().key(),
                IdentitySyncJobStatus.QUEUED,
                IdentitySourceHealth.DEGRADED,
                completed.freshness(),
                requestedAt,
                null,
                result.sourceWatermark(),
                requestedAt,
                attempt.job().retryBudget(),
                0,
                "RESPONSIBILITY_SLO_COMPENSATION_PERSISTENCE_UNAVAILABLE",
                attempt.job().traceId());
        transactions.execute(() -> {
            replay.request(
                    batch.key(),
                    batch.fromWatermark() + 1,
                    batch.toWatermark(),
                    batch.traceId());
            jobs.enqueueIfEligible(retry);
            return null;
        });
    }

    private void quarantine(
            RunningIdentitySyncAttempt attempt,
            IdentitySourcePoisonException poison,
            Instant now) {
        transactions.execute(() -> {
            if (!repository.leaseIsCurrent(attempt.lease())) {
                throw new IdentitySyncException(
                        "IDENTITY_SYNC_FENCING_STALE");
            }
            repository.reject(new IdentitySyncRejection(
                    UUID.fromString(UuidV7.generate(now)),
                    poison.batchId(),
                    attempt.job().key(),
                    poison.sourceVersion(),
                    poison.sourceWatermark(),
                    poison.payloadDigest(),
                    poison.code(),
                    false,
                    attempt.job().jobId(),
                    attempt.attemptNo(),
                    attempt.job().traceId(),
                    now));
            audit.append(new IdentitySyncAuditEvent(
                    "responsibility.sync.rejected",
                    "rejected",
                    poison.code(),
                    attempt.job().jobId(),
                    attempt.attemptNo(),
                    attempt.lease().fencingToken(),
                    poison.sourceVersion(),
                    poison.sourceWatermark(),
                    0,
                    attempt.job().traceId(),
                    now,
                    policyVersions()));
            return null;
        });
    }

    private IdentitySyncWorkerRun fail(
            RunningIdentitySyncAttempt attempt,
            String reasonCode,
            boolean retryable,
            Instant now) {
        boolean retry =
                retryable
                        && attempt.attemptNo()
                                < attempt.job().retryBudget();
        Instant nextAttemptAt = retry
                ? now.plusSeconds(backoffSeconds(attempt.attemptNo()))
                : null;
        IdentitySyncJobStatus status = retry
                ? IdentitySyncJobStatus.QUEUED
                : IdentitySyncJobStatus.FAILED;
        IdentitySyncJob completed = attempt.job().transitionTo(
                status,
                IdentitySourceHealth.DEGRADED,
                attempt.job().freshness(),
                retry ? null : now,
                nextAttemptAt,
                reasonCode,
                attempt.job().lastSuccessfulWatermark());
        transactions.execute(() -> {
            jobs.save(attempt, completed, now);
            audit.append(new IdentitySyncAuditEvent(
                    "responsibility.sync.failed",
                    retry ? "retry_scheduled" : "failed",
                    reasonCode,
                    attempt.job().jobId(),
                    attempt.attemptNo(),
                    attempt.lease().fencingToken(),
                    0,
                    attempt.inputWatermark(),
                    0,
                    attempt.job().traceId(),
                    now,
                    policyVersions()));
            return null;
        });
        record(
                "responsibility_sync_job_total",
                status.wireName(),
                attempt,
                now);
        return result(attempt, completed);
    }

    private void record(
            String metric,
            String outcome,
            RunningIdentitySyncAttempt attempt,
            Instant now) {
        observability.record(new IdentitySyncObservation(
                metric,
                1,
                Map.of(
                        "sourceId", attempt.job().key().sourceId(),
                        "feedId", attempt.job().key().feedId(),
                        "consumerProjection", "responsibility",
                        "outcome", outcome),
                attempt.job().traceId(),
                now));
    }

    private static long backoffSeconds(int attemptNo) {
        int shift = Math.min(5, Math.max(0, attemptNo - 1));
        return Math.min(900, 30L << shift);
    }

    private static IdentitySyncWorkerRun result(
            RunningIdentitySyncAttempt attempt,
            IdentitySyncJob completed) {
        return new IdentitySyncWorkerRun(
                completed.jobId(),
                attempt.attemptNo(),
                completed.status(),
                completed.reasonCode(),
                completed.lastSuccessfulWatermark());
    }

    private static Map<String, String> policyVersions() {
        return Map.of(
                "identitySessionPolicy", "ISP-1.0.0",
                "roleFieldPolicy", "RFP-1.0.0",
                "responsibilityContract",
                "RESPONSIBILITY-AUTHORITY-1.0.0",
                "retentionSchedule", "RS-1.0.0");
    }
}
