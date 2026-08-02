package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationIdPort;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationReconciliationPort;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReconciliation;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

public final class JdbcAccessInvalidationReconciler
        implements AccessInvalidationReconciliationPort {
    private static final Duration RETENTION = Duration.ofDays(2190);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccessInvalidationIdPort identifiers;
    private final ObjectMapper json;

    public JdbcAccessInvalidationReconciler(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            AccessInvalidationIdPort identifiers,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.identifiers = identifiers;
        this.json = json;
    }

    @Override
    public AccessInvalidationReconciliation reconcile(
            AccessInvalidationLineageId lineageId,
            long targetVersion,
            Instant checkedAt,
            String traceId) {
        return reconcile(
                lineageId,
                targetVersion,
                checkedAt,
                traceId,
                "incremental");
    }

    public int reconcileIncremental(
            Instant checkedAt, int batchSize) {
        return reconcileRows("""
                select propagation.lineage_id,
                       propagation.target_version,
                       propagation.trace_id
                  from identity_access
                    .ia_access_invalidation_propagation propagation
                 where propagation.propagation_status<>'complete'
                    or propagation.required_consumer_count<>(
                      select count(*)
                        from identity_access
                          .ia_access_invalidation_consumer_registry registry
                       where registry.lifecycle='active'
                         and registry.required)
                    or propagation.last_checked_at<coalesce((
                      select max(registry.updated_at)
                        from identity_access
                          .ia_access_invalidation_consumer_registry registry
                    ), propagation.last_checked_at)
                 order by propagation.last_checked_at,
                          propagation.lineage_id,
                          propagation.target_version
                 limit ?
                """, "incremental", checkedAt, batchSize);
    }

    public int reconcileDaily(
            Instant checkedAt, int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_RECONCILIATION_BATCH_INVALID");
        }
        int total = 0;
        String after = "";
        while (true) {
            var rows = jdbc.query("""
                    select lineage_id,
                           current_version as target_version,
                           trace_id
                      from identity_access
                        .ia_access_invalidation_lineage_head
                     where lineage_id>?
                     order by lineage_id
                     limit ?
                    """,
                    (rs, row) -> new Target(
                            new AccessInvalidationLineageId(
                                    rs.getString("lineage_id")),
                            rs.getLong("target_version"),
                            rs.getString("trace_id")),
                    after,
                    batchSize);
            for (Target row : rows) {
                reconcile(
                        row.lineageId(),
                        row.targetVersion(),
                        checkedAt,
                        row.traceId(),
                        "daily-full");
            }
            total += rows.size();
            if (rows.size() < batchSize) {
                return total;
            }
            after = rows.getLast().lineageId().value();
        }
    }

    private int reconcileRows(
            String sql,
            String kind,
            Instant checkedAt,
            int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_RECONCILIATION_BATCH_INVALID");
        }
        var rows = jdbc.query(
                sql,
                (rs, row) -> new Target(
                        new AccessInvalidationLineageId(
                                rs.getString("lineage_id")),
                        rs.getLong("target_version"),
                        rs.getString("trace_id")),
                batchSize);
        for (Target row : rows) {
            reconcile(
                    row.lineageId(),
                    row.targetVersion(),
                    checkedAt,
                    row.traceId(),
                    kind);
        }
        return rows.size();
    }

    private AccessInvalidationReconciliation reconcile(
            AccessInvalidationLineageId lineageId,
            long targetVersion,
            Instant checkedAt,
            String traceId,
            String kind) {
        return transactions.execute(status -> {
            List<ConsumerState> states = jdbc.query("""
                    select registry.consumer_id,
                           coalesce(watermark.current_watermark, 0)
                             as current_watermark,
                           ack.applied_payload_digest,
                           fact.payload_digest
                      from identity_access
                        .ia_access_invalidation_consumer_registry registry
                      left join identity_access
                        .ia_access_invalidation_consumer_watermark watermark
                        on watermark.consumer_id=registry.consumer_id
                       and watermark.producer='identity-access'
                       and watermark.aggregate_type=(
                         select target.aggregate_type
                           from identity_access
                             .ia_access_invalidation_fact target
                          where target.lineage_id=?
                            and target.aggregate_version=?)
                       and watermark.lineage_id=?
                      left join identity_access
                        .ia_access_invalidation_fact fact
                        on fact.lineage_id=?
                       and fact.aggregate_version=?
                      left join identity_access
                        .ia_access_invalidation_observed_ack ack
                        on ack.consumer_id=registry.consumer_id
                       and ack.event_id=fact.event_id
                       and ack.aggregate_type=fact.aggregate_type
                       and ack.lineage_id=fact.lineage_id
                       and ack.applied_version=fact.aggregate_version
                     where registry.lifecycle='active'
                       and registry.required
                     order by registry.consumer_id
                    """,
                    (rs, row) -> new ConsumerState(
                            rs.getString("consumer_id"),
                            rs.getLong("current_watermark"),
                            rs.getString("applied_payload_digest"),
                            rs.getString("payload_digest")),
                    lineageId.value(),
                    targetVersion,
                    lineageId.value(),
                    lineageId.value(),
                    targetVersion);
            Set<String> gaps = new LinkedHashSet<>();
            Set<String> conflicts = new LinkedHashSet<>();
            long observed = Long.MAX_VALUE;
            for (ConsumerState state : states) {
                observed = Math.min(
                        observed, state.currentWatermark());
                if (state.currentWatermark() < targetVersion) {
                    gaps.add(state.consumerId());
                } else if (state.appliedDigest() == null) {
                    gaps.add(state.consumerId());
                } else if (!state.appliedDigest()
                        .equals(state.expectedDigest())) {
                    conflicts.add(state.consumerId());
                }
            }
            if (observed == Long.MAX_VALUE) {
                observed = 0;
            }
            var result = new AccessInvalidationReconciliation(
                    identifiers.next(checkedAt),
                    lineageId,
                    targetVersion,
                    gaps,
                    conflicts,
                    checkedAt,
                    traceId);
            String outcome = !conflicts.isEmpty()
                    ? "conflict"
                    : !gaps.isEmpty() ? "gap" : "healthy";
            jdbc.update("""
                    insert into identity_access
                      .ia_access_invalidation_reconciliation (
                        reconciliation_id, reconciliation_kind,
                        consumer_id, lineage_id, target_version,
                        observed_watermark, outcome, reason_code,
                        gap_consumer_ids, conflict_consumer_ids,
                        checked_at, trace_id, retain_until)
                    values (?, ?, null, ?, ?, ?, ?, ?,
                            cast(? as jsonb), cast(? as jsonb), ?, ?, ?)
                    """,
                    result.reconciliationId(),
                    kind,
                    lineageId.value(),
                    targetVersion,
                    observed,
                    outcome,
                    "healthy".equals(outcome)
                            ? "ACCESS_INVALIDATION_RECONCILIATION_HEALTHY"
                            : "conflict".equals(outcome)
                                    ? "ACCESS_INVALIDATION_RECONCILIATION_CONFLICT"
                                    : "ACCESS_INVALIDATION_RECONCILIATION_GAP",
                    json.writeValueAsString(gaps),
                    json.writeValueAsString(conflicts),
                    Timestamp.from(checkedAt),
                    traceId,
                    Timestamp.from(checkedAt.plus(RETENTION)));
            int applied = (int) states.stream()
                    .filter(value -> value.currentWatermark()
                                    >= targetVersion
                            && value.appliedDigest() != null
                            && value.appliedDigest()
                                    .equals(value.expectedDigest()))
                    .count();
            String impactStatus = impactStatus(
                    lineageId, targetVersion);
            boolean impactPending = impactStatus != null
                    && !"completed".equals(impactStatus)
                    && !"quarantined".equals(impactStatus);
            boolean impactQuarantined =
                    "quarantined".equals(impactStatus);
            boolean complete = result.healthy()
                    && applied == states.size()
                    && impactStatusIsComplete(impactStatus);
            jdbc.update("""
                    update identity_access
                      .ia_access_invalidation_propagation
                       set required_consumer_count=?,
                           applied_required_consumer_count=?,
                           reconciliation_status=?,
                           propagation_status=?,
                           last_checked_at=?
                     where lineage_id=? and target_version=?
                    """,
                    states.size(),
                    applied,
                    outcome,
                    complete
                            ? "complete"
                            : "conflict".equals(outcome)
                                            || impactQuarantined
                                    ? "quarantined"
                                    : impactPending
                                            ? "pending"
                                            : "lagging",
                    Timestamp.from(checkedAt),
                    lineageId.value(),
                    targetVersion);
            return result;
        });
    }

    private String impactStatus(
            AccessInvalidationLineageId lineageId,
            long targetVersion) {
        return jdbc.query("""
                select job.status
                  from identity_access.ia_access_invalidation_fact fact
                  join identity_access.ia_access_invalidation_job job
                    on job.cause_event_id=fact.event_id
                   and job.job_kind='impact'
                 where fact.lineage_id=?
                   and fact.aggregate_version=?
                   and fact.aggregate_type='identity-cause'
                 order by job.created_at desc, job.job_id desc
                 limit 1
                """,
                (rs, row) -> rs.getString("status"),
                lineageId.value(),
                targetVersion)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static boolean impactStatusIsComplete(String status) {
        return status == null || "completed".equals(status);
    }

    private record Target(
            AccessInvalidationLineageId lineageId,
            long targetVersion,
            String traceId) {}

    private record ConsumerState(
            String consumerId,
            long currentWatermark,
            String appliedDigest,
            String expectedDigest) {}
}
