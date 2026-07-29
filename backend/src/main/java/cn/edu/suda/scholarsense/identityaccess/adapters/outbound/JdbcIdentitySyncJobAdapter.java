package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityLease;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityProjectionFreshness;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourceHealth;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncFailureResolution;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncJob;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncJobPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncJobStatus;
import cn.edu.suda.scholarsense.identityaccess.application.RunningIdentitySyncAttempt;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists the published job state machine and serializes lease transfer on the checkpoint row. */
public final class JdbcIdentitySyncJobAdapter implements IdentitySyncJobPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcIdentitySyncJobAdapter(
            JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public void enqueue(IdentitySyncJob job) {
        jdbc.update("""
                insert into identity_access.ia_identity_sync_job (
                  job_id, source_id, feed_id, partition_id, consumer_projection,
                  status, health, freshness, requested_at, completed_at,
                  last_successful_watermark, next_attempt_at, retry_budget,
                  reason_code, trace_id, retention_effective_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                job.jobId(), job.key().sourceId(), job.key().feedId(),
                job.key().partitionId(), job.key().consumerProjection(),
                job.status().wireName(), job.health().wireName(),
                job.freshness().wireName(), timestamp(job.requestedAt()),
                timestamp(job.completedAt()), job.lastSuccessfulWatermark(),
                timestamp(job.nextAttemptAt()), job.retryBudget(), job.reasonCode(),
                job.traceId(), timestamp(job.requestedAt()),
                timestamp(job.requestedAt().plus(Duration.ofDays(90))));
    }

    @Override
    public boolean enqueueIfEligible(IdentitySyncJob job) {
        Boolean enqueued = transactions.execute(status -> {
            lockScope(job.key());
            try {
                int inserted = jdbc.update("""
                    insert into identity_access.ia_identity_sync_job (
                      job_id, source_id, feed_id, partition_id, consumer_projection,
                      status, health, freshness, requested_at, completed_at,
                      last_successful_watermark, next_attempt_at, retry_budget,
                      reason_code, trace_id, retention_effective_at, expires_at)
                    select ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                     where not exists (
                       select 1
                         from identity_access.ia_identity_sync_job existing
                        where existing.source_id=?
                          and existing.feed_id=?
                          and existing.partition_id=?
                          and existing.consumer_projection=?
                          and existing.status='failed'
                          and not exists (
                            select 1
                              from identity_access.ia_identity_sync_failure_resolution resolution
                             where resolution.failed_job_id=existing.job_id))
                    """,
                    job.jobId(), job.key().sourceId(), job.key().feedId(),
                    job.key().partitionId(), job.key().consumerProjection(),
                    job.status().wireName(), job.health().wireName(),
                    job.freshness().wireName(), timestamp(job.requestedAt()),
                    timestamp(job.completedAt()), job.lastSuccessfulWatermark(),
                    timestamp(job.nextAttemptAt()), job.retryBudget(), job.reasonCode(),
                    job.traceId(), timestamp(job.requestedAt()),
                    timestamp(job.requestedAt().plus(Duration.ofDays(90))),
                    job.key().sourceId(), job.key().feedId(), job.key().partitionId(),
                    job.key().consumerProjection());
                return inserted == 1;
            } catch (DuplicateKeyException concurrentSchedulerWon) {
                return false;
            }
        });
        return Boolean.TRUE.equals(enqueued);
    }

    @Override
    public boolean resolveFailureAndEnqueue(
            IdentitySyncFailureResolution resolution,
            IdentitySyncJob replacement) {
        if (!resolution.key().equals(replacement.key())) {
            throw new IllegalArgumentException("resolution and replacement scope differ");
        }
        Boolean resolved = transactions.execute(status -> {
            lockScope(resolution.key());
            List<UUID> failures = jdbc.query("""
                    select failed.job_id
                      from identity_access.ia_identity_sync_job failed
                     where failed.source_id=? and failed.feed_id=?
                       and failed.partition_id=? and failed.consumer_projection=?
                       and failed.status='failed'
                       and not exists (
                         select 1
                           from identity_access.ia_identity_sync_failure_resolution existing
                          where existing.failed_job_id=failed.job_id)
                     order by failed.completed_at desc nulls last, failed.job_id
                     limit 1
                     for update
                    """,
                    (rs, row) -> rs.getObject(1, UUID.class),
                    resolution.key().sourceId(), resolution.key().feedId(),
                    resolution.key().partitionId(),
                    resolution.key().consumerProjection());
            if (failures.isEmpty()) {
                return false;
            }
            try {
                enqueue(replacement);
            } catch (DuplicateKeyException activeWorkExists) {
                return false;
            }
            jdbc.update("""
                    insert into identity_access.ia_identity_sync_failure_resolution (
                      resolution_id, failed_job_id, replacement_job_id,
                      resolved_by, resolution_code, trace_id, resolved_at,
                      retention_effective_at, expires_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    resolution.resolutionId(), failures.getFirst(),
                    replacement.jobId(), resolution.resolvedBy(),
                    resolution.resolutionCode(), resolution.traceId(),
                    timestamp(resolution.resolvedAt()),
                    timestamp(resolution.resolvedAt()),
                    timestamp(resolution.resolvedAt().plus(Duration.ofDays(365))));
            return true;
        });
        return Boolean.TRUE.equals(resolved);
    }

    @Override
    public long lastSuccessfulWatermark(CheckpointKey key) {
        List<Long> values = jdbc.query("""
                select source_watermark
                  from identity_access.ia_identity_sync_checkpoint
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection=?
                """,
                (rs, row) -> rs.getLong(1),
                key.sourceId(), key.feedId(), key.partitionId(),
                key.consumerProjection());
        return values.stream().findFirst().orElse(0L);
    }

    @Override
    public boolean hasPending(CheckpointKey key) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from identity_access.ia_identity_sync_job
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection=?
                   and status in ('queued', 'running')
                """,
                Integer.class,
                key.sourceId(), key.feedId(), key.partitionId(),
                key.consumerProjection());
        return count != null && count > 0;
    }

    @Override
    public Optional<IdentitySyncJob> nextDue(Instant now) {
        List<IdentitySyncJob> values = jdbc.query("""
                select job_id, source_id, feed_id, partition_id, consumer_projection,
                       status, health, freshness, requested_at, completed_at,
                       last_successful_watermark, next_attempt_at, retry_budget,
                       coalesce((
                         select max(attempt_no)
                           from identity_access.ia_identity_sync_attempt attempt
                          where attempt.job_id=job.job_id), 0) as last_attempt_no,
                       reason_code, trace_id
                 from identity_access.ia_identity_sync_job job
                 where (status='queued'
                        and (next_attempt_at is null or next_attempt_at<=?))
                    or (status='running' and exists (
                         select 1
                           from identity_access.ia_identity_sync_lease lease
                          where lease.job_id=job.job_id
                            and lease.lease_expires_at<=?))
                 order by requested_at, job_id
                 limit 1
                """,
                (rs, row) -> mapJob(rs),
                timestamp(now), timestamp(now));
        return values.stream().findFirst();
    }

    @Override
    public Optional<RunningIdentitySyncAttempt> start(
            UUID jobId, String leaseOwner, Instant now) {
        return transactions.execute(status -> {
            IdentitySyncJob candidate = find(jobId).orElse(null);
            if (candidate == null
                    || candidate.status() != IdentitySyncJobStatus.QUEUED
                        && candidate.status() != IdentitySyncJobStatus.RUNNING
                    || candidate.status() == IdentitySyncJobStatus.QUEUED
                        && candidate.nextAttemptAt() != null
                        && candidate.nextAttemptAt().isAfter(now)) {
                return Optional.empty();
            }
            ensureCheckpoint(candidate.key(), now, candidate.traceId());
            Long inputWatermark = jdbc.queryForObject("""
                    select source_watermark
                      from identity_access.ia_identity_sync_checkpoint
                     where source_id=? and feed_id=? and partition_id=?
                       and consumer_projection=?
                     for update
                    """,
                    Long.class,
                    candidate.key().sourceId(), candidate.key().feedId(),
                    candidate.key().partitionId(), candidate.key().consumerProjection());
            lockScope(candidate.key());
            List<LeaseState> leaseStates = jdbc.query("""
                    select job_id, attempt_no, fencing_token, lease_expires_at
                      from identity_access.ia_identity_sync_lease
                     where source_id=? and feed_id=? and partition_id=?
                       and consumer_projection=?
                    """,
                    (rs, row) -> new LeaseState(
                            rs.getObject("job_id", UUID.class),
                            rs.getInt("attempt_no"),
                            rs.getLong("fencing_token"),
                            rs.getTimestamp("lease_expires_at").toInstant()),
                    candidate.key().sourceId(), candidate.key().feedId(),
                    candidate.key().partitionId(), candidate.key().consumerProjection());
            if (candidate.status() == IdentitySyncJobStatus.RUNNING) {
                if (leaseStates.isEmpty()
                        || leaseStates.getFirst().expiresAt().isAfter(now)) {
                    return Optional.empty();
                }
                if (!recoverExpiredJob(jobId, now)) {
                    return Optional.empty();
                }
                candidate = find(jobId).orElseThrow();
                if (candidate.status() != IdentitySyncJobStatus.QUEUED) {
                    return Optional.empty();
                }
            } else if (!leaseStates.isEmpty()
                    && leaseStates.getFirst().expiresAt().isAfter(now)) {
                return Optional.empty();
            } else if (!leaseStates.isEmpty()) {
                LeaseState expired = leaseStates.getFirst();
                recoverExpiredJob(expired.jobId(), now);
            }
            int claimed = jdbc.update("""
                    update identity_access.ia_identity_sync_job
                       set status='running', next_attempt_at=null, reason_code=null
                     where job_id=? and status='queued'
                       and (next_attempt_at is null or next_attempt_at<=?)
                    """,
                    jobId, timestamp(now));
            if (claimed != 1) {
                return Optional.empty();
            }
            int attemptNo = candidate.lastAttemptNo() + 1;
            long fencingToken =
                    leaseStates.isEmpty() ? 1 : leaseStates.getFirst().fencingToken() + 1;
            Instant expiresAt = now.plus(Duration.ofMinutes(2));
            jdbc.update("""
                    insert into identity_access.ia_identity_sync_attempt (
                      job_id, attempt_no, fencing_token, status, input_watermark,
                      started_at, trace_id, retention_effective_at, expires_at)
                    values (?, ?, ?, 'running', ?, ?, ?, ?, ?)
                    """,
                    jobId, attemptNo, fencingToken, inputWatermark,
                    timestamp(now), candidate.traceId(), timestamp(now),
                    timestamp(now.plus(Duration.ofDays(90))));
            jdbc.update("""
                    insert into identity_access.ia_identity_sync_lease (
                      source_id, feed_id, partition_id, consumer_projection,
                      job_id, attempt_no, fencing_token, lease_owner,
                      acquired_at, lease_expires_at, retention_effective_at, expires_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (source_id, feed_id, partition_id, consumer_projection)
                    do update set job_id=excluded.job_id,
                      attempt_no=excluded.attempt_no,
                      fencing_token=excluded.fencing_token,
                      lease_owner=excluded.lease_owner,
                      acquired_at=excluded.acquired_at,
                      lease_expires_at=excluded.lease_expires_at,
                      retention_effective_at=excluded.retention_effective_at,
                      expires_at=excluded.expires_at,
                      legal_hold=false
                    """,
                    candidate.key().sourceId(), candidate.key().feedId(),
                    candidate.key().partitionId(), candidate.key().consumerProjection(),
                    jobId, attemptNo, fencingToken, leaseOwner,
                    timestamp(now), timestamp(expiresAt), timestamp(now),
                    timestamp(now.plus(Duration.ofDays(90))));
            IdentitySyncJob running = candidate.transitionTo(
                    IdentitySyncJobStatus.RUNNING,
                    candidate.health(),
                    candidate.freshness(),
                    null,
                    null,
                    null,
                    candidate.lastSuccessfulWatermark());
            return Optional.of(new RunningIdentitySyncAttempt(
                    running,
                    attemptNo,
                    new IdentityLease(
                            candidate.key(), jobId, attemptNo, fencingToken,
                            leaseOwner, now, expiresAt),
                    inputWatermark == null ? 0 : inputWatermark,
                    now));
        });
    }

    /**
     * Closes one timed-out attempt and either queues another attempt or makes the
     * job terminal when its total-attempt budget has been consumed.
     */
    private boolean recoverExpiredJob(UUID jobId, Instant now) {
        jdbc.update("""
                update identity_access.ia_identity_sync_attempt
                   set status='failed', completed_at=?,
                       reason_code='IDENTITY_SYNC_ATTEMPT_TIMEOUT'
                 where job_id=? and status='running'
                """,
                timestamp(now), jobId);
        List<Integer> recovered = jdbc.query("""
                with attempt_count as (
                  select coalesce(max(attempt_no), 0) as used
                    from identity_access.ia_identity_sync_attempt
                   where job_id=?
                )
                update identity_access.ia_identity_sync_job job
                   set status=case
                         when attempt_count.used >= job.retry_budget
                           then 'failed'
                         else 'queued'
                       end,
                       health='degraded',
                       reason_code=case
                         when attempt_count.used >= job.retry_budget
                           then 'IDENTITY_SYNC_RETRY_BUDGET_EXHAUSTED'
                         else 'IDENTITY_SYNC_ATTEMPT_TIMEOUT'
                       end,
                       next_attempt_at=case
                         when attempt_count.used >= job.retry_budget
                           then null
                         else cast(? as timestamptz)
                       end,
                       completed_at=case
                         when attempt_count.used >= job.retry_budget
                           then ?
                         else cast(null as timestamptz)
                       end
                  from attempt_count
                 where job.job_id=? and job.status='running'
                returning 1
                """,
                (rs, row) -> rs.getInt(1),
                jobId,
                timestamp(now),
                timestamp(now),
                jobId);
        return !recovered.isEmpty();
    }

    @Override
    public void save(
            RunningIdentitySyncAttempt attempt,
            IdentitySyncJob completed,
            Instant now) {
        transactions.executeWithoutResult(status -> {
            lockScope(attempt.job().key());
            String attemptStatus = completed.status() == IdentitySyncJobStatus.SUCCEEDED
                    ? "succeeded"
                    : completed.status() == IdentitySyncJobStatus.CANCELLED
                            ? "cancelled" : "failed";
            int savedAttempt = jdbc.update("""
                    update identity_access.ia_identity_sync_attempt attempt
                       set status=?, output_watermark=?, completed_at=?, reason_code=?
                     where job_id=? and attempt_no=? and status='running'
                       and fencing_token=?
                       and exists (
                         select 1 from identity_access.ia_identity_sync_lease lease
                          where lease.source_id=? and lease.feed_id=?
                            and lease.partition_id=? and lease.consumer_projection=?
                            and lease.job_id=attempt.job_id
                            and lease.attempt_no=attempt.attempt_no
                            and lease.fencing_token=attempt.fencing_token
                            and lease.lease_expires_at>clock_timestamp())
                    """,
                    attemptStatus,
                    completed.status() == IdentitySyncJobStatus.SUCCEEDED
                            ? completed.lastSuccessfulWatermark() : null,
                    timestamp(now),
                    completed.reasonCode(),
                    attempt.job().jobId(),
                    attempt.attemptNo(),
                    attempt.lease().fencingToken(),
                    attempt.job().key().sourceId(),
                    attempt.job().key().feedId(),
                    attempt.job().key().partitionId(),
                    attempt.job().key().consumerProjection());
            int savedJob = jdbc.update("""
                    update identity_access.ia_identity_sync_job job
                       set status=?, health=?, freshness=?, completed_at=?,
                           last_successful_watermark=?, next_attempt_at=?,
                           reason_code=?
                     where job_id=? and status='running'
                       and exists (
                         select 1 from identity_access.ia_identity_sync_lease lease
                          where lease.source_id=? and lease.feed_id=?
                            and lease.partition_id=? and lease.consumer_projection=?
                            and lease.job_id=job.job_id and lease.attempt_no=?
                            and lease.fencing_token=?
                            and lease.lease_expires_at>clock_timestamp())
                    """,
                    completed.status().wireName(),
                    completed.health().wireName(),
                    completed.freshness().wireName(),
                    timestamp(completed.completedAt()),
                    completed.lastSuccessfulWatermark(),
                    timestamp(completed.nextAttemptAt()),
                    completed.reasonCode(),
                    completed.jobId(),
                    completed.key().sourceId(),
                    completed.key().feedId(),
                    completed.key().partitionId(),
                    completed.key().consumerProjection(),
                    attempt.attemptNo(),
                    attempt.lease().fencingToken());
            if (savedAttempt != 1 || savedJob != 1) {
                throw new IdentitySyncException("IDENTITY_SYNC_FENCING_STALE");
            }
            if (completed.status() == IdentitySyncJobStatus.SUCCEEDED) {
                int refreshed = jdbc.update("""
                        update identity_access.ia_identity_sync_checkpoint checkpoint
                           set last_successful_at=?,
                               health='healthy',
                               freshness='fresh',
                               updated_at=?,
                               trace_id=?
                         where checkpoint.source_id=? and checkpoint.feed_id=?
                           and checkpoint.partition_id=?
                           and checkpoint.consumer_projection=?
                           and exists (
                             select 1
                               from identity_access.ia_identity_sync_lease lease
                              where lease.source_id=checkpoint.source_id
                                and lease.feed_id=checkpoint.feed_id
                                and lease.partition_id=checkpoint.partition_id
                                and lease.consumer_projection=checkpoint.consumer_projection
                                and lease.job_id=? and lease.attempt_no=?
                                and lease.fencing_token=?
                                and lease.lease_expires_at>clock_timestamp())
                        """,
                        timestamp(now), timestamp(now), completed.traceId(),
                        completed.key().sourceId(), completed.key().feedId(),
                        completed.key().partitionId(),
                        completed.key().consumerProjection(),
                        attempt.job().jobId(), attempt.attemptNo(),
                        attempt.lease().fencingToken());
                if (refreshed != 1) {
                    throw new IdentitySyncException("IDENTITY_SYNC_FENCING_STALE");
                }
            } else if (completed.health() == IdentitySourceHealth.DEGRADED) {
                jdbc.update("""
                        update identity_access.ia_identity_sync_checkpoint
                           set health='degraded',
                               freshness=case
                                 when last_successful_at is null
                                   or last_successful_at<=? then 'stale'
                                 else freshness
                               end,
                               updated_at=?, trace_id=?
                         where source_id=? and feed_id=? and partition_id=?
                           and consumer_projection=?
                        """,
                        timestamp(now.minus(Duration.ofMinutes(15))),
                        timestamp(now),
                        completed.traceId(),
                        completed.key().sourceId(),
                        completed.key().feedId(),
                        completed.key().partitionId(),
                        completed.key().consumerProjection());
            }
        });
    }

    private void lockScope(CheckpointKey key) {
        jdbc.queryForObject("""
                select 1
                  from (
                    select pg_advisory_xact_lock(hashtextextended(
                      concat_ws('|', ?, ?, ?, ?), 0))
                  ) locked
                """,
                Integer.class,
                key.sourceId(), key.feedId(), key.partitionId(),
                key.consumerProjection());
    }

    private Optional<IdentitySyncJob> find(UUID jobId) {
        List<IdentitySyncJob> values = jdbc.query("""
                select job_id, source_id, feed_id, partition_id, consumer_projection,
                       status, health, freshness, requested_at, completed_at,
                       last_successful_watermark, next_attempt_at, retry_budget,
                       coalesce((
                         select max(attempt_no)
                           from identity_access.ia_identity_sync_attempt attempt
                          where attempt.job_id=job.job_id), 0) as last_attempt_no,
                       reason_code, trace_id
                  from identity_access.ia_identity_sync_job job
                 where job_id=?
                """,
                (rs, row) -> mapJob(rs),
                jobId);
        return values.stream().findFirst();
    }

    private void ensureCheckpoint(
            CheckpointKey key, Instant now, String traceId) {
        jdbc.update("""
                insert into identity_access.ia_identity_sync_checkpoint (
                  source_id, feed_id, partition_id, consumer_projection,
                  source_version, source_watermark, aggregate_version,
                  health, freshness, updated_at, trace_id, retention_effective_at)
                values (?, ?, ?, ?, 0, 0, 0, 'degraded', 'stale', ?, ?, ?)
                on conflict (source_id, feed_id, partition_id, consumer_projection) do nothing
                """,
                key.sourceId(), key.feedId(), key.partitionId(),
                key.consumerProjection(), timestamp(now), traceId, timestamp(now));
    }

    private static IdentitySyncJob mapJob(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new IdentitySyncJob(
                rs.getObject("job_id", UUID.class),
                new CheckpointKey(
                        rs.getString("source_id"),
                        rs.getString("feed_id"),
                        rs.getString("partition_id"),
                        rs.getString("consumer_projection")),
                IdentitySyncJobStatus.valueOf(rs.getString("status").toUpperCase()),
                IdentitySourceHealth.valueOf(rs.getString("health").toUpperCase()),
                IdentityProjectionFreshness.valueOf(
                        rs.getString("freshness").toUpperCase()),
                instant(rs.getTimestamp("requested_at")),
                instant(rs.getTimestamp("completed_at")),
                rs.getLong("last_successful_watermark"),
                instant(rs.getTimestamp("next_attempt_at")),
                rs.getInt("retry_budget"),
                rs.getInt("last_attempt_no"),
                rs.getString("reason_code"),
                rs.getString("trace_id"));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record LeaseState(
            UUID jobId, int attemptNo, long fencingToken, Instant expiresAt) {}
}
