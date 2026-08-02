package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityExceptionAuditTransition;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityReconciliationJobPort;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityReconciliationLease;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityReconciliationResult;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityReconciliationStorePort;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySnapshotEntry;
import cn.edu.suda.scholarsense.identityaccess.application.RunningResponsibilityReconciliationAttempt;
import cn.edu.suda.scholarsense.identityaccess.application.UuidV7;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Persistent daily reconciliation queue, lease, as-of view, and evidence store. */
public final class JdbcResponsibilityReconciliationAdapter
        implements ResponsibilityReconciliationJobPort,
                ResponsibilityReconciliationStorePort {
    private static final Duration LEASE_DURATION =
            Duration.ofMinutes(10);
    private static final Duration RETENTION =
            Duration.ofDays(365);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TrustedTimeSource time;
    private final int retryBudget;

    public JdbcResponsibilityReconciliationAdapter(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            TrustedTimeSource time,
            int retryBudget) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.transactions =
                java.util.Objects.requireNonNull(transactions);
        this.time = java.util.Objects.requireNonNull(time);
        if (retryBudget < 0) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_RECONCILIATION_RETRY_INVALID");
        }
        this.retryBudget = retryBudget;
    }

    @Override
    public boolean terminalRunExists(
            CheckpointKey key, LocalDate businessDate) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from identity_access.ia_responsibility_reconciliation_job
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                   and business_date=?
                   and job_kind='full-reconciliation'
                   and status in (
                     'succeeded', 'failed', 'missed', 'cancelled')
                """,
                Integer.class,
                key.sourceId(),
                key.feedId(),
                key.partitionId(),
                businessDate);
        return count != null && count > 0;
    }

    @Override
    public Optional<LocalDate> latestScheduledBusinessDate(
            CheckpointKey key) {
        return Optional.ofNullable(jdbc.queryForObject("""
                select max(business_date)
                  from identity_access.ia_responsibility_reconciliation_job
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                   and job_kind='full-reconciliation'
                """,
                LocalDate.class,
                key.sourceId(),
                key.feedId(),
                key.partitionId()));
    }

    @Override
    public void enqueue(
            CheckpointKey key,
            LocalDate businessDate,
            String traceId) {
        Instant now = time.now().instant();
        jdbc.update("""
                insert into identity_access.ia_responsibility_reconciliation_job (
                  job_id, source_id, feed_id, partition_id,
                  consumer_projection, business_date, job_kind,
                  status, requested_at, retry_budget, trace_id,
                  retention_effective_at, expires_at)
                values (?, ?, ?, ?, 'responsibility', ?,
                        'full-reconciliation', 'queued', ?, ?, ?, ?, ?)
                on conflict (
                  source_id, feed_id, partition_id,
                  consumer_projection, business_date, job_kind)
                  do nothing
                """,
                UUID.fromString(UuidV7.generate(now)),
                key.sourceId(),
                key.feedId(),
                key.partitionId(),
                businessDate,
                timestamp(now),
                retryBudget,
                traceId,
                timestamp(now),
                timestamp(now.plus(Duration.ofDays(90))));
    }

    @Override
    public void miss(
            CheckpointKey key,
            LocalDate businessDate,
            String traceId,
            String reasonCode,
            Instant detectedAt) {
        jdbc.update("""
                insert into identity_access.ia_responsibility_reconciliation_job (
                  job_id, source_id, feed_id, partition_id,
                  consumer_projection, business_date, job_kind,
                  status, requested_at, completed_at, retry_budget,
                  reason_code, trace_id, retention_effective_at,
                  expires_at)
                values (?, ?, ?, ?, 'responsibility', ?,
                        'full-reconciliation', 'missed', ?, ?, ?, ?, ?, ?, ?)
                on conflict (
                  source_id, feed_id, partition_id,
                  consumer_projection, business_date, job_kind)
                  do nothing
                """,
                UUID.fromString(UuidV7.generate(detectedAt)),
                key.sourceId(),
                key.feedId(),
                key.partitionId(),
                businessDate,
                timestamp(detectedAt),
                timestamp(detectedAt),
                retryBudget,
                reasonCode,
                traceId,
                timestamp(detectedAt),
                timestamp(detectedAt.plus(Duration.ofDays(90))));
    }

    @Override
    public Optional<UUID> nextDue(
            CheckpointKey key, Instant now) {
        return jdbc.query("""
                select job.job_id
                  from identity_access.ia_responsibility_reconciliation_job job
                 where job.source_id=? and job.feed_id=?
                   and job.partition_id=?
                   and job.consumer_projection='responsibility'
                   and job.job_kind='full-reconciliation'
                   and ((job.status='queued'
                         and (job.next_attempt_at is null
                              or job.next_attempt_at<=?))
                     or (job.status='running' and exists (
                       select 1
                         from identity_access.ia_responsibility_reconciliation_lease lease
                        where lease.job_id=job.job_id
                          and lease.lease_expires_at<=?)))
                 order by job.business_date, job.requested_at, job.job_id
                 limit 1
                """,
                (rs, row) -> rs.getObject(1, UUID.class),
                key.sourceId(),
                key.feedId(),
                key.partitionId(),
                timestamp(now),
                timestamp(now))
                .stream()
                .findFirst();
    }

    @Override
    public Optional<RunningResponsibilityReconciliationAttempt> start(
            UUID jobId, String leaseOwner, Instant now) {
        return transactions.execute(status -> {
            List<JobState> jobs = jdbc.query("""
                    select job_id, source_id, feed_id, partition_id,
                           consumer_projection, business_date,
                           retry_budget, trace_id, status,
                           next_attempt_at
                      from identity_access.ia_responsibility_reconciliation_job
                     where job_id=?
                     for update
                    """,
                    (rs, row) -> new JobState(
                            rs.getObject("job_id", UUID.class),
                            new CheckpointKey(
                                    rs.getString("source_id"),
                                    rs.getString("feed_id"),
                                    rs.getString("partition_id"),
                                    rs.getString(
                                            "consumer_projection")),
                            rs.getObject(
                                    "business_date",
                                    LocalDate.class),
                            rs.getInt("retry_budget"),
                            rs.getString("trace_id"),
                            rs.getString("status"),
                            instant(rs.getTimestamp(
                                    "next_attempt_at"))),
                    jobId);
            if (jobs.isEmpty()) {
                return Optional.empty();
            }
            JobState job = jobs.getFirst();
            if (!List.of("queued", "running").contains(job.status())
                    || job.nextAttemptAt() != null
                            && job.nextAttemptAt().isAfter(now)) {
                return Optional.empty();
            }
            if ("running".equals(job.status())) {
                Integer expired = jdbc.queryForObject("""
                        select count(*)
                          from identity_access.ia_responsibility_reconciliation_lease
                         where job_id=? and lease_expires_at<=clock_timestamp()
                        """,
                        Integer.class,
                        jobId);
                if (expired == null || expired != 1) {
                    return Optional.empty();
                }
            }
            Integer previousAttempt = jdbc.queryForObject("""
                    select coalesce(max(attempt_no), 0)
                      from identity_access.ia_responsibility_reconciliation_attempt
                     where job_id=?
                    """,
                    Integer.class,
                    jobId);
            Long previousFencing = jdbc.queryForObject("""
                    select coalesce(max(fencing_token), 0)
                      from identity_access.ia_responsibility_reconciliation_attempt
                    """,
                    Long.class);
            int attemptNo =
                    (previousAttempt == null ? 0 : previousAttempt) + 1;
            long fencing =
                    (previousFencing == null ? 0 : previousFencing) + 1;
            Instant expiresAt = now.plus(LEASE_DURATION);
            jdbc.update("""
                    insert into identity_access.ia_responsibility_reconciliation_attempt (
                      job_id, attempt_no, fencing_token, status,
                      started_at, trace_id, retention_effective_at,
                      expires_at)
                    values (?, ?, ?, 'running', ?, ?, ?, ?)
                    """,
                    jobId,
                    attemptNo,
                    fencing,
                    timestamp(now),
                    job.traceId(),
                    timestamp(now),
                    timestamp(now.plus(RETENTION)));
            int leaseUpdated = jdbc.update("""
                    insert into identity_access.ia_responsibility_reconciliation_lease (
                      source_id, feed_id, partition_id,
                      consumer_projection, business_date, job_kind,
                      job_id, attempt_no, fencing_token, lease_owner,
                      acquired_at, lease_expires_at,
                      retention_effective_at, expires_at)
                    values (?, ?, ?, 'responsibility', ?,
                            'full-reconciliation', ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (
                      source_id, feed_id, partition_id,
                      consumer_projection, business_date, job_kind)
                    do update set
                      job_id=excluded.job_id,
                      attempt_no=excluded.attempt_no,
                      fencing_token=excluded.fencing_token,
                      lease_owner=excluded.lease_owner,
                      acquired_at=excluded.acquired_at,
                      lease_expires_at=excluded.lease_expires_at,
                      retention_effective_at=
                          excluded.retention_effective_at,
                      expires_at=excluded.expires_at
                    where identity_access.ia_responsibility_reconciliation_lease
                            .lease_expires_at<=clock_timestamp()
                    """,
                    job.key().sourceId(),
                    job.key().feedId(),
                    job.key().partitionId(),
                    job.businessDate(),
                    jobId,
                    attemptNo,
                    fencing,
                    leaseOwner,
                    timestamp(now),
                    timestamp(expiresAt),
                    timestamp(now),
                    timestamp(now.plus(RETENTION)));
            if (leaseUpdated != 1) {
                throw new IdentitySyncException(
                        "RESPONSIBILITY_RECONCILIATION_FENCING_STALE");
            }
            int jobUpdated = jdbc.update("""
                    update identity_access.ia_responsibility_reconciliation_job
                       set status='running', next_attempt_at=null,
                           reason_code=null
                     where job_id=? and status=?
                    """,
                    jobId,
                    job.status());
            if (jobUpdated != 1) {
                throw new IdentitySyncException(
                        "RESPONSIBILITY_RECONCILIATION_FENCING_STALE");
            }
            var lease = new ResponsibilityReconciliationLease(
                    job.key(),
                    job.businessDate(),
                    jobId,
                    attemptNo,
                    fencing,
                    leaseOwner,
                    now,
                    expiresAt);
            return Optional.of(
                    new RunningResponsibilityReconciliationAttempt(
                            jobId,
                            job.key(),
                            job.businessDate(),
                            attemptNo,
                            job.retryBudget(),
                            job.traceId(),
                            now,
                            lease));
        });
    }

    @Override
    public void complete(
            RunningResponsibilityReconciliationAttempt attempt,
            ResponsibilityReconciliationResult result,
            Instant completedAt) {
        int attemptUpdated = jdbc.update("""
                update identity_access.ia_responsibility_reconciliation_attempt attempt
                   set status='succeeded', completed_at=?, reason_code=null
                 where attempt.job_id=? and attempt.attempt_no=?
                   and exists (
                     select 1
                       from identity_access.ia_responsibility_reconciliation_lease lease
                      where lease.job_id=attempt.job_id
                        and lease.attempt_no=attempt.attempt_no
                        and lease.fencing_token=?
                        and lease.lease_owner=?
                        and lease.lease_expires_at>clock_timestamp())
                """,
                timestamp(completedAt),
                attempt.jobId(),
                attempt.attemptNo(),
                attempt.lease().fencingToken(),
                attempt.lease().leaseOwner());
        int jobUpdated = jdbc.update("""
                update identity_access.ia_responsibility_reconciliation_job job
                   set status='succeeded', completed_at=?,
                       reason_code=null
                 where job.job_id=? and job.status='running'
                   and exists (
                     select 1
                       from identity_access.ia_responsibility_reconciliation_lease lease
                      where lease.job_id=job.job_id
                        and lease.attempt_no=?
                        and lease.fencing_token=?
                        and lease.lease_owner=?
                        and lease.lease_expires_at>clock_timestamp())
                """,
                timestamp(completedAt),
                attempt.jobId(),
                attempt.attemptNo(),
                attempt.lease().fencingToken(),
                attempt.lease().leaseOwner());
        if (attemptUpdated != 1 || jobUpdated != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_RECONCILIATION_FENCING_STALE");
        }
    }

    @Override
    public void fail(
            RunningResponsibilityReconciliationAttempt attempt,
            String reasonCode,
            boolean retry,
            Instant nextAttemptAt,
            Instant failedAt) {
        int attemptUpdated = jdbc.update("""
                update identity_access.ia_responsibility_reconciliation_attempt attempt
                   set status='failed', completed_at=?, reason_code=?
                 where attempt.job_id=? and attempt.attempt_no=?
                   and exists (
                     select 1
                       from identity_access.ia_responsibility_reconciliation_lease lease
                      where lease.job_id=attempt.job_id
                        and lease.attempt_no=attempt.attempt_no
                        and lease.fencing_token=?
                        and lease.lease_owner=?
                        and lease.lease_expires_at>clock_timestamp())
                """,
                timestamp(failedAt),
                reasonCode,
                attempt.jobId(),
                attempt.attemptNo(),
                attempt.lease().fencingToken(),
                attempt.lease().leaseOwner());
        int jobUpdated = jdbc.update("""
                update identity_access.ia_responsibility_reconciliation_job job
                   set status=?, completed_at=?, next_attempt_at=?,
                       reason_code=?
                 where job.job_id=? and job.status='running'
                   and exists (
                     select 1
                       from identity_access.ia_responsibility_reconciliation_lease lease
                      where lease.job_id=job.job_id
                        and lease.attempt_no=?
                        and lease.fencing_token=?
                        and lease.lease_owner=?
                        and lease.lease_expires_at>clock_timestamp())
                """,
                retry ? "queued" : "failed",
                retry ? null : timestamp(failedAt),
                timestamp(nextAttemptAt),
                reasonCode,
                attempt.jobId(),
                attempt.attemptNo(),
                attempt.lease().fencingToken(),
                attempt.lease().leaseOwner());
        if (attemptUpdated != 1 || jobUpdated != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_RECONCILIATION_FENCING_STALE");
        }
        int leaseDeleted = jdbc.update("""
                delete from identity_access.ia_responsibility_reconciliation_lease
                 where job_id=? and attempt_no=? and fencing_token=?
                   and lease_owner=?
                """,
                attempt.jobId(),
                attempt.attemptNo(),
                attempt.lease().fencingToken(),
                attempt.lease().leaseOwner());
        if (leaseDeleted != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_RECONCILIATION_FENCING_STALE");
        }
    }

    @Override
    public String activeContractVersion(CheckpointKey key) {
        boolean active = jdbc.query("""
                select active
                  from identity_access
                       .ia_responsibility_v2_shadow_checkpoint
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                """,
                (rs, row) -> rs.getBoolean("active"),
                key.sourceId(), key.feedId(), key.partitionId())
                .stream()
                .findFirst()
                .orElse(false);
        return active
                ? "RESPONSIBILITY-AUTHORITY-2.0.0"
                : "RESPONSIBILITY-AUTHORITY-1.0.0";
    }

    @Override
    public long identityOrgWatermark(
            String feedId, String partitionId) {
        List<Long> values = jdbc.query("""
                select source_watermark
                  from identity_access.ia_identity_sync_checkpoint
                 where source_id='SRC-P0-RESPONSIBILITY-001'
                   and feed_id=? and partition_id=?
                   and consumer_projection='identity-org'
                """,
                (rs, row) -> rs.getLong(1),
                feedId,
                partitionId);
        return values.stream().findFirst().orElse(0L);
    }

    @Override
    public List<ResponsibilitySnapshotEntry> actualSnapshot(
            CheckpointKey key,
            String contractVersion,
            long throughWatermark,
            Instant cutoffAt,
            java.util.Map<String, Long>
                    supportingIdentityOrgWatermarks) {
        if (!activeContractVersion(key).equals(contractVersion)) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_RECONCILIATION_CONTRACT_STALE");
        }
        String sql = "RESPONSIBILITY-AUTHORITY-2.0.0".equals(
                contractVersion)
                ? """
                with latest as (
                  select distinct on (fact.relation_ref_token)
                         fact.relation_ref_token,
                         fact.student_equivalence_digest,
                         fact.record_version,
                         fact.payload_digest,
                         fact.relation_status,
                         fact.effective_from,
                         fact.effective_to,
                         fact.recipient_mapped,
                         fact.supporting_identity_org_watermarks =
                           cast(? as jsonb)
                           as recipient_mapping_baseline_matches
                    from identity_access
                         .ia_responsibility_v2_source_fact fact
                   where fact.source_id=? and fact.feed_id=?
                     and fact.partition_id=?
                     and fact.consumer_projection='responsibility'
                     and fact.source_watermark<=?
                   order by fact.relation_ref_token,
                            fact.source_watermark desc,
                            fact.record_version desc,
                            fact.fact_id desc)
                select latest.*
                  from latest
                 order by latest.relation_ref_token
                """
                : """
                with latest as (
                  select distinct on (fact.relation_ref_token)
                         fact.relation_ref_token,
                         fact.student_equivalence_digest,
                         fact.record_version,
                         fact.payload_digest,
                         fact.relation_status,
                         fact.effective_from,
                         fact.effective_to,
                         fact.recipient_mapped,
                         fact.supporting_identity_org_watermarks =
                           cast(? as jsonb)
                           as recipient_mapping_baseline_matches
                    from identity_access.ia_responsibility_source_fact fact
                   where fact.source_id=? and fact.feed_id=?
                     and fact.partition_id=?
                     and fact.consumer_projection='responsibility'
                     and fact.source_watermark<=?
                   order by fact.relation_ref_token,
                            fact.source_watermark desc,
                            fact.record_version desc,
                            fact.fact_id desc)
                select latest.*
                 from latest
                 order by latest.relation_ref_token
                """;
        return jdbc.query(sql,
                (rs, row) -> {
                    Instant effectiveFrom =
                            instant(rs.getTimestamp(
                                    "effective_from"));
                    Instant effectiveTo =
                            instant(rs.getTimestamp(
                                    "effective_to"));
                    boolean active = "active".equals(
                                    rs.getString(
                                            "relation_status"))
                            && !cutoffAt.isBefore(effectiveFrom)
                            && (effectiveTo == null
                                    || cutoffAt.isBefore(effectiveTo));
                    return new ResponsibilitySnapshotEntry(
                            rs.getString("relation_ref_token"),
                            rs.getString(
                                    "student_equivalence_digest"),
                            rs.getLong("record_version"),
                            rs.getString("payload_digest"),
                            active,
                            active
                                    && rs.getBoolean(
                                            "recipient_mapped")
                                    && rs.getBoolean(
                                            "recipient_mapping_baseline_matches"));
                },
                dependencyJson(
                        supportingIdentityOrgWatermarks),
                key.sourceId(),
                key.feedId(),
                key.partitionId(),
                throughWatermark);
    }

    @Override
    public long openExceptionCount(CheckpointKey key) {
        Long count = jdbc.queryForObject("""
                select count(*)
                  from identity_access.ia_responsibility_exception_current
                 where status='open'
                """,
                Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public void append(
            ResponsibilityReconciliationResult result,
            ResponsibilityReconciliationLease lease) {
        appendWithAuditTransitions(result, lease);
    }

    @Override
    public List<ResponsibilityExceptionAuditTransition>
            appendWithAuditTransitions(
                    ResponsibilityReconciliationResult result,
                    ResponsibilityReconciliationLease lease) {
        if (!leaseIsCurrent(lease)) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_RECONCILIATION_FENCING_STALE");
        }
        lockReconciliationContract(result);
        jdbc.update("""
                insert into identity_access.ia_responsibility_reconciliation_run (
                  run_id, job_id, source_id, feed_id, partition_id,
                  consumer_projection, contract_version, schema_version,
                  business_date, source_version, through_watermark,
                  supporting_identity_org_watermarks, expected_count,
                  actual_count, expected_digest, actual_digest,
                  matched_count, missing_count, unexpected_count,
                  version_drift_count, match_rate,
                  active_unmapped_count, exception_count, job_outcome,
                  reconciliation_outcome, reason_code, fencing_token,
                  started_at, completed_at, trace_id, consumer_watermark,
                  retention_effective_at, expires_at)
                values (?, ?, ?, ?, ?, 'responsibility',
                        ?,
                        'RESPONSIBILITY-SNAPSHOT-1.0.0',
                        ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                result.runId(),
                result.jobId(),
                result.key().sourceId(),
                result.key().feedId(),
                result.key().partitionId(),
                result.contractVersion(),
                result.businessDate(),
                result.sourceVersion(),
                result.throughWatermark(),
                dependencyJson(
                        result.supportingIdentityOrgWatermarks()),
                result.expectedCount(),
                result.actualCount(),
                result.expectedDigest(),
                result.actualDigest(),
                result.matched(),
                result.missing(),
                result.unexpected(),
                result.versionDrift(),
                result.matchRate(),
                result.activeUnmappedCount(),
                result.exceptionCount(),
                result.jobOutcome(),
                result.reconciliationOutcome(),
                result.reasonCode(),
                result.fencingToken(),
                timestamp(result.startedAt()),
                timestamp(result.completedAt()),
                result.traceId(),
                result.throughWatermark(),
                timestamp(result.completedAt()),
                timestamp(result.completedAt().plus(RETENTION)));
        List<ResponsibilityExceptionAuditTransition> transitions =
                new ArrayList<>();
        for (var difference : result.differences()) {
            jdbc.update("""
                    insert into identity_access.ia_responsibility_reconciliation_detail (
                      detail_id, run_id, difference_type,
                      relation_ref_token, student_ref_digest,
                      expected_record_version, actual_record_version,
                      expected_payload_digest, actual_payload_digest,
                      reason_code, trace_id, consumer_watermark,
                      retention_effective_at, expires_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.fromString(UuidV7.generate(
                            result.completedAt())),
                    result.runId(),
                    difference.differenceType(),
                    difference.relationRefToken(),
                    difference.studentSourceRefDigest(),
                    difference.expectedRecordVersion(),
                    difference.actualRecordVersion(),
                    difference.expectedPayloadDigest(),
                    difference.actualPayloadDigest(),
                    difference.reasonCode(),
                    result.traceId(),
                    result.throughWatermark(),
                    timestamp(result.completedAt()),
                    timestamp(result.completedAt().plus(RETENTION)));
            jdbc.update("""
                    update identity_access.ia_responsibility_current
                       set quality_gate_status='blocked',
                           recipient_reason_code=?,
                           trace_id=?
                     where student_equivalence_digest=?
                    """,
                    difference.reasonCode(),
                    result.traceId(),
                    difference.studentSourceRefDigest());
            transitions.addAll(openReconciliationException(
                    result,
                    difference.studentSourceRefDigest()));
        }
        if (result.missing() > 0
                && result.throughWatermark() > 0) {
            jdbc.update("""
                    insert into identity_access.ia_identity_replay_request (
                      request_id, source_id, feed_id, partition_id,
                      consumer_projection, requested_from, requested_to,
                      status, trace_id, requested_at,
                      retention_effective_at, expires_at)
                    values (?, ?, ?, ?, 'responsibility', 1, ?,
                            'requested', ?, ?, ?, ?)
                    on conflict (
                      source_id, feed_id, partition_id,
                      consumer_projection, requested_from, requested_to,
                      trace_id) do nothing
                    """,
                    UUID.fromString(UuidV7.generate(
                            result.completedAt())),
                    result.key().sourceId(),
                    result.key().feedId(),
                    result.key().partitionId(),
                    result.throughWatermark(),
                    result.traceId(),
                    timestamp(result.completedAt()),
                    timestamp(result.completedAt()),
                    timestamp(result.completedAt().plus(
                            Duration.ofDays(90))));
        }
        if (result.qualityPassed()) {
            jdbc.update("""
                update identity_access.ia_responsibility_current current_scope
                       set quality_gate_status='trusted',
                           trace_id=?
                     where current_scope.source_id=?
                       and current_scope.feed_id=?
                       and current_scope.partition_id=?
                       and current_scope.consumer_projection='responsibility'
                       and current_scope.source_watermark<=?
                       and not exists (
                         select 1
                           from identity_access
                                .ia_responsibility_reconciliation_detail detail
                          where detail.run_id=?
                            and detail.student_ref_digest=
                                current_scope.student_equivalence_digest)
                    """,
                    result.traceId(),
                    result.key().sourceId(),
                    result.key().feedId(),
                    result.key().partitionId(),
                    result.throughWatermark(),
                    result.runId());
            transitions.addAll(
                    resolveReconciliationExceptions(result));
        }
        return List.copyOf(transitions);
    }

    private void lockReconciliationContract(
            ResponsibilityReconciliationResult result) {
        List<Long> locked = jdbc.query("""
                select source_watermark
                  from identity_access.ia_identity_sync_checkpoint
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                 for update
                """,
                (rs, row) -> rs.getLong("source_watermark"),
                result.key().sourceId(), result.key().feedId(),
                result.key().partitionId());
        if (locked.isEmpty()
                || !activeContractVersion(result.key()).equals(
                        result.contractVersion())) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_RECONCILIATION_CONTRACT_STALE");
        }
    }

    @Override
    public boolean leaseIsCurrent(
            ResponsibilityReconciliationLease lease) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from identity_access.ia_responsibility_reconciliation_lease
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                   and business_date=?
                   and job_kind='full-reconciliation'
                   and job_id=? and attempt_no=? and fencing_token=?
                   and lease_owner=?
                   and lease_expires_at>clock_timestamp()
                """,
                Integer.class,
                lease.key().sourceId(),
                lease.key().feedId(),
                lease.key().partitionId(),
                lease.businessDate(),
                lease.jobId(),
                lease.attemptNo(),
                lease.fencingToken(),
                lease.leaseOwner());
        return count != null && count == 1;
    }

    private static String dependencyJson(
            java.util.Map<String, Long> values) {
        StringBuilder result = new StringBuilder("{");
        boolean first = true;
        for (var entry :
                new java.util.TreeMap<>(values).entrySet()) {
            if (!first) {
                result.append(',');
            }
            result.append('"')
                    .append(entry.getKey())
                    .append("\":")
                    .append(entry.getValue());
            first = false;
        }
        return result.append('}').toString();
    }

    private List<ResponsibilityExceptionAuditTransition>
            openReconciliationException(
            ResponsibilityReconciliationResult result,
            String studentDigest) {
        List<String> colleges = jdbc.query("""
                select distinct college_organization_ref_digest
                  from identity_access.ia_responsibility_current
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                   and source_watermark<=?
                   and student_equivalence_digest=?
                 order by college_organization_ref_digest
                """,
                (rs, row) -> rs.getString(1),
                result.key().sourceId(),
                result.key().feedId(),
                result.key().partitionId(),
                result.throughWatermark(),
                studentDigest);
        List<ResponsibilityExceptionAuditTransition> transitions =
                new ArrayList<>();
        for (String collegeDigest : colleges) {
            String businessKey = digest(
                    "reconciliation\0"
                            + collegeDigest
                            + "\0"
                            + studentDigest);
            List<ExceptionState> states = jdbc.query("""
                    select exception_id, status, aggregate_version,
                           first_seen_at
                      from identity_access.ia_responsibility_exception_current
                     where business_key_digest=?
                     for update
                    """,
                    (rs, row) -> new ExceptionState(
                            rs.getObject(
                                    "exception_id", UUID.class),
                            rs.getString("status"),
                            rs.getLong("aggregate_version"),
                            instant(rs.getTimestamp(
                                    "first_seen_at"))),
                    businessKey);
            UUID exceptionId = states.isEmpty()
                    ? UUID.fromString(UuidV7.generate(
                            result.completedAt()))
                    : states.getFirst().exceptionId();
            long aggregateVersion = states.isEmpty()
                    ? 1
                    : states.getFirst().aggregateVersion() + 1;
            Instant firstSeen = states.isEmpty()
                    ? result.completedAt()
                    : states.getFirst().firstSeenAt();
            String eventType = reconciliationExceptionEventType(
                    states.isEmpty()
                            ? null
                            : states.getFirst().status());
            jdbc.update("""
                    insert into identity_access.ia_responsibility_exception_history (
                      exception_event_id, exception_id,
                      business_key_digest,
                      college_organization_ref_digest,
                      student_ref_digest, event_type, reason_code,
                      source_kind, source_version, source_watermark,
                      aggregate_version, occurred_at, trace_id,
                      consumer_watermark, retention_effective_at,
                      expires_at)
                    values (?, ?, ?, ?, ?, ?, ?,
                            'reconciliation', ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.fromString(UuidV7.generate(
                            result.completedAt())),
                    exceptionId,
                    businessKey,
                    collegeDigest,
                    studentDigest,
                    eventType,
                    "RESPONSIBILITY_RECONCILIATION_DIFFERENCES",
                    result.sourceVersion(),
                    result.throughWatermark(),
                    aggregateVersion,
                    timestamp(result.completedAt()),
                    result.traceId(),
                    result.throughWatermark(),
                    timestamp(result.completedAt()),
                    timestamp(result.completedAt().plus(RETENTION)));
            jdbc.update("""
                    insert into identity_access.ia_responsibility_exception_current (
                      exception_id, business_key_digest,
                      college_organization_ref_digest,
                      student_ref_digest, reason_code, source_kind,
                      source_version, source_watermark, first_seen_at,
                      last_seen_at, resolved_at, status,
                      aggregate_version, trace_id,
                      retention_effective_at)
                    values (?, ?, ?, ?,
                            'RESPONSIBILITY_RECONCILIATION_DIFFERENCES',
                            'reconciliation', ?, ?, ?, ?, null, 'open',
                            ?, ?, ?)
                    on conflict (business_key_digest) do update set
                      reason_code=excluded.reason_code,
                      source_version=excluded.source_version,
                      source_watermark=excluded.source_watermark,
                      last_seen_at=excluded.last_seen_at,
                      resolved_at=null, status='open',
                      aggregate_version=excluded.aggregate_version,
                      trace_id=excluded.trace_id,
                      retention_effective_at=
                          excluded.retention_effective_at
                    """,
                    exceptionId,
                    businessKey,
                    collegeDigest,
                    studentDigest,
                    result.sourceVersion(),
                    result.throughWatermark(),
                    timestamp(firstSeen),
                    timestamp(result.completedAt()),
                    aggregateVersion,
                    result.traceId(),
                    timestamp(result.completedAt()));
            if ("opened".equals(eventType)) {
                transitions.add(
                        new ResponsibilityExceptionAuditTransition(
                                exceptionId,
                                "responsibility.exception.opened",
                                "RESPONSIBILITY_RECONCILIATION_DIFFERENCES",
                                aggregateVersion));
            }
        }
        return List.copyOf(transitions);
    }

    private List<ResponsibilityExceptionAuditTransition>
            resolveReconciliationExceptions(
            ResponsibilityReconciliationResult result) {
        List<OpenException> open = jdbc.query("""
                select exception_id, business_key_digest,
                       college_organization_ref_digest,
                       student_ref_digest, aggregate_version
                 from identity_access.ia_responsibility_exception_current
                 where source_kind='reconciliation' and status='open'
                   and exists (
                     select 1
                       from identity_access.ia_responsibility_current current_scope
                      where current_scope.source_id=?
                        and current_scope.feed_id=?
                        and current_scope.partition_id=?
                        and current_scope.consumer_projection='responsibility'
                        and current_scope.source_watermark<=?
                        and current_scope.student_equivalence_digest=
                            ia_responsibility_exception_current.student_ref_digest
                        and current_scope.college_organization_ref_digest=
                            ia_responsibility_exception_current
                              .college_organization_ref_digest)
                 for update
                """,
                (rs, row) -> new OpenException(
                        rs.getObject("exception_id", UUID.class),
                        rs.getString("business_key_digest"),
                        rs.getString(
                                "college_organization_ref_digest"),
                        rs.getString("student_ref_digest"),
                        rs.getLong("aggregate_version")),
                result.key().sourceId(),
                result.key().feedId(),
                result.key().partitionId(),
                result.throughWatermark());
        List<ResponsibilityExceptionAuditTransition> transitions =
                new ArrayList<>();
        for (OpenException exception : open) {
            long aggregateVersion =
                    exception.aggregateVersion() + 1;
            jdbc.update("""
                    insert into identity_access.ia_responsibility_exception_history (
                      exception_event_id, exception_id,
                      business_key_digest,
                      college_organization_ref_digest,
                      student_ref_digest, event_type, reason_code,
                      source_kind, source_version, source_watermark,
                      aggregate_version, occurred_at, trace_id,
                      consumer_watermark, retention_effective_at,
                      expires_at)
                    values (?, ?, ?, ?, ?, 'resolved',
                            'RESPONSIBILITY_RECONCILIATION_MATCHED',
                            'reconciliation', ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.fromString(UuidV7.generate(
                            result.completedAt())),
                    exception.exceptionId(),
                    exception.businessKey(),
                    exception.collegeDigest(),
                    exception.studentDigest(),
                    result.sourceVersion(),
                    result.throughWatermark(),
                    aggregateVersion,
                    timestamp(result.completedAt()),
                    result.traceId(),
                    result.throughWatermark(),
                    timestamp(result.completedAt()),
                    timestamp(result.completedAt().plus(RETENTION)));
            jdbc.update("""
                    update identity_access.ia_responsibility_exception_current
                       set reason_code=
                             'RESPONSIBILITY_RECONCILIATION_MATCHED',
                           source_version=?, source_watermark=?,
                           last_seen_at=?, resolved_at=?,
                           status='resolved', aggregate_version=?,
                           trace_id=?, retention_effective_at=?
                     where exception_id=? and status='open'
                    """,
                    result.sourceVersion(),
                    result.throughWatermark(),
                    timestamp(result.completedAt()),
                    timestamp(result.completedAt()),
                    aggregateVersion,
                    result.traceId(),
                    timestamp(result.completedAt()),
                    exception.exceptionId());
            transitions.add(
                    new ResponsibilityExceptionAuditTransition(
                            exception.exceptionId(),
                            "responsibility.exception.resolved",
                            "RESPONSIBILITY_RECONCILIATION_MATCHED",
                            aggregateVersion));
        }
        return List.copyOf(transitions);
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(
                                    StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    static String reconciliationExceptionEventType(
            String currentStatus) {
        return currentStatus == null
                        || "resolved".equals(currentStatus)
                ? "opened"
                : "updated";
    }

    private record JobState(
            UUID jobId,
            CheckpointKey key,
            LocalDate businessDate,
            int retryBudget,
            String traceId,
            String status,
            Instant nextAttemptAt) {}

    private record ExceptionState(
            UUID exceptionId,
            String status,
            long aggregateVersion,
            Instant firstSeenAt) {}

    private record OpenException(
            UUID exceptionId,
            String businessKey,
            String collegeDigest,
            String studentDigest,
            long aggregateVersion) {}
}
