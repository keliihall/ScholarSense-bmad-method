package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientEvaluator;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientEvidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Applies one authenticated responsibility batch with independent ordering and fencing. */
public final class ResponsibilitySyncService {
    private static final Instant NO_V2_CUTOVER = Instant.MAX;
    private final ResponsibilitySyncRepository repository;
    private final ResponsibilityRecipientEvidencePort recipientEvidence;
    private final IdentityReplayPort replay;
    private final IdentitySyncTransactionPort transactions;
    private final IdentitySyncAuditPort audit;
    private final IdentitySyncObservabilityPort observability;
    private final TrustedTimeSource time;
    private final AccessInvalidationChangePublisherPort invalidations;
    private final Instant version2EffectiveAt;

    public ResponsibilitySyncService(
            ResponsibilitySyncRepository repository,
            IdentityReplayPort replay,
            IdentitySyncTransactionPort transactions,
            IdentitySyncAuditPort audit,
            IdentitySyncObservabilityPort observability,
            TrustedTimeSource time) {
        this(
                repository,
                (relations, serverNow) -> relations.stream()
                        .map(relation ->
                                new ResponsibilityRecipientEvidence(
                                        relation,
                                        null,
                                        null,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false))
                        .toList(),
                replay,
                transactions,
                audit,
                observability,
                time,
                AccessInvalidationChangePublisherPort.noOp(),
                NO_V2_CUTOVER);
    }

    public ResponsibilitySyncService(
            ResponsibilitySyncRepository repository,
            ResponsibilityRecipientEvidencePort recipientEvidence,
            IdentityReplayPort replay,
            IdentitySyncTransactionPort transactions,
            IdentitySyncAuditPort audit,
            IdentitySyncObservabilityPort observability,
            TrustedTimeSource time) {
        this(
                repository,
                recipientEvidence,
                replay,
                transactions,
                audit,
                observability,
                time,
                AccessInvalidationChangePublisherPort.noOp(),
                NO_V2_CUTOVER);
    }

    public ResponsibilitySyncService(
            ResponsibilitySyncRepository repository,
            ResponsibilityRecipientEvidencePort recipientEvidence,
            IdentityReplayPort replay,
            IdentitySyncTransactionPort transactions,
            IdentitySyncAuditPort audit,
            IdentitySyncObservabilityPort observability,
            TrustedTimeSource time,
            AccessInvalidationChangePublisherPort invalidations) {
        this(
                repository,
                recipientEvidence,
                replay,
                transactions,
                audit,
                observability,
                time,
                invalidations,
                NO_V2_CUTOVER);
    }

    public ResponsibilitySyncService(
            ResponsibilitySyncRepository repository,
            ResponsibilityRecipientEvidencePort recipientEvidence,
            IdentityReplayPort replay,
            IdentitySyncTransactionPort transactions,
            IdentitySyncAuditPort audit,
            IdentitySyncObservabilityPort observability,
            TrustedTimeSource time,
            AccessInvalidationChangePublisherPort invalidations,
            Instant version2EffectiveAt) {
        this.repository = repository;
        this.recipientEvidence = recipientEvidence;
        this.replay = replay;
        this.transactions = transactions;
        this.audit = audit;
        this.observability = observability;
        this.time = time;
        this.invalidations = invalidations;
        this.version2EffectiveAt = java.util.Objects.requireNonNull(
                version2EffectiveAt, "version2EffectiveAt");
    }

    public IdentitySyncResult process(
            NormalizedResponsibilityBatch batch, IdentityLease lease) {
        return process(batch, lease, ignored -> {});
    }

