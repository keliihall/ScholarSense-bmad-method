package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Runs one persisted attempt. Scheduling is an inbound concern; correctness stays in the ports. */
public final class IdentitySyncWorker {
    private static final CheckpointKey DEFAULT_IDENTITY_ROUTE = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "identity-authority",
            "sandbox-0",
            "identity-org");
    private static final Set<String> RETRYABLE_SYNC_CODES = Set.of(
            "IDENTITY_SOURCE_CURSOR_GAP",
            "IDENTITY_SOURCE_DEPENDENCY_UNAVAILABLE",
            "IDENTITY_SYNC_FENCING_STALE",
            "IDENTITY_SOURCE_VERSION_STALE",
            "IDENTITY_SYNC_PERSISTENCE_UNAVAILABLE");

    private final IdentitySyncJobPort jobs;
    private final IdentityAuthoritySourcePort source;
    private final IdentitySyncProcessorPort processor;
    private final IdentitySyncAuditPort audit;
    private final IdentitySyncObservabilityPort observability;
    private final IdentitySyncTransactionPort transactions;
    private final TrustedTimeSource time;
    private final IdentityReplayPort replay;
    private final IdentitySyncRejectionPort rejections;
    private final CheckpointKey routeKey;

    public IdentitySyncWorker(
            IdentitySyncJobPort jobs,
            IdentityAuthoritySourcePort source,
            IdentitySyncProcessorPort processor,
            IdentitySyncAuditPort audit,
            IdentitySyncObservabilityPort observability,
            IdentitySyncTransactionPort transactions,
            TrustedTimeSource time) {
        this(
                jobs, source, processor, audit, observability, transactions, time,
                (key, fromInclusive, toInclusive, traceId) -> {},
                rejection -> {},
                DEFAULT_IDENTITY_ROUTE);
    }

    public IdentitySyncWorker(
            IdentitySyncJobPort jobs,
            IdentityAuthoritySourcePort source,
            IdentitySyncProcessorPort processor,
            IdentitySyncAuditPort audit,
            IdentitySyncObservabilityPort observability,
            IdentitySyncTransactionPort transactions,
            TrustedTimeSource time,
            IdentityReplayPort replay) {
        this(
                jobs, source, processor, audit, observability, transactions, time,
                replay, rejection -> {}, DEFAULT_IDENTITY_ROUTE);
    }

    public IdentitySyncWorker(
            IdentitySyncJobPort jobs,
            IdentityAuthoritySourcePort source,
            IdentitySyncProcessorPort processor,
            IdentitySyncAuditPort audit,
            IdentitySyncObservabilityPort observability,
            IdentitySyncTransactionPort transactions,
            TrustedTimeSource time,
            IdentityReplayPort replay,
            IdentitySyncRejectionPort rejections) {
        this(
                jobs, source, processor, audit, observability, transactions, time,
                replay, rejections, DEFAULT_IDENTITY_ROUTE);
    }

    public IdentitySyncWorker(
            IdentitySyncJobPort jobs,
            IdentityAuthoritySourcePort source,
            IdentitySyncProcessorPort processor,
            IdentitySyncAuditPort audit,
            IdentitySyncObservabilityPort observability,
            IdentitySyncTransactionPort transactions,
            TrustedTimeSource time,
            IdentityReplayPort replay,
            IdentitySyncRejectionPort rejections,
            CheckpointKey routeKey) {
        this.jobs = jobs;
        this.source = source;
        this.processor = processor;
        this.audit = audit;
        this.observability = observability;
        this.transactions = transactions;
        this.time = time;
        this.replay = replay;
        this.rejections = rejections;
        if (routeKey == null || !"identity-org".equals(routeKey.consumerProjection())) {
            throw new IllegalArgumentException("IDENTITY_SYNC_ROUTE_INVALID");
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
        try {
            Optional<IdentityReplayRange> requested =
                    replay.nextRequested(attempt.job().key());
            NormalizedIdentityBatch batch = requested.isPresent()
                    ? source.fetchRange(
                            attempt.job().key(),
                            requested.get().fromInclusive(),
                            requested.get().toInclusive(),
                            attempt.job().traceId())
                    : source.fetch(
                            attempt.job().key(),
                            attempt.inputWatermark(),
                            attempt.job().traceId());
            if (batch != null && batch.noChange()) {
                if (!batch.signatureVerified()) {
                    throw new IdentitySyncException("IDENTITY_SOURCE_SIGNATURE_INVALID");
                }
                Instant heartbeatAt = time.now().instant();
                IdentitySyncJob completed = attempt.job().transitionTo(
                        IdentitySyncJobStatus.SUCCEEDED,
                        IdentitySourceHealth.HEALTHY,
                        IdentityProjectionFreshness.FRESH,
                        heartbeatAt,
                        null,
                        null,
                        attempt.inputWatermark());
                transactions.execute(() -> {
                    jobs.save(attempt, completed, heartbeatAt);
                    return null;
                });
                record("identity_sync_job_total", "no_change", attempt, heartbeatAt);
                return Optional.of(result(attempt, completed));
            }
            java.util.concurrent.atomic.AtomicReference<IdentitySyncJob> saved =
                    new java.util.concurrent.atomic.AtomicReference<>();
            processor.process(batch, attempt.lease(), result -> {
                IdentitySyncJob completed = attempt.job().transitionTo(
                        IdentitySyncJobStatus.SUCCEEDED,
                        IdentitySourceHealth.HEALTHY,
                        IdentityProjectionFreshness.FRESH,
                        now,
                        null,
                        null,
                        result.sourceWatermark());
                jobs.save(attempt, completed, now);
                saved.set(completed);
            });
            IdentitySyncJob completed = saved.get();
            if (completed == null) {
                throw new IdentitySyncException(
                        "IDENTITY_SYNC_PERSISTENCE_UNAVAILABLE");
            }
            record("identity_sync_job_total", "succeeded", attempt, now);
            return Optional.of(result(attempt, completed));
        } catch (IdentitySourcePoisonException poison) {
            quarantine(attempt, poison, now);
            return Optional.of(fail(attempt, poison.code(), false, now));
        } catch (IdentitySyncException failure) {
            return Optional.of(fail(
                    attempt,
                    failure.code(),
                    RETRYABLE_SYNC_CODES.contains(failure.code()),
                    now));
        } catch (RuntimeException unavailable) {
            return Optional.of(fail(
                    attempt,
                    "IDENTITY_SOURCE_DEPENDENCY_UNAVAILABLE",
                    true,
                    now));
        }
    }

    private void quarantine(
            RunningIdentitySyncAttempt attempt,
            IdentitySourcePoisonException poison,
            Instant now) {
        transactions.execute(() -> {
            if (!rejections.leaseIsCurrent(attempt.lease())) {
                throw new IdentitySyncException(
                        "IDENTITY_SYNC_FENCING_STALE");
            }
            rejections.reject(new IdentitySyncRejection(
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
                    "identity.sync.rejected",
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
                    Map.of(
                            "identitySessionPolicy", "ISP-1.0.0",
                            "roleFieldPolicy", "RFP-1.0.0",
                            "roleMapping", "IDENTITY-ROLE-MAPPING-1.0.0",
                            "retentionSchedule", "RS-1.0.0")));
            return null;
        });
    }

    private IdentitySyncWorkerRun fail(
            RunningIdentitySyncAttempt attempt,
            String reasonCode,
            boolean retryable,
            Instant now) {
        boolean retry = retryable && attempt.attemptNo() < attempt.job().retryBudget();
        Instant nextAttemptAt = retry ? now.plusSeconds(backoffSeconds(attempt.attemptNo())) : null;
        IdentitySyncJobStatus status =
                retry ? IdentitySyncJobStatus.QUEUED : IdentitySyncJobStatus.FAILED;
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
                    "identity.sync.failed",
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
                    Map.of(
                            "identitySessionPolicy", "ISP-1.0.0",
                            "roleFieldPolicy", "RFP-1.0.0",
                            "roleMapping", "IDENTITY-ROLE-MAPPING-1.0.0",
                            "retentionSchedule", "RS-1.0.0")));
            return null;
        });
        record("identity_sync_job_total", status.wireName(), attempt, now);
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
                        "consumerProjection", attempt.job().key().consumerProjection(),
                        "outcome", outcome),
                attempt.job().traceId(),
                now));
    }

    private static long backoffSeconds(int attemptNo) {
        int shift = Math.min(5, Math.max(0, attemptNo - 1));
        return Math.min(900, 30L << shift);
    }

    private static IdentitySyncWorkerRun result(
            RunningIdentitySyncAttempt attempt, IdentitySyncJob completed) {
        return new IdentitySyncWorkerRun(
                completed.jobId(),
                attempt.attemptNo(),
                completed.status(),
                completed.reasonCode(),
                completed.lastSuccessfulWatermark());
    }
}
