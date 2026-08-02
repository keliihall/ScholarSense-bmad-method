package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationStorePort;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAggregateType;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationConsumerRoute;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationDeliveryDecision;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Replays a durable route-scoped gap without ever assigning or skipping a
 * consumer watermark. Each fact is passed through the ordinary consumer
 * transaction, so a crash can at worst cause an idempotent replay.
 */
public final class JdbcAccessInvalidationBackfillProcessor {
    private static final int MAX_BATCH_SIZE = 100;
    private static final long MAX_ATTEMPTS = 8;
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccessInvalidationStorePort facts;
    private final JdbcAccessInvalidationConsumer consumer;
    private final AccessInvalidationEventJsonCodec events;

    public JdbcAccessInvalidationBackfillProcessor(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            AccessInvalidationStorePort facts,
            JdbcAccessInvalidationConsumer consumer,
            AccessInvalidationEventJsonCodec events) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.facts = Objects.requireNonNull(facts);
        this.consumer = Objects.requireNonNull(consumer);
        this.events = Objects.requireNonNull(events);
    }

    /**
     * Claims at most {@code requestBatchSize} requests and applies at most
     * {@code versionBatchSize} consecutive facts for each request.
     */
    public int process(
            String leaseOwner,
            int requestBatchSize,
            int versionBatchSize,
            Instant now) {
        if (leaseOwner == null
                || leaseOwner.isBlank()
                || requestBatchSize < 1
                || requestBatchSize > MAX_BATCH_SIZE
                || versionBatchSize < 1
                || versionBatchSize > MAX_BATCH_SIZE
                || now == null) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_BACKFILL_BATCH_INVALID");
        }
        List<Claim> claims = claim(
                leaseOwner, requestBatchSize, now);
        for (Claim claim : claims) {
            try {
                replay(claim, versionBatchSize, now);
                checkpoint(claim, now);
            } catch (BackfillValidationException invalid) {
                failed(claim, invalid.getMessage(), now, true);
            } catch (RuntimeException failure) {
                if (isFenced(failure)) {
                    throw failure;
                }
                failed(
                        claim,
                        "ACCESS_INVALIDATION_BACKFILL_RETRYABLE_FAILURE",
                        now,
                        claim.attempts() >= MAX_ATTEMPTS);
            }
        }
        return claims.size();
    }

    private List<Claim> claim(
            String leaseOwner, int batchSize, Instant now) {
        return transactions.execute(status -> jdbc.query("""
                with due as (
                  select request_id
                    from identity_access
                      .ia_access_invalidation_backfill_request
                   where status in ('pending', 'running')
                     and next_attempt_at<=?
                     and (
                       status<>'running'
                       or lease_expires_at is null
                       or lease_expires_at<=?
                     )
                   order by next_attempt_at, requested_at, request_id
                   for update skip locked
                   limit ?
                )
                update identity_access
                  .ia_access_invalidation_backfill_request request
                   set status='running',
                       attempts=request.attempts+1,
                       lease_owner=?,
                       lease_expires_at=?,
                       claim_fencing_token=request.claim_fencing_token+1,
                       updated_at=?
                  from due
                 where request.request_id=due.request_id
                returning request.request_id, request.consumer_id,
                          request.producer, request.aggregate_type,
                          request.lineage_id, request.from_version,
                          request.through_version, request.trigger_event_id,
                          request.trigger_version,
                          request.trigger_payload_digest,
                          request.watermark_fencing_token,
                          request.attempts, request.lease_owner,
                          request.claim_fencing_token, request.trace_id
                """,
                (rs, row) -> new Claim(
                        rs.getObject("request_id", UUID.class),
                        rs.getString("consumer_id"),
                        rs.getString("producer"),
                        aggregateType(rs.getString("aggregate_type")),
                        new AccessInvalidationLineageId(
                                rs.getString("lineage_id")),
                        rs.getLong("from_version"),
                        rs.getLong("through_version"),
                        rs.getObject("trigger_event_id", UUID.class),
                        rs.getLong("trigger_version"),
                        rs.getString("trigger_payload_digest"),
                        rs.getLong("watermark_fencing_token"),
                        rs.getLong("attempts"),
                        rs.getString("lease_owner"),
                        rs.getLong("claim_fencing_token"),
                        rs.getString("trace_id")),
                Timestamp.from(now),
                Timestamp.from(now),
                batchSize,
                leaseOwner,
                Timestamp.from(now.plus(LEASE)),
                Timestamp.from(now)));
    }

    private void replay(
            Claim claim, int versionBatchSize, Instant appliedAt) {
        validateRoute(claim);
        StoredEvent trigger = event(
                claim, claim.triggerVersion());
        if (!claim.triggerEventId().equals(trigger.fact().eventId())
                || !claim.triggerPayloadDigest()
                        .equals(trigger.payloadDigest())
                || !claim.traceId().equals(trigger.fact().traceId())) {
            throw invalid(
                    "ACCESS_INVALIDATION_BACKFILL_TRIGGER_MISMATCH");
        }
        StoredEvent lastMissing = event(
                claim, claim.throughVersion());
        if (!lastMissing.fact().eventId()
                .equals(trigger.fact().supersedesId())) {
            throw invalid(
                    "ACCESS_INVALIDATION_BACKFILL_SUPERSEDES_MISMATCH");
        }

        Watermark current = watermark(claim);
        validateWatermarkFence(claim, current);
        if (current.version() >= claim.triggerVersion()) {
            return;
        }
        long firstVersion = Math.max(
                claim.fromVersion(), current.version() + 1);
        long lastVersion = Math.min(
                claim.triggerVersion(),
                firstVersion + versionBatchSize - 1L);
        List<StoredEvent> replay = events(
                claim, firstVersion, lastVersion);
        long expectedCount = lastVersion - firstVersion + 1L;
        if (replay.size() != expectedCount) {
            throw invalid(
                    "ACCESS_INVALIDATION_BACKFILL_RANGE_INCOMPLETE");
        }

        UUID predecessor = current.eventId();
        long expectedVersion = firstVersion;
        for (StoredEvent stored : replay) {
            AccessInvalidationFact fact = stored.fact();
            if (fact.aggregateVersion() != expectedVersion
                    || !Objects.equals(
                            fact.supersedesId(), predecessor)) {
                throw invalid(
                        "ACCESS_INVALIDATION_BACKFILL_SUPERSEDES_MISMATCH");
            }
            AccessInvalidationDeliveryDecision decision;
            try {
                decision = consumer.apply(
                        claim.route(),
                        fact,
                        stored.eventPayload(),
                        stored.payloadDigest(),
                        appliedAt);
            } catch (IllegalArgumentException invalidPayload) {
                throw invalid(
                        "ACCESS_INVALIDATION_BACKFILL_EVENT_INVALID");
            }
            if (decision
                            == AccessInvalidationDeliveryDecision.CONFLICT
                    || decision
                            == AccessInvalidationDeliveryDecision
                                    .GAP_BACKFILL_REQUIRED) {
                throw invalid(
                        "ACCESS_INVALIDATION_BACKFILL_APPLY_CONFLICT");
            }
            predecessor = fact.eventId();
            expectedVersion++;
        }
        validateWatermarkFence(claim, watermark(claim));
    }

    private List<StoredEvent> events(
            Claim claim, long firstVersion, long lastVersion) {
        return jdbc.query("""
                select event_id, aggregate_version,
                       event_payload::text as event_payload,
                       payload_digest
                  from identity_access.ia_access_invalidation_fact
                 where aggregate_type=? and lineage_id=?
                   and aggregate_version between ? and ?
                 order by aggregate_version
                """,
                (rs, row) -> storedEvent(
                        claim,
                        rs.getObject("event_id", UUID.class),
                        rs.getLong("aggregate_version"),
                        rs.getString("event_payload"),
                        rs.getString("payload_digest")),
                wire(claim.aggregateType()),
                claim.lineageId().value(),
                firstVersion,
                lastVersion);
    }

    private StoredEvent event(Claim claim, long version) {
        return jdbc.query("""
                select event_id, aggregate_version,
                       event_payload::text as event_payload,
                       payload_digest
                  from identity_access.ia_access_invalidation_fact
                 where aggregate_type=? and lineage_id=?
                   and aggregate_version=?
                """,
                (rs, row) -> storedEvent(
                        claim,
                        rs.getObject("event_id", UUID.class),
                        rs.getLong("aggregate_version"),
                        rs.getString("event_payload"),
                        rs.getString("payload_digest")),
                wire(claim.aggregateType()),
                claim.lineageId().value(),
                version).stream().findFirst().orElseThrow(() -> invalid(
                        "ACCESS_INVALIDATION_BACKFILL_FACT_MISSING"));
    }

    private StoredEvent storedEvent(
            Claim claim,
            UUID eventId,
            long aggregateVersion,
            String eventPayload,
            String payloadDigest) {
        AccessInvalidationFact fact = facts.find(eventId)
                .orElseThrow(() -> invalid(
                        "ACCESS_INVALIDATION_BACKFILL_FACT_MISSING"));
        if (fact.aggregateType() != claim.aggregateType()
                || !fact.lineageId().equals(claim.lineageId())
                || fact.aggregateVersion() != aggregateVersion) {
            throw invalid(
                    "ACCESS_INVALIDATION_BACKFILL_ROUTE_MISMATCH");
        }
        try {
            var validated = events.validate(fact, eventPayload);
            if (!payloadDigest.equals(validated.payloadDigest())) {
                throw invalid(
                        "ACCESS_INVALIDATION_BACKFILL_DIGEST_MISMATCH");
            }
        } catch (IllegalArgumentException invalidPayload) {
            throw invalid(
                    "ACCESS_INVALIDATION_BACKFILL_EVENT_INVALID");
        }
        return new StoredEvent(fact, eventPayload, payloadDigest);
    }

    private Watermark watermark(Claim claim) {
        return jdbc.query("""
                select current_watermark, last_event_id,
                       last_payload_digest, fencing_token
                  from identity_access
                    .ia_access_invalidation_consumer_watermark
                 where consumer_id=? and producer=?
                   and aggregate_type=? and lineage_id=?
                """,
                (rs, row) -> new Watermark(
                        rs.getLong("current_watermark"),
                        rs.getObject("last_event_id", UUID.class),
                        rs.getString("last_payload_digest"),
                        rs.getLong("fencing_token")),
                claim.consumerId(),
                claim.producer(),
                wire(claim.aggregateType()),
                claim.lineageId().value()).stream().findFirst()
                .orElseThrow(() -> invalid(
                        "ACCESS_INVALIDATION_BACKFILL_WATERMARK_MISSING"));
    }

    private static void validateRoute(Claim claim) {
        if (!"authorization-current-scope".equals(
                        claim.consumerId())
                || !"identity-access".equals(claim.producer())
                || claim.fromVersion() < 1
                || claim.throughVersion() < claim.fromVersion()
                || claim.triggerVersion()
                        != claim.throughVersion() + 1) {
            throw invalid(
                    "ACCESS_INVALIDATION_BACKFILL_ROUTE_INVALID");
        }
    }

    private void validateWatermarkFence(
            Claim claim, Watermark watermark) {
        long initialVersion = claim.fromVersion() - 1;
        if (watermark.version() < initialVersion) {
            throw invalid(
                    "ACCESS_INVALIDATION_BACKFILL_WATERMARK_REGRESSED");
        }
        long expectedFence;
        try {
            expectedFence = Math.addExact(
                    claim.watermarkFencingToken(),
                    watermark.version() - initialVersion);
        } catch (ArithmeticException overflow) {
            throw invalid(
                    "ACCESS_INVALIDATION_BACKFILL_WATERMARK_FENCED");
        }
        if (watermark.fencingToken() != expectedFence) {
            throw invalid(
                    "ACCESS_INVALIDATION_BACKFILL_WATERMARK_FENCED");
        }
        if (watermark.version() == 0) {
            if (watermark.eventId() != null
                    || watermark.payloadDigest() != null) {
                throw invalid(
                        "ACCESS_INVALIDATION_BACKFILL_WATERMARK_MISMATCH");
            }
            return;
        }
        StoredEvent current = event(claim, watermark.version());
        if (!current.fact().eventId().equals(watermark.eventId())
                || !current.payloadDigest()
                        .equals(watermark.payloadDigest())) {
            throw invalid(
                    "ACCESS_INVALIDATION_BACKFILL_WATERMARK_MISMATCH");
        }
    }

    private void checkpoint(Claim claim, Instant updatedAt) {
        Watermark watermark = watermark(claim);
        validateWatermarkFence(claim, watermark);
        boolean completed = watermark.version()
                >= claim.triggerVersion();
        int updated = jdbc.update("""
                update identity_access
                  .ia_access_invalidation_backfill_request
                   set status=?, lease_owner=null, lease_expires_at=null,
                       next_attempt_at=?, last_error_code=null,
                       completed_at=?, updated_at=?
                 where request_id=? and status='running'
                   and lease_owner=? and claim_fencing_token=?
                """,
                completed ? "completed" : "pending",
                Timestamp.from(updatedAt),
                completed ? Timestamp.from(updatedAt) : null,
                Timestamp.from(updatedAt),
                claim.requestId(),
                claim.leaseOwner(),
                claim.claimFencingToken());
        requireCurrent(updated);
    }

    private void failed(
            Claim claim,
            String reasonCode,
            Instant failedAt,
            boolean quarantine) {
        Instant nextAttemptAt = failedAt.plus(backoff(claim.attempts()));
        int updated = jdbc.update("""
                update identity_access
                  .ia_access_invalidation_backfill_request
                   set status=?, lease_owner=null, lease_expires_at=null,
                       next_attempt_at=?, last_error_code=?, updated_at=?
                 where request_id=? and status='running'
                   and lease_owner=? and claim_fencing_token=?
                """,
                quarantine ? "quarantined" : "pending",
                Timestamp.from(nextAttemptAt),
                reasonCode,
                Timestamp.from(failedAt),
                claim.requestId(),
                claim.leaseOwner(),
                claim.claimFencingToken());
        requireCurrent(updated);
    }

    private static Duration backoff(long attempt) {
        return Duration.ofSeconds(
                Math.min(900, 1L << Math.min(10, attempt)));
    }

    private static boolean isFenced(RuntimeException failure) {
        return "ACCESS_INVALIDATION_BACKFILL_WORKER_FENCED"
                .equals(failure.getMessage());
    }

    private static void requireCurrent(int updated) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_BACKFILL_WORKER_FENCED");
        }
    }

    private static BackfillValidationException invalid(
            String reasonCode) {
        return new BackfillValidationException(reasonCode);
    }

    private static AccessInvalidationAggregateType aggregateType(
            String value) {
        try {
            return AccessInvalidationAggregateType.valueOf(
                    value.toUpperCase().replace('-', '_'));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_BACKFILL_ROUTE_INVALID",
                    invalid);
        }
    }

    private static String wire(Enum<?> value) {
        return value.name().toLowerCase().replace('_', '-');
    }

    private record Claim(
            UUID requestId,
            String consumerId,
            String producer,
            AccessInvalidationAggregateType aggregateType,
            AccessInvalidationLineageId lineageId,
            long fromVersion,
            long throughVersion,
            UUID triggerEventId,
            long triggerVersion,
            String triggerPayloadDigest,
            long watermarkFencingToken,
            long attempts,
            String leaseOwner,
            long claimFencingToken,
            String traceId) {
        private AccessInvalidationConsumerRoute route() {
            return new AccessInvalidationConsumerRoute(
                    consumerId,
                    producer,
                    aggregateType,
                    lineageId);
        }
    }

    private record StoredEvent(
            AccessInvalidationFact fact,
            String eventPayload,
            String payloadDigest) {}

    private record Watermark(
            long version,
            UUID eventId,
            String payloadDigest,
            long fencingToken) {}

    private static final class BackfillValidationException
            extends RuntimeException {
        private BackfillValidationException(String reasonCode) {
            super(reasonCode);
        }
    }
}