    public IdentitySyncResult process(
            NormalizedResponsibilityBatch batch,
            IdentityLease lease,
            Consumer<IdentitySyncResult> afterApply) {
        Instant now = time.now().instant();
        validate(batch, lease, now);
        boolean version2 = version2(batch);
        IdentityCheckpoint checkpoint = (version2
                        ? repository.v2ShadowCheckpoint(batch.key())
                        : repository.checkpoint(batch.key()))
                .orElseGet(() -> IdentityCheckpoint.initial(batch.key()));
        var existingEnvelope = version2
                ? repository.v2ShadowEnvelopeDigest(batch.batchId())
                : repository.envelopeDigest(batch.batchId());
        if (existingEnvelope.isPresent()) {
            if (!existingEnvelope.get().equals(batch.envelopeDigest())) {
                reject(
                        batch,
                        lease,
                        now,
                        "RESPONSIBILITY_IDEMPOTENCY_CONFLICT",
                        false,
                        () -> {});
                throw new IdentitySyncException(
                        "RESPONSIBILITY_IDEMPOTENCY_CONFLICT");
            }
            IdentitySyncResult replayed = new IdentitySyncResult(
                    IdentitySyncOutcome.REPLAYED,
                    "RESPONSIBILITY_SYNC_REPLAYED",
                    checkpoint.watermark(),
                    checkpoint.sourceVersion(),
                    checkpoint.aggregateVersion());
            transactions.execute(() -> {
                afterApply.accept(replayed);
                return null;
            });
            return replayed;
        }
        if (batch.noChange()) {
            IdentitySyncResult heartbeat = new IdentitySyncResult(
                    IdentitySyncOutcome.REPLAYED,
                    "RESPONSIBILITY_SYNC_HEARTBEAT",
                    checkpoint.watermark(),
                    checkpoint.sourceVersion(),
                    checkpoint.aggregateVersion());
            transactions.execute(() -> {
                if (version2) {
                    repository.recordV2ShadowHeartbeat(batch, lease, now);
                } else {
                    repository.recordHeartbeat(batch, lease, now);
                }
                audit.append(new IdentitySyncAuditEvent(
                        "responsibility.sync.heartbeat",
                        "accepted",
                        "RESPONSIBILITY_SYNC_HEARTBEAT",
                        lease.jobId(),
                        lease.attemptNo(),
                        lease.fencingToken(),
                        batch.sourceVersion(),
                        checkpoint.watermark(),
                        checkpoint.aggregateVersion(),
                        batch.traceId(),
                        now,
                        policyVersions(batch)));
                afterApply.accept(heartbeat);
                return null;
            });
            return heartbeat;
        }
        requireContinuous(batch, lease, checkpoint, now);
        long nextAggregateVersion = checkpoint.aggregateVersion() + 1;
        IdentitySyncResult result = new IdentitySyncResult(
                IdentitySyncOutcome.APPLIED,
                "RESPONSIBILITY_SYNC_APPLIED",
                batch.toWatermark(),
                batch.sourceVersion(),
                nextAggregateVersion);
        transactions.execute(() -> {
            List<ResponsibilityScopeProjectionUpdate> scopeUpdates =
                    scopeUpdates(batch, now);
            List<ResponsibilityExceptionAuditTransition> transitions;
            if (version2) {
                repository.applyV2Shadow(
                        batch, lease, now, scopeUpdates);
                transitions = List.of();
            } else {
                transitions = repository.applyWithAuditTransitions(
                        batch, lease, now, scopeUpdates);
            }
            audit.append(new IdentitySyncAuditEvent(
                    "responsibility.sync.applied",
                    "accepted",
                    "RESPONSIBILITY_SYNC_APPLIED",
                    lease.jobId(),
                    lease.attemptNo(),
                    lease.fencingToken(),
                    batch.sourceVersion(),
                    batch.toWatermark(),
                    nextAggregateVersion,
                    batch.traceId(),
                    now,
                    policyVersions(batch)));
            for (ResponsibilityExceptionAuditTransition transition :
                    transitions) {
                audit.append(new IdentitySyncAuditEvent(
                        transition.action(),
                        "accepted",
                        transition.reasonCode(),
                        lease.jobId(),
                        lease.attemptNo(),
                        lease.fencingToken(),
                        batch.sourceVersion(),
                        batch.toWatermark(),
                        transition.aggregateVersion(),
                        batch.traceId(),
                        now,
                        policyVersions(batch),
                        transition.exceptionId()));
            }
            // V1 remains the live fail-closed current projection while the
            // V2 feed replays from zero. It has no approved lineage metadata,
            // so publishing it would roll back the authority change and could
            // preserve stale access. Only the atomically activated V2 path may
            // emit the asynchronous invalidation contract.
            if (version2 && repository.v2ShadowActive(batch.key())) {
                invalidations.publish(
                        new CommittedResponsibilityChangeSet(
                                batch, result, scopeUpdates, now));
            }
            afterApply.accept(result);
            return null;
        });
        observability.record(new IdentitySyncObservation(
                "responsibility_sync_applied_total",
                1,
                Map.of(
                        "sourceId", batch.key().sourceId(),
                        "feedId", batch.key().feedId(),
                        "consumerProjection", "responsibility",
                        "outcome", "applied"),
                batch.traceId(),
                now));
        return result;
    }

