package cn.edu.suda.scholarsense.contractfixture.publicintegration;

import cn.edu.suda.scholarsense.shared.outbox.DeliveryRecordKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test-only PostgreSQL reference adapter. It intentionally has no production
 * configuration, bean, migration, scheduler, endpoint, or domain ownership.
 */
final class PostgresPublicIntegrationReferenceAdapter {

    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Set<String> TABLES = Set.of(
            "queued_delivery",
            "current_delivery",
            "generation_ledger",
            "transition_ledger",
            "delivery_attempt",
            "outbox",
            "provider_lineage_map",
            "source_terminal_fence",
            "lane_cutover_fence",
            "cutover_holding_delivery",
            "idempotency_result",
            "callback_inbox",
            "callback_nonce",
            "reconciliation_state",
            "route_watermark");

    private final String schema;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    PostgresPublicIntegrationReferenceAdapter(
            String schema,
            JdbcTemplate jdbc,
            TransactionTemplate transactions) {
        if (schema == null || !schema.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("PIC_TEST_SCHEMA_INVALID");
        }
        this.schema = schema;
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    TransactionTemplate transactionTemplateForTest() {
        return transactions;
    }

    PublicIntegrationCallbackSecurityVerifier.ReplayStore callbackReplayStore() {
        return registration -> recordAuthenticatedCallback(
                registration.workloadSubject(), registration.keyId(),
                registration.nonceDigest(), registration.contractVersion(),
                registration.source(), registration.eventId(),
                registration.payloadDigest(), registration.technicalResult(),
                registration.firstSeenAt());
    }

    PublicIntegrationCallbackSecurityVerifier.Decision recordAuthenticatedCallback(
            String workloadSubject,
            String keyId,
            String nonceDigest,
            String contractVersion,
            String source,
            String eventId,
            String payloadDigest,
            byte[] technicalResult,
            Instant now) {
        requireText(workloadSubject, "workloadSubject");
        requireText(keyId, "keyId");
        requireHexDigest(nonceDigest, "nonceDigest");
        requireText(contractVersion, "contractVersion");
        requireText(source, "source");
        requireText(eventId, "eventId");
        requireHexDigest(payloadDigest, "payloadDigest");
        Objects.requireNonNull(technicalResult, "technicalResult");
        Objects.requireNonNull(now, "now");
        return transactions.execute(status -> {
            int nonceInserted = jdbc.update("""
                    insert into %s.callback_nonce (
                      workload_sub,key_id,nonce_digest,contract_version,
                      first_seen_at,retain_until)
                    values (?,?,?,?,?,?)
                    on conflict do nothing
                    """.formatted(schema), workloadSubject, keyId, nonceDigest,
                    contractVersion, timestamp(now), timestamp(now.plusSeconds(600)));
            if (nonceInserted != 1) {
                return PublicIntegrationCallbackSecurityVerifier.Decision.REPLAY;
            }
            int inboxInserted = jdbc.update("""
                    insert into %s.callback_inbox (
                      source,event_id,payload_digest,technical_result,applied_at)
                    values (?,?,?,?,?)
                    on conflict do nothing
                    """.formatted(schema), source, eventId, payloadDigest,
                    technicalResult, timestamp(now));
            if (inboxInserted == 1) {
                return PublicIntegrationCallbackSecurityVerifier.Decision.ACCEPT;
            }
            Map<String, Object> existing = jdbc.queryForMap("""
                    select payload_digest from %s.callback_inbox
                     where source=? and event_id=?
                     for update
                    """.formatted(schema), source, eventId);
            return MessageDigest.isEqual(
                    payloadDigest.getBytes(StandardCharsets.UTF_8),
                    text(existing, "payload_digest").getBytes(StandardCharsets.UTF_8))
                    ? PublicIntegrationCallbackSecurityVerifier.Decision.ALREADY_APPLIED
                    : PublicIntegrationCallbackSecurityVerifier.Decision.PAYLOAD_CONFLICT;
        });
    }

    byte[] callbackTechnicalResult(String source, String eventId) {
        requireText(source, "source");
        requireText(eventId, "eventId");
        return jdbc.queryForObject("""
                select technical_result from %s.callback_inbox
                 where source=? and event_id=?
                """.formatted(schema), byte[].class, source, eventId);
    }

    boolean admitStream(StreamAdmission admission, boolean failAfterWrites) {
        Objects.requireNonNull(admission, "admission");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            lockProviderLineage(admission.tokenizedWorkItemKey());
            lockLane(admission.key());
            rejectAdmissionAfterTerminal(
                    admission.key(), admission.tokenizedWorkItemKey());
            if (!applyRoute(admission)) {
                return false;
            }
            insertStreamQueue(admission);
            boolean activated = activateIfIdle(
                    admission.key(), admission.generationKey(),
                    admission.sourceAggregateVersion(), admission.acceptedAt());
            if (failAfterWrites) {
                throw new IllegalStateException("PIC_INJECTED_ADMISSION_ROLLBACK");
            }
            return activated;
        }));
    }

    boolean admitIntent(IntentAdmission admission, boolean failAfterWrites) {
        Objects.requireNonNull(admission, "admission");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            lockProviderLineage(admission.tokenizedWorkItemKey());
            lockLane(admission.key());
            rejectAdmissionAfterTerminal(
                    admission.key(), admission.tokenizedWorkItemKey());
            insertIntentQueue(admission);
            boolean activated = activateIfIdle(
                    admission.key(), admission.generationKey(),
                    admission.sourceAggregateVersion(), admission.acceptedAt());
            if (failAfterWrites) {
                throw new IllegalStateException("PIC_INJECTED_ADMISSION_ROLLBACK");
            }
            return activated;
        }));
    }

    Optional<Claim> claim(String workerId, Instant now, Duration lease) {
        return claimInternal(workerId, now, lease, null);
    }

    Optional<Claim> claim(
            String workerId,
            Instant now,
            Duration lease,
            DeliveryRecordKey requiredKey) {
        Objects.requireNonNull(requiredKey, "requiredKey");
        return claimInternal(workerId, now, lease, requiredKey);
    }

    private Optional<Claim> claimInternal(
            String workerId,
            Instant now,
            Duration lease,
            DeliveryRecordKey requiredKey) {
        requireText(workerId, "workerId");
        Objects.requireNonNull(now, "now");
        if (lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("PIC_LEASE_INVALID");
        }
        return transactions.execute(status -> {
            String laneFilter = requiredKey == null ? "" : """
                       and outbox.aggregate_type=? and outbox.aggregate_id=?
                       and outbox.channel_id=? and outbox.contract_version=?
                    """;
            List<Object> arguments = new ArrayList<>();
            arguments.add(timestamp(now));
            arguments.add(timestamp(now));
            if (requiredKey != null) {
                arguments.add(requiredKey.aggregateType());
                arguments.add(requiredKey.aggregateId());
                arguments.add(requiredKey.channelId());
                arguments.add(requiredKey.contractVersion());
            }
            var rows = jdbc.queryForList("""
                    select outbox.aggregate_type, outbox.aggregate_id,
                           outbox.channel_id, outbox.contract_version,
                           outbox.generation_key, outbox.fencing_token,
                           queued.tokenized_work_item_key,
                           queued.source_aggregate_version, queued.operation,
                           queued.event_payload_digest
                      from %s.outbox outbox
                      join %s.queued_delivery queued using (
                           aggregate_type,aggregate_id,channel_id,
                           contract_version,generation_key)
                     where outbox.delivered_at is null
                       and outbox.available_at <= ?
                       and (outbox.lease_until is null or outbox.lease_until < ?)
                    %s
                     order by outbox.available_at, outbox.generation_key
                     limit 1
                     for update of outbox skip locked
                    """.formatted(schema, schema, laneFilter), arguments.toArray());
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> row = rows.getFirst();
            var key = new DeliveryRecordKey(
                    text(row, "aggregate_type"),
                    text(row, "aggregate_id"),
                    text(row, "channel_id"),
                    text(row, "contract_version"));
            String generationKey = text(row, "generation_key");
            String tokenizedWorkItemKey = text(row, "tokenized_work_item_key");
            long sourceAggregateVersion = number(row, "source_aggregate_version");
            String operation = text(row, "operation");
            String eventPayloadDigest = nullableText(row, "event_payload_digest");
            lockProviderLineage(tokenizedWorkItemKey);
            lockLane(key);
            var claimable = jdbc.queryForList("""
                    select 1 from %s.outbox
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and generation_key=? and delivered_at is null
                       and available_at <= ?
                       and (lease_until is null or lease_until < ?)
                     for update
                    """.formatted(schema), key.aggregateType(), key.aggregateId(), key.channelId(),
                    key.contractVersion(), generationKey,
                    timestamp(now), timestamp(now));
            if (claimable.size() != 1) {
                return Optional.empty();
            }
            Claim unfenced = new Claim(
                    key, generationKey, 0, 0, workerId, now,
                    tokenizedWorkItemKey, sourceAggregateVersion, operation,
                    eventPayloadDigest);
            if (terminalFenceBlocks(unfenced)) {
                jdbc.update("""
                        update %s.outbox set delivered_at=?
                         where aggregate_type=? and aggregate_id=?
                           and channel_id=? and contract_version=?
                           and generation_key=? and delivered_at is null
                        """.formatted(schema), timestamp(now), key.aggregateType(),
                        key.aggregateId(), key.channelId(), key.contractVersion(),
                        generationKey);
                jdbc.update("""
                        update %s.queued_delivery set disposition='terminal-cancelled'
                         where aggregate_type=? and aggregate_id=?
                           and channel_id=? and contract_version=?
                           and generation_key=?
                        """.formatted(schema), key.aggregateType(), key.aggregateId(),
                        key.channelId(), key.contractVersion(), generationKey);
                return Optional.empty();
            }
            Long currentFence = jdbc.queryForObject("""
                    select fencing_token from %s.current_delivery
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and current_generation_key=?
                     for update
                    """.formatted(schema), Long.class, key.aggregateType(),
                    key.aggregateId(), key.channelId(), key.contractVersion(),
                    generationKey);
            if (currentFence == null || currentFence == Long.MAX_VALUE) {
                throw new IllegalStateException("PIC_FENCE_EXHAUSTED");
            }
            long fence = currentFence + 1;
            int changed = jdbc.update("""
                    update %s.current_delivery set fencing_token=?
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and current_generation_key=? and fencing_token=?
                    """.formatted(schema), fence, key.aggregateType(),
                    key.aggregateId(), key.channelId(), key.contractVersion(),
                    generationKey, currentFence);
            if (changed != 1) {
                return Optional.empty();
            }
            Instant leaseUntil = now.plus(lease);
            jdbc.update("""
                    update %s.outbox
                       set claimed_by=?, lease_until=?, fencing_token=?
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and generation_key=? and delivered_at is null
                    """.formatted(schema), workerId, timestamp(leaseUntil), fence,
                    key.aggregateType(), key.aggregateId(), key.channelId(),
                    key.contractVersion(), generationKey);
            Long attemptNo = jdbc.queryForObject("""
                    select coalesce(max(attempt_no),0)+1
                      from %s.delivery_attempt
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and generation_key=?
                    """.formatted(schema), Long.class, key.aggregateType(),
                    key.aggregateId(), key.channelId(), key.contractVersion(),
                    generationKey);
            jdbc.update("""
                    insert into %s.delivery_attempt (
                      aggregate_type,aggregate_id,channel_id,contract_version,
                      generation_key,attempt_no,worker_id,fencing_token,
                      claimed_at,lease_until)
                    values (?,?,?,?,?,?,?,?,?,?)
                    """.formatted(schema), key.aggregateType(), key.aggregateId(),
                    key.channelId(), key.contractVersion(), generationKey,
                    attemptNo, workerId, fence, timestamp(now), timestamp(leaseUntil));
            return Optional.of(new Claim(
                    key, generationKey, attemptNo, fence, workerId, leaseUntil,
                    tokenizedWorkItemKey, sourceAggregateVersion, operation,
                    eventPayloadDigest));
        });
    }

    boolean confirm(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            lockProviderLineage(claim.tokenizedWorkItemKey());
            lockLane(claim.key());
            if (terminalFenceBlocks(claim)) {
                return false;
            }
            int changed = jdbc.update("""
                    update %s.current_delivery
                       set status='confirmed'
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and current_generation_key=? and fencing_token=?
                       and status in ('pending','retrying')
                    """.formatted(schema), claim.key().aggregateType(),
                    claim.key().aggregateId(), claim.key().channelId(),
                    claim.key().contractVersion(), claim.generationKey(),
                    claim.fencingToken());
            return changed == 1;
        }));
    }

    boolean preSendAllowed(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            lockProviderLineage(claim.tokenizedWorkItemKey());
            lockLane(claim.key());
            if (terminalFenceBlocks(claim)) {
                return false;
            }
            Integer current = jdbc.queryForObject("""
                    select count(*) from %s.current_delivery
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and current_generation_key=? and fencing_token=?
                       and status in ('pending','retrying')
                    """.formatted(schema), Integer.class,
                    claim.key().aggregateType(), claim.key().aggregateId(),
                    claim.key().channelId(), claim.key().contractVersion(),
                    claim.generationKey(), claim.fencingToken());
            return current != null && current == 1;
        }));
    }

    boolean confirmAndPromote(
            Claim claim,
            String externalTaskRef,
            String providerReceiptDigest,
            Instant confirmedAt,
            boolean failBeforeCommit) {
        Objects.requireNonNull(claim, "claim");
        requireText(externalTaskRef, "externalTaskRef");
        requireDigest(providerReceiptDigest, "providerReceiptDigest");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            lockProviderLineage(claim.tokenizedWorkItemKey());
            lockLane(claim.key());
            if (terminalFenceBlocks(claim)) {
                return false;
            }
            var currentRows = jdbc.queryForList("""
                    select current_delivery_sequence, fencing_token,
                           current_generation_key, status
                      from %s.current_delivery
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                     for update
                    """.formatted(schema), claim.key().aggregateType(),
                    claim.key().aggregateId(), claim.key().channelId(),
                    claim.key().contractVersion());
            if (currentRows.isEmpty()) {
                return false;
            }
            Map<String, Object> current = currentRows.getFirst();
            long sequence = number(current, "current_delivery_sequence");
            long fence = number(current, "fencing_token");
            if (!claim.generationKey().equals(text(current, "current_generation_key"))
                    || fence != claim.fencingToken()
                    || !("pending".equals(text(current, "status"))
                    || "retrying".equals(text(current, "status")))) {
                return false;
            }
            var existingRefs = jdbc.queryForList("""
                    select external_task_ref from %s.generation_ledger
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and generation_key=?
                     for update
                    """.formatted(schema), claim.key().aggregateType(),
                    claim.key().aggregateId(), claim.key().channelId(),
                    claim.key().contractVersion(), claim.generationKey());
            if (!existingRefs.isEmpty()) {
                Object existing = existingRefs.getFirst().get("external_task_ref");
                if (existing != null && !externalTaskRef.equals(existing.toString())) {
                    throw new IllegalStateException("PIC_EXTERNAL_ID_CONFLICT");
                }
            }
            jdbc.update("""
                    update %s.generation_ledger
                       set external_task_ref=?, provider_receipt_digest=?,
                           confirmed_at=?
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and generation_key=?
                    """.formatted(schema), externalTaskRef,
                    providerReceiptDigest, timestamp(confirmedAt),
                    claim.key().aggregateType(), claim.key().aggregateId(),
                    claim.key().channelId(), claim.key().contractVersion(),
                    claim.generationKey());
            jdbc.update("""
                    update %s.current_delivery
                       set status='confirmed',
                           last_confirmed_source_aggregate_version=(
                             select source_aggregate_version
                               from %s.generation_ledger
                              where aggregate_type=? and aggregate_id=?
                                and channel_id=? and contract_version=?
                                and generation_key=?),
                           last_confirmed_delivery_sequence=?,
                           last_confirmed_generation_key=?,
                           current_external_task_ref=?,
                           current_provider_receipt_digest=?
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and current_generation_key=? and fencing_token=?
                    """.formatted(schema, schema),
                    claim.key().aggregateType(), claim.key().aggregateId(),
                    claim.key().channelId(), claim.key().contractVersion(),
                    claim.generationKey(), sequence, claim.generationKey(),
                    externalTaskRef, providerReceiptDigest,
                    claim.key().aggregateType(), claim.key().aggregateId(),
                    claim.key().channelId(), claim.key().contractVersion(),
                    claim.generationKey(), fence);
            jdbc.update("""
                    update %s.outbox set delivered_at=?
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and generation_key=? and fencing_token=?
                    """.formatted(schema), timestamp(confirmedAt),
                    claim.key().aggregateType(), claim.key().aggregateId(),
                    claim.key().channelId(), claim.key().contractVersion(),
                    claim.generationKey(), fence);
            jdbc.update("""
                    update %s.delivery_attempt set outcome='confirmed'
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and generation_key=? and attempt_no=? and fencing_token=?
                    """.formatted(schema), claim.key().aggregateType(),
                    claim.key().aggregateId(), claim.key().channelId(),
                    claim.key().contractVersion(), claim.generationKey(),
                    claim.attemptNo(), fence);
            boolean promoted = promoteNext(claim.key(), sequence, fence, confirmedAt);
            if (failBeforeCommit) {
                throw new IllegalStateException("PIC_INJECTED_CONFIRM_ROLLBACK");
            }
            return promoted;
        }));
    }

    boolean recoverPromoter(
            DeliveryRecordKey key,
            boolean authorizedAdvance,
            Instant promotedAt,
            boolean failBeforeCommit) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(promotedAt, "promotedAt");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            lockLane(key);
            var rows = jdbc.queryForList("""
                    select current_generation_key,current_delivery_sequence,
                           fencing_token,status,recoverable
                      from %s.current_delivery
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                     for update
                    """.formatted(schema), key.aggregateType(), key.aggregateId(),
                    key.channelId(), key.contractVersion());
            if (rows.isEmpty()) {
                return false;
            }
            Map<String, Object> current = rows.getFirst();
            String currentStatus = text(current, "status");
            boolean confirmed = "confirmed".equals(currentStatus);
            boolean advanceableFailure = "failed".equals(currentStatus)
                    && Boolean.TRUE.equals(current.get("recoverable"))
                    && authorizedAdvance;
            if (!confirmed && !advanceableFailure) {
                return false;
            }
            String generationKey = text(current, "current_generation_key");
            long sequence = number(current, "current_delivery_sequence");
            long fence = number(current, "fencing_token");
            jdbc.update("""
                    update %s.generation_ledger
                       set sealed=true,final_status=?
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and generation_key=?
                    """.formatted(schema), currentStatus,
                    key.aggregateType(), key.aggregateId(), key.channelId(),
                    key.contractVersion(), generationKey);
            jdbc.update("""
                    update %s.outbox set delivered_at=?
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and generation_key=? and delivered_at is null
                    """.formatted(schema), timestamp(promotedAt),
                    key.aggregateType(), key.aggregateId(), key.channelId(),
                    key.contractVersion(), generationKey);
            boolean promoted = promoteNext(key, sequence, fence, promotedAt);
            if (failBeforeCommit) {
                throw new IllegalStateException("PIC_INJECTED_PROMOTER_ROLLBACK");
            }
            return promoted;
        }));
    }

    void fail(Claim claim, boolean recoverable) {
        Objects.requireNonNull(claim, "claim");
        transactions.executeWithoutResult(status -> {
            lockLane(claim.key());
            int changed = jdbc.update("""
                    update %s.current_delivery
                       set status='failed', recoverable=?
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and current_generation_key=? and fencing_token=?
                       and status in ('pending','retrying')
                    """.formatted(schema), recoverable,
                    claim.key().aggregateType(), claim.key().aggregateId(),
                    claim.key().channelId(), claim.key().contractVersion(),
                    claim.generationKey(), claim.fencingToken());
            if (changed != 1) {
                throw new IllegalStateException("PIC_STALE_FENCE");
            }
            jdbc.update("""
                    update %s.delivery_attempt set outcome='failed'
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and generation_key=? and attempt_no=? and fencing_token=?
                    """.formatted(schema), claim.key().aggregateType(),
                    claim.key().aggregateId(), claim.key().channelId(),
                    claim.key().contractVersion(), claim.generationKey(),
                    claim.attemptNo(), claim.fencingToken());
        });
    }

    void retryCurrent(
            DeliveryRecordKey key,
            String generationKey,
            long expectedFence,
            Instant retryAt,
            boolean authorized,
            boolean failBeforeCommit) {
        Objects.requireNonNull(key, "key");
        requireText(generationKey, "generationKey");
        Objects.requireNonNull(retryAt, "retryAt");
        if (!authorized) {
            throw new IllegalStateException("PIC_REMEDIATION_AUTHORIZATION_REQUIRED");
        }
        transactions.executeWithoutResult(status -> {
            String tokenizedWorkItemKey = workItemToken(key, generationKey);
            lockProviderLineage(tokenizedWorkItemKey);
            lockLane(key);
            rejectAdmissionAfterTerminal(key, tokenizedWorkItemKey);
            int changed = jdbc.update("""
                    update %s.current_delivery
                       set status='retrying', fencing_token=fencing_token+1
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and current_generation_key=? and fencing_token=?
                       and status='failed' and recoverable
                    """.formatted(schema), key.aggregateType(), key.aggregateId(),
                    key.channelId(), key.contractVersion(), generationKey,
                    expectedFence);
            if (changed != 1) {
                throw new IllegalStateException("PIC_RETRY_NOT_ALLOWED");
            }
            int outboxChanged = jdbc.update("""
                    update %s.outbox
                       set available_at=?, claimed_by=null,
                           lease_until=null, fencing_token=?
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and generation_key=? and delivered_at is null
                    """.formatted(schema), timestamp(retryAt), expectedFence + 1,
                    key.aggregateType(), key.aggregateId(), key.channelId(),
                    key.contractVersion(), generationKey);
            if (outboxChanged != 1) {
                throw new IllegalStateException("PIC_RETRY_OUTBOX_NOT_FOUND");
            }
            appendTransition(key, generationKey, "failed", "retrying",
                    expectedFence + 1, "authorized-retry", retryAt);
            if (failBeforeCommit) {
                throw new IllegalStateException("PIC_INJECTED_RETRY_ROLLBACK");
            }
        });
    }

    private String workItemToken(DeliveryRecordKey key, String generationKey) {
        var rows = jdbc.queryForList("""
                select tokenized_work_item_key from %s.queued_delivery
                 where aggregate_type=? and aggregate_id=?
                   and channel_id=? and contract_version=?
                   and generation_key=?
                """.formatted(schema), key.aggregateType(), key.aggregateId(),
                key.channelId(), key.contractVersion(), generationKey);
        if (rows.size() != 1) {
            throw new IllegalStateException("PIC_GENERATION_NOT_FOUND");
        }
        return text(rows.getFirst(), "tokenized_work_item_key");
    }

    byte[] reserveCommand(
            String scopeToken,
            String idempotencyKey,
            String requestDigest,
            byte[] receipt,
            Instant now,
            boolean failBeforeCommit) {
        requireText(scopeToken, "scopeToken");
        requireText(idempotencyKey, "idempotencyKey");
        requireDigest(requestDigest, "requestDigest");
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(now, "now");
        return transactions.execute(status -> {
            jdbc.queryForList(
                    "select pg_advisory_xact_lock(hashtextextended(?,0))",
                    scopeToken + "|" + idempotencyKey);
            var existing = jdbc.queryForList("""
                    select request_digest, receipt_bytes
                      from %s.idempotency_result
                     where scope_token=? and idempotency_key=?
                    """.formatted(schema), scopeToken, idempotencyKey);
            if (!existing.isEmpty()) {
                Map<String, Object> row = existing.getFirst();
                if (!requestDigest.equals(text(row, "request_digest"))) {
                    throw new IllegalStateException("PIC_IDEMPOTENCY_MISMATCH");
                }
                return (byte[]) row.get("receipt_bytes");
            }
            jdbc.update("""
                    insert into %s.idempotency_result (
                      scope_token,idempotency_key,request_digest,receipt_bytes,
                      created_at,retain_until)
                    values (?,?,?,?,?,?)
                    """.formatted(schema), scopeToken, idempotencyKey,
                    requestDigest, receipt.clone(), timestamp(now),
                    timestamp(now.plus(90, ChronoUnit.DAYS)));
            if (failBeforeCommit) {
                throw new IllegalStateException("PIC_INJECTED_IDEMPOTENCY_ROLLBACK");
            }
            return receipt.clone();
        });
    }

    void sealTerminal(String generationKey, Instant terminalAt) {
        requireText(generationKey, "generationKey");
        Objects.requireNonNull(terminalAt, "terminalAt");
        int changed = jdbc.update("""
                update %s.generation_ledger
                   set sealed=true, final_status='terminal',
                       retention_until=?,
                       tokenized_external_ref_digest=case
                         when external_task_ref is null then null
                         else ? end,
                       terminal_tombstone=true
                 where generation_key=?
                """.formatted(schema),
                timestamp(terminalAt.plus(90, ChronoUnit.DAYS)),
                tokenizedDigest(externalTaskRef(generationKey)), generationKey);
        if (changed != 1) {
            throw new IllegalStateException("PIC_GENERATION_NOT_FOUND");
        }
    }

    void bindProviderLineage(
            DeliveryRecordKey key,
            String generationKey,
            String tokenizedWorkItemKey,
            String externalTaskRef,
            Instant boundAt) {
        Objects.requireNonNull(key, "key");
        requireText(generationKey, "generationKey");
        requireText(tokenizedWorkItemKey, "tokenizedWorkItemKey");
        requireText(externalTaskRef, "externalTaskRef");
        Objects.requireNonNull(boundAt, "boundAt");
        transactions.executeWithoutResult(status -> {
            lockProviderLineage(tokenizedWorkItemKey);
            lockLane(key);
            var generationRows = jdbc.queryForList("""
                    select tokenized_work_item_key
                      from %s.queued_delivery
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and generation_key=?
                     for update
                    """.formatted(schema), key.aggregateType(), key.aggregateId(),
                    key.channelId(), key.contractVersion(), generationKey);
            if (generationRows.size() != 1 || !tokenizedWorkItemKey.equals(
                    text(generationRows.getFirst(), "tokenized_work_item_key"))) {
                throw new IllegalStateException("PIC_PROVIDER_LINEAGE_GENERATION_MISMATCH");
            }
            var existing = jdbc.queryForList("""
                    select external_task_ref, terminal_tombstone
                      from %s.provider_lineage_map
                     where tenant_token='synthetic-tenant'
                       and capability_family='public-task'
                       and tokenized_work_item_key=?
                     for update
                    """.formatted(schema), tokenizedWorkItemKey);
            if (!existing.isEmpty()) {
                Map<String, Object> row = existing.getFirst();
                if (Boolean.TRUE.equals(row.get("terminal_tombstone"))) {
                    throw new IllegalStateException("PIC_PROVIDER_LINEAGE_TOMBSTONED");
                }
                Object current = row.get("external_task_ref");
                if (current != null && !externalTaskRef.equals(current.toString())) {
                    throw new IllegalStateException("PIC_EXTERNAL_ID_CONFLICT");
                }
            } else {
                String providerLineageKey = providerLineageKey(tokenizedWorkItemKey);
                jdbc.update("""
                        insert into %s.provider_lineage_map (
                          tenant_token,capability_family,tokenized_work_item_key,
                          provider_lineage_key,external_task_ref)
                        values ('synthetic-tenant','public-task',?,?,?)
                        """.formatted(schema), tokenizedWorkItemKey,
                        providerLineageKey, externalTaskRef);
            }
            int ledgerChanged = jdbc.update("""
                    update %s.generation_ledger
                       set external_task_ref=?
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and generation_key=?
                       and (external_task_ref is null or external_task_ref=?)
                    """.formatted(schema), externalTaskRef,
                    key.aggregateType(), key.aggregateId(), key.channelId(),
                    key.contractVersion(), generationKey, externalTaskRef);
            if (ledgerChanged != 1) {
                throw new IllegalStateException("PIC_GENERATION_NOT_FOUND_OR_CONFLICT");
            }
            jdbc.update("""
                    update %s.current_delivery
                       set current_external_task_ref=?
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and current_generation_key=?
                    """.formatted(schema), externalTaskRef,
                    key.aggregateType(), key.aggregateId(), key.channelId(),
                    key.contractVersion(), generationKey);
        });
    }

    void sealLineageTerminal(
            String tokenizedWorkItemKey,
            String generationKey,
            Instant terminalAt,
            Instant holdUntil) {
        requireText(tokenizedWorkItemKey, "tokenizedWorkItemKey");
        requireText(generationKey, "generationKey");
        Objects.requireNonNull(terminalAt, "terminalAt");
        Objects.requireNonNull(holdUntil, "holdUntil");
        Instant retentionUntil = terminalAt.plus(90, ChronoUnit.DAYS);
        if (holdUntil.isAfter(retentionUntil)) {
            retentionUntil = holdUntil;
        }
        Instant finalRetentionUntil = retentionUntil;
        transactions.executeWithoutResult(status -> {
            lockProviderLineage(tokenizedWorkItemKey);
            String externalRef = externalTaskRef(generationKey);
            int ledgerChanged = jdbc.update("""
                    update %s.generation_ledger
                       set sealed=true, final_status='terminal',
                           retention_until=?,
                           tokenized_external_ref_digest=?,
                           terminal_tombstone=true
                     where generation_key=?
                    """.formatted(schema), timestamp(finalRetentionUntil),
                    tokenizedDigest(externalRef), generationKey);
            if (ledgerChanged != 1) {
                throw new IllegalStateException("PIC_GENERATION_NOT_FOUND");
            }
            int lineageChanged = jdbc.update("""
                    update %s.provider_lineage_map
                       set final_status='terminal', retention_until=?,
                           tokenized_external_ref_digest=?,
                           terminal_tombstone=true
                     where tenant_token='synthetic-tenant'
                       and capability_family='public-task'
                       and tokenized_work_item_key=?
                    """.formatted(schema), timestamp(finalRetentionUntil),
                    tokenizedDigest(externalRef), tokenizedWorkItemKey);
            if (lineageChanged != 1) {
                throw new IllegalStateException("PIC_PROVIDER_LINEAGE_NOT_FOUND");
            }
        });
    }

    int purgeExpiredMappings(Instant now) {
        Objects.requireNonNull(now, "now");
        return transactions.execute(status -> {
            int generationCount = jdbc.update("""
                    update %s.generation_ledger
                       set external_task_ref=null,
                           external_notification_id=null,
                           provider_receipt_digest=null,
                           terminal_tombstone=true
                     where retention_until < ?
                       and (external_task_ref is not null
                         or external_notification_id is not null
                         or provider_receipt_digest is not null)
                    """.formatted(schema), timestamp(now));
            jdbc.update("""
                    update %s.provider_lineage_map
                       set external_task_ref=null,
                           terminal_tombstone=true
                     where retention_until < ?
                       and external_task_ref is not null
                    """.formatted(schema), timestamp(now));
            return generationCount;
        });
    }

    boolean rebuildCurrentCache(DeliveryRecordKey key) {
        Objects.requireNonNull(key, "key");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            lockLane(key);
            int changed = jdbc.update("""
                    update %s.current_delivery current
                       set current_external_task_ref=ledger.external_task_ref,
                           current_provider_receipt_digest=ledger.provider_receipt_digest
                      from %s.generation_ledger ledger
                     where current.aggregate_type=? and current.aggregate_id=?
                       and current.channel_id=? and current.contract_version=?
                       and ledger.aggregate_type=current.aggregate_type
                       and ledger.aggregate_id=current.aggregate_id
                       and ledger.channel_id=current.channel_id
                       and ledger.contract_version=current.contract_version
                       and ledger.generation_key=current.current_generation_key
                    """.formatted(schema, schema), key.aggregateType(),
                    key.aggregateId(), key.channelId(), key.contractVersion());
            return changed == 1;
        }));
    }

    boolean preemptWithTerminal(
            StreamAdmission terminal,
            String tokenizedWorkItemKey,
            boolean failBeforeCommit) {
        Objects.requireNonNull(terminal, "terminal");
        requireText(tokenizedWorkItemKey, "tokenizedWorkItemKey");
        if (!("close".equals(terminal.operation())
                || "revoke".equals(terminal.operation()))) {
            throw new IllegalArgumentException("PIC_TERMINAL_OPERATION_REQUIRED");
        }
        if (!tokenizedWorkItemKey.equals(terminal.tokenizedWorkItemKey())) {
            throw new IllegalArgumentException("PIC_TERMINAL_WORK_ITEM_KEY_MISMATCH");
        }
        return Boolean.TRUE.equals(transactions.execute(status -> {
            lockProviderLineage(tokenizedWorkItemKey);
            lockLane(terminal.key());
            var priorFences = jdbc.queryForList("""
                    select applied_terminal_version,event_digest,fence_epoch
                      from %s.source_terminal_fence
                     where tenant_token='synthetic-tenant'
                       and aggregate_type=? and tokenized_aggregate_id=?
                       and tokenized_work_item_key=?
                     for update
                    """.formatted(schema), terminal.key().aggregateType(),
                    tokenizedDigest(terminal.key().aggregateId()),
                    tokenizedWorkItemKey);
            if (!priorFences.isEmpty()) {
                Map<String, Object> prior = priorFences.getFirst();
                long appliedVersion = number(prior, "applied_terminal_version");
                if (terminal.sourceAggregateVersion() < appliedVersion) {
                    return false;
                }
                if (terminal.sourceAggregateVersion() == appliedVersion) {
                    if (terminal.eventPayloadDigest().equals(
                            text(prior, "event_digest"))) {
                        return false;
                    }
                    throw new IllegalStateException("PIC_TERMINAL_REPLAY_CONFLICT");
                }
            }
            if (!applyRoute(terminal)) {
                return false;
            }
            var activeRows = jdbc.queryForList("""
                    select current.current_generation_key,
                           current.current_delivery_sequence,
                           current.fencing_token,current.status,
                           current.channel_id,current.contract_version
                      from %s.current_delivery current
                      join %s.queued_delivery queued
                        on queued.aggregate_type=current.aggregate_type
                       and queued.aggregate_id=current.aggregate_id
                       and queued.channel_id=current.channel_id
                       and queued.contract_version=current.contract_version
                       and queued.generation_key=current.current_generation_key
                     where current.aggregate_type=? and current.aggregate_id=?
                       and queued.tokenized_work_item_key=?
                     for update of current
                    """.formatted(schema, schema), terminal.key().aggregateType(),
                    terminal.key().aggregateId(), tokenizedWorkItemKey);
            Map<String, Object> current = activeRows.stream()
                    .filter(row -> terminal.key().channelId().equals(
                                    text(row, "channel_id"))
                            && terminal.key().contractVersion().equals(
                                    text(row, "contract_version")))
                    .findFirst()
                    .orElse(null);
            if (current == null) {
                throw new IllegalStateException("PIC_TERMINAL_LANE_NOT_FOUND");
            }
            long currentSequence = number(current, "current_delivery_sequence");
            long currentFence = number(current, "fencing_token");
            if (currentSequence == MAX_SAFE_INTEGER || activeRows.stream().anyMatch(
                    row -> number(row, "fencing_token") == Long.MAX_VALUE)) {
                throw new IllegalStateException("PIC_SEQUENCE_OR_FENCE_EXHAUSTED");
            }
            jdbc.update("""
                    update %s.queued_delivery
                       set disposition='terminal-cancelled'
                     where aggregate_type=? and aggregate_id=?
                       and tokenized_work_item_key=?
                       and disposition in ('waiting','activated')
                    """.formatted(schema), terminal.key().aggregateType(),
                    terminal.key().aggregateId(), tokenizedWorkItemKey);
            jdbc.update("""
                    update %s.generation_ledger ledger
                       set sealed=true, final_status='terminal-preempted'
                      from %s.current_delivery current
                      join %s.queued_delivery queued
                        on queued.aggregate_type=current.aggregate_type
                       and queued.aggregate_id=current.aggregate_id
                       and queued.channel_id=current.channel_id
                       and queued.contract_version=current.contract_version
                       and queued.generation_key=current.current_generation_key
                     where ledger.aggregate_type=current.aggregate_type
                       and ledger.aggregate_id=current.aggregate_id
                       and ledger.channel_id=current.channel_id
                       and ledger.contract_version=current.contract_version
                       and ledger.generation_key=current.current_generation_key
                       and current.aggregate_type=? and current.aggregate_id=?
                       and queued.tokenized_work_item_key=?
                    """.formatted(schema, schema, schema),
                    terminal.key().aggregateType(), terminal.key().aggregateId(),
                    tokenizedWorkItemKey);
            jdbc.update("""
                    update %s.outbox outbox set delivered_at=?
                      from %s.current_delivery current
                      join %s.queued_delivery queued
                        on queued.aggregate_type=current.aggregate_type
                       and queued.aggregate_id=current.aggregate_id
                       and queued.channel_id=current.channel_id
                       and queued.contract_version=current.contract_version
                       and queued.generation_key=current.current_generation_key
                     where outbox.aggregate_type=current.aggregate_type
                       and outbox.aggregate_id=current.aggregate_id
                       and outbox.channel_id=current.channel_id
                       and outbox.contract_version=current.contract_version
                       and outbox.generation_key=current.current_generation_key
                       and outbox.delivered_at is null
                       and current.aggregate_type=? and current.aggregate_id=?
                       and queued.tokenized_work_item_key=?
                    """.formatted(schema, schema, schema),
                    timestamp(terminal.acceptedAt()), terminal.key().aggregateType(),
                    terminal.key().aggregateId(), tokenizedWorkItemKey);
            jdbc.update("""
                    update %s.current_delivery current
                       set fencing_token=current.fencing_token+1,
                           recoverable=false
                      from %s.queued_delivery queued
                     where queued.aggregate_type=current.aggregate_type
                       and queued.aggregate_id=current.aggregate_id
                       and queued.channel_id=current.channel_id
                       and queued.contract_version=current.contract_version
                       and queued.generation_key=current.current_generation_key
                       and current.aggregate_type=? and current.aggregate_id=?
                       and queued.tokenized_work_item_key=?
                    """.formatted(schema, schema), terminal.key().aggregateType(),
                    terminal.key().aggregateId(), tokenizedWorkItemKey);
            for (Map<String, Object> active : activeRows) {
                var activeKey = new DeliveryRecordKey(
                        terminal.key().aggregateType(), terminal.key().aggregateId(),
                        text(active, "channel_id"), text(active, "contract_version"));
                appendTransition(activeKey, text(active, "current_generation_key"),
                        text(active, "status"), "terminal-cancelled",
                        number(active, "fencing_token") + 1,
                        "cross-channel-terminal-preempt", terminal.acceptedAt());
            }
            insertStreamQueue(terminal);
            jdbc.update("""
                    update %s.queued_delivery set disposition='activated'
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                       and generation_key=?
                    """.formatted(schema), terminal.key().aggregateType(),
                    terminal.key().aggregateId(), terminal.key().channelId(),
                    terminal.key().contractVersion(), terminal.generationKey());
            long nextSequence = currentSequence + 1;
            long nextFence = currentFence + 1;
            jdbc.update("""
                    insert into %s.generation_ledger (
                      aggregate_type,aggregate_id,channel_id,contract_version,
                      generation_key,delivery_sequence,source_aggregate_version)
                    values (?,?,?,?,?,?,?)
                    """.formatted(schema), terminal.key().aggregateType(),
                    terminal.key().aggregateId(), terminal.key().channelId(),
                    terminal.key().contractVersion(), terminal.generationKey(),
                    nextSequence, terminal.sourceAggregateVersion());
            jdbc.update("""
                    update %s.current_delivery
                       set current_generation_key=?,current_delivery_sequence=?,
                           status='pending',fencing_token=?,recoverable=false,
                           current_external_task_ref=null,
                           current_provider_receipt_digest=null
                     where aggregate_type=? and aggregate_id=?
                       and channel_id=? and contract_version=?
                    """.formatted(schema), terminal.generationKey(), nextSequence,
                    nextFence, terminal.key().aggregateType(),
                    terminal.key().aggregateId(), terminal.key().channelId(),
                    terminal.key().contractVersion());
            jdbc.update("""
                    insert into %s.outbox (
                      aggregate_type,aggregate_id,channel_id,contract_version,
                      generation_key,available_at,fencing_token)
                    values (?,?,?,?,?,?,?)
                    """.formatted(schema), terminal.key().aggregateType(),
                    terminal.key().aggregateId(), terminal.key().channelId(),
                    terminal.key().contractVersion(), terminal.generationKey(),
                    timestamp(terminal.acceptedAt()), nextFence);
            jdbc.update("""
                    insert into %s.source_terminal_fence (
                      tenant_token,aggregate_type,tokenized_aggregate_id,
                      tokenized_work_item_key,applied_terminal_version,
                      event_digest,fence_epoch)
                    values ('synthetic-tenant',?,?,?,?,?,1)
                    on conflict (tenant_token,aggregate_type,tokenized_aggregate_id,
                                 tokenized_work_item_key)
                    do update set
                      applied_terminal_version=excluded.applied_terminal_version,
                      event_digest=excluded.event_digest,
                      fence_epoch=%s.source_terminal_fence.fence_epoch+1
                    where %s.source_terminal_fence.applied_terminal_version
                          < excluded.applied_terminal_version
                    """.formatted(schema, schema, schema), terminal.key().aggregateType(),
                    tokenizedDigest(terminal.key().aggregateId()),
                    tokenizedWorkItemKey, terminal.sourceAggregateVersion(),
                    terminal.eventPayloadDigest());
            appendTransition(terminal.key(), terminal.generationKey(),
                    text(current, "status"), "pending", nextFence,
                    "terminal-preempt", terminal.acceptedAt());
            if (failBeforeCommit) {
                throw new IllegalStateException("PIC_INJECTED_TERMINAL_ROLLBACK");
            }
            return true;
        }));
    }

    String externalTaskRef(String generationKey) {
        var values = jdbc.queryForList("""
                select external_task_ref from %s.generation_ledger
                 where generation_key=?
                """.formatted(schema), generationKey);
        if (values.isEmpty()) {
            return null;
        }
        Object value = values.getFirst().get("external_task_ref");
        return value == null ? null : value.toString();
    }

    Instant confirmedAt(DeliveryRecordKey key, String generationKey) {
        Objects.requireNonNull(key, "key");
        requireText(generationKey, "generationKey");
        Timestamp value = jdbc.queryForObject("""
                select confirmed_at from %s.generation_ledger
                 where aggregate_type=? and aggregate_id=?
                   and channel_id=? and contract_version=?
                   and generation_key=?
                """.formatted(schema), Timestamp.class,
                key.aggregateType(), key.aggregateId(), key.channelId(),
                key.contractVersion(), generationKey);
        return value == null ? null : value.toInstant();
    }

    boolean hasTerminalTombstone(String generationKey) {
        Boolean value = jdbc.queryForObject("""
                select terminal_tombstone from %s.generation_ledger
                 where generation_key=?
                """.formatted(schema), Boolean.class, generationKey);
        return Boolean.TRUE.equals(value);
    }

    boolean hasProviderLineageTombstone(String tokenizedWorkItemKey) {
        Boolean value = jdbc.queryForObject("""
                select terminal_tombstone from %s.provider_lineage_map
                 where tenant_token='synthetic-tenant'
                   and capability_family='public-task'
                   and tokenized_work_item_key=?
                """.formatted(schema), Boolean.class, tokenizedWorkItemKey);
        return Boolean.TRUE.equals(value);
    }

    String currentExternalTaskRef() {
        return jdbc.queryForObject(
                "select current_external_task_ref from " + schema + ".current_delivery",
                String.class);
    }

    long countTerminalCancelledQueue() {
        Long value = jdbc.queryForObject("""
                select count(*) from %s.queued_delivery
                 where disposition='terminal-cancelled'
                """.formatted(schema), Long.class);
        return value == null ? 0 : value;
    }

    long appliedTerminalVersion(String tokenizedWorkItemKey) {
        Long value = jdbc.queryForObject("""
                select applied_terminal_version
                  from %s.source_terminal_fence
                 where tenant_token='synthetic-tenant'
                   and tokenized_work_item_key=?
                """.formatted(schema), Long.class, tokenizedWorkItemKey);
        return value == null ? 0 : value;
    }

    long terminalFenceEpoch(String tokenizedWorkItemKey) {
        Long value = jdbc.queryForObject("""
                select fence_epoch from %s.source_terminal_fence
                 where tenant_token='synthetic-tenant'
                   and tokenized_work_item_key=?
                """.formatted(schema), Long.class, tokenizedWorkItemKey);
        return value == null ? 0 : value;
    }

    long count(String table) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("PIC_TEST_TABLE_INVALID");
        }
        Long value = jdbc.queryForObject(
                "select count(*) from " + schema + "." + table,
                Long.class);
        return value == null ? 0 : value;
    }

    long routeWatermark() {
        Long value = jdbc.queryForObject(
                "select max(current_route_sequence) from " + schema + ".route_watermark",
                Long.class);
        return value == null ? 0 : value;
    }

    String currentGenerationKey() {
        return jdbc.queryForObject(
                "select current_generation_key from " + schema + ".current_delivery",
                String.class);
    }

    String currentStatus() {
        return jdbc.queryForObject(
                "select status from " + schema + ".current_delivery",
                String.class);
    }

    long currentDeliverySequence() {
        Long value = jdbc.queryForObject(
                "select current_delivery_sequence from " + schema + ".current_delivery",
                Long.class);
        return value == null ? 0 : value;
    }

    long currentFence() {
        Long value = jdbc.queryForObject(
                "select fencing_token from " + schema + ".current_delivery",
                Long.class);
        return value == null ? 0 : value;
    }

    private boolean applyRoute(StreamAdmission admission) {
        var rows = jdbc.queryForList("""
                select current_route_sequence, last_source_aggregate_version,
                       last_event_id, last_event_payload_digest
                  from %s.route_watermark
                 where tenant_token='synthetic-tenant'
                   and source='urn:scholarsense:synthetic'
                   and aggregate_type=? and aggregate_id=?
                   and channel_id=? and contract_major=1
                 for update
                """.formatted(schema), admission.key().aggregateType(),
                admission.key().aggregateId(), admission.key().channelId());
        if (rows.isEmpty()) {
            if (admission.routeSequence() != 1) {
                throw new IllegalStateException("PIC_ROUTE_BOOTSTRAP_REQUIRED");
            }
            jdbc.update("""
                    insert into %s.route_watermark (
                      tenant_token,source,aggregate_type,aggregate_id,
                      channel_id,contract_major,current_route_sequence,
                      last_source_aggregate_version,last_event_id,
                      last_event_payload_digest)
                    values ('synthetic-tenant','urn:scholarsense:synthetic',?,?,?,1,?,?,?,?)
                    """.formatted(schema), admission.key().aggregateType(),
                    admission.key().aggregateId(), admission.key().channelId(),
                    admission.routeSequence(), admission.sourceAggregateVersion(),
                    admission.sourceEventId(), admission.eventPayloadDigest());
            return true;
        }
        Map<String, Object> row = rows.getFirst();
        long currentSequence = number(row, "current_route_sequence");
        long currentVersion = number(row, "last_source_aggregate_version");
        if (admission.routeSequence() == currentSequence
                && admission.sourceEventId().equals(text(row, "last_event_id"))
                && admission.eventPayloadDigest().equals(
                        text(row, "last_event_payload_digest"))) {
            return false;
        }
        if (admission.routeSequence() != currentSequence + 1) {
            throw new IllegalStateException("PIC_ROUTE_GAP_OR_CONFLICT");
        }
        if (admission.sourceAggregateVersion() <= currentVersion) {
            throw new IllegalStateException("PIC_SOURCE_VERSION_CONFLICT");
        }
        jdbc.update("""
                update %s.route_watermark
                   set current_route_sequence=?,
                       last_source_aggregate_version=?,
                       last_event_id=?, last_event_payload_digest=?
                 where tenant_token='synthetic-tenant'
                   and source='urn:scholarsense:synthetic'
                   and aggregate_type=? and aggregate_id=?
                   and channel_id=? and contract_major=1
                """.formatted(schema), admission.routeSequence(),
                admission.sourceAggregateVersion(), admission.sourceEventId(),
                admission.eventPayloadDigest(), admission.key().aggregateType(),
                admission.key().aggregateId(), admission.key().channelId());
        return true;
    }

    private void insertStreamQueue(StreamAdmission admission) {
        jdbc.update("""
                insert into %s.queued_delivery (
                  aggregate_type,aggregate_id,channel_id,contract_version,
                  generation_key,tokenized_work_item_key,operation,accepted_at,
                  provenance_mode,
                  source_event_id,source_fact_id,source_fact_digest,
                  event_payload_digest,route_sequence,
                  source_aggregate_version,disposition)
                values (?,?,?,?,?,?,?,?,'aggregate-stream',?,?,?,?,?,?,'waiting')
                on conflict do nothing
                """.formatted(schema), admission.key().aggregateType(),
                admission.key().aggregateId(), admission.key().channelId(),
                admission.key().contractVersion(), admission.generationKey(),
                admission.tokenizedWorkItemKey(), admission.operation(),
                timestamp(admission.acceptedAt()),
                admission.sourceEventId(), admission.sourceFactId(),
                admission.sourceFactDigest(), admission.eventPayloadDigest(),
                admission.routeSequence(), admission.sourceAggregateVersion());
    }

    private void insertIntentQueue(IntentAdmission admission) {
        jdbc.update("""
                insert into %s.queued_delivery (
                  aggregate_type,aggregate_id,channel_id,contract_version,
                  generation_key,tokenized_work_item_key,operation,accepted_at,
                  provenance_mode,
                  delivery_intent_id,request_digest,source_aggregate_version,
                  disposition)
                values (?,?,?,?,?,?,?,?,'intent-command',?,?,?,'waiting')
                on conflict do nothing
                """.formatted(schema), admission.key().aggregateType(),
                admission.key().aggregateId(), admission.key().channelId(),
                admission.key().contractVersion(), admission.generationKey(),
                admission.tokenizedWorkItemKey(), admission.operation(),
                timestamp(admission.acceptedAt()),
                admission.deliveryIntentId(), admission.requestDigest(),
                admission.sourceAggregateVersion());
    }

    private boolean activateIfIdle(
            DeliveryRecordKey key,
            String generationKey,
            long sourceAggregateVersion,
            Instant acceptedAt) {
        Integer active = jdbc.queryForObject("""
                select count(*) from %s.current_delivery
                 where aggregate_type=? and aggregate_id=?
                   and channel_id=? and contract_version=?
                   and status in ('pending','retrying')
                """.formatted(schema), Integer.class, key.aggregateType(),
                key.aggregateId(), key.channelId(), key.contractVersion());
        Integer currentCount = jdbc.queryForObject("""
                select count(*) from %s.current_delivery
                 where aggregate_type=? and aggregate_id=?
                   and channel_id=? and contract_version=?
                """.formatted(schema), Integer.class, key.aggregateType(),
                key.aggregateId(), key.channelId(), key.contractVersion());
        if ((active != null && active > 0) || (currentCount != null && currentCount > 0)) {
            return false;
        }
        jdbc.update("""
                update %s.queued_delivery set disposition='activated'
                 where aggregate_type=? and aggregate_id=?
                   and channel_id=? and contract_version=?
                   and generation_key=? and disposition='waiting'
                """.formatted(schema), key.aggregateType(), key.aggregateId(),
                key.channelId(), key.contractVersion(), generationKey);
        jdbc.update("""
                insert into %s.generation_ledger (
                  aggregate_type,aggregate_id,channel_id,contract_version,
                  generation_key,delivery_sequence,source_aggregate_version)
                values (?,?,?,?,?,1,?)
                """.formatted(schema), key.aggregateType(), key.aggregateId(),
                key.channelId(), key.contractVersion(), generationKey,
                sourceAggregateVersion);
        jdbc.update("""
                insert into %s.current_delivery (
                  aggregate_type,aggregate_id,channel_id,contract_version,
                  current_generation_key,current_delivery_sequence,status,
                  fencing_token)
                values (?,?,?,?,?,1,'pending',1)
                """.formatted(schema), key.aggregateType(), key.aggregateId(),
                key.channelId(), key.contractVersion(), generationKey);
        jdbc.update("""
                insert into %s.outbox (
                  aggregate_type,aggregate_id,channel_id,contract_version,
                  generation_key,available_at,fencing_token)
                values (?,?,?,?,?,?,1)
                """.formatted(schema), key.aggregateType(), key.aggregateId(),
                key.channelId(), key.contractVersion(), generationKey,
                timestamp(acceptedAt));
        appendTransition(key, generationKey, null, "pending", 1, "activate", acceptedAt);
        return true;
    }

    private boolean promoteNext(
            DeliveryRecordKey key,
            long currentSequence,
            long currentFence,
            Instant promotedAt) {
        var queued = jdbc.queryForList("""
                select generation_key, source_aggregate_version, accepted_at
                  from %s.queued_delivery
                 where aggregate_type=? and aggregate_id=?
                   and channel_id=? and contract_version=?
                   and disposition='waiting'
                 order by accepted_at, generation_key
                 for update skip locked
                 limit 1
                """.formatted(schema), key.aggregateType(), key.aggregateId(),
                key.channelId(), key.contractVersion());
        if (queued.isEmpty()) {
            return false;
        }
        if (currentSequence == MAX_SAFE_INTEGER) {
            throw new IllegalStateException("PIC_DELIVERY_SEQUENCE_EXHAUSTED");
        }
        Map<String, Object> next = queued.getFirst();
        String generationKey = text(next, "generation_key");
        long sourceVersion = number(next, "source_aggregate_version");
        long sequence = currentSequence + 1;
        long fence = currentFence + 1;
        jdbc.update("""
                update %s.generation_ledger set sealed=true
                 where aggregate_type=? and aggregate_id=?
                   and channel_id=? and contract_version=?
                   and delivery_sequence=?
                """.formatted(schema), key.aggregateType(), key.aggregateId(),
                key.channelId(), key.contractVersion(), currentSequence);
        jdbc.update("""
                update %s.queued_delivery set disposition='activated'
                 where aggregate_type=? and aggregate_id=?
                   and channel_id=? and contract_version=?
                   and generation_key=? and disposition='waiting'
                """.formatted(schema), key.aggregateType(), key.aggregateId(),
                key.channelId(), key.contractVersion(), generationKey);
        jdbc.update("""
                insert into %s.generation_ledger (
                  aggregate_type,aggregate_id,channel_id,contract_version,
                  generation_key,delivery_sequence,source_aggregate_version)
                values (?,?,?,?,?,?,?)
                """.formatted(schema), key.aggregateType(), key.aggregateId(),
                key.channelId(), key.contractVersion(), generationKey,
                sequence, sourceVersion);
        jdbc.update("""
                update %s.current_delivery
                   set current_generation_key=?, current_delivery_sequence=?,
                       status='pending', fencing_token=?, recoverable=false,
                       current_external_task_ref=null,
                       current_provider_receipt_digest=null
                 where aggregate_type=? and aggregate_id=?
                   and channel_id=? and contract_version=?
                """.formatted(schema), generationKey, sequence, fence,
                key.aggregateType(), key.aggregateId(), key.channelId(),
                key.contractVersion());
        jdbc.update("""
                insert into %s.outbox (
                  aggregate_type,aggregate_id,channel_id,contract_version,
                  generation_key,available_at,fencing_token)
                values (?,?,?,?,?,?,?)
                """.formatted(schema), key.aggregateType(), key.aggregateId(),
                key.channelId(), key.contractVersion(), generationKey,
                timestamp(promotedAt), fence);
        appendTransition(key, generationKey, null, "pending", fence,
                "confirm-promote", promotedAt);
        return true;
    }

    private void appendTransition(
            DeliveryRecordKey key,
            String generationKey,
            String from,
            String to,
            long fence,
            String reason,
            Instant at) {
        jdbc.update("""
                insert into %s.transition_ledger (
                  aggregate_type,aggregate_id,channel_id,contract_version,
                  generation_key,from_status,to_status,fencing_token,
                  reason_code,occurred_at)
                values (?,?,?,?,?,?,?,?,?,?)
                """.formatted(schema), key.aggregateType(), key.aggregateId(),
                key.channelId(), key.contractVersion(), generationKey,
                from, to, fence, reason, timestamp(at));
    }

    private void rejectAdmissionAfterTerminal(
            DeliveryRecordKey key,
            String tokenizedWorkItemKey) {
        var rows = jdbc.queryForList("""
                select applied_terminal_version from %s.source_terminal_fence
                 where tenant_token='synthetic-tenant'
                   and aggregate_type=? and tokenized_aggregate_id=?
                   and tokenized_work_item_key=?
                 for update
                """.formatted(schema), key.aggregateType(),
                tokenizedDigest(key.aggregateId()), tokenizedWorkItemKey);
        if (!rows.isEmpty()) {
            throw new IllegalStateException("PIC_SOURCE_TERMINAL_FENCE_REJECTED");
        }
    }

    private boolean terminalFenceBlocks(Claim claim) {
        var rows = jdbc.queryForList("""
                select applied_terminal_version,event_digest
                  from %s.source_terminal_fence
                 where tenant_token='synthetic-tenant'
                   and aggregate_type=? and tokenized_aggregate_id=?
                   and tokenized_work_item_key=?
                 for update
                """.formatted(schema), claim.key().aggregateType(),
                tokenizedDigest(claim.key().aggregateId()),
                claim.tokenizedWorkItemKey());
        if (rows.isEmpty()) {
            return false;
        }
        Object rawVersion = rows.getFirst().get("applied_terminal_version");
        long terminalVersion = rawVersion instanceof Number number
                ? number.longValue() : 0;
        boolean isTerminal = "close".equals(claim.operation())
                || "revoke".equals(claim.operation());
        if (!isTerminal) {
            return true;
        }
        return terminalVersion != claim.sourceAggregateVersion()
                || !Objects.equals(
                nullableText(rows.getFirst(), "event_digest"),
                claim.eventPayloadDigest());
    }

    private void lockLane(DeliveryRecordKey key) {
        jdbc.queryForList(
                "select pg_advisory_xact_lock(hashtextextended(?,0))",
                key.aggregateType() + "|" + key.aggregateId() + "|"
                        + key.channelId() + "|" + key.contractVersion());
    }

    private void lockProviderLineage(String tokenizedWorkItemKey) {
        jdbc.queryForList(
                "select pg_advisory_xact_lock(hashtextextended(?,0))",
                "synthetic-tenant|public-task|" + tokenizedWorkItemKey);
    }

    private static String providerLineageKey(String tokenizedWorkItemKey) {
        return "pl1." + tokenizedDigest(tokenizedWorkItemKey).substring(4);
    }

    private static String tokenizedDigest(String value) {
        if (value == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "xr1." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            throw new IllegalStateException("PIC_DB_FIELD_MISSING: " + key);
        }
        return value.toString();
    }

    private static String nullableText(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("PIC_DB_NUMBER_MISSING: " + key);
        }
        return number.longValue();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireDigest(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be sha256");
        }
        return value;
    }

    private static String requireHexDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase sha256 hex");
        }
        return value;
    }

    record StreamAdmission(
            DeliveryRecordKey key,
            String generationKey,
            String operation,
            Instant acceptedAt,
            String sourceEventId,
            String sourceFactId,
            String sourceFactDigest,
            String eventPayloadDigest,
            long routeSequence,
            long sourceAggregateVersion,
            String tokenizedWorkItemKey) {

        StreamAdmission {
            Objects.requireNonNull(key, "key");
            requireText(generationKey, "generationKey");
            requireText(operation, "operation");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            requireText(sourceEventId, "sourceEventId");
            requireText(sourceFactId, "sourceFactId");
            requireDigest(sourceFactDigest, "sourceFactDigest");
            requireDigest(eventPayloadDigest, "eventPayloadDigest");
            requireText(tokenizedWorkItemKey, "tokenizedWorkItemKey");
            if (routeSequence < 1 || routeSequence > MAX_SAFE_INTEGER
                    || sourceAggregateVersion < 1
                    || sourceAggregateVersion > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("PIC_SEQUENCE_INVALID");
            }
        }
    }

    record IntentAdmission(
            DeliveryRecordKey key,
            String generationKey,
            String operation,
            Instant acceptedAt,
            String deliveryIntentId,
            String requestDigest,
            long sourceAggregateVersion,
            String tokenizedWorkItemKey) {

        IntentAdmission {
            Objects.requireNonNull(key, "key");
            requireText(generationKey, "generationKey");
            requireText(operation, "operation");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            requireText(deliveryIntentId, "deliveryIntentId");
            requireDigest(requestDigest, "requestDigest");
            requireText(tokenizedWorkItemKey, "tokenizedWorkItemKey");
            if (sourceAggregateVersion < 1
                    || sourceAggregateVersion > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("PIC_SOURCE_VERSION_INVALID");
            }
        }
    }

    record Claim(
            DeliveryRecordKey key,
            String generationKey,
            long attemptNo,
            long fencingToken,
            String workerId,
            Instant leaseUntil,
            String tokenizedWorkItemKey,
            long sourceAggregateVersion,
            String operation,
            String eventPayloadDigest) {
    }
}
