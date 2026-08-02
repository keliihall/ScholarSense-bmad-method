package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationConsumerPort;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationIdPort;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationWatermarkQueryPort;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationConsumerRoute;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationConsumerWatermark;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationDeliveryDecision;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The real current-scope fence consumer. Inbox, apply record, watermark, and
 * consumer-applied outbox commit in one identity-access transaction.
 */
public final class JdbcAccessInvalidationConsumer
        implements AccessInvalidationConsumerPort,
                AccessInvalidationWatermarkQueryPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccessInvalidationIdPort identifiers;
    private final AccessInvalidationEventJsonCodec events;

    public JdbcAccessInvalidationConsumer(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            AccessInvalidationIdPort identifiers) {
        this(jdbc, transactions, identifiers, new ObjectMapper());
    }

    public JdbcAccessInvalidationConsumer(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            AccessInvalidationIdPort identifiers,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.identifiers = identifiers;
        this.events = new AccessInvalidationEventJsonCodec(json);
    }

    @Override
    public AccessInvalidationDeliveryDecision apply(
            AccessInvalidationConsumerRoute route,
            AccessInvalidationFact fact,
            String eventPayload,
            String payloadDigest,
            Instant appliedAt) {
        if (!"authorization-current-scope".equals(route.consumerId())
                || !route.lineageId().equals(fact.lineageId())
                || route.aggregateType() != fact.aggregateType()) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_LOCAL_CONSUMER_ROUTE_INVALID");
        }
        var validated = events.validate(fact, eventPayload);
        if (!validated.payloadDigest().equals(payloadDigest)) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_EVENT_DIGEST_MISMATCH");
        }
        return transactions.execute(status -> applyInTransaction(
                route, fact, validated, appliedAt));
    }

    @Override
    public Optional<AccessInvalidationConsumerWatermark> find(
            AccessInvalidationConsumerRoute route) {
        return jdbc.query("""
                select current_watermark, last_event_id,
                       last_payload_digest, applied_at
                  from identity_access
                    .ia_access_invalidation_consumer_watermark
                 where consumer_id=? and producer=?
                   and aggregate_type=? and lineage_id=?
                   and current_watermark > 0
                """,
                (rs, row) -> new AccessInvalidationConsumerWatermark(
                        route,
                        rs.getLong("current_watermark"),
                        rs.getObject("last_event_id", UUID.class),
                        rs.getString("last_payload_digest"),
                        rs.getTimestamp("applied_at").toInstant()),
                route.consumerId(),
                route.producer(),
                wire(route.aggregateType()),
                route.lineageId().value())
                .stream()
                .findFirst();
    }

    private AccessInvalidationDeliveryDecision applyInTransaction(
            AccessInvalidationConsumerRoute route,
            AccessInvalidationFact fact,
            AccessInvalidationEventJsonCodec.ValidatedPayload validated,
            Instant appliedAt) {
        String payloadDigest = validated.payloadDigest();
        int insertedInbox = jdbc.update("""
                insert into identity_access
                  .ia_access_invalidation_local_inbox (
                    consumer_id, event_id, event_payload, payload_digest,
                    received_at, trace_id)
                values (?, ?, cast(? as jsonb), ?, ?, ?)
                on conflict (consumer_id, event_id) do nothing
                """,
                route.consumerId(),
                fact.eventId(),
                validated.canonicalPayload(),
                payloadDigest,
                Timestamp.from(appliedAt),
                fact.traceId());
        if (insertedInbox == 0) {
            String existing = jdbc.queryForObject("""
                    select payload_digest
                      from identity_access
                        .ia_access_invalidation_local_inbox
                     where consumer_id=? and event_id=?
                    """,
                    String.class,
                    route.consumerId(),
                    fact.eventId());
            if (!payloadDigest.equals(existing)) {
                return AccessInvalidationDeliveryDecision.CONFLICT;
            }
        }
        jdbc.update("""
                insert into identity_access
                  .ia_access_invalidation_consumer_watermark (
                    consumer_id, producer, aggregate_type, lineage_id,
                    current_watermark, fencing_token, trace_id)
                values (?, ?, ?, ?, 0, 0, ?)
                on conflict (
                  consumer_id, producer, aggregate_type, lineage_id)
                do nothing
                """,
                route.consumerId(),
                route.producer(),
                wire(route.aggregateType()),
                route.lineageId().value(),
                fact.traceId());
        LockedWatermark current = jdbc.query("""
                select current_watermark, last_event_id,
                       last_payload_digest, fencing_token
                  from identity_access
                    .ia_access_invalidation_consumer_watermark
                 where consumer_id=? and producer=?
                   and aggregate_type=? and lineage_id=?
                   for update
                """,
                (rs, row) -> new LockedWatermark(
                        rs.getLong("current_watermark"),
                        rs.getObject("last_event_id", UUID.class),
                        rs.getString("last_payload_digest"),
                        rs.getLong("fencing_token")),
                route.consumerId(),
                route.producer(),
                wire(route.aggregateType()),
                route.lineageId().value())
                .getFirst();
        AccessInvalidationDeliveryDecision decision =
                classify(current, fact, payloadDigest);
        if (decision
                == AccessInvalidationDeliveryDecision
                        .GAP_BACKFILL_REQUIRED) {
            requestBackfill(route, fact, payloadDigest, current, appliedAt);
            return decision;
        }
        if (decision != AccessInvalidationDeliveryDecision.APPLIED) {
            return decision;
        }
        applyFence(route, fact, payloadDigest, current, appliedAt);
        UUID applyId = identifiers.next(appliedAt);
        jdbc.update("""
                insert into identity_access.ia_access_invalidation_local_apply (
                  apply_id, consumer_id, event_id, aggregate_type,
                  lineage_id, applied_version, payload_digest, decision,
                  applied_at, trace_id)
                values (?, ?, ?, ?, ?, ?, ?, 'applied', ?, ?)
                """,
                applyId,
                route.consumerId(),
                fact.eventId(),
                wire(route.aggregateType()),
                route.lineageId().value(),
                fact.aggregateVersion(),
                payloadDigest,
                Timestamp.from(appliedAt),
                fact.traceId());
        int advanced = jdbc.update("""
                update identity_access
                    .ia_access_invalidation_consumer_watermark
                   set current_watermark=?,
                       last_event_id=?,
                       last_payload_digest=?,
                       applied_at=?,
                       fencing_token=fencing_token+1,
                       trace_id=?
                 where consumer_id=? and producer=?
                   and aggregate_type=? and lineage_id=?
                   and current_watermark=?
                   and fencing_token=?
                """,
                fact.aggregateVersion(),
                fact.eventId(),
                payloadDigest,
                Timestamp.from(appliedAt),
                fact.traceId(),
                route.consumerId(),
                route.producer(),
                wire(route.aggregateType()),
                route.lineageId().value(),
                current.watermark(),
                current.fencingToken());
        if (advanced != 1) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_CONSUMER_FENCED");
        }
        jdbc.update("""
                insert into identity_access
                  .ia_access_invalidation_consumer_applied_outbox (
                    applied_fact_id, apply_id, consumer_id, event_id,
                    applied_version, payload_digest, status, attempts,
                    next_attempt_at, created_at, trace_id)
                values (?, ?, ?, ?, ?, ?, 'pending', 0, ?, ?, ?)
                """,
                identifiers.next(appliedAt),
                applyId,
                route.consumerId(),
                fact.eventId(),
                fact.aggregateVersion(),
                payloadDigest,
                Timestamp.from(appliedAt),
                Timestamp.from(appliedAt),
                fact.traceId());
        return decision;
    }

    private void applyFence(
            AccessInvalidationConsumerRoute route,
            AccessInvalidationFact fact,
            String payloadDigest,
            LockedWatermark current,
            Instant appliedAt) {
        if (current.watermark() == 0) {
            int inserted = jdbc.update("""
                    insert into identity_access
                      .ia_access_invalidation_local_fence (
                        consumer_id, aggregate_type, lineage_id,
                        current_event_id, current_version, current_state,
                        payload_digest, applied_at, fencing_token, trace_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                    on conflict (consumer_id, aggregate_type, lineage_id)
                    do nothing
                    """,
                    route.consumerId(),
                    wire(route.aggregateType()),
                    route.lineageId().value(),
                    fact.eventId(),
                    fact.aggregateVersion(),
                    wire(fact.authorizationSnapshot().currentState()),
                    payloadDigest,
                    Timestamp.from(appliedAt),
                    fact.traceId());
            requireFenceApplied(inserted);
            return;
        }
        int updated = jdbc.update("""
                update identity_access
                  .ia_access_invalidation_local_fence
                   set current_event_id=?, current_version=?,
                       current_state=?, payload_digest=?, applied_at=?,
                       fencing_token=fencing_token+1, trace_id=?
                 where consumer_id=? and aggregate_type=? and lineage_id=?
                   and current_event_id=? and current_version=?
                """,
                fact.eventId(),
                fact.aggregateVersion(),
                wire(fact.authorizationSnapshot().currentState()),
                payloadDigest,
                Timestamp.from(appliedAt),
                fact.traceId(),
                route.consumerId(),
                wire(route.aggregateType()),
                route.lineageId().value(),
                current.eventId(),
                current.watermark());
        requireFenceApplied(updated);
    }

    private void requestBackfill(
            AccessInvalidationConsumerRoute route,
            AccessInvalidationFact fact,
            String payloadDigest,
            LockedWatermark current,
            Instant requestedAt) {
        jdbc.update("""
                insert into identity_access
                  .ia_access_invalidation_backfill_request (
                    request_id, consumer_id, producer, aggregate_type,
                    lineage_id, from_version, through_version,
                    trigger_event_id, trigger_version,
                    trigger_payload_digest,
                    watermark_fencing_token, status, requested_at,
                    updated_at, next_attempt_at, trace_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?)
                on conflict (
                  consumer_id, producer, aggregate_type, lineage_id,
                  from_version, through_version, trigger_event_id,
                  trigger_payload_digest, watermark_fencing_token)
                do update set updated_at=excluded.updated_at
                """,
                identifiers.next(requestedAt),
                route.consumerId(),
                route.producer(),
                wire(route.aggregateType()),
                route.lineageId().value(),
                current.watermark() + 1,
                fact.aggregateVersion() - 1,
                fact.eventId(),
                fact.aggregateVersion(),
                payloadDigest,
                current.fencingToken(),
                Timestamp.from(requestedAt),
                Timestamp.from(requestedAt),
                Timestamp.from(requestedAt),
                fact.traceId());
    }

    private static void requireFenceApplied(int changed) {
        if (changed != 1) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_LOCAL_FENCE_CONFLICT");
        }
    }

    private static AccessInvalidationDeliveryDecision classify(
            LockedWatermark current,
            AccessInvalidationFact fact,
            String payloadDigest) {
        if (fact.aggregateVersion() == current.watermark()) {
            return fact.eventId().equals(current.eventId())
                            && payloadDigest.equals(
                                    current.payloadDigest())
                    ? AccessInvalidationDeliveryDecision.DUPLICATE
                    : AccessInvalidationDeliveryDecision.CONFLICT;
        }
        if (fact.aggregateVersion() < current.watermark()) {
            return AccessInvalidationDeliveryDecision.OLD_IGNORED;
        }
        if (fact.aggregateVersion() == current.watermark() + 1) {
            return Objects.equals(fact.supersedesId(), current.eventId())
                    ? AccessInvalidationDeliveryDecision.APPLIED
                    : AccessInvalidationDeliveryDecision.CONFLICT;
        }
        return AccessInvalidationDeliveryDecision
                .GAP_BACKFILL_REQUIRED;
    }

    private static String wire(Enum<?> value) {
        return value.name().toLowerCase().replace('_', '-');
    }

    private record LockedWatermark(
            long watermark,
            UUID eventId,
            String payloadDigest,
            long fencingToken) {}
}