    private List<ResponsibilityScopeProjectionUpdate> scopeUpdates(
            NormalizedResponsibilityBatch batch, Instant now) {
        Map<String, List<AuthoritativeResponsibilityRelation>> incoming =
                new LinkedHashMap<>();
        for (AuthoritativeResponsibilityRelation relation :
                batch.relations()) {
            incoming.computeIfAbsent(
                            relation.studentSourceReference()
                                    .equivalenceDomain(),
                            ignored -> new ArrayList<>())
                    .add(relation);
        }
        List<ResponsibilityScopeProjectionUpdate> updates =
                new ArrayList<>();
        try {
            for (var student : incoming.entrySet()) {
                Map<String, AuthoritativeResponsibilityRelation> resulting =
                        new LinkedHashMap<>();
                for (AuthoritativeResponsibilityRelation current :
                        currentByStudentDigest(
                                batch, student.getKey(), now)) {
                    resulting.put(
                            current.relationRefToken(), current);
                }
                for (AuthoritativeResponsibilityRelation changed :
                        student.getValue()) {
                    resulting.put(
                            changed.relationRefToken(), changed);
                }
                List<AuthoritativeResponsibilityRelation> relations =
                        List.copyOf(resulting.values());
                var evidence = recipientEvidence.resolve(relations, now);
                var decision = ResponsibilityRecipientEvaluator.evaluate(
                        evidence, now, true, true);
                updates.add(new ResponsibilityScopeProjectionUpdate(
                        student.getKey(), relations, decision, evidence));
            }
        } catch (IdentitySyncException failure) {
            throw failure;
        } catch (RuntimeException unavailable) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_IDENTITY_PROJECTION_UNAVAILABLE");
        }
        return List.copyOf(updates);
    }

    private void validate(
            NormalizedResponsibilityBatch batch,
            IdentityLease lease,
            Instant now) {
        if (!batch.key().equals(lease.key())
                || !"responsibility".equals(batch.key().consumerProjection())
                || !lease.expiresAt().isAfter(now)
                || !repository.leaseIsCurrent(lease)) {
            throw new IdentitySyncException("IDENTITY_SYNC_FENCING_STALE");
        }
        if (!batch.signatureVerified()) {
            reject(
                    batch,
                    lease,
                    now,
                    "RESPONSIBILITY_SOURCE_SIGNATURE_INVALID",
                    false,
                    () -> {});
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_SIGNATURE_INVALID");
        }
        if (!version2(batch)
                && repository.v2ShadowActive(batch.key())) {
            reject(
                    batch,
                    lease,
                    now,
                    "RESPONSIBILITY_V2_REPLAY_REQUIRED",
                    true,
                    () -> {});
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_REPLAY_REQUIRED");
        }
        for (var dependency : batch.supportingIdentityOrgWatermarks().entrySet()) {
            String[] key = dependency.getKey().split("\\|", -1);
            if (repository.identityOrgWatermark(key[0], key[1])
                    < dependency.getValue()) {
                reject(
                        batch,
                        lease,
                        now,
                        "RESPONSIBILITY_DEPENDENCY_WATERMARK_BEHIND",
                        true,
                        () -> {});
                throw new IdentitySyncException(
                        "RESPONSIBILITY_DEPENDENCY_WATERMARK_BEHIND");
            }
        }
        var relationKeys = new HashSet<String>();
        var eventIds = new HashSet<UUID>();
        var lineageIds = new HashSet<String>();
        for (var relation : batch.relations()) {
            if (!relationKeys.add(relation.relationRefToken())
                    || !eventIds.add(relation.relationId())
                    || version2(batch)
                            && !lineageIds.add(relation.lineageId().value())) {
                reject(
                        batch,
                        lease,
                        now,
                        "RESPONSIBILITY_DUPLICATE_RELATION",
                        false,
                        () -> {});
                throw new IdentitySyncException(
                        "RESPONSIBILITY_DUPLICATE_RELATION");
            }
            currentRecord(batch, relation.relationRefToken())
                    .ifPresent(current -> {
                        if (relation.recordVersion() == current.recordVersion()
                                && !relation.payloadDigest().equals(
                                        current.payloadDigest())) {
                            reject(
                                    batch,
                                    lease,
                                    now,
                                    "RESPONSIBILITY_IDEMPOTENCY_CONFLICT",
                                    false,
                                    () -> {});
                            throw new IdentitySyncException(
                                    "RESPONSIBILITY_IDEMPOTENCY_CONFLICT");
                        }
                        if (relation.recordVersion() <= current.recordVersion()) {
                            reject(
                                    batch,
                                    lease,
                                    now,
                                    "RESPONSIBILITY_SOURCE_VERSION_STALE",
                                    true,
                                    () -> {});
                            throw new IdentitySyncException(
                                    "RESPONSIBILITY_SOURCE_VERSION_STALE");
                        }
                    });
            if (version2(batch)) {
                repository.v2BoundLineage(
                                batch.key(), relation.relationRefToken())
                        .ifPresent(bound -> {
                            if (!bound.equals(relation.lineageId())) {
                                reject(
                                        batch,
                                        lease,
                                        now,
                                        "RESPONSIBILITY_IDEMPOTENCY_CONFLICT",
                                        false,
                                        () -> {});
                                throw new IdentitySyncException(
                                        "RESPONSIBILITY_IDEMPOTENCY_CONFLICT");
                            }
                        });
            }
        }
        validateNoOverlappingPrimary(batch, lease, now);
    }

    private void validateNoOverlappingPrimary(
            NormalizedResponsibilityBatch batch,
            IdentityLease lease,
            Instant now) {
        Map<String, List<AuthoritativeResponsibilityRelation>> incoming =
                new LinkedHashMap<>();
        for (AuthoritativeResponsibilityRelation relation :
                batch.relations()) {
            incoming.computeIfAbsent(
                            relation.studentSourceReference()
                                    .equivalenceDomain(),
                            ignored -> new ArrayList<>())
                    .add(relation);
        }
        for (var student : incoming.entrySet()) {
            Map<String, AuthoritativeResponsibilityRelation> resulting =
                    new LinkedHashMap<>();
            for (AuthoritativeResponsibilityRelation current :
                    currentByStudentDigest(
                            batch, student.getKey(), now)) {
                resulting.put(current.relationRefToken(), current);
            }
            for (AuthoritativeResponsibilityRelation changed :
                    student.getValue()) {
                resulting.put(changed.relationRefToken(), changed);
            }
            List<AuthoritativeResponsibilityRelation> primary =
                    resulting.values().stream()
                            .filter(relation ->
                                    relation.status()
                                            == cn.edu.suda.scholarsense
                                                    .identityaccess.domain
                                                    .ResponsibilityStatus.ACTIVE)
                            .filter(relation ->
                                    relation.responsibilityType()
                                            == cn.edu.suda.scholarsense
                                                    .identityaccess.domain
                                                    .ResponsibilityType.PRIMARY)
                            .toList();
            for (int left = 0; left < primary.size(); left++) {
                for (int right = left + 1;
                        right < primary.size();
                        right++) {
                    if (overlaps(
                            primary.get(left), primary.get(right))) {
                        reject(
                                batch,
                                lease,
                                now,
                                "RESPONSIBILITY_OVERLAPPING_PRIMARY",
                                false,
                                () -> {});
                        throw new IdentitySyncException(
                                "RESPONSIBILITY_OVERLAPPING_PRIMARY");
                    }
                }
            }
        }
    }

    private static boolean overlaps(
            AuthoritativeResponsibilityRelation left,
            AuthoritativeResponsibilityRelation right) {
        Instant leftEnd =
                left.effectiveInterval().effectiveTo();
        Instant rightEnd =
                right.effectiveInterval().effectiveTo();
        return (rightEnd == null
                        || left.effectiveInterval()
                                .effectiveFrom()
                                .isBefore(rightEnd))
                && (leftEnd == null
                        || right.effectiveInterval()
                                .effectiveFrom()
                                .isBefore(leftEnd));
    }

    private void requireContinuous(
            NormalizedResponsibilityBatch batch,
            IdentityLease lease,
            IdentityCheckpoint checkpoint,
            Instant now) {
        if (version2(batch)
                && batch.sourceVersion()
                        != checkpoint.sourceVersion() + 1) {
            reject(
                    batch,
                    lease,
                    now,
                    "RESPONSIBILITY_SOURCE_VERSION_GAP",
                    true,
                    () -> {});
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_VERSION_GAP");
        }
        if (version2(batch)
                && checkpoint.aggregateVersion() == 0
                && batch.fromWatermark() != 0) {
            reject(
                    batch,
                    lease,
                    now,
                    "RESPONSIBILITY_WATERMARK_GAP",
                    true,
                    () -> replay.request(
                            batch.key(),
                            1,
                            batch.fromWatermark(),
                            batch.traceId()));
            throw new IdentitySyncException("RESPONSIBILITY_WATERMARK_GAP");
        }
        if (batch.fromWatermark() > checkpoint.watermark()) {
            reject(
                    batch,
                    lease,
                    now,
                    "RESPONSIBILITY_WATERMARK_GAP",
                    true,
                    () -> replay.request(
                            batch.key(),
                            checkpoint.watermark() + 1,
                            batch.fromWatermark(),
                            batch.traceId()));
            throw new IdentitySyncException("RESPONSIBILITY_WATERMARK_GAP");
        }
        if (batch.fromWatermark() < checkpoint.watermark()
                || batch.sourceVersion() <= checkpoint.sourceVersion()) {
            reject(
                    batch,
                    lease,
                    now,
                    "RESPONSIBILITY_SOURCE_VERSION_STALE",
                    true,
                    () -> {});
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_VERSION_STALE");
        }
    }

    private void reject(
            NormalizedResponsibilityBatch batch,
            IdentityLease lease,
            Instant now,
            String reasonCode,
            boolean replayable,
            Runnable beforeReject) {
        transactions.execute(() -> {
            if (!repository.leaseIsCurrent(lease)) {
                throw new IdentitySyncException("IDENTITY_SYNC_FENCING_STALE");
            }
            beforeReject.run();
            repository.reject(new IdentitySyncRejection(
                    UUID.fromString(UuidV7.generate(now)),
                    batch.batchId(),
                    batch.key(),
                    batch.sourceVersion(),
                    batch.toWatermark(),
                    batch.envelopeDigest(),
                    reasonCode,
                    replayable,
                    lease.jobId(),
                    lease.attemptNo(),
                    batch.traceId(),
                    now));
            audit.append(new IdentitySyncAuditEvent(
                    "responsibility.sync.rejected",
                    "rejected",
                    reasonCode,
                    lease.jobId(),
                    lease.attemptNo(),
                    lease.fencingToken(),
                    batch.sourceVersion(),
                    batch.toWatermark(),
                    0,
                    batch.traceId(),
                    now,
                    policyVersions(batch)));
            return null;
        });
    }

    private Optional<ResponsibilityRecordState> currentRecord(
            NormalizedResponsibilityBatch batch, String relationRefToken) {
        return version2(batch)
                ? repository.v2ShadowCurrentRecord(
                        batch.key(), relationRefToken)
                : repository.currentRecord(batch.key(), relationRefToken);
    }

    private List<AuthoritativeResponsibilityRelation>
            currentByStudentDigest(
                    NormalizedResponsibilityBatch batch,
                    String studentDigest,
                    Instant now) {
        return version2(batch)
                ? repository.v2ShadowCurrentByStudentDigest(
                        batch.key(), studentDigest, now)
                : repository.currentByStudentDigest(
                        batch.key(), studentDigest, now);
    }

    private static boolean version2(
            NormalizedResponsibilityBatch batch) {
        return "RESPONSIBILITY-AUTHORITY-2.0.0".equals(
                batch.contractVersion());
    }

    private static Map<String, String> policyVersions(
            NormalizedResponsibilityBatch batch) {
        return Map.of(
                "identitySessionPolicy", "ISP-1.0.0",
                "roleFieldPolicy", "RFP-1.0.0",
                "responsibilityContract", batch.contractVersion(),
                "retentionSchedule", "RS-1.0.0");
    }
}
