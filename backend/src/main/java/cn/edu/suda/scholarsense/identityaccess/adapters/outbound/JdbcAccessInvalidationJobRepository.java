package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationExpiryPort;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationJobLease;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationJobStorePort;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationJob;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationJobKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationJobState;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReason;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcAccessInvalidationJobRepository
        implements AccessInvalidationExpiryPort,
                AccessInvalidationJobStorePort {
    private static final Duration RETENTION = Duration.ofDays(2190);
    private static final int MAX_BATCH_SIZE = 100;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcAccessInvalidationJobRepository(
            JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public AccessInvalidationJob enqueue(
            UUID jobId,
            AccessInvalidationLineageId lineageId,
            Instant effectiveTo,
            String traceId) {
        ScheduledHead head = jdbc.query("""
                select current_event_id, current_version
                  from identity_access.ia_access_invalidation_lineage_head
                 where lineage_id=?
                """,
                (rs, row) -> new ScheduledHead(
                        rs.getObject("current_event_id", UUID.class),
                        rs.getLong("current_version")),
                lineageId.value()).stream().findFirst().orElseThrow(() ->
                        new IllegalStateException(
                                "ACCESS_INVALIDATION_EXPIRY_HEAD_MISSING"));
        Timestamp databaseNow = jdbc.queryForObject(
                "select current_timestamp", Timestamp.class);
        if (databaseNow == null) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_EXPIRY_CLOCK_UNAVAILABLE");
        }
        return enqueue(
                jobId,
                lineageId,
                head.eventId(),
                head.aggregateVersion(),
                effectiveTo,
                AccessInvalidationReason.RELATION_EXPIRED,
                traceId,
                databaseNow.toInstant());
    }

    @Override
    public AccessInvalidationJob enqueue(
            UUID jobId,
            AccessInvalidationLineageId lineageId,
            UUID scheduledEventId,
            long scheduledAggregateVersion,
            Instant effectiveTo,
            AccessInvalidationReason reasonCode,
            String traceId,
            Instant scheduledAt) {
        if (scheduledEventId == null
                || scheduledAggregateVersion < 1
                || effectiveTo == null
                || reasonCode
                        != AccessInvalidationReason.RELATION_EXPIRED
                || scheduledAt == null) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_EXPIRY_BINDING_INVALID");
        }
        Instant retentionBase = effectiveTo.isAfter(scheduledAt)
                ? effectiveTo : scheduledAt;
        jdbc.update("""
                insert into identity_access.ia_access_invalidation_job (
                  job_id, job_kind, lineage_id, cause_event_id,
                  status, due_at,
                  fencing_token, cursor_value, attempts, next_attempt_at,
                  reason_code, trace_id, created_at, updated_at,
                  retain_until)
                values (
                  ?, 'expiry', ?, ?, 'pending', ?, 0, ?, 0, ?,
                  ?, ?, ?, ?, ?)
                on conflict (job_id) do nothing
                """,
                jobId,
                lineageId.value(),
                scheduledEventId,
                Timestamp.from(effectiveTo),
                scheduledAggregateVersion,
                Timestamp.from(effectiveTo),
                reasonCode.name(),
                traceId,
                Timestamp.from(scheduledAt),
                Timestamp.from(scheduledAt),
                Timestamp.from(retentionBase.plus(RETENTION)));
        ExistingExpiry existing = jdbc.query("""
                select job_id, job_kind, lineage_id, status, due_at,
                       lease_owner, fencing_token, cursor_value,
                       trace_id, updated_at,
                       (
                         job_kind='expiry'
                         and lineage_id=?
                         and cause_event_id=?
                         and cursor_value=?
                         and due_at=?
                         and reason_code=?
                         and trace_id=?
                         and created_at=?
                       ) as binding_matches
                  from identity_access.ia_access_invalidation_job
                 where job_id=?
                """,
                (rs, row) -> new ExistingExpiry(
                        new AccessInvalidationJob(
                                rs.getObject("job_id", UUID.class),
                                AccessInvalidationJobKind.valueOf(
                                        rs.getString("job_kind")
                                                .toUpperCase()),
                                new AccessInvalidationLineageId(
                                        rs.getString("lineage_id")),
                                AccessInvalidationJobState.valueOf(
                                        rs.getString("status")
                                                .toUpperCase()),
                                rs.getTimestamp("due_at").toInstant(),
                                rs.getString("lease_owner"),
                                rs.getLong("fencing_token"),
                                rs.getLong("cursor_value"),
                                rs.getString("trace_id"),
                                rs.getTimestamp("updated_at").toInstant()),
                        rs.getBoolean("binding_matches")),
                lineageId.value(),
                scheduledEventId,
                scheduledAggregateVersion,
                Timestamp.from(effectiveTo),
                reasonCode.name(),
                traceId,
                Timestamp.from(scheduledAt),
                jobId).stream().findFirst().orElseThrow(() ->
                        new IllegalStateException(
                                "ACCESS_INVALIDATION_EXPIRY_JOB_MISSING"));
        if (!existing.bindingMatches()) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_EXPIRY_IDEMPOTENCY_CONFLICT");
        }
        return existing.job();
    }

    @Override
    public List<AccessInvalidationJobLease> claim(
            AccessInvalidationJobKind kind,
            String leaseOwner,
            Instant now,
            int batchSize) {
        if (leaseOwner == null
                || leaseOwner.isBlank()
                || batchSize < 1
                || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_JOB_CLAIM_INVALID");
        }
        return transactions.execute(status -> jdbc.query("""
                with due as (
                  select job_id
                    from identity_access.ia_access_invalidation_job
                   where job_kind=?
                     and status in ('pending', 'retry', 'running')
                     and due_at<=?
                     and next_attempt_at<=?
                     and (
                       status<>'running'
                       or lease_expires_at is null
                       or lease_expires_at<=?
                     )
                   order by due_at, job_id
                   for update skip locked
                   limit ?
                )
                update identity_access.ia_access_invalidation_job job
                   set status='running',
                       lease_owner=?,
                       lease_expires_at=?,
                       fencing_token=job.fencing_token+1,
                       attempts=job.attempts+1,
                       updated_at=?
                  from due
                 where job.job_id=due.job_id
                returning job.job_id, job.job_kind, job.lineage_id,
                          job.cause_event_id, job.due_at,
                          job.lease_owner, job.fencing_token,
                          job.cursor_value, job.cursor_lineage_id,
                          job.attempts, job.trace_id,
                          job.updated_at, job.retain_until
                """,
                (rs, row) -> {
                    var job = new AccessInvalidationJob(
                            rs.getObject("job_id", UUID.class),
                            AccessInvalidationJobKind.valueOf(
                                    rs.getString("job_kind")
                                            .toUpperCase()),
                            new AccessInvalidationLineageId(
                                    rs.getString("lineage_id")),
                            AccessInvalidationJobState.RUNNING,
                            rs.getTimestamp("due_at").toInstant(),
                            rs.getString("lease_owner"),
                            rs.getLong("fencing_token"),
                            rs.getLong("cursor_value"),
                            rs.getString("trace_id"),
                            rs.getTimestamp("updated_at").toInstant());
                    return new AccessInvalidationJobLease(
                            job,
                            rs.getObject("cause_event_id", UUID.class),
                            rs.getLong("attempts"),
                            rs.getTimestamp("retain_until").toInstant(),
                            rs.getString("cursor_lineage_id"));
                },
                kind.name().toLowerCase(),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                batchSize,
                leaseOwner,
                Timestamp.from(now.plus(Duration.ofMinutes(2))),
                Timestamp.from(now)));
    }

    @Override
    public boolean isCurrentExpiry(
            AccessInvalidationJobLease lease) {
        if (lease.job().kind() != AccessInvalidationJobKind.EXPIRY) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_EXPIRY_LEASE_REQUIRED");
        }
        List<UUID> lockedJobs = jdbc.query("""
                select job_id
                  from identity_access.ia_access_invalidation_job
                 where job_id=? and job_kind='expiry'
                   and status='running' and lease_owner=?
                   and fencing_token=? and cause_event_id=?
                   and cursor_value=? and due_at=?
                   and reason_code='RELATION_EXPIRED'
                   for update
                """,
                (rs, row) -> rs.getObject("job_id", UUID.class),
                lease.job().jobId(),
                lease.job().leaseOwner(),
                lease.job().fence(),
                lease.causeEventId(),
                lease.job().cursor(),
                Timestamp.from(lease.job().dueAt()));
        if (lockedJobs.size() != 1) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_EXPIRY_BINDING_FENCED");
        }
        List<String> current = jdbc.query("""
                select current_scope.access_lineage_id
                  from identity_access.ia_responsibility_current current_scope
                  join identity_access.ia_access_invalidation_lineage_head head
                    on head.lineage_id=current_scope.access_lineage_id
                 where current_scope.access_lineage_id=?
                   and current_scope.access_event_id=?
                   and current_scope.effective_to=?
                   and head.current_event_id=?
                   and head.current_version=?
                   for update of current_scope, head
                """,
                (rs, row) -> rs.getString("access_lineage_id"),
                lease.job().lineageId().value(),
                lease.causeEventId(),
                Timestamp.from(lease.job().dueAt()),
                lease.causeEventId(),
                lease.job().cursor());
        return current.size() == 1;
    }

    @Override
    public boolean isCurrentImpact(
            AccessInvalidationJobLease lease) {
        if (lease.job().kind() != AccessInvalidationJobKind.IMPACT) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_IMPACT_LEASE_REQUIRED");
        }
        List<UUID> lockedJobs = jdbc.query("""
                select job.job_id
                  from identity_access.ia_access_invalidation_job job
                  join identity_access.ia_access_invalidation_fact cause
                    on cause.event_id=job.cause_event_id
                 where job.job_id=? and job.job_kind='impact'
                   and job.status='running' and job.lease_owner=?
                   and job.fencing_token=? and job.cause_event_id=?
                   and job.lineage_id=?
                   and cause.aggregate_type='identity-cause'
                   and cause.lineage_id=job.lineage_id
                   and cause.trace_id=job.trace_id
                 for update of job
                """,
                (rs, row) -> rs.getObject("job_id", UUID.class),
                lease.job().jobId(),
                lease.job().leaseOwner(),
                lease.job().fence(),
                lease.causeEventId(),
                lease.job().lineageId().value());
        if (lockedJobs.size() != 1) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_IMPACT_BINDING_FENCED");
        }
        List<UUID> current = jdbc.query("""
                select head.current_event_id
                  from identity_access.ia_access_invalidation_lineage_head head
                 where head.lineage_id=? and head.current_event_id=?
                 for update of head
                """,
                (rs, row) -> rs.getObject("current_event_id", UUID.class),
                lease.job().lineageId().value(),
                lease.causeEventId());
        return current.size() == 1;
    }

    @Override
    public void checkpoint(
            AccessInvalidationJobLease lease,
            long nextCursor,
            boolean completed,
            Instant updatedAt) {
        checkpoint(
                lease,
                nextCursor,
                null,
                completed,
                updatedAt);
    }

    @Override
    public void checkpoint(
            AccessInvalidationJobLease lease,
            long nextCursor,
            String nextCursorKey,
            boolean completed,
            Instant updatedAt) {
        int updated = jdbc.update("""
                update identity_access.ia_access_invalidation_job
                   set status=?,
                       cursor_value=?,
                       cursor_lineage_id=case
                         when job_kind='impact'
                           then coalesce(?, cursor_lineage_id)
                         else cursor_lineage_id end,
                       lease_owner=null,
                       lease_expires_at=null,
                       next_attempt_at=?,
                       updated_at=?
                 where job_id=? and status='running'
                   and lease_owner=? and fencing_token=?
                """,
                completed ? "completed" : "pending",
                nextCursor,
                nextCursorKey,
                Timestamp.from(updatedAt),
                Timestamp.from(updatedAt),
                lease.job().jobId(),
                lease.job().leaseOwner(),
                lease.job().fence());
        requireCurrent(updated);
    }

    @Override
    public void failed(
            AccessInvalidationJobLease lease,
            String reasonCode,
            Instant failedAt,
            Instant nextAttemptAt,
            boolean quarantine) {
        int updated = jdbc.update("""
                update identity_access.ia_access_invalidation_job
                   set status=?,
                       last_error_code=?,
                       lease_owner=null,
                       lease_expires_at=null,
                       next_attempt_at=?,
                       updated_at=?
                 where job_id=? and status='running'
                   and lease_owner=? and fencing_token=?
                """,
                quarantine ? "quarantined" : "retry",
                reasonCode,
                Timestamp.from(nextAttemptAt),
                Timestamp.from(failedAt),
                lease.job().jobId(),
                lease.job().leaseOwner(),
                lease.job().fence());
        requireCurrent(updated);
    }

    private static void requireCurrent(int updated) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_JOB_FENCED");
        }
    }

    private record ScheduledHead(
            UUID eventId, long aggregateVersion) {}

    private record ExistingExpiry(
            AccessInvalidationJob job, boolean bindingMatches) {}
}
