package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Producer-side observer of transport-neutral consumer-applied facts. */
public final class JdbcAccessInvalidationAppliedFactObserver {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcAccessInvalidationAppliedFactObserver(
            JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public int observe(int batchSize, Instant observedAt) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_ACK_BATCH_INVALID");
        }
        return transactions.execute(status -> {
            var rows = jdbc.query("""
                    select applied_fact_id, apply_id, consumer_id, event_id,
                           applied_version, payload_digest, trace_id
                      from identity_access
                        .ia_access_invalidation_consumer_applied_outbox
                     where status in ('pending', 'retry')
                       and next_attempt_at<=?
                     order by created_at, applied_fact_id
                     for update skip locked
                     limit ?
                    """,
                    (rs, row) -> new Applied(
                            rs.getObject(
                                    "applied_fact_id",
                                    java.util.UUID.class),
                            rs.getObject(
                                    "apply_id",
                                    java.util.UUID.class),
                            rs.getString("consumer_id"),
                            rs.getObject(
                                    "event_id",
                                    java.util.UUID.class),
                            rs.getLong("applied_version"),
                            rs.getString("payload_digest"),
                            rs.getString("trace_id")),
                    Timestamp.from(observedAt),
                    batchSize);
            for (Applied applied : rows) {
                int inserted = jdbc.update("""
                        insert into identity_access
                          .ia_access_invalidation_observed_ack (
                            observed_ack_id, consumer_id, event_id,
                            aggregate_type, lineage_id, applied_version,
                            applied_fact_id, applied_payload_digest,
                            observed_at, trace_id)
                        select ?, ?, fact.event_id, fact.aggregate_type,
                               fact.lineage_id, fact.aggregate_version,
                               ?, fact.payload_digest, ?, fact.trace_id
                          from identity_access
                            .ia_access_invalidation_fact fact
                          join identity_access
                            .ia_access_invalidation_local_apply local_apply
                            on local_apply.apply_id=?
                           and local_apply.consumer_id=?
                           and local_apply.event_id=fact.event_id
                           and local_apply.aggregate_type=fact.aggregate_type
                           and local_apply.lineage_id=fact.lineage_id
                           and local_apply.applied_version=
                               fact.aggregate_version
                           and local_apply.payload_digest=fact.payload_digest
                           and local_apply.trace_id=fact.trace_id
                           and local_apply.decision='applied'
                         where fact.event_id=?
                           and fact.aggregate_version=?
                           and fact.payload_digest=?
                           and fact.trace_id=?
                        on conflict (consumer_id, event_id) do nothing
                        """,
                        applied.appliedFactId(),
                        applied.consumerId(),
                        applied.appliedFactId(),
                        Timestamp.from(observedAt),
                        applied.applyId(),
                        applied.consumerId(),
                        applied.eventId(),
                        applied.appliedVersion(),
                        applied.payloadDigest(),
                        applied.traceId());
                if (inserted != 1 && !existingAckMatches(applied)) {
                    jdbc.update("""
                            update identity_access
                              .ia_access_invalidation_consumer_applied_outbox
                               set status='quarantined', attempts=attempts+1
                             where applied_fact_id=?
                            """,
                            applied.appliedFactId());
                    continue;
                }
                jdbc.update("""
                        update identity_access
                          .ia_access_invalidation_consumer_applied_outbox
                           set status='published', attempts=attempts+1
                         where applied_fact_id=?
                        """,
                        applied.appliedFactId());
                jdbc.update("""
                        update identity_access
                          .ia_access_invalidation_propagation propagation
                           set required_consumer_count=(
                                 select count(*)
                                   from identity_access
                                     .ia_access_invalidation_consumer_registry registry
                                  where registry.lifecycle='active'
                                    and registry.required
                               ),
                               applied_required_consumer_count=(
                                 select count(*)
                                   from identity_access
                                     .ia_access_invalidation_consumer_registry registry
                                  where registry.lifecycle='active'
                                    and registry.required
                                    and exists (
                                      select 1
                                        from identity_access
                                          .ia_access_invalidation_consumer_watermark watermark
                                        join identity_access
                                          .ia_access_invalidation_observed_ack ack
                                          on ack.consumer_id=
                                             watermark.consumer_id
                                         and ack.event_id=
                                             propagation.event_id
                                        join identity_access
                                          .ia_access_invalidation_fact fact
                                          on fact.event_id=ack.event_id
                                       where watermark.consumer_id=
                                             registry.consumer_id
                                         and watermark.producer='identity-access'
                                         and watermark.aggregate_type=
                                             fact.aggregate_type
                                         and watermark.lineage_id=
                                             propagation.lineage_id
                                         and watermark.current_watermark>=
                                             propagation.target_version
                                         and ack.aggregate_type=
                                             fact.aggregate_type
                                         and ack.lineage_id=fact.lineage_id
                                         and ack.applied_version=
                                             fact.aggregate_version
                                         and ack.applied_payload_digest=
                                             fact.payload_digest
                                    )
                               ),
                               last_checked_at=?
                         where propagation.event_id=?
                        """,
                        Timestamp.from(observedAt),
                        applied.eventId());
            }
            return rows.size();
        });
    }

    private boolean existingAckMatches(Applied applied) {
        Boolean matches = jdbc.queryForObject("""
                select exists (
                  select 1
                    from identity_access
                      .ia_access_invalidation_observed_ack ack
                    join identity_access
                      .ia_access_invalidation_fact fact
                      on fact.event_id=ack.event_id
                     and fact.aggregate_type=ack.aggregate_type
                     and fact.lineage_id=ack.lineage_id
                     and fact.aggregate_version=ack.applied_version
                     and fact.payload_digest=ack.applied_payload_digest
                   where ack.observed_ack_id=?
                     and ack.applied_fact_id=?
                     and ack.consumer_id=?
                     and ack.event_id=?
                     and ack.applied_version=?
                     and ack.applied_payload_digest=?
                     and ack.trace_id=?
                )
                """,
                Boolean.class,
                applied.appliedFactId(),
                applied.appliedFactId(),
                applied.consumerId(),
                applied.eventId(),
                applied.appliedVersion(),
                applied.payloadDigest(),
                applied.traceId());
        return Boolean.TRUE.equals(matches);
    }

    private record Applied(
            java.util.UUID appliedFactId,
            java.util.UUID applyId,
            String consumerId,
            java.util.UUID eventId,
            long appliedVersion,
            String payloadDigest,
            String traceId) {}
}
