package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationOutboxClaim;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationRelayRepositoryPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcAccessInvalidationDeliveryRepository
        implements AccessInvalidationRelayRepositoryPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcAccessInvalidationDeliveryRepository(
            JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public List<AccessInvalidationOutboxClaim> claim(
            String leaseOwner,
            int batchSize,
            Instant now,
            Instant leaseExpiresAt) {
        return transactions.execute(status -> jdbc.query("""
                with due as (
                  select outbox_id
                    from identity_access.ia_access_invalidation_outbox
                   where status in ('pending', 'retry', 'claimed')
                     and next_attempt_at <= ?
                     and (
                       status <> 'claimed'
                       or lease_expires_at is null
                       or lease_expires_at <= ?
                     )
                   order by next_attempt_at, created_at, outbox_id
                   for update skip locked
                   limit ?
                )
                update identity_access.ia_access_invalidation_outbox item
                   set status='claimed',
                       attempts=item.attempts+1,
                       lease_owner=?,
                       lease_expires_at=?,
                       fencing_token=item.fencing_token+1
                  from due
                 where item.outbox_id=due.outbox_id
                returning item.outbox_id, item.event_id, item.event_type,
                          item.event_payload::text as event_payload,
                          item.payload_digest, item.delivery_key,
                          item.attempts, item.fencing_token,
                          item.lease_owner, item.trace_id
                """,
                (rs, row) -> new AccessInvalidationOutboxClaim(
                        rs.getObject("outbox_id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("event_payload"),
                        rs.getString("payload_digest"),
                        rs.getString("delivery_key"),
                        rs.getLong("attempts"),
                        rs.getLong("fencing_token"),
                        rs.getString("lease_owner"),
                        rs.getString("trace_id"),
                        now),
                Timestamp.from(now),
                Timestamp.from(now),
                batchSize,
                leaseOwner,
                Timestamp.from(leaseExpiresAt)));
    }

    @Override
    public void published(
            AccessInvalidationOutboxClaim claim,
            UUID attemptId,
            Instant publishedAt) {
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.update("""
                    update identity_access.ia_access_invalidation_outbox
                       set status='published',
                           published_at=?,
                           lease_owner=null,
                           lease_expires_at=null
                     where outbox_id=?
                       and status='claimed'
                       and lease_owner=?
                       and fencing_token=?
                    """,
                    Timestamp.from(publishedAt),
                    claim.outboxId(),
                    claim.leaseOwner(),
                    claim.fencingToken());
            requireCurrent(updated);
            attempt(
                    claim,
                    attemptId,
                    "published",
                    "ACCESS_INVALIDATION_TRANSPORT_PUBLISHED",
                    publishedAt);
        });
    }

    @Override
    public void failed(
            AccessInvalidationOutboxClaim claim,
            UUID attemptId,
            String reasonCode,
            Instant failedAt,
            Instant nextAttemptAt,
            boolean quarantine) {
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.update("""
                    update identity_access.ia_access_invalidation_outbox
                       set status=?,
                           next_attempt_at=?,
                           lease_owner=null,
                           lease_expires_at=null
                     where outbox_id=?
                       and status='claimed'
                       and lease_owner=?
                       and fencing_token=?
                    """,
                    quarantine ? "quarantined" : "retry",
                    Timestamp.from(nextAttemptAt),
                    claim.outboxId(),
                    claim.leaseOwner(),
                    claim.fencingToken());
            requireCurrent(updated);
            attempt(
                    claim,
                    attemptId,
                    quarantine ? "quarantined" : "retry",
                    reasonCode,
                    failedAt);
        });
    }

    private void attempt(
            AccessInvalidationOutboxClaim claim,
            UUID attemptId,
            String outcome,
            String reasonCode,
            Instant attemptedAt) {
        jdbc.update("""
                insert into identity_access
                  .ia_access_invalidation_delivery_attempt (
                    attempt_id, outbox_id, attempt_no, fencing_token,
                    outcome, reason_code, attempted_at, trace_id)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                attemptId,
                claim.outboxId(),
                claim.attemptNo(),
                claim.fencingToken(),
                outcome,
                reasonCode,
                Timestamp.from(attemptedAt),
                claim.traceId());
    }

    private static void requireCurrent(int updated) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_RELAY_FENCED");
        }
    }
}
