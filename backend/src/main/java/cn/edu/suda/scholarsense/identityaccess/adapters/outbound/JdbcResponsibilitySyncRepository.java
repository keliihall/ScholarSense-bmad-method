package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityCheckpoint;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityLease;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityProjectionFreshness;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourceHealth;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncRejection;
import cn.edu.suda.scholarsense.identityaccess.application.NormalizedResponsibilityBatch;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityExceptionAuditTransition;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityFullSnapshot;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityRecordState;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityInvalidationFactFactory;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityScopeProjectionUpdate;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySyncRepository;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2CutoverRequest;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2CutoverCommand;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2CutoverCommandState;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2ReconciliationEvidence;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2ExpiryCandidate;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2ProjectionFingerprint;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2LineageManifest;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2LineageDigest;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2LineageDigestEvent;
import cn.edu.suda.scholarsense.identityaccess.application.UuidV7;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationChangeKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAggregateType;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationSnapshot;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationState;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationDependencyWatermark;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReason;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationRetention;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSourceVector;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSubjectSnapshot;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientDecision;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientValidity;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStudentSourceReference;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityType;
import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Parameterized responsibility-owned SQL; callers provide the enclosing transaction. */
public final class JdbcResponsibilitySyncRepository
        implements ResponsibilitySyncRepository {
    private final JdbcTemplate jdbc;

    public JdbcResponsibilitySyncRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
    }

    @Override
    public Optional<IdentityCheckpoint> checkpoint(CheckpointKey key) {
        return jdbc.query("""
                select source_version, source_watermark, aggregate_version,
                       last_successful_at, health, freshness
                  from identity_access.ia_identity_sync_checkpoint
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection=?
                """,
                (rs, row) -> new IdentityCheckpoint(
                        key,
                        rs.getLong("source_version"),
                        rs.getLong("source_watermark"),
                        rs.getLong("aggregate_version"),
                        instant(rs.getTimestamp("last_successful_at")),
                        IdentitySourceHealth.valueOf(
                                rs.getString("health").toUpperCase()),
                        IdentityProjectionFreshness.valueOf(
                                rs.getString("freshness").toUpperCase())),
                key.sourceId(),
                key.feedId(),
                key.partitionId(),
                key.consumerProjection())
                .stream()
                .findFirst();
    }

    @Override
    public Optional<String> envelopeDigest(UUID batchId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    select envelope_digest
                      from identity_access.ia_responsibility_source_archive
                     where batch_id=?
                    """, String.class, batchId));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ResponsibilityRecordState> currentRecord(
            CheckpointKey key, String relationRefToken) {
        return jdbc.query("""
                select record_version, payload_digest
                  from identity_access.ia_responsibility_source_fact
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection=? and relation_ref_token=?
                 order by record_version desc, applied_at desc
                 limit 1
                """,
                (rs, row) -> new ResponsibilityRecordState(
                        rs.getLong("record_version"),
                        rs.getString("payload_digest")),
                key.sourceId(),
                key.feedId(),
                key.partitionId(),
                key.consumerProjection(),
                relationRefToken)
                .stream()
                .findFirst();
    }

    @Override
    public long identityOrgWatermark(String feedId, String partitionId) {
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
    public void recordHeartbeat(
            NormalizedResponsibilityBatch batch,
            IdentityLease lease,
            Instant appliedAt) {
        if (!batch.noChange() || !leaseIsCurrent(lease)) {
            throw new IdentitySyncException(
                    "IDENTITY_SYNC_FENCING_STALE");
        }
        requireV1WriteAllowed(batch);
        int inserted = jdbc.update("""
                insert into identity_access.ia_responsibility_source_archive (
                  batch_id, source_id, feed_id, partition_id,
                  consumer_projection, schema_version, contract_version,
                  source_version, from_watermark, to_watermark,
                  supporting_identity_org_watermarks, envelope_digest,
                  signature_digest, encrypted_payload, encrypted_data_key,
                  encryption_nonce, encryption_key_ref,
                  encryption_key_version, source_visible_at, observed_at,
                  applied_at, trace_id, consumer_watermark,
                  retention_effective_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict do nothing
                """,
                batch.batchId(),
                batch.key().sourceId(),
                batch.key().feedId(),
                batch.key().partitionId(),
                batch.key().consumerProjection(),
                batch.schemaVersion(),
                batch.contractVersion(),
                batch.sourceVersion(),
                batch.fromWatermark(),
                batch.toWatermark(),
                dependencyJson(batch.supportingIdentityOrgWatermarks()),
                batch.envelopeDigest(),
                batch.signatureDigest(),
                batch.encryptedEnvelope(),
                batch.wrappedDataKey(),
                batch.encryptionNonce(),
                batch.encryptionKeyRef(),
                batch.encryptionKeyVersion(),
                timestamp(batch.sourceVisibleAt()),
                timestamp(batch.observedAt()),
                timestamp(appliedAt),
                batch.traceId(),
                batch.toWatermark(),
                timestamp(appliedAt),
                timestamp(appliedAt.plus(Duration.ofDays(365))));
        if (inserted != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_IDEMPOTENCY_CONFLICT");
        }
    }

    @Override
    public void apply(
            NormalizedResponsibilityBatch batch,
            IdentityLease lease,
            Instant appliedAt) {
        apply(batch, lease, appliedAt, List.of());
    }

    @Override
    public void apply(
            NormalizedResponsibilityBatch batch,
            IdentityLease lease,
            Instant appliedAt,
            List<ResponsibilityScopeProjectionUpdate> scopeUpdates) {
        applyWithAuditTransitions(
                batch, lease, appliedAt, scopeUpdates);
    }

    @Override
    public List<ResponsibilityExceptionAuditTransition>
            applyWithAuditTransitions(
                    NormalizedResponsibilityBatch batch,
                    IdentityLease lease,
                    Instant appliedAt,
                    List<ResponsibilityScopeProjectionUpdate> scopeUpdates) {
        if (batch.noChange()) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_HEARTBEAT_NOT_APPLICABLE");
        }
        if (!leaseIsCurrent(lease)) {
            throw new IdentitySyncException("IDENTITY_SYNC_FENCING_STALE");
        }
        requireV1WriteAllowed(batch);
        long nextAggregate = checkpoint(batch.key())
                .orElseGet(() -> IdentityCheckpoint.initial(batch.key()))
                .aggregateVersion() + 1;
        Instant inboxExpiry = appliedAt.plus(Duration.ofDays(30));
        int inboxInserted = jdbc.update("""
                insert into identity_access.ia_responsibility_source_inbox (
                  batch_id, source_id, feed_id, partition_id,
                  consumer_projection, schema_version, contract_version,
                  source_version, from_watermark, to_watermark,
                  supporting_identity_org_watermarks, envelope_digest,
                  signature_digest, encrypted_payload, encrypted_data_key,
                  encryption_nonce, encryption_key_ref,
                  encryption_key_version, source_visible_at, observed_at,
                  processing_status, consumer_watermark, trace_id,
                  retention_effective_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, 'processing', ?, ?, ?, ?)
                on conflict do nothing
                """,
                batch.batchId(),
                batch.key().sourceId(),
                batch.key().feedId(),
                batch.key().partitionId(),
                batch.key().consumerProjection(),
                batch.schemaVersion(),
                batch.contractVersion(),
                batch.sourceVersion(),
                batch.fromWatermark(),
                batch.toWatermark(),
                dependencyJson(batch.supportingIdentityOrgWatermarks()),
                batch.envelopeDigest(),
                batch.signatureDigest(),
                batch.encryptedEnvelope(),
                batch.wrappedDataKey(),
                batch.encryptionNonce(),
                batch.encryptionKeyRef(),
                batch.encryptionKeyVersion(),
                timestamp(batch.sourceVisibleAt()),
                timestamp(batch.observedAt()),
                batch.fromWatermark(),
                batch.traceId(),
                timestamp(appliedAt),
                timestamp(inboxExpiry));
        if (inboxInserted != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_IDEMPOTENCY_CONFLICT");
        }
        int archiveInserted = jdbc.update("""
                insert into identity_access.ia_responsibility_source_archive (
                  batch_id, source_id, feed_id, partition_id,
                  consumer_projection, schema_version, contract_version,
                  source_version, from_watermark, to_watermark,
                  supporting_identity_org_watermarks, envelope_digest,
                  signature_digest, encrypted_payload, encrypted_data_key,
                  encryption_nonce, encryption_key_ref,
                  encryption_key_version, source_visible_at, observed_at,
                  applied_at, trace_id, consumer_watermark,
                  retention_effective_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict do nothing
                """,
                batch.batchId(),
                batch.key().sourceId(),
                batch.key().feedId(),
                batch.key().partitionId(),
                batch.key().consumerProjection(),
                batch.schemaVersion(),
                batch.contractVersion(),
                batch.sourceVersion(),
                batch.fromWatermark(),
                batch.toWatermark(),
                dependencyJson(batch.supportingIdentityOrgWatermarks()),
                batch.envelopeDigest(),
                batch.signatureDigest(),
                batch.encryptedEnvelope(),
                batch.wrappedDataKey(),
                batch.encryptionNonce(),
                batch.encryptionKeyRef(),
                batch.encryptionKeyVersion(),
                timestamp(batch.sourceVisibleAt()),
                timestamp(batch.observedAt()),
                timestamp(appliedAt),
                batch.traceId(),
                batch.toWatermark(),
                timestamp(appliedAt),
                timestamp(appliedAt.plus(Duration.ofDays(365))));
        if (archiveInserted != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_IDEMPOTENCY_CONFLICT");
        }
        Map<String, ResponsibilityScopeProjectionUpdate> scopeByStudent =
                scopeUpdates.stream().collect(
                        java.util.stream.Collectors.toMap(
                                ResponsibilityScopeProjectionUpdate
                                        ::studentSourceRefDigest,
                                update -> update));
        for (AuthoritativeResponsibilityRelation relation :
                batch.relations()) {
            ResponsibilityScopeProjectionUpdate scope =
                    scopeByStudent.get(
                            relation.studentSourceReference()
                                    .equivalenceDomain());
            if (scope == null) {
                throw new IdentitySyncException(
                        "RESPONSIBILITY_SCOPE_PROJECTION_MISSING");
            }
            insertFact(
                    batch,
                    relation,
                    scope,
                    nextAggregate,
                    appliedAt);
            upsertUnevaluatedCurrent(
                    batch, relation, nextAggregate, appliedAt);
        }
        List<ResponsibilityExceptionAuditTransition> transitions =
                new ArrayList<>();
        for (ResponsibilityScopeProjectionUpdate scopeUpdate :
                scopeUpdates) {
            transitions.addAll(applyScopeDecision(
                    batch, scopeUpdate, nextAggregate, appliedAt));
        }
        ensureCheckpointExists(batch.key(), appliedAt, batch.traceId());
        int checkpointUpdated = jdbc.update("""
                update identity_access.ia_identity_sync_checkpoint checkpoint
                   set source_version=?, source_watermark=?,
                       aggregate_version=?, last_successful_at=?,
                       health='healthy', freshness='fresh',
                       updated_at=?, trace_id=?
                 where checkpoint.source_id=? and checkpoint.feed_id=?
                   and checkpoint.partition_id=?
                   and checkpoint.consumer_projection=?
                   and checkpoint.source_watermark=?
                   and checkpoint.aggregate_version=?
                   and exists (
                     select 1
                       from identity_access.ia_identity_sync_lease lease
                      where lease.source_id=checkpoint.source_id
                        and lease.feed_id=checkpoint.feed_id
                        and lease.partition_id=checkpoint.partition_id
                        and lease.consumer_projection=
                            checkpoint.consumer_projection
                        and lease.job_id=? and lease.attempt_no=?
                        and lease.fencing_token=?
                        and lease.lease_expires_at>clock_timestamp())
                """,
                batch.sourceVersion(),
                batch.toWatermark(),
                nextAggregate,
                timestamp(appliedAt),
                timestamp(appliedAt),
                batch.traceId(),
                batch.key().sourceId(),
                batch.key().feedId(),
                batch.key().partitionId(),
                batch.key().consumerProjection(),
                batch.fromWatermark(),
                nextAggregate - 1,
                lease.jobId(),
                lease.attemptNo(),
                lease.fencingToken());
        if (checkpointUpdated != 1) {
            throw new IdentitySyncException("IDENTITY_SYNC_FENCING_STALE");
        }
        jdbc.update("""
                update identity_access.ia_responsibility_source_inbox
                   set processing_status='applied', consumer_watermark=?
                 where batch_id=? and processing_status='processing'
                """,
                batch.toWatermark(),
                batch.batchId());
        jdbc.update("""
                update identity_access.ia_identity_replay_request
                   set status='covered', covered_at=?
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                   and status='requested'
                   and requested_from>=? and requested_to<=?
                """,
                timestamp(appliedAt),
                batch.key().sourceId(),
                batch.key().feedId(),
                batch.key().partitionId(),
                batch.fromWatermark() + 1,
                batch.toWatermark());
        return List.copyOf(transitions);
    }

    @Override
    public List<AuthoritativeResponsibilityRelation>
            currentByStudentDigest(
                    CheckpointKey key,
                    String studentEquivalenceDigest,
                    Instant serverNow) {
        return jdbc.query("""
                select projection.relation_id, projection.source_id,
                       projection.relation_ref_token,
                       projection.student_ref_purpose,
                       projection.student_ref_key_version,
                       projection.student_ref_token,
                       projection.student_ref_digest,
                       projection.student_equivalence_digest,
                       projection.counselor_account_ref_digest,
                       projection.college_organization_ref_digest,
                       projection.responsibility_type,
                       projection.relation_status,
                       projection.effective_from, projection.effective_to,
                       projection.source_version,
                       projection.source_watermark,
                       projection.record_version,
                       projection.aggregate_version,
                       projection.payload_digest,
                       projection.access_change_kind,
                       projection.access_reason_code,
                       projection.access_effective_at,
                       projection.access_lineage_id,
                       projection.access_supersedes_id
                  from identity_access.ia_responsibility_current projection
                  join identity_access.ia_authoritative_account_current account
                    on account.source_id=projection.source_id
                   and account.consumer_projection='identity-org'
                   and account.external_ref_digest=
                       projection.counselor_account_ref_digest
                   and (projection.counselor_account_id is null
                        or projection.counselor_account_id=account.account_id)
                 where projection.source_id=? and projection.feed_id=?
                   and projection.partition_id=?
                   and projection.consumer_projection=?
                   and projection.student_equivalence_digest=?
                 order by projection.relation_ref_token,
                          projection.relation_id
                """,
                (rs, row) -> new AuthoritativeResponsibilityRelation(
                        rs.getObject("relation_id", UUID.class),
                        rs.getString("source_id"),
                        rs.getString("relation_ref_token"),
                        new ResponsibilityStudentSourceReference(
                                rs.getString("student_ref_purpose"),
                                rs.getString("student_ref_key_version"),
                                rs.getString("student_ref_token"),
                                rs.getString("student_ref_digest"),
                                rs.getString(
                                        "student_equivalence_digest")),
                        rs.getString("counselor_account_ref_digest"),
                        rs.getString("college_organization_ref_digest"),
                        ResponsibilityType.valueOf(
                                rs.getString("responsibility_type")
                                        .toUpperCase()),
                        ResponsibilityStatus.valueOf(
                                rs.getString("relation_status")
                                        .toUpperCase()),
                        new EffectiveInterval(
                                instant(rs.getTimestamp("effective_from")),
                                instant(rs.getTimestamp("effective_to"))),
                        rs.getLong("source_version"),
                        rs.getLong("source_watermark"),
                        rs.getLong("record_version"),
                        rs.getLong("aggregate_version"),
                        rs.getString("payload_digest"),
                        rs.getString("access_change_kind") == null
                                ? null
                                : AccessInvalidationChangeKind.valueOf(
                                        rs.getString("access_change_kind")
                                                .toUpperCase()
                                                .replace('-', '_')),
                        rs.getString("access_reason_code") == null
                                ? null
                                : AccessInvalidationReason.valueOf(
                                        rs.getString(
                                                "access_reason_code")),
                        instant(rs.getTimestamp(
                                "access_effective_at")),
                        rs.getString("access_lineage_id") == null
                                ? null
                                : new AccessInvalidationLineageId(
                                        rs.getString(
                                                "access_lineage_id")),
                        rs.getObject(
                                "access_supersedes_id",
                                UUID.class)),
                key.sourceId(),
                key.feedId(),
                key.partitionId(),
                key.consumerProjection(),
                studentEquivalenceDigest);
    }

    @Override
    public Optional<AuthoritativeResponsibilityRelation>
            currentCascadeScope(
                    CheckpointKey key,
                    AccessInvalidationLineageId accessLineageId,
                    UUID counselorAccountId,
                    String studentEquivalenceDigest,
                    Instant serverNow) {
        return jdbc.query("""
                select projection.relation_id, projection.source_id,
                       projection.relation_ref_token,
                       projection.student_ref_purpose,
                       projection.student_ref_key_version,
                       projection.student_ref_token,
                       projection.student_ref_digest,
                       projection.student_equivalence_digest,
                       projection.counselor_account_ref_digest,
                       projection.college_organization_ref_digest,
                       projection.responsibility_type,
                       projection.relation_status,
                       projection.effective_from, projection.effective_to,
                       projection.source_version,
                       projection.source_watermark,
                       projection.record_version,
                       projection.aggregate_version,
                       projection.payload_digest,
                       projection.access_change_kind,
                       projection.access_reason_code,
                       projection.access_effective_at,
                       projection.access_lineage_id,
                       projection.access_supersedes_id
                  from identity_access.ia_responsibility_current projection
                 where projection.source_id=? and projection.feed_id=?
                   and projection.partition_id=?
                   and projection.consumer_projection=?
                   and projection.access_lineage_id=?
                   and exists (
                     select 1
                       from identity_access
                            .ia_authoritative_account_current account
                      where account.source_id=projection.source_id
                        and account.consumer_projection='identity-org'
                        and account.account_id=?
                        and account.external_ref_digest=
                            projection.counselor_account_ref_digest
                        and (projection.counselor_account_id is null
                             or projection.counselor_account_id=
                                 account.account_id)
                   )
                   and projection.student_equivalence_digest=?
                   and projection.relation_status='active'
                   and projection.quality_gate_status='trusted'
                   and projection.effective_from<=?
                   and (projection.effective_to is null
                        or projection.effective_to>?)
                 order by projection.relation_id
                """,
                (rs, row) -> mapRelation(rs),
                key.sourceId(),
                key.feedId(),
                key.partitionId(),
                key.consumerProjection(),
                accessLineageId.value(),
                counselorAccountId,
                studentEquivalenceDigest,
                timestamp(serverNow),
                timestamp(serverNow))
                .stream()
                .findFirst();
    }

    @Override
    public boolean qualityGateTrusted(
            CheckpointKey key, String studentEquivalenceDigest) {
        List<String> statuses = jdbc.query("""
                select quality_gate_status
                  from identity_access.ia_responsibility_current
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                   and student_equivalence_digest=?
                """,
                (rs, row) -> rs.getString(1),
                key.sourceId(),
                key.feedId(),
                key.partitionId(),
                studentEquivalenceDigest);
        return statuses.stream().allMatch("trusted"::equals);
    }

    @Override
    public Optional<IdentityCheckpoint> v2ShadowCheckpoint(
            CheckpointKey key) {
        return jdbc.query("""
                select source_version, source_watermark, aggregate_version,
                       last_successful_at
                  from identity_access.ia_responsibility_v2_shadow_checkpoint
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                """,
                (rs, row) -> new IdentityCheckpoint(
                        key,
                        rs.getLong("source_version"),
                        rs.getLong("source_watermark"),
                        rs.getLong("aggregate_version"),
                        instant(rs.getTimestamp("last_successful_at")),
                        IdentitySourceHealth.HEALTHY,
                        IdentityProjectionFreshness.FRESH),
                key.sourceId(),
                key.feedId(),
                key.partitionId())
                .stream()
                .findFirst();
    }

    @Override
    public Optional<String> v2ShadowEnvelopeDigest(UUID batchId) {
        return jdbc.query("""
                select envelope_digest
                  from identity_access.ia_responsibility_source_archive
                 where batch_id=?
                   and contract_version='RESPONSIBILITY-AUTHORITY-2.0.0'
                """,
                (rs, row) -> rs.getString(1),
                batchId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<ResponsibilityRecordState> v2ShadowCurrentRecord(
            CheckpointKey key, String relationRefToken) {
        return jdbc.query("""
                select record_version, payload_digest
                  from identity_access.ia_responsibility_v2_shadow_current
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                   and relation_ref_token=?
                """,
                (rs, row) -> new ResponsibilityRecordState(
                        rs.getLong("record_version"),
                        rs.getString("payload_digest")),
                key.sourceId(), key.feedId(), key.partitionId(),
                relationRefToken)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<AccessInvalidationLineageId> v2BoundLineage(
            CheckpointKey key, String relationRefToken) {
        return jdbc.query("""
                select lineage_id
                  from identity_access.ia_responsibility_lineage_binding
                 where source_id=? and relation_ref_token=?
                """,
                (rs, row) -> new AccessInvalidationLineageId(
                        rs.getString(1)),
                key.sourceId(), relationRefToken)
                .stream()
                .findFirst();
    }

    @Override
    public List<AuthoritativeResponsibilityRelation>
            v2ShadowCurrentByStudentDigest(
                    CheckpointKey key,
                    String studentEquivalenceDigest,
                    Instant serverNow) {
        return jdbc.query("""
                select relation_id, source_id, relation_ref_token,
                       student_ref_purpose, student_ref_key_version,
                       student_ref_token, student_ref_digest,
                       student_equivalence_digest,
                       counselor_account_ref_digest,
                       college_organization_ref_digest,
                       responsibility_type, relation_status,
                       effective_from, effective_to, source_version,
                       source_watermark, record_version, aggregate_version,
                       payload_digest, access_change_kind,
                       access_reason_code, access_effective_at,
                       access_lineage_id, access_supersedes_id
                  from identity_access.ia_responsibility_v2_shadow_current
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                   and student_equivalence_digest=?
                 order by relation_ref_token, relation_id
                """,
                (rs, row) -> mapRelation(rs),
                key.sourceId(), key.feedId(), key.partitionId(),
                studentEquivalenceDigest);
    }

    @Override
    public void recordV2ShadowHeartbeat(
            NormalizedResponsibilityBatch batch,
            IdentityLease lease,
            Instant appliedAt) {
        requireV2(batch);
        if (!batch.noChange() || !leaseIsCurrent(lease)) {
            throw new IdentitySyncException(
                    "IDENTITY_SYNC_FENCING_STALE");
        }
        int inserted = jdbc.update("""
                insert into identity_access.ia_responsibility_source_archive (
                  batch_id, source_id, feed_id, partition_id,
                  consumer_projection, schema_version, contract_version,
                  source_version, from_watermark, to_watermark,
                  supporting_identity_org_watermarks, envelope_digest,
                  signature_digest, encrypted_payload, encrypted_data_key,
                  encryption_nonce, encryption_key_ref,
                  encryption_key_version, source_visible_at, observed_at,
                  applied_at, trace_id, consumer_watermark,
                  retention_effective_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict do nothing
                """,
                batch.batchId(), batch.key().sourceId(),
                batch.key().feedId(), batch.key().partitionId(),
                batch.key().consumerProjection(), batch.schemaVersion(),
                batch.contractVersion(), batch.sourceVersion(),
                batch.fromWatermark(), batch.toWatermark(),
                dependencyJson(batch.supportingIdentityOrgWatermarks()),
                batch.envelopeDigest(), batch.signatureDigest(),
                batch.encryptedEnvelope(), batch.wrappedDataKey(),
                batch.encryptionNonce(), batch.encryptionKeyRef(),
                batch.encryptionKeyVersion(),
                timestamp(batch.sourceVisibleAt()),
                timestamp(batch.observedAt()), timestamp(appliedAt),
                batch.traceId(), batch.toWatermark(), timestamp(appliedAt),
                timestamp(appliedAt.plus(Duration.ofDays(365))));
        if (inserted != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_IDEMPOTENCY_CONFLICT");
        }
    }

    @Override
    public void applyV2Shadow(
            NormalizedResponsibilityBatch batch,
            IdentityLease lease,
            Instant appliedAt,
            List<ResponsibilityScopeProjectionUpdate> scopeUpdates) {
        requireV2(batch);
        if (batch.noChange() || !leaseIsCurrent(lease)) {
            throw new IdentitySyncException("IDENTITY_SYNC_FENCING_STALE");
        }
        long nextAggregate = v2ShadowCheckpoint(batch.key())
                .orElseGet(() -> IdentityCheckpoint.initial(batch.key()))
                .aggregateVersion() + 1;
        insertV2Custody(batch, appliedAt);
        Map<String, ResponsibilityScopeProjectionUpdate> scopeByStudent =
                scopeUpdates.stream().collect(
                        java.util.stream.Collectors.toMap(
                                ResponsibilityScopeProjectionUpdate
                                        ::studentSourceRefDigest,
                                update -> update));
        for (AuthoritativeResponsibilityRelation relation :
                batch.relations()) {
            ResponsibilityScopeProjectionUpdate scope = scopeByStudent.get(
                    relation.studentSourceReference().equivalenceDomain());
            if (scope == null) {
                throw new IdentitySyncException(
                        "RESPONSIBILITY_SCOPE_PROJECTION_MISSING");
            }
            bindV2Lineage(batch, relation, appliedAt);
            AccessInvalidationFact staged = stageV2Invalidation(
                    batch, relation, scopeUpdates, appliedAt);
            insertV2SourceFact(
                    batch, relation, scope, staged, appliedAt);
            upsertV2ShadowCurrent(
                    batch, relation, scope, nextAggregate, appliedAt);
        }
        for (ResponsibilityScopeProjectionUpdate scope : scopeUpdates) {
            applyV2ScopeDecision(batch, scope, nextAggregate, appliedAt);
        }
        ensureV2ShadowCheckpoint(batch.key(), appliedAt, batch.traceId());
        int updated = jdbc.update("""
                update identity_access.ia_responsibility_v2_shadow_checkpoint c
                   set source_version=?, source_watermark=?,
                       aggregate_version=?, last_successful_at=?,
                       replay_started_at_zero=
                           c.replay_started_at_zero or ?=0,
                       updated_at=?, trace_id=?
                 where c.source_id=? and c.feed_id=? and c.partition_id=?
                   and c.consumer_projection='responsibility'
                   and c.source_watermark=?
                   and c.aggregate_version=?
                   and exists (
                     select 1
                       from identity_access.ia_identity_sync_lease lease
                      where lease.source_id=c.source_id
                        and lease.feed_id=c.feed_id
                        and lease.partition_id=c.partition_id
                        and lease.consumer_projection='responsibility'
                        and lease.job_id=? and lease.attempt_no=?
                        and lease.fencing_token=?
                        and lease.lease_expires_at>clock_timestamp())
                """,
                batch.sourceVersion(), batch.toWatermark(), nextAggregate,
                timestamp(appliedAt), batch.fromWatermark(),
                timestamp(appliedAt), batch.traceId(),
                batch.key().sourceId(), batch.key().feedId(),
                batch.key().partitionId(), batch.fromWatermark(),
                nextAggregate - 1, lease.jobId(), lease.attemptNo(),
                lease.fencingToken());
        if (updated != 1) {
            throw new IdentitySyncException("IDENTITY_SYNC_FENCING_STALE");
        }
        jdbc.update("""
                update identity_access.ia_responsibility_source_inbox
                   set processing_status='applied', consumer_watermark=?
                 where batch_id=? and processing_status='processing'
                """, batch.toWatermark(), batch.batchId());
        if (v2ShadowActive(batch.key())) {
            upsertLiveProjectionFromV2(batch.key());
            upsertLiveCheckpointFromV2(
                    batch.key(), appliedAt, batch.traceId());
        }
    }

    @Override
    public boolean v2ShadowActive(CheckpointKey key) {
        return jdbc.query("""
                select active
                  from identity_access.ia_responsibility_v2_shadow_checkpoint
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                """,
                (rs, row) -> rs.getBoolean(1),
                key.sourceId(), key.feedId(), key.partitionId())
                .stream()
                .findFirst()
                .orElse(false);
    }

    @Override
    public Optional<ResponsibilityV2CutoverCommandState>
            v2CutoverCommand(UUID commandId) {
        return jdbc.query("""
                select command_id, source_id, feed_id, partition_id,
                       consumer_projection, business_date, operator_ref,
                       approval_ref, profile_digest, requested_at, trace_id,
                       signature_digest, status, reason_code, snapshot_id,
                       completed_at
                  from identity_access
                       .ia_responsibility_v2_cutover_command
                 where command_id=?
                """,
                (rs, row) -> {
                    UUID storedId = rs.getObject("command_id", UUID.class);
                    ResponsibilityV2CutoverCommand stored =
                            new ResponsibilityV2CutoverCommand(
                                    storedId,
                                    new CheckpointKey(
                                            rs.getString("source_id"),
                                            rs.getString("feed_id"),
                                            rs.getString("partition_id"),
                                            rs.getString(
                                                    "consumer_projection")),
                                    rs.getObject(
                                            "business_date",
                                            java.time.LocalDate.class),
                                    rs.getString("operator_ref"),
                                    rs.getString("approval_ref"),
                                    rs.getString("profile_digest"),
                                    instant(rs.getTimestamp("requested_at")),
                                    rs.getString("trace_id"),
                                    rs.getString("signature_digest"));
                    return new ResponsibilityV2CutoverCommandState(
                            storedId,
                            stored.canonicalDigest(),
                            rs.getString("status"),
                            rs.getString("reason_code"),
                            rs.getObject("snapshot_id", UUID.class),
                            instant(rs.getTimestamp("completed_at")));
                },
                commandId).stream().findFirst();
    }

    @Override
    public void beginV2CutoverCommand(
            ResponsibilityV2CutoverCommand command) {
        int inserted = jdbc.update("""
                insert into identity_access
                     .ia_responsibility_v2_cutover_command (
                  command_id, source_id, feed_id, partition_id,
                  consumer_projection, business_date, operator_ref,
                  approval_ref, profile_digest, status, reason_code,
                  snapshot_id, requested_at, updated_at, completed_at,
                  trace_id, signature_digest)
                values (?, ?, ?, ?, 'responsibility', ?, ?, ?, ?,
                        'requested', 'RESPONSIBILITY_V2_CUTOVER_REQUESTED',
                        null, ?, ?, null, ?, ?)
                on conflict (command_id) do nothing
                """,
                command.commandId(), command.key().sourceId(),
                command.key().feedId(), command.key().partitionId(),
                command.businessDate(), command.operatorRef(),
                command.approvalRef(), command.profileDigest(),
                timestamp(command.requestedAt()),
                timestamp(command.requestedAt()), command.traceId(),
                command.signatureDigest());
        if (inserted != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_CUTOVER_COMMAND_DUPLICATE");
        }
    }

    @Override
    public void finishV2CutoverCommand(
            UUID commandId,
            String status,
            String reasonCode,
            UUID snapshotId,
            Instant completedAt) {
        if (!java.util.Set.of("denied", "failed", "activated")
                        .contains(status)
                || reasonCode == null
                || !reasonCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_CUTOVER_COMMAND_STATE_INVALID");
        }
        int updated = jdbc.update("""
                update identity_access
                       .ia_responsibility_v2_cutover_command
                   set status=?, reason_code=?, snapshot_id=?,
                       updated_at=?, completed_at=?
                 where command_id=? and status='requested'
                """,
                status, reasonCode, snapshotId,
                timestamp(completedAt), timestamp(completedAt),
                commandId);
        if (updated != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_CUTOVER_COMMAND_STATE_INVALID");
        }
    }

    @Override
    public ResponsibilityV2ReconciliationEvidence recordV2Reconciliation(
            ResponsibilityFullSnapshot snapshot,
            Instant reconciledAt) {
        if (!"RESPONSIBILITY-AUTHORITY-2.0.0".equals(
                        snapshot.contractVersion())
                || !snapshot.signatureVerified()) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_RECONCILIATION_SNAPSHOT_INVALID");
        }
        V2GateState gate = lockV2Gate(snapshot.key());
        LiveProjectionHead live = lockLiveProjectionHead(snapshot.key());
        if (gate.active()
                || gate.watermark() != snapshot.throughWatermark()
                || gate.sourceVersion() != snapshot.sourceVersion()
                || gate.watermark() < live.watermark()
                || gate.sourceVersion() < live.sourceVersion()) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_RECONCILIATION_STALE");
        }
        ResponsibilityV2ProjectionFingerprint actual =
                v2ShadowFingerprint(snapshot.key());
        ResponsibilityV2ReconciliationEvidence evidence =
                new ResponsibilityV2ReconciliationEvidence(
                        snapshot.key(),
                        snapshot.snapshotId(),
                        snapshot.sourceVersion(),
                        snapshot.throughWatermark(),
                        snapshot.expectedCount(),
                        actual.recordCount(),
                        snapshot.canonicalDigest(),
                        actual.canonicalDigest(),
                        snapshot.lineageCount(),
                        actual.lineageCount(),
                        snapshot.canonicalLineageDigest(),
                        actual.canonicalLineageDigest(),
                        actual.lineageConflictCount(),
                        snapshot.envelopeDigest(),
                        snapshot.signatureDigest(),
                        reconciledAt,
                        snapshot.traceId());
        appendV2Snapshot(snapshot, reconciledAt);
        int updated = jdbc.update("""
                update identity_access.ia_responsibility_v2_shadow_checkpoint
                   set reconciliation_watermark=?,
                       reconciliation_snapshot_id=?,
                       reconciliation_snapshot_source_version=?,
                       reconciliation_envelope_digest=?,
                       reconciliation_signature_digest=?,
                       reconciliation_expected_count=?,
                       reconciliation_actual_count=?,
                       reconciliation_expected_digest=?,
                       reconciliation_actual_digest=?,
                       reconciliation_expected_lineage_count=?,
                       reconciliation_actual_lineage_count=?,
                       reconciliation_expected_lineage_digest=?,
                       reconciliation_actual_lineage_digest=?,
                       reconciliation_lineage_conflicts=?,
                       reconciliation_live_source_version=?,
                       reconciliation_live_watermark=?,
                       reconciliation_status=?, reconciled_at=?, trace_id=?
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                   and source_version=? and source_watermark=? and not active
                """,
                evidence.throughWatermark(),
                evidence.snapshotId(),
                evidence.snapshotSourceVersion(),
                evidence.envelopeDigest(),
                evidence.signatureDigest(),
                evidence.expectedCount(),
                actual.recordCount(),
                evidence.expectedDigest(),
                actual.canonicalDigest(),
                evidence.expectedLineageCount(),
                actual.lineageCount(),
                evidence.expectedLineageDigest(),
                actual.canonicalLineageDigest(),
                actual.lineageConflictCount(),
                live.sourceVersion(),
                live.watermark(),
                evidence.matched() ? "matched" : "differences-found",
                timestamp(evidence.reconciledAt()),
                evidence.traceId(),
                evidence.key().sourceId(),
                evidence.key().feedId(),
                evidence.key().partitionId(),
                evidence.snapshotSourceVersion(),
                evidence.throughWatermark());
        if (updated != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_RECONCILIATION_STALE");
        }
        return evidence;
    }

    @Override
    public ResponsibilityV2ProjectionFingerprint v2ShadowFingerprint(
            CheckpointKey key) {
        List<V2ProjectionDigestEntry> entries = jdbc.query("""
                select relation_ref_token, student_equivalence_digest,
                       record_version, payload_digest
                  from identity_access.ia_responsibility_v2_shadow_current
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                 order by relation_ref_token, student_equivalence_digest,
                          record_version, payload_digest
                """,
                (rs, row) -> new V2ProjectionDigestEntry(
                        rs.getString("relation_ref_token"),
                        rs.getString("student_equivalence_digest"),
                        rs.getLong("record_version"),
                        rs.getString("payload_digest")),
                key.sourceId(), key.feedId(), key.partitionId());
        String canonical = entries.stream()
                .map(entry -> entry.relationRefToken()
                        + "|" + entry.studentEquivalenceDigest()
                        + "|" + entry.recordVersion()
                        + "|" + entry.payloadDigest())
                .collect(java.util.stream.Collectors.joining("\n"));
        V2LineageFingerprint lineages = v2LineageFingerprint(key);
        return new ResponsibilityV2ProjectionFingerprint(
                entries.size(),
                digest(canonical),
                lineages.manifests().size(),
                ResponsibilityV2LineageManifest.digest(
                        lineages.manifests()),
                lineageBindingConflicts(key) + lineages.conflicts());
    }

    @Override
    public List<AccessInvalidationFact> v2ShadowInvalidationFacts(
            CheckpointKey key) {
        return jdbc.query("""
                select event_id, trace_id, change_kind, reason_code,
                       lineage_id, supersedes_id, aggregate_version,
                       effective_at, source_id, feed_id, partition_id,
                       source_version,
                       source_watermark, dependency_vector,
                       subject_token, scope_token, object_digest,
                       authorization_state, account_active,
                       r1_employment_valid, college_active,
                       relation_effective, policy_version,
                       source_payload_digest, retain_until, legal_hold
                  from identity_access
                       .ia_responsibility_v2_shadow_invalidation_fact
                 where source_id=? and feed_id=? and partition_id=?
                 order by lineage_id, aggregate_version
                """,
                (rs, row) -> mapShadowInvalidationFact(rs),
                key.sourceId(), key.feedId(), key.partitionId());
    }

    @Override
    public List<ResponsibilityV2ExpiryCandidate> v2ShadowExpiryCandidates(
            CheckpointKey key, Instant activatedAt) {
        java.util.Objects.requireNonNull(activatedAt, "activatedAt");
        return jdbc.query("""
                select current.access_lineage_id, head.current_event_id,
                       head.current_version, current.effective_to,
                       current.trace_id
                  from identity_access
                       .ia_responsibility_v2_shadow_current current
                  join identity_access
                       .ia_responsibility_v2_shadow_lineage_head head
                    on head.source_id=current.source_id
                   and head.feed_id=current.feed_id
                   and head.partition_id=current.partition_id
                   and head.lineage_id=current.access_lineage_id
                 where current.source_id=? and current.feed_id=?
                   and current.partition_id=?
                   and current.relation_status='active'
                   and current.effective_to is not null
                 order by current.access_lineage_id
                """,
                (rs, row) -> new ResponsibilityV2ExpiryCandidate(
                        new AccessInvalidationLineageId(
                                rs.getString("access_lineage_id")),
                        rs.getObject("current_event_id", UUID.class),
                        rs.getLong("current_version"),
                        instant(rs.getTimestamp("effective_to")),
                        rs.getString("trace_id")),
                key.sourceId(), key.feedId(), key.partitionId());
    }

    @Override
    public void markV2InvalidationReplayMaterialized(
            ResponsibilityV2CutoverRequest request, int factCount) {
        int updated = jdbc.update("""
                update identity_access.ia_responsibility_v2_shadow_checkpoint
                   set invalidation_materialized=true,
                       invalidation_materialized_count=?, trace_id=?
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                   and reconciliation_snapshot_id=? and not active
                   and reconciliation_status='matched'
                   and reconciliation_expected_digest=
                       reconciliation_actual_digest
                   and reconciliation_expected_lineage_digest=
                       reconciliation_actual_lineage_digest
                   and (select count(*)
                          from identity_access
                               .ia_responsibility_v2_shadow_invalidation_fact f
                         where f.source_id=? and f.feed_id=?
                           and f.partition_id=?)=?
                """,
                factCount,
                request.traceId(),
                request.key().sourceId(),
                request.key().feedId(),
                request.key().partitionId(),
                request.reconciliationSnapshotId(),
                request.key().sourceId(),
                request.key().feedId(),
                request.key().partitionId(),
                factCount);
        if (updated != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_INVALIDATION_REPLAY_NOT_READY");
        }
    }

    @Override
    public IdentityCheckpoint activateV2Shadow(
            ResponsibilityV2CutoverRequest request,
            Instant activatedAt) {
        V2GateState gate = lockV2Gate(request.key());
        LiveProjectionHead live = lockLiveProjectionHead(request.key());
        ResponsibilityV2ProjectionFingerprint actual =
                v2ShadowFingerprint(request.key());
        if (!gate.replayStartedAtZero()
                || !"matched".equals(gate.reconciliationStatus())
                || gate.reconciliationWatermark()
                        != gate.watermark()
                || !request.reconciliationSnapshotId().equals(
                        gate.reconciliationSnapshotId())
                || gate.reconciliationSnapshotSourceVersion()
                        != gate.sourceVersion()
                || !gate.expectedDigest().equals(gate.actualDigest())
                || gate.expectedCount() != gate.actualCount()
                || gate.actualCount() != actual.recordCount()
                || !gate.actualDigest().equals(actual.canonicalDigest())
                || gate.expectedLineageCount()
                        != gate.actualLineageCount()
                || gate.actualLineageCount() != actual.lineageCount()
                || !gate.expectedLineageDigest().equals(
                        gate.actualLineageDigest())
                || !gate.actualLineageDigest().equals(
                        actual.canonicalLineageDigest())
                || gate.lineageConflicts()
                        != actual.lineageConflictCount()
                || actual.lineageConflictCount() != 0
                || gate.liveSourceVersion() != live.sourceVersion()
                || gate.liveWatermark() != live.watermark()
                || gate.sourceVersion() < live.sourceVersion()
                || gate.watermark() < live.watermark()
                || !gate.invalidationMaterialized()
                || gate.invalidationMaterializedCount()
                        != gate.shadowFactCount()
                || gate.active()) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_CUTOVER_GATE_BLOCKED");
        }
        replaceLiveProjectionFromV2(request.key());
        upsertLiveCheckpointFromV2(request.key(), activatedAt,
                request.traceId());
        int activated = jdbc.update("""
                update identity_access.ia_responsibility_v2_shadow_checkpoint
                   set active=true, activated_at=?, trace_id=?
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                   and not active and invalidation_materialized
                   and reconciliation_snapshot_id=?
                """,
                timestamp(activatedAt), request.traceId(),
                request.key().sourceId(), request.key().feedId(),
                request.key().partitionId(),
                request.reconciliationSnapshotId());
        if (activated != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_CUTOVER_GATE_BLOCKED");
        }
        return v2ShadowCheckpoint(request.key())
                .orElseThrow(() -> new IdentitySyncException(
                        "RESPONSIBILITY_V2_SHADOW_UNAVAILABLE"));
    }

    private void insertV2Custody(
            NormalizedResponsibilityBatch batch, Instant appliedAt) {
        Instant inboxExpiry = appliedAt.plus(Duration.ofDays(30));
        int inbox = jdbc.update("""
                insert into identity_access.ia_responsibility_source_inbox (
                  batch_id, source_id, feed_id, partition_id,
                  consumer_projection, schema_version, contract_version,
                  source_version, from_watermark, to_watermark,
                  supporting_identity_org_watermarks, envelope_digest,
                  signature_digest, encrypted_payload, encrypted_data_key,
                  encryption_nonce, encryption_key_ref,
                  encryption_key_version, source_visible_at, observed_at,
                  processing_status, consumer_watermark, trace_id,
                  retention_effective_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, 'processing', ?, ?, ?, ?)
                on conflict do nothing
                """,
                batch.batchId(), batch.key().sourceId(),
                batch.key().feedId(), batch.key().partitionId(),
                batch.key().consumerProjection(), batch.schemaVersion(),
                batch.contractVersion(), batch.sourceVersion(),
                batch.fromWatermark(), batch.toWatermark(),
                dependencyJson(batch.supportingIdentityOrgWatermarks()),
                batch.envelopeDigest(), batch.signatureDigest(),
                batch.encryptedEnvelope(), batch.wrappedDataKey(),
                batch.encryptionNonce(), batch.encryptionKeyRef(),
                batch.encryptionKeyVersion(),
                timestamp(batch.sourceVisibleAt()),
                timestamp(batch.observedAt()), batch.fromWatermark(),
                batch.traceId(), timestamp(appliedAt),
                timestamp(inboxExpiry));
        int archive = jdbc.update("""
                insert into identity_access.ia_responsibility_source_archive (
                  batch_id, source_id, feed_id, partition_id,
                  consumer_projection, schema_version, contract_version,
                  source_version, from_watermark, to_watermark,
                  supporting_identity_org_watermarks, envelope_digest,
                  signature_digest, encrypted_payload, encrypted_data_key,
                  encryption_nonce, encryption_key_ref,
                  encryption_key_version, source_visible_at, observed_at,
                  applied_at, trace_id, consumer_watermark,
                  retention_effective_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict do nothing
                """,
                batch.batchId(), batch.key().sourceId(),
                batch.key().feedId(), batch.key().partitionId(),
                batch.key().consumerProjection(), batch.schemaVersion(),
                batch.contractVersion(), batch.sourceVersion(),
                batch.fromWatermark(), batch.toWatermark(),
                dependencyJson(batch.supportingIdentityOrgWatermarks()),
                batch.envelopeDigest(), batch.signatureDigest(),
                batch.encryptedEnvelope(), batch.wrappedDataKey(),
                batch.encryptionNonce(), batch.encryptionKeyRef(),
                batch.encryptionKeyVersion(),
                timestamp(batch.sourceVisibleAt()),
                timestamp(batch.observedAt()), timestamp(appliedAt),
                batch.traceId(), batch.toWatermark(), timestamp(appliedAt),
                timestamp(appliedAt.plus(Duration.ofDays(365))));
        if (inbox != 1 || archive != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_IDEMPOTENCY_CONFLICT");
        }
    }

    private void bindV2Lineage(
            NormalizedResponsibilityBatch batch,
            AuthoritativeResponsibilityRelation relation,
            Instant appliedAt) {
        jdbc.update("""
                insert into identity_access
                     .ia_responsibility_lineage_binding (
                  source_id, relation_ref_token, lineage_id,
                  bound_event_id, bound_at, trace_id)
                values (?, ?, ?, ?, ?, ?)
                on conflict do nothing
                """,
                batch.key().sourceId(), relation.relationRefToken(),
                relation.lineageId().value(), relation.relationId(),
                timestamp(appliedAt), batch.traceId());
        List<LineageBinding> conflicts = jdbc.query("""
                select relation_ref_token, lineage_id
                  from identity_access.ia_responsibility_lineage_binding
                 where source_id=?
                   and (relation_ref_token=? or lineage_id=?)
                """,
                (rs, row) -> new LineageBinding(
                        rs.getString("relation_ref_token"),
                        rs.getString("lineage_id")),
                batch.key().sourceId(), relation.relationRefToken(),
                relation.lineageId().value());
        if (conflicts.size() != 1
                || conflicts.stream().anyMatch(binding ->
                !binding.relationRefToken().equals(relation.relationRefToken())
                        || !binding.lineageId().equals(
                                relation.lineageId().value()))) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_IDEMPOTENCY_CONFLICT");
        }
    }

    private AccessInvalidationFact stageV2Invalidation(
            NormalizedResponsibilityBatch batch,
            AuthoritativeResponsibilityRelation relation,
            List<ResponsibilityScopeProjectionUpdate> scopeUpdates,
            Instant appliedAt) {
        Optional<ShadowLineageHead> head = jdbc.query("""
                select current_event_id, current_version
                  from identity_access
                       .ia_responsibility_v2_shadow_lineage_head
                 where source_id=? and feed_id=? and partition_id=?
                   and lineage_id=?
                 for update
                """,
                (rs, row) -> new ShadowLineageHead(
                        rs.getObject("current_event_id", UUID.class),
                        rs.getLong("current_version")),
                batch.key().sourceId(), batch.key().feedId(),
                batch.key().partitionId(), relation.lineageId().value())
                .stream()
                .findFirst();
        UUID expectedSupersedes = head
                .map(ShadowLineageHead::eventId).orElse(null);
        if (!java.util.Objects.equals(
                expectedSupersedes, relation.supersedesId())) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_SUPERSEDES_INVALID");
        }
        long version = head.map(ShadowLineageHead::version).orElse(0L) + 1;
        if (relation.aggregateVersion() != version) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_SUPERSEDES_INVALID");
        }
        AccessInvalidationFact fact = ResponsibilityInvalidationFactFactory
                .create(batch, scopeUpdates, relation, version);
        jdbc.update("""
                insert into identity_access
                     .ia_responsibility_v2_shadow_invalidation_fact (
                  event_id, source_id, feed_id, partition_id, trace_id,
                  change_kind, reason_code, lineage_id, supersedes_id,
                  aggregate_version, effective_at, source_version,
                  source_watermark, dependency_vector, subject_token,
                  scope_token, object_digest, authorization_state,
                  account_active, r1_employment_valid, college_active,
                  relation_effective, policy_version,
                  source_payload_digest, retain_until, legal_hold,
                  staged_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                fact.eventId(), batch.key().sourceId(),
                batch.key().feedId(), batch.key().partitionId(),
                fact.traceId(), wire(fact.changeKind()),
                fact.reasonCode().name(), fact.lineageId().value(),
                fact.supersedesId(), fact.aggregateVersion(),
                timestamp(fact.effectiveAt()),
                fact.sourceVector().sourceVersion(),
                fact.sourceVector().sourceWatermark(),
                dependencyJson(batch.supportingIdentityOrgWatermarks()),
                fact.subjectSnapshot().subjectToken(),
                fact.subjectSnapshot().scopeToken(),
                fact.subjectSnapshot().objectDigest(),
                wire(fact.authorizationSnapshot().currentState()),
                fact.authorizationSnapshot().accountActive(),
                fact.authorizationSnapshot().r1EmploymentValid(),
                fact.authorizationSnapshot().collegeActive(),
                fact.authorizationSnapshot().relationEffective(),
                fact.authorizationSnapshot().policyVersion(),
                fact.payloadDigest(),
                timestamp(fact.retention().retainUntil()),
                fact.retention().legalHold(), timestamp(appliedAt));
        jdbc.update("""
                insert into identity_access
                     .ia_responsibility_v2_shadow_lineage_head (
                  source_id, feed_id, partition_id, lineage_id,
                  relation_ref_token, current_event_id, current_version,
                  updated_at, trace_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (source_id, feed_id, partition_id, lineage_id)
                do update set current_event_id=excluded.current_event_id,
                              current_version=excluded.current_version,
                              updated_at=excluded.updated_at,
                              trace_id=excluded.trace_id
                """,
                batch.key().sourceId(), batch.key().feedId(),
                batch.key().partitionId(), fact.lineageId().value(),
                relation.relationRefToken(), fact.eventId(), version,
                timestamp(appliedAt), batch.traceId());
        return fact;
    }

    private void insertV2SourceFact(
            NormalizedResponsibilityBatch batch,
            AuthoritativeResponsibilityRelation relation,
            ResponsibilityScopeProjectionUpdate scope,
            AccessInvalidationFact fact,
            Instant appliedAt) {
        boolean recipientMapped = scope.decision().validity()
                        == ResponsibilityRecipientValidity.VALID
                && relation.responsibilityType()
                        == ResponsibilityType.PRIMARY
                && relation.status() == ResponsibilityStatus.ACTIVE
                && relation.effectiveInterval().contains(appliedAt);
        int inserted = jdbc.update("""
                insert into identity_access
                     .ia_responsibility_v2_source_fact (
                  fact_id, batch_id, event_id, source_id, feed_id,
                  partition_id, consumer_projection, relation_ref_token,
                  student_ref_digest, student_equivalence_digest,
                  relation_status, effective_from, effective_to,
                  source_version, source_watermark, record_version,
                  aggregate_version, payload_digest,
                  supporting_identity_org_watermarks, recipient_mapped,
                  lineage_id, supersedes_id, change_kind, reason_code,
                  change_effective_at, applied_at, trace_id,
                  consumer_watermark, retention_effective_at, expires_at)
                values (?, ?, ?, ?, ?, ?, 'responsibility', ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?)
                """,
                relation.relationId(), batch.batchId(), fact.eventId(),
                batch.key().sourceId(), batch.key().feedId(),
                batch.key().partitionId(), relation.relationRefToken(),
                relation.studentSourceReference().digest(),
                relation.studentSourceReference().equivalenceDomain(),
                relation.status().name().toLowerCase(),
                timestamp(relation.effectiveInterval().effectiveFrom()),
                timestamp(relation.effectiveInterval().effectiveTo()),
                batch.sourceVersion(), batch.toWatermark(),
                relation.recordVersion(), fact.aggregateVersion(),
                relation.payloadDigest(),
                dependencyJson(batch.supportingIdentityOrgWatermarks()),
                recipientMapped, fact.lineageId().value(),
                fact.supersedesId(), wire(fact.changeKind()),
                fact.reasonCode().name(), timestamp(fact.effectiveAt()),
                timestamp(appliedAt), batch.traceId(),
                batch.toWatermark(), timestamp(appliedAt),
                timestamp(appliedAt.plus(Duration.ofDays(365))));
        if (inserted != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_IDEMPOTENCY_CONFLICT");
        }
    }

    private void upsertV2ShadowCurrent(
            NormalizedResponsibilityBatch batch,
            AuthoritativeResponsibilityRelation relation,
            ResponsibilityScopeProjectionUpdate scope,
            long aggregateVersion,
            Instant appliedAt) {
        var student = relation.studentSourceReference();
        var decision = scope.decision();
        boolean valid = decision.validity()
                == ResponsibilityRecipientValidity.VALID;
        int updated = jdbc.update("""
                insert into identity_access
                     .ia_responsibility_v2_shadow_current (
                  relation_id, source_id, feed_id, partition_id,
                  consumer_projection, relation_ref_token,
                  student_ref_purpose, student_ref_key_version,
                  student_ref_token, student_ref_digest,
                  student_equivalence_digest,
                  counselor_account_ref_digest,
                  college_organization_ref_digest,
                  counselor_account_id, college_organization_id,
                  responsibility_type, relation_status,
                  recipient_validity, recipient_reason_code,
                  quality_gate_status, effective_from, effective_to,
                  source_version, source_watermark, record_version,
                  aggregate_version, payload_digest, applied_at, trace_id,
                  retention_effective_at, access_lineage_id,
                  access_event_id, access_change_kind,
                  access_reason_code, access_effective_at,
                  access_supersedes_id)
                values (?, ?, ?, ?, 'responsibility', ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?)
                on conflict (source_id, feed_id, partition_id,
                             relation_ref_token)
                do update set
                  relation_id=excluded.relation_id,
                  student_ref_purpose=excluded.student_ref_purpose,
                  student_ref_key_version=excluded.student_ref_key_version,
                  student_ref_token=excluded.student_ref_token,
                  student_ref_digest=excluded.student_ref_digest,
                  student_equivalence_digest=
                      excluded.student_equivalence_digest,
                  counselor_account_ref_digest=
                      excluded.counselor_account_ref_digest,
                  college_organization_ref_digest=
                      excluded.college_organization_ref_digest,
                  counselor_account_id=excluded.counselor_account_id,
                  college_organization_id=excluded.college_organization_id,
                  responsibility_type=excluded.responsibility_type,
                  relation_status=excluded.relation_status,
                  recipient_validity=excluded.recipient_validity,
                  recipient_reason_code=excluded.recipient_reason_code,
                  quality_gate_status=excluded.quality_gate_status,
                  effective_from=excluded.effective_from,
                  effective_to=excluded.effective_to,
                  source_version=excluded.source_version,
                  source_watermark=excluded.source_watermark,
                  record_version=excluded.record_version,
                  aggregate_version=excluded.aggregate_version,
                  payload_digest=excluded.payload_digest,
                  applied_at=excluded.applied_at,
                  trace_id=excluded.trace_id,
                  retention_effective_at=excluded.retention_effective_at,
                  access_lineage_id=excluded.access_lineage_id,
                  access_event_id=excluded.access_event_id,
                  access_change_kind=excluded.access_change_kind,
                  access_reason_code=excluded.access_reason_code,
                  access_effective_at=excluded.access_effective_at,
                  access_supersedes_id=excluded.access_supersedes_id
                where excluded.record_version >
                      identity_access
                        .ia_responsibility_v2_shadow_current.record_version
                """,
                relation.relationId(), batch.key().sourceId(),
                batch.key().feedId(), batch.key().partitionId(),
                relation.relationRefToken(), student.purposeCode(),
                student.keyVersion(), student.tokenValue(), student.digest(),
                student.equivalenceDomain(),
                relation.counselorAccountRefDigest(),
                relation.collegeOrganizationRefDigest(),
                valid ? decision.counselorAccountId() : null,
                valid ? decision.collegeOrganizationId() : null,
                relation.responsibilityType().name().toLowerCase(),
                relation.status().name().toLowerCase(),
                decision.validity().name().toLowerCase().replace('_', '-'),
                decision.reason().code(), valid ? "trusted" : "blocked",
                timestamp(relation.effectiveInterval().effectiveFrom()),
                timestamp(relation.effectiveInterval().effectiveTo()),
                batch.sourceVersion(), batch.toWatermark(),
                relation.recordVersion(), aggregateVersion,
                relation.payloadDigest(), timestamp(appliedAt),
                batch.traceId(), timestamp(appliedAt),
                relation.lineageId().value(), relation.relationId(),
                wire(relation.changeKind()), relation.changeReason().name(),
                timestamp(relation.changeEffectiveAt()),
                relation.supersedesId());
        if (updated != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_VERSION_STALE");
        }
    }

    private void applyV2ScopeDecision(
            NormalizedResponsibilityBatch batch,
            ResponsibilityScopeProjectionUpdate scope,
            long aggregateVersion,
            Instant appliedAt) {
        var decision = scope.decision();
        boolean valid = decision.validity()
                == ResponsibilityRecipientValidity.VALID;
        int updated = jdbc.update("""
                update identity_access
                       .ia_responsibility_v2_shadow_current
                   set counselor_account_id=?, college_organization_id=?,
                       recipient_validity=?, recipient_reason_code=?,
                       quality_gate_status=?, aggregate_version=?,
                       applied_at=?, trace_id=?, retention_effective_at=?
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                   and student_equivalence_digest=?
                """,
                valid ? decision.counselorAccountId() : null,
                valid ? decision.collegeOrganizationId() : null,
                decision.validity().name().toLowerCase().replace('_', '-'),
                decision.reason().code(), valid ? "trusted" : "blocked",
                aggregateVersion, timestamp(appliedAt), batch.traceId(),
                timestamp(appliedAt), batch.key().sourceId(),
                batch.key().feedId(), batch.key().partitionId(),
                scope.studentSourceRefDigest());
        if (updated < 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SCOPE_PROJECTION_MISSING");
        }
    }

    private void ensureV2ShadowCheckpoint(
            CheckpointKey key, Instant now, String traceId) {
        jdbc.update("""
                insert into identity_access
                     .ia_responsibility_v2_shadow_checkpoint (
                  source_id, feed_id, partition_id, consumer_projection,
                  source_version, source_watermark, aggregate_version,
                  replay_started_at_zero, updated_at, trace_id)
                values (?, ?, ?, 'responsibility', 0, 0, 0, false, ?, ?)
                on conflict (source_id, feed_id, partition_id,
                             consumer_projection) do nothing
                """,
                key.sourceId(), key.feedId(), key.partitionId(),
                timestamp(now), traceId);
    }

    private V2GateState lockV2Gate(CheckpointKey key) {
        return jdbc.query("""
                select c.source_version, c.source_watermark,
                       c.replay_started_at_zero,
                       c.reconciliation_status,
                       c.reconciliation_watermark,
                       c.reconciliation_snapshot_id,
                       c.reconciliation_snapshot_source_version,
                       c.reconciliation_expected_count,
                       c.reconciliation_actual_count,
                       c.reconciliation_expected_digest,
                       c.reconciliation_actual_digest,
                       c.reconciliation_expected_lineage_count,
                       c.reconciliation_actual_lineage_count,
                       c.reconciliation_expected_lineage_digest,
                       c.reconciliation_actual_lineage_digest,
                       c.reconciliation_lineage_conflicts,
                       c.reconciliation_live_source_version,
                       c.reconciliation_live_watermark,
                       c.invalidation_materialized,
                       c.invalidation_materialized_count, c.active,
                       (select count(*)
                          from identity_access
                               .ia_responsibility_v2_shadow_invalidation_fact f
                         where f.source_id=c.source_id
                           and f.feed_id=c.feed_id
                           and f.partition_id=c.partition_id) shadow_fact_count
                  from identity_access
                       .ia_responsibility_v2_shadow_checkpoint c
                 where c.source_id=? and c.feed_id=? and c.partition_id=?
                   and c.consumer_projection='responsibility'
                 for update
                """,
                (rs, row) -> new V2GateState(
                        rs.getLong("source_version"),
                        rs.getLong("source_watermark"),
                        rs.getBoolean("replay_started_at_zero"),
                        rs.getString("reconciliation_status"),
                        rs.getLong("reconciliation_watermark"),
                        rs.getObject(
                                "reconciliation_snapshot_id", UUID.class),
                        rs.getLong(
                                "reconciliation_snapshot_source_version"),
                        rs.getLong("reconciliation_expected_count"),
                        rs.getLong("reconciliation_actual_count"),
                        rs.getString("reconciliation_expected_digest"),
                        rs.getString("reconciliation_actual_digest"),
                        rs.getLong(
                                "reconciliation_expected_lineage_count"),
                        rs.getLong(
                                "reconciliation_actual_lineage_count"),
                        rs.getString(
                                "reconciliation_expected_lineage_digest"),
                        rs.getString(
                                "reconciliation_actual_lineage_digest"),
                        rs.getLong("reconciliation_lineage_conflicts"),
                        rs.getLong(
                                "reconciliation_live_source_version"),
                        rs.getLong("reconciliation_live_watermark"),
                        rs.getBoolean("invalidation_materialized"),
                        rs.getLong("invalidation_materialized_count"),
                        rs.getLong("shadow_fact_count"),
                        rs.getBoolean("active")),
                key.sourceId(), key.feedId(), key.partitionId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IdentitySyncException(
                        "RESPONSIBILITY_V2_SHADOW_UNAVAILABLE"));
    }

    private LiveProjectionHead lockLiveProjectionHead(
            CheckpointKey key) {
        return jdbc.query("""
                select source_version, source_watermark
                  from identity_access.ia_identity_sync_checkpoint
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                 for update
                """,
                (rs, row) -> new LiveProjectionHead(
                        rs.getLong("source_version"),
                        rs.getLong("source_watermark")),
                key.sourceId(), key.feedId(), key.partitionId())
                .stream()
                .findFirst()
                .orElse(new LiveProjectionHead(0, 0));
    }

    private void appendV2Snapshot(
            ResponsibilityFullSnapshot snapshot,
            Instant recordedAt) {
        int inserted = jdbc.update("""
                insert into identity_access
                     .ia_responsibility_v2_reconciliation_snapshot (
                  snapshot_id, source_id, feed_id, partition_id,
                  consumer_projection, schema_version, contract_version,
                  business_date, cutoff_at, source_version,
                  through_watermark, supporting_identity_org_watermarks,
                  expected_count, canonical_digest, lineage_count,
                  canonical_lineage_digest, envelope_digest,
                  signature_digest, recorded_at, trace_id,
                  retention_effective_at, expires_at)
                values (?, ?, ?, ?, 'responsibility', ?, ?, ?, ?, ?, ?,
                        cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (snapshot_id) do nothing
                """,
                snapshot.snapshotId(), snapshot.key().sourceId(),
                snapshot.key().feedId(), snapshot.key().partitionId(),
                snapshot.schemaVersion(), snapshot.contractVersion(),
                snapshot.businessDate(), timestamp(snapshot.cutoffAt()),
                snapshot.sourceVersion(), snapshot.throughWatermark(),
                dependencyJson(
                        snapshot.supportingIdentityOrgWatermarks()),
                snapshot.expectedCount(), snapshot.canonicalDigest(),
                snapshot.lineageCount(),
                snapshot.canonicalLineageDigest(),
                snapshot.envelopeDigest(), snapshot.signatureDigest(),
                timestamp(recordedAt), snapshot.traceId(),
                timestamp(recordedAt),
                timestamp(recordedAt.plus(Duration.ofDays(365))));
        if (inserted == 0 && !v2SnapshotHeaderMatches(snapshot)) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_RECONCILIATION_SNAPSHOT_CONFLICT");
        }
        for (var entry : snapshot.entries()) {
            jdbc.update("""
                    insert into identity_access
                         .ia_responsibility_v2_snapshot_entry (
                      snapshot_id, relation_ref_token,
                      student_equivalence_digest, record_version,
                      payload_digest)
                    values (?, ?, ?, ?, ?)
                    on conflict do nothing
                    """,
                    snapshot.snapshotId(), entry.relationRefToken(),
                    entry.studentSourceRefDigest(), entry.recordVersion(),
                    entry.payloadDigest());
        }
        for (var lineage : snapshot.lineageManifests()) {
            jdbc.update("""
                    insert into identity_access
                         .ia_responsibility_v2_snapshot_lineage (
                      snapshot_id, relation_ref_token, lineage_id,
                      root_event_id, head_event_id, event_count,
                      canonical_chain_digest)
                    values (?, ?, ?, ?, ?, ?, ?)
                    on conflict do nothing
                    """,
                    snapshot.snapshotId(), lineage.relationRefToken(),
                    lineage.lineageId().value(), lineage.rootEventId(),
                    lineage.headEventId(), lineage.eventCount(),
                    lineage.canonicalChainDigest());
        }
        Long entryCount = jdbc.queryForObject("""
                select count(*)
                  from identity_access.ia_responsibility_v2_snapshot_entry
                 where snapshot_id=?
                """, Long.class, snapshot.snapshotId());
        Long lineageCount = jdbc.queryForObject("""
                select count(*)
                  from identity_access.ia_responsibility_v2_snapshot_lineage
                 where snapshot_id=?
                """, Long.class, snapshot.snapshotId());
        if (entryCount == null
                || entryCount != snapshot.expectedCount()
                || lineageCount == null
                || lineageCount != snapshot.lineageCount()) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_RECONCILIATION_SNAPSHOT_CONFLICT");
        }
    }

    private boolean v2SnapshotHeaderMatches(
            ResponsibilityFullSnapshot snapshot) {
        Long count = jdbc.queryForObject("""
                select count(*)
                  from identity_access
                       .ia_responsibility_v2_reconciliation_snapshot
                 where snapshot_id=? and source_id=? and feed_id=?
                   and partition_id=? and consumer_projection='responsibility'
                   and schema_version=? and contract_version=?
                   and business_date=? and cutoff_at=?
                   and source_version=? and through_watermark=?
                   and supporting_identity_org_watermarks=cast(? as jsonb)
                   and expected_count=? and canonical_digest=?
                   and lineage_count=? and canonical_lineage_digest=?
                   and envelope_digest=? and signature_digest=?
                   and trace_id=?
                """,
                Long.class,
                snapshot.snapshotId(), snapshot.key().sourceId(),
                snapshot.key().feedId(), snapshot.key().partitionId(),
                snapshot.schemaVersion(), snapshot.contractVersion(),
                snapshot.businessDate(), timestamp(snapshot.cutoffAt()),
                snapshot.sourceVersion(), snapshot.throughWatermark(),
                dependencyJson(
                        snapshot.supportingIdentityOrgWatermarks()),
                snapshot.expectedCount(), snapshot.canonicalDigest(),
                snapshot.lineageCount(),
                snapshot.canonicalLineageDigest(),
                snapshot.envelopeDigest(), snapshot.signatureDigest(),
                snapshot.traceId());
        return count != null && count == 1;
    }

    private V2LineageFingerprint v2LineageFingerprint(
            CheckpointKey key) {
        List<V2LineageEvent> events = jdbc.query("""
                select relation_ref_token, lineage_id, event_id,
                       supersedes_id, aggregate_version, source_version,
                       source_watermark, record_version, payload_digest,
                       change_kind, reason_code, change_effective_at
                  from identity_access.ia_responsibility_v2_source_fact
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                 order by lineage_id, aggregate_version, event_id
                """,
                (rs, row) -> new V2LineageEvent(
                        rs.getString("relation_ref_token"),
                        rs.getString("lineage_id"),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("supersedes_id", UUID.class),
                        rs.getLong("aggregate_version"),
                        rs.getLong("source_version"),
                        rs.getLong("source_watermark"),
                        rs.getLong("record_version"),
                        rs.getString("payload_digest"),
                        rs.getString("change_kind"),
                        rs.getString("reason_code"),
                        instant(rs.getTimestamp("change_effective_at"))),
                key.sourceId(), key.feedId(), key.partitionId());
        Map<String, List<V2LineageEvent>> grouped =
                new java.util.LinkedHashMap<>();
        for (V2LineageEvent event : events) {
            grouped.computeIfAbsent(
                            event.lineageId(), ignored -> new ArrayList<>())
                    .add(event);
        }
        List<ResponsibilityV2LineageManifest> manifests =
                new ArrayList<>();
        long conflicts = 0;
        for (var lineage : grouped.entrySet()) {
            List<V2LineageEvent> chain = lineage.getValue();
            UUID previous = null;
            String relationToken = chain.getFirst().relationRefToken();
            List<ResponsibilityV2LineageDigestEvent> digestEvents =
                    new ArrayList<>();
            for (int index = 0; index < chain.size(); index++) {
                V2LineageEvent event = chain.get(index);
                if (event.aggregateVersion() != index + 1L
                        || !java.util.Objects.equals(
                                previous, event.supersedesId())
                        || !relationToken.equals(
                                event.relationRefToken())) {
                    conflicts++;
                }
                digestEvents.add(
                        new ResponsibilityV2LineageDigestEvent(
                                event.aggregateVersion(),
                                event.eventId(),
                                event.supersedesId(),
                                event.sourceVersion(),
                                event.sourceWatermark(),
                                event.recordVersion(),
                                event.payloadDigest(),
                                event.changeKind(),
                                event.reasonCode(),
                                event.effectiveAt()));
                previous = event.eventId();
            }
            manifests.add(new ResponsibilityV2LineageManifest(
                    relationToken,
                    new AccessInvalidationLineageId(lineage.getKey()),
                    chain.getFirst().eventId(),
                    chain.getLast().eventId(),
                    chain.size(),
                    ResponsibilityV2LineageDigest.digest(
                            digestEvents)));
        }
        Long headConflicts = jdbc.queryForObject("""
                select count(*)
                  from identity_access
                       .ia_responsibility_v2_shadow_lineage_head head
                  left join identity_access
                       .ia_responsibility_v2_shadow_current current
                    on current.source_id=head.source_id
                   and current.feed_id=head.feed_id
                   and current.partition_id=head.partition_id
                   and current.relation_ref_token=head.relation_ref_token
                   and current.access_lineage_id=head.lineage_id
                   and current.access_event_id=head.current_event_id
                 where head.source_id=? and head.feed_id=?
                   and head.partition_id=?
                   and (current.relation_ref_token is null
                        or head.current_version<>(
                            select count(*)
                              from identity_access
                                   .ia_responsibility_v2_source_fact fact
                             where fact.source_id=head.source_id
                               and fact.feed_id=head.feed_id
                               and fact.partition_id=head.partition_id
                               and fact.lineage_id=head.lineage_id))
                """,
                Long.class, key.sourceId(), key.feedId(),
                key.partitionId());
        return new V2LineageFingerprint(
                List.copyOf(manifests),
                conflicts + (headConflicts == null
                        ? Long.MAX_VALUE : headConflicts));
    }

    private long lineageBindingConflicts(CheckpointKey key) {
        Long count = jdbc.queryForObject("""
                select count(*)
                  from identity_access
                       .ia_responsibility_v2_shadow_current current
                  left join identity_access
                       .ia_responsibility_lineage_binding binding
                    on binding.source_id=current.source_id
                   and binding.relation_ref_token=
                       current.relation_ref_token
                   and binding.lineage_id=current.access_lineage_id
                 where current.source_id=? and current.feed_id=?
                   and current.partition_id=?
                   and binding.relation_ref_token is null
                """,
                Long.class, key.sourceId(), key.feedId(),
                key.partitionId());
        return count == null ? Long.MAX_VALUE : count;
    }

    /**
     * Advances the already-active V2 live projection without requiring the
     * routine sync principal to own the destructive cutover privilege.
     * Revocations remain explicit inactive rows, so a successor batch only
     * needs to upsert shadow rows whose aggregate version advanced.
     */
    private void upsertLiveProjectionFromV2(CheckpointKey key) {
        jdbc.update("""
                insert into identity_access.ia_responsibility_current (
                  relation_id, source_id, feed_id, partition_id,
                  consumer_projection, relation_ref_token,
                  student_ref_purpose, student_ref_key_version,
                  student_ref_token, student_ref_digest,
                  student_equivalence_digest,
                  counselor_account_ref_digest,
                  college_organization_ref_digest,
                  counselor_account_id, college_organization_id,
                  responsibility_type, relation_status,
                  recipient_validity, recipient_reason_code,
                  quality_gate_status, effective_from, effective_to,
                  source_version, source_watermark, record_version,
                  aggregate_version, payload_digest, applied_at, trace_id,
                  retention_effective_at, access_lineage_id,
                  access_event_id, access_change_kind,
                  access_reason_code, access_effective_at,
                  access_supersedes_id)
                select relation_id, source_id, feed_id, partition_id,
                       consumer_projection, relation_ref_token,
                       student_ref_purpose, student_ref_key_version,
                       student_ref_token, student_ref_digest,
                       student_equivalence_digest,
                       counselor_account_ref_digest,
                       college_organization_ref_digest,
                       counselor_account_id, college_organization_id,
                       responsibility_type, relation_status,
                       recipient_validity, recipient_reason_code,
                       quality_gate_status, effective_from, effective_to,
                       source_version, source_watermark, record_version,
                       aggregate_version, payload_digest, applied_at, trace_id,
                       retention_effective_at, access_lineage_id,
                       access_event_id, access_change_kind,
                       access_reason_code, access_effective_at,
                       access_supersedes_id
                  from identity_access
                       .ia_responsibility_v2_shadow_current
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                on conflict (relation_ref_token) do update set
                  relation_id=excluded.relation_id,
                  feed_id=excluded.feed_id,
                  partition_id=excluded.partition_id,
                  student_ref_purpose=excluded.student_ref_purpose,
                  student_ref_key_version=excluded.student_ref_key_version,
                  student_ref_token=excluded.student_ref_token,
                  student_ref_digest=excluded.student_ref_digest,
                  student_equivalence_digest=
                      excluded.student_equivalence_digest,
                  counselor_account_ref_digest=
                      excluded.counselor_account_ref_digest,
                  college_organization_ref_digest=
                      excluded.college_organization_ref_digest,
                  counselor_account_id=excluded.counselor_account_id,
                  college_organization_id=excluded.college_organization_id,
                  responsibility_type=excluded.responsibility_type,
                  relation_status=excluded.relation_status,
                  recipient_validity=excluded.recipient_validity,
                  recipient_reason_code=excluded.recipient_reason_code,
                  quality_gate_status=excluded.quality_gate_status,
                  effective_from=excluded.effective_from,
                  effective_to=excluded.effective_to,
                  source_version=excluded.source_version,
                  source_watermark=excluded.source_watermark,
                  record_version=excluded.record_version,
                  aggregate_version=excluded.aggregate_version,
                  payload_digest=excluded.payload_digest,
                  applied_at=excluded.applied_at,
                  trace_id=excluded.trace_id,
                  retention_effective_at=excluded.retention_effective_at,
                  access_lineage_id=excluded.access_lineage_id,
                  access_event_id=excluded.access_event_id,
                  access_change_kind=excluded.access_change_kind,
                  access_reason_code=excluded.access_reason_code,
                  access_effective_at=excluded.access_effective_at,
                  access_supersedes_id=excluded.access_supersedes_id
                where excluded.aggregate_version >
                      identity_access
                        .ia_responsibility_current.aggregate_version
                """, key.sourceId(), key.feedId(), key.partitionId());
    }

    private void replaceLiveProjectionFromV2(CheckpointKey key) {
        jdbc.update("""
                delete from identity_access.ia_responsibility_current
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                """, key.sourceId(), key.feedId(), key.partitionId());
        jdbc.update("""
                insert into identity_access.ia_responsibility_current (
                  relation_id, source_id, feed_id, partition_id,
                  consumer_projection, relation_ref_token,
                  student_ref_purpose, student_ref_key_version,
                  student_ref_token, student_ref_digest,
                  student_equivalence_digest,
                  counselor_account_ref_digest,
                  college_organization_ref_digest,
                  counselor_account_id, college_organization_id,
                  responsibility_type, relation_status,
                  recipient_validity, recipient_reason_code,
                  quality_gate_status, effective_from, effective_to,
                  source_version, source_watermark, record_version,
                  aggregate_version, payload_digest, applied_at, trace_id,
                  retention_effective_at, access_lineage_id,
                  access_event_id, access_change_kind,
                  access_reason_code, access_effective_at,
                  access_supersedes_id)
                select relation_id, source_id, feed_id, partition_id,
                       consumer_projection, relation_ref_token,
                       student_ref_purpose, student_ref_key_version,
                       student_ref_token, student_ref_digest,
                       student_equivalence_digest,
                       counselor_account_ref_digest,
                       college_organization_ref_digest,
                       counselor_account_id, college_organization_id,
                       responsibility_type, relation_status,
                       recipient_validity, recipient_reason_code,
                       quality_gate_status, effective_from, effective_to,
                       source_version, source_watermark, record_version,
                       aggregate_version, payload_digest, applied_at, trace_id,
                       retention_effective_at, access_lineage_id,
                       access_event_id, access_change_kind,
                       access_reason_code, access_effective_at,
                       access_supersedes_id
                  from identity_access
                       .ia_responsibility_v2_shadow_current
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                """, key.sourceId(), key.feedId(), key.partitionId());
    }

    private void upsertLiveCheckpointFromV2(
            CheckpointKey key, Instant appliedAt, String traceId) {
        jdbc.update("""
                insert into identity_access.ia_identity_sync_checkpoint (
                  source_id, feed_id, partition_id, consumer_projection,
                  source_version, source_watermark, aggregate_version,
                  last_successful_at, health, freshness, updated_at,
                  trace_id, retention_effective_at)
                select source_id, feed_id, partition_id,
                       consumer_projection, source_version,
                       source_watermark, aggregate_version,
                       last_successful_at, 'healthy', 'fresh', ?, ?, ?
                  from identity_access
                       .ia_responsibility_v2_shadow_checkpoint
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                on conflict (source_id, feed_id, partition_id,
                             consumer_projection)
                do update set source_version=excluded.source_version,
                              source_watermark=excluded.source_watermark,
                              aggregate_version=excluded.aggregate_version,
                              last_successful_at=
                                  excluded.last_successful_at,
                              health='healthy', freshness='fresh',
                              updated_at=excluded.updated_at,
                              trace_id=excluded.trace_id,
                              retention_effective_at=
                                  excluded.retention_effective_at
                """,
                timestamp(appliedAt), traceId, timestamp(appliedAt),
                key.sourceId(), key.feedId(), key.partitionId());
    }

    static AuthoritativeResponsibilityRelation mapRelation(
            ResultSet rs) throws SQLException {
        UUID relationId = rs.getObject("relation_id", UUID.class);
        String sourceId = rs.getString("source_id");
        String relationRefToken = rs.getString("relation_ref_token");
        var student = new ResponsibilityStudentSourceReference(
                rs.getString("student_ref_purpose"),
                rs.getString("student_ref_key_version"),
                rs.getString("student_ref_token"),
                rs.getString("student_ref_digest"),
                rs.getString("student_equivalence_digest"));
        String counselorDigest =
                rs.getString("counselor_account_ref_digest");
        String collegeDigest =
                rs.getString("college_organization_ref_digest");
        ResponsibilityType type = ResponsibilityType.valueOf(
                rs.getString("responsibility_type").toUpperCase());
        ResponsibilityStatus status = ResponsibilityStatus.valueOf(
                rs.getString("relation_status").toUpperCase());
        var interval = new EffectiveInterval(
                instant(rs.getTimestamp("effective_from")),
                instant(rs.getTimestamp("effective_to")));
        long sourceVersion = rs.getLong("source_version");
        long sourceWatermark = rs.getLong("source_watermark");
        long recordVersion = rs.getLong("record_version");
        long aggregateVersion = rs.getLong("aggregate_version");
        String payloadDigest = rs.getString("payload_digest");
        String changeKind = rs.getString("access_change_kind");
        String reasonCode = rs.getString("access_reason_code");
        Timestamp effectiveAt = rs.getTimestamp("access_effective_at");
        String lineageId = rs.getString("access_lineage_id");
        UUID supersedesId =
                rs.getObject("access_supersedes_id", UUID.class);
        if (changeKind == null
                && reasonCode == null
                && effectiveAt == null
                && lineageId == null
                && supersedesId == null) {
            return new AuthoritativeResponsibilityRelation(
                    relationId, sourceId, relationRefToken, student,
                    counselorDigest, collegeDigest, type, status, interval,
                    sourceVersion, sourceWatermark, recordVersion,
                    aggregateVersion, payloadDigest);
        }
        return new AuthoritativeResponsibilityRelation(
                relationId, sourceId, relationRefToken, student,
                counselorDigest, collegeDigest, type, status, interval,
                sourceVersion, sourceWatermark, recordVersion,
                aggregateVersion, payloadDigest,
                changeKind == null
                        ? null
                        : AccessInvalidationChangeKind.valueOf(
                                changeKind.toUpperCase().replace('-', '_')),
                reasonCode == null
                        ? null
                        : AccessInvalidationReason.valueOf(reasonCode),
                instant(effectiveAt),
                lineageId == null
                        ? null
                        : new AccessInvalidationLineageId(lineageId),
                supersedesId);
    }

    private static AccessInvalidationFact mapShadowInvalidationFact(
            ResultSet rs) throws SQLException {
        return new AccessInvalidationFact(
                rs.getObject("event_id", UUID.class),
                rs.getString("trace_id"),
                AccessInvalidationChangeKind.valueOf(
                        rs.getString("change_kind")
                                .toUpperCase().replace('-', '_')),
                AccessInvalidationReason.valueOf(rs.getString("reason_code")),
                new AccessInvalidationLineageId(rs.getString("lineage_id")),
                rs.getObject("supersedes_id", UUID.class),
                null,
                AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE,
                rs.getString("lineage_id"),
                rs.getLong("aggregate_version"),
                rs.getLong("aggregate_version"),
                instant(rs.getTimestamp("effective_at")),
                new AccessInvalidationSourceVector(
                        rs.getString("source_id"),
                        rs.getLong("source_version"),
                        rs.getLong("source_watermark"),
                        dependencyWatermarks(
                                rs.getString("dependency_vector"),
                                rs.getString("feed_id"),
                                rs.getString("partition_id"),
                                rs.getLong("source_watermark"))),
                new AccessInvalidationSubjectSnapshot(
                        rs.getString("subject_token"),
                        rs.getString("scope_token"),
                        rs.getString("object_digest"),
                        "ACCESS-INVALIDATION-TOKENIZATION-1.0.0"),
                new AccessInvalidationAuthorizationSnapshot(
                        AccessInvalidationAuthorizationState.valueOf(
                                rs.getString("authorization_state")
                                        .toUpperCase()),
                        rs.getBoolean("account_active"),
                        rs.getBoolean("r1_employment_valid"),
                        rs.getBoolean("college_active"),
                        rs.getBoolean("relation_effective"),
                        rs.getString("policy_version")),
                new AccessInvalidationRetention(
                        "restricted", "RS-1.0.0",
                        instant(rs.getTimestamp("retain_until")),
                        rs.getBoolean("legal_hold")),
                rs.getString("source_payload_digest"));
    }

    private static List<AccessInvalidationDependencyWatermark>
            dependencyWatermarks(
                    String json,
                    String sourceFeed,
                    String sourcePartition,
                    long sourceWatermark) {
        List<AccessInvalidationDependencyWatermark> values =
                new ArrayList<>();
        String body = json == null ? "" : json.trim();
        if (body.startsWith("{") && body.endsWith("}")) {
            body = body.substring(1, body.length() - 1);
        }
        if (!body.isBlank()) {
            for (String entry : body.split(",")) {
                String[] pair = entry.split(":", 2);
                String route = pair[0].trim().replace("\"", "");
                String[] parts = route.split("\\|", -1);
                values.add(new AccessInvalidationDependencyWatermark(
                        parts[0], parts[1],
                        Long.parseLong(pair[1].trim())));
            }
        }
        values.add(new AccessInvalidationDependencyWatermark(
                sourceFeed, sourcePartition, sourceWatermark));
        return List.copyOf(values);
    }

    private static void requireV2(NormalizedResponsibilityBatch batch) {
        if (!"RESPONSIBILITY-AUTHORITY-2.0.0".equals(
                batch.contractVersion())) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_REPLAY_REQUIRED");
        }
    }

    /**
     * Serializes every legacy write with reconciliation/cutover on the V2
     * gate. A request validated just before activation therefore cannot
     * overwrite the activated projection afterwards.
     */
    private void requireV1WriteAllowed(
            NormalizedResponsibilityBatch batch) {
        if (!"RESPONSIBILITY-AUTHORITY-1.0.0".equals(
                batch.contractVersion())) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_CONTRACT_UNAPPROVED");
        }
        boolean active = jdbc.query("""
                select active
                  from identity_access
                       .ia_responsibility_v2_shadow_checkpoint
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                 for update
                """,
                (rs, row) -> rs.getBoolean("active"),
                batch.key().sourceId(), batch.key().feedId(),
                batch.key().partitionId())
                .stream()
                .findFirst()
                .orElse(false);
        if (active) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_REPLAY_REQUIRED");
        }
    }

    private static String wire(Enum<?> value) {
        return value.name().toLowerCase().replace('_', '-');
    }

    @Override
    public void reject(IdentitySyncRejection rejection) {
        jdbc.update("""
                insert into identity_access.ia_identity_rejected_record (
                  rejection_id, batch_id, source_id, feed_id, partition_id,
                  consumer_projection, source_version, source_watermark,
                  payload_digest, reason_code, replayable, job_id,
                  attempt_no, trace_id, rejected_at,
                  retention_effective_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rejection.rejectionId(),
                rejection.batchId(),
                rejection.key().sourceId(),
                rejection.key().feedId(),
                rejection.key().partitionId(),
                rejection.key().consumerProjection(),
                rejection.sourceVersion(),
                rejection.sourceWatermark(),
                rejection.payloadDigest(),
                rejection.reasonCode(),
                rejection.replayable(),
                rejection.jobId(),
                rejection.attemptNo(),
                rejection.traceId(),
                timestamp(rejection.rejectedAt()),
                timestamp(rejection.rejectedAt()),
                timestamp(rejection.rejectedAt()
                        .plus(Duration.ofDays(180))));
    }

    @Override
    public boolean leaseIsCurrent(IdentityLease lease) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from identity_access.ia_identity_sync_lease
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                   and job_id=? and attempt_no=? and fencing_token=?
                   and lease_expires_at>clock_timestamp()
                """,
                Integer.class,
                lease.key().sourceId(),
                lease.key().feedId(),
                lease.key().partitionId(),
                lease.jobId(),
                lease.attemptNo(),
                lease.fencingToken());
        return count != null && count == 1;
    }

    private void insertFact(
            NormalizedResponsibilityBatch batch,
            AuthoritativeResponsibilityRelation relation,
            ResponsibilityScopeProjectionUpdate scope,
            long aggregateVersion,
            Instant appliedAt) {
        var student = relation.studentSourceReference();
        ResponsibilityRecipientDecision decision = scope.decision();
        boolean recipientMapped =
                decision.validity()
                                == ResponsibilityRecipientValidity.VALID
                        && relation.responsibilityType()
                                == ResponsibilityType.PRIMARY
                        && relation.status()
                                == ResponsibilityStatus.ACTIVE
                        && relation.effectiveInterval()
                                .contains(appliedAt);
        int inserted = jdbc.update("""
                insert into identity_access.ia_responsibility_source_fact (
                  fact_id, batch_id, event_id, source_id, feed_id,
                  partition_id, consumer_projection, relation_ref_token,
                  student_ref_purpose, student_ref_key_version,
                  student_ref_token, student_ref_digest,
                  student_equivalence_digest,
                  counselor_account_ref_digest,
                  college_organization_ref_digest, responsibility_type,
                  relation_status, effective_from, effective_to,
                  source_version, source_watermark, record_version,
                  aggregate_version, payload_digest,
                  supporting_identity_org_watermarks,
                  recipient_validity, recipient_reason_code,
                  recipient_mapped, applied_at, trace_id,
                  consumer_watermark, retention_effective_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?,
                        ?, ?, ?)
                on conflict do nothing
                """,
                relation.relationId(),
                batch.batchId(),
                relation.relationId(),
                batch.key().sourceId(),
                batch.key().feedId(),
                batch.key().partitionId(),
                batch.key().consumerProjection(),
                relation.relationRefToken(),
                student.purposeCode(),
                student.keyVersion(),
                student.tokenValue(),
                student.digest(),
                student.equivalenceDomain(),
                relation.counselorAccountRefDigest(),
                relation.collegeOrganizationRefDigest(),
                relation.responsibilityType().name().toLowerCase(),
                relation.status().name().toLowerCase(),
                timestamp(relation.effectiveInterval().effectiveFrom()),
                timestamp(relation.effectiveInterval().effectiveTo()),
                batch.sourceVersion(),
                batch.toWatermark(),
                relation.recordVersion(),
                aggregateVersion,
                relation.payloadDigest(),
                dependencyJson(
                        batch.supportingIdentityOrgWatermarks()),
                decision.validity()
                        .name()
                        .toLowerCase()
                        .replace('_', '-'),
                decision.reason().code(),
                recipientMapped,
                timestamp(appliedAt),
                batch.traceId(),
                batch.toWatermark(),
                timestamp(appliedAt),
                timestamp(appliedAt.plus(Duration.ofDays(365))));
        if (inserted != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_IDEMPOTENCY_CONFLICT");
        }
    }

    private void upsertUnevaluatedCurrent(
            NormalizedResponsibilityBatch batch,
            AuthoritativeResponsibilityRelation relation,
            long aggregateVersion,
            Instant appliedAt) {
        var student = relation.studentSourceReference();
        int updated = jdbc.update("""
                insert into identity_access.ia_responsibility_current (
                  relation_id, source_id, feed_id, partition_id,
                  consumer_projection, relation_ref_token,
                  student_ref_purpose, student_ref_key_version,
                  student_ref_token, student_ref_digest,
                  student_equivalence_digest,
                  counselor_account_ref_digest,
                  college_organization_ref_digest,
                  counselor_account_id, college_organization_id,
                  responsibility_type, relation_status,
                  recipient_validity, recipient_reason_code,
                  quality_gate_status, effective_from, effective_to,
                  source_version, source_watermark, record_version,
                  aggregate_version, payload_digest, applied_at, trace_id,
                  retention_effective_at, access_lineage_id,
                  access_event_id, access_change_kind,
                  access_reason_code, access_effective_at,
                  access_supersedes_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, null,
                        ?, ?, 'dependency-unavailable',
                        'RESPONSIBILITY_RECIPIENT_NOT_EVALUATED',
                        'blocked', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (relation_ref_token) do update set
                  relation_id=excluded.relation_id,
                  feed_id=excluded.feed_id,
                  partition_id=excluded.partition_id,
                  student_ref_purpose=excluded.student_ref_purpose,
                  student_ref_key_version=excluded.student_ref_key_version,
                  student_ref_token=excluded.student_ref_token,
                  student_ref_digest=excluded.student_ref_digest,
                  student_equivalence_digest=
                      excluded.student_equivalence_digest,
                  counselor_account_ref_digest=
                      excluded.counselor_account_ref_digest,
                  college_organization_ref_digest=
                      excluded.college_organization_ref_digest,
                  counselor_account_id=null,
                  college_organization_id=null,
                  responsibility_type=excluded.responsibility_type,
                  relation_status=excluded.relation_status,
                  recipient_validity='dependency-unavailable',
                  recipient_reason_code=
                      'RESPONSIBILITY_RECIPIENT_NOT_EVALUATED',
                  quality_gate_status='blocked',
                  effective_from=excluded.effective_from,
                  effective_to=excluded.effective_to,
                  source_version=excluded.source_version,
                  source_watermark=excluded.source_watermark,
                  record_version=excluded.record_version,
                  aggregate_version=excluded.aggregate_version,
                  payload_digest=excluded.payload_digest,
                  applied_at=excluded.applied_at,
                  trace_id=excluded.trace_id,
                  retention_effective_at=excluded.retention_effective_at,
                  access_lineage_id=excluded.access_lineage_id,
                  access_event_id=excluded.access_event_id,
                  access_change_kind=excluded.access_change_kind,
                  access_reason_code=excluded.access_reason_code,
                  access_effective_at=excluded.access_effective_at,
                  access_supersedes_id=excluded.access_supersedes_id
                where excluded.record_version >
                    identity_access.ia_responsibility_current.record_version
                """,
                relation.relationId(),
                batch.key().sourceId(),
                batch.key().feedId(),
                batch.key().partitionId(),
                batch.key().consumerProjection(),
                relation.relationRefToken(),
                student.purposeCode(),
                student.keyVersion(),
                student.tokenValue(),
                student.digest(),
                student.equivalenceDomain(),
                relation.counselorAccountRefDigest(),
                relation.collegeOrganizationRefDigest(),
                relation.responsibilityType().name().toLowerCase(),
                relation.status().name().toLowerCase(),
                timestamp(relation.effectiveInterval().effectiveFrom()),
                timestamp(relation.effectiveInterval().effectiveTo()),
                batch.sourceVersion(),
                batch.toWatermark(),
                relation.recordVersion(),
                aggregateVersion,
                relation.payloadDigest(),
                timestamp(appliedAt),
                batch.traceId(),
                timestamp(appliedAt),
                relation.lineageId() == null
                        ? null
                        : relation.lineageId().value(),
                relation.hasInvalidationMetadata()
                        ? relation.relationId()
                        : null,
                relation.changeKind() == null
                        ? null
                        : relation.changeKind()
                                .name()
                                .toLowerCase()
                                .replace('_', '-'),
                relation.changeReason() == null
                        ? null
                        : relation.changeReason().name(),
                timestamp(relation.changeEffectiveAt()),
                relation.supersedesId());
        if (updated != 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_VERSION_STALE");
        }
    }

    private void ensureCheckpointExists(
            CheckpointKey key, Instant now, String traceId) {
        jdbc.update("""
                insert into identity_access.ia_identity_sync_checkpoint (
                  source_id, feed_id, partition_id, consumer_projection,
                  source_version, source_watermark, aggregate_version,
                  health, freshness, updated_at, trace_id,
                  retention_effective_at)
                values (?, ?, ?, ?, 0, 0, 0, 'degraded', 'stale', ?, ?, ?)
                on conflict (
                  source_id, feed_id, partition_id, consumer_projection)
                  do nothing
                """,
                key.sourceId(),
                key.feedId(),
                key.partitionId(),
                key.consumerProjection(),
                timestamp(now),
                traceId,
                timestamp(now));
    }

    private List<ResponsibilityExceptionAuditTransition>
            applyScopeDecision(
            NormalizedResponsibilityBatch batch,
            ResponsibilityScopeProjectionUpdate update,
            long aggregateVersion,
            Instant appliedAt) {
        ResponsibilityRecipientDecision decision = update.decision();
        String validity = decision.validity()
                .name()
                .toLowerCase()
                .replace('_', '-');
        int updated = jdbc.update("""
                update identity_access.ia_responsibility_current
                   set counselor_account_id=?,
                       college_organization_id=?,
                       recipient_validity=?,
                       recipient_reason_code=?,
                       quality_gate_status=?,
                       aggregate_version=?,
                       applied_at=?,
                       trace_id=?,
                       retention_effective_at=?
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection='responsibility'
                   and student_equivalence_digest=?
                """,
                decision.counselorAccountId(),
                decision.collegeOrganizationId(),
                validity,
                decision.reason().code(),
                decision.validity()
                                == ResponsibilityRecipientValidity.VALID
                        ? "trusted"
                        : "blocked",
                aggregateVersion,
                timestamp(appliedAt),
                batch.traceId(),
                timestamp(appliedAt),
                batch.key().sourceId(),
                batch.key().feedId(),
                batch.key().partitionId(),
                update.studentSourceRefDigest());
        if (updated < 1) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SCOPE_PROJECTION_MISSING");
        }
        if (decision.validity()
                == ResponsibilityRecipientValidity.DEPENDENCY_UNAVAILABLE) {
            return List.of();
        }
        if (decision.validity()
                == ResponsibilityRecipientValidity.VALID) {
            return resolveIncrementalExceptions(
                    batch,
                    update.studentSourceRefDigest(),
                    aggregateVersion,
                    appliedAt);
        }
        List<ResponsibilityExceptionAuditTransition> transitions =
                new ArrayList<>();
        update.resultingRelations().stream()
                .map(AuthoritativeResponsibilityRelation
                        ::collegeOrganizationRefDigest)
                .distinct()
                .sorted()
                .forEach(collegeDigest ->
                        openOrUpdateException(
                                        batch,
                                        collegeDigest,
                                        update.studentSourceRefDigest(),
                                        decision.reason().code(),
                                        aggregateVersion,
                                        appliedAt)
                                .ifPresent(transitions::add));
        return List.copyOf(transitions);
    }

    private Optional<ResponsibilityExceptionAuditTransition>
            openOrUpdateException(
            NormalizedResponsibilityBatch batch,
            String collegeDigest,
            String studentDigest,
            String reasonCode,
            long aggregateVersion,
            Instant appliedAt) {
        String businessKey = digest(
                "incremental\0"
                        + collegeDigest
                        + "\0"
                        + studentDigest);
        List<ExceptionState> existing = jdbc.query("""
                select exception_id, status, aggregate_version,
                       first_seen_at
                  from identity_access.ia_responsibility_exception_current
                 where business_key_digest=?
                 for update
                """,
                (rs, row) -> new ExceptionState(
                        rs.getObject("exception_id", UUID.class),
                        rs.getString("status"),
                        rs.getLong("aggregate_version"),
                        instant(rs.getTimestamp("first_seen_at"))),
                businessKey);
        UUID exceptionId = existing.isEmpty()
                ? UUID.fromString(UuidV7.generate(appliedAt))
                : existing.getFirst().exceptionId();
        long exceptionVersion = existing.isEmpty()
                ? 1
                : existing.getFirst().aggregateVersion() + 1;
        boolean opened = existing.isEmpty()
                || "opened".equals(exceptionEventType(
                        existing.getFirst().status()));
        String eventType = exceptionEventType(
                existing.isEmpty()
                        ? null
                        : existing.getFirst().status());
        Instant firstSeen = existing.isEmpty()
                ? appliedAt
                : existing.getFirst().firstSeenAt();
        jdbc.update("""
                insert into identity_access.ia_responsibility_exception_history (
                  exception_event_id, exception_id, business_key_digest,
                  college_organization_ref_digest, student_ref_digest,
                  event_type, reason_code, source_kind, source_version,
                  source_watermark, aggregate_version, occurred_at,
                  trace_id, consumer_watermark, retention_effective_at,
                  expires_at)
                values (?, ?, ?, ?, ?, ?, ?, 'incremental', ?, ?, ?, ?, ?,
                        ?, ?, ?)
                """,
                UUID.fromString(UuidV7.generate(appliedAt)),
                exceptionId,
                businessKey,
                collegeDigest,
                studentDigest,
                eventType,
                reasonCode,
                batch.sourceVersion(),
                batch.toWatermark(),
                exceptionVersion,
                timestamp(appliedAt),
                batch.traceId(),
                batch.toWatermark(),
                timestamp(appliedAt),
                timestamp(appliedAt.plus(Duration.ofDays(365))));
        jdbc.update("""
                insert into identity_access.ia_responsibility_exception_current (
                  exception_id, business_key_digest,
                  college_organization_ref_digest, student_ref_digest,
                  reason_code, source_kind, source_version,
                  source_watermark, first_seen_at, last_seen_at,
                  resolved_at, status, aggregate_version, trace_id,
                  retention_effective_at)
                values (?, ?, ?, ?, ?, 'incremental', ?, ?, ?, ?, null,
                        'open', ?, ?, ?)
                on conflict (business_key_digest) do update set
                  college_organization_ref_digest=
                      excluded.college_organization_ref_digest,
                  student_ref_digest=excluded.student_ref_digest,
                  reason_code=excluded.reason_code,
                  source_version=excluded.source_version,
                  source_watermark=excluded.source_watermark,
                  last_seen_at=excluded.last_seen_at,
                  resolved_at=null,
                  status='open',
                  aggregate_version=excluded.aggregate_version,
                  trace_id=excluded.trace_id,
                  retention_effective_at=
                      excluded.retention_effective_at
                where excluded.source_watermark >=
                    identity_access.ia_responsibility_exception_current
                      .source_watermark
                """,
                exceptionId,
                businessKey,
                collegeDigest,
                studentDigest,
                reasonCode,
                batch.sourceVersion(),
                batch.toWatermark(),
                timestamp(firstSeen),
                timestamp(appliedAt),
                exceptionVersion,
                batch.traceId(),
                timestamp(appliedAt));
        return opened
                ? Optional.of(
                        new ResponsibilityExceptionAuditTransition(
                                exceptionId,
                                "responsibility.exception.opened",
                                reasonCode,
                                exceptionVersion))
                : Optional.empty();
    }

    private List<ResponsibilityExceptionAuditTransition>
            resolveIncrementalExceptions(
            NormalizedResponsibilityBatch batch,
            String studentDigest,
            long aggregateVersion,
            Instant appliedAt) {
        List<OpenException> open = jdbc.query("""
                select exception_id, business_key_digest,
                       college_organization_ref_digest,
                       aggregate_version, first_seen_at
                  from identity_access.ia_responsibility_exception_current
                 where student_ref_digest=?
                   and source_kind='incremental'
                   and status='open'
                 for update
                """,
                (rs, row) -> new OpenException(
                        rs.getObject("exception_id", UUID.class),
                        rs.getString("business_key_digest"),
                        rs.getString(
                                "college_organization_ref_digest"),
                        rs.getLong("aggregate_version"),
                        instant(rs.getTimestamp("first_seen_at"))),
                studentDigest);
        List<ResponsibilityExceptionAuditTransition> transitions =
                new ArrayList<>();
        for (OpenException exception : open) {
            long exceptionVersion =
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
                            'RESPONSIBILITY_VALID', 'incremental',
                            ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.fromString(UuidV7.generate(appliedAt)),
                    exception.exceptionId(),
                    exception.businessKey(),
                    exception.collegeDigest(),
                    studentDigest,
                    batch.sourceVersion(),
                    batch.toWatermark(),
                    exceptionVersion,
                    timestamp(appliedAt),
                    batch.traceId(),
                    batch.toWatermark(),
                    timestamp(appliedAt),
                    timestamp(appliedAt.plus(
                            Duration.ofDays(365))));
            jdbc.update("""
                    update identity_access.ia_responsibility_exception_current
                       set reason_code='RESPONSIBILITY_VALID',
                           source_version=?, source_watermark=?,
                           last_seen_at=?, resolved_at=?, status='resolved',
                           aggregate_version=?, trace_id=?,
                           retention_effective_at=?
                     where exception_id=? and status='open'
                    """,
                    batch.sourceVersion(),
                    batch.toWatermark(),
                    timestamp(appliedAt),
                    timestamp(appliedAt),
                    exceptionVersion,
                    batch.traceId(),
                    timestamp(appliedAt),
                    exception.exceptionId());
            transitions.add(
                    new ResponsibilityExceptionAuditTransition(
                            exception.exceptionId(),
                            "responsibility.exception.resolved",
                            "RESPONSIBILITY_VALID",
                            exceptionVersion));
        }
        return List.copyOf(transitions);
    }

    private static String dependencyJson(Map<String, Long> values) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Long> entry :
                new TreeMap<>(values).entrySet()) {
            if (!first) {
                json.append(',');
            }
            json.append('"')
                    .append(entry.getKey())
                    .append("\":")
                    .append(entry.getValue());
            first = false;
        }
        return json.append('}').toString();
    }

    static String exceptionEventType(String currentStatus) {
        return currentStatus == null
                        || "resolved".equals(currentStatus)
                ? "opened"
                : "updated";
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

    private record ExceptionState(
            UUID exceptionId,
            String status,
            long aggregateVersion,
            Instant firstSeenAt) {}

    private record OpenException(
            UUID exceptionId,
            String businessKey,
            String collegeDigest,
            long aggregateVersion,
            Instant firstSeenAt) {}

    private record LineageBinding(
            String relationRefToken, String lineageId) {}

    private record ShadowLineageHead(UUID eventId, long version) {}

    private record V2ProjectionDigestEntry(
            String relationRefToken,
            String studentEquivalenceDigest,
            long recordVersion,
            String payloadDigest) {}

    private record V2LineageEvent(
            String relationRefToken,
            String lineageId,
            UUID eventId,
            UUID supersedesId,
            long aggregateVersion,
            long sourceVersion,
            long sourceWatermark,
            long recordVersion,
            String payloadDigest,
            String changeKind,
            String reasonCode,
            Instant effectiveAt) {}

    private record V2LineageFingerprint(
            List<ResponsibilityV2LineageManifest> manifests,
            long conflicts) {}

    private record LiveProjectionHead(
            long sourceVersion, long watermark) {}

    private record V2GateState(
            long sourceVersion,
            long watermark,
            boolean replayStartedAtZero,
            String reconciliationStatus,
            long reconciliationWatermark,
            UUID reconciliationSnapshotId,
            long reconciliationSnapshotSourceVersion,
            long expectedCount,
            long actualCount,
            String expectedDigest,
            String actualDigest,
            long expectedLineageCount,
            long actualLineageCount,
            String expectedLineageDigest,
            String actualLineageDigest,
            long lineageConflicts,
            long liveSourceVersion,
            long liveWatermark,
            boolean invalidationMaterialized,
            long invalidationMaterializedCount,
            long shadowFactCount,
            boolean active) {}
}
