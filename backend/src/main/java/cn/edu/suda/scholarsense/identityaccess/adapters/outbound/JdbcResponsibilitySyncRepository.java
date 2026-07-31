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
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityRecordState;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityScopeProjectionUpdate;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySyncRepository;
import cn.edu.suda.scholarsense.identityaccess.application.UuidV7;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientDecision;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientValidity;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStudentSourceReference;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityType;
import java.sql.Timestamp;
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
                       fact.payload_digest
                  from identity_access.ia_responsibility_current projection
                  join identity_access.ia_responsibility_source_fact fact
                    on fact.source_id=projection.source_id
                   and fact.relation_ref_token=
                       projection.relation_ref_token
                   and fact.record_version=projection.record_version
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
                        rs.getString("payload_digest")),
                key.sourceId(),
                key.feedId(),
                key.partitionId(),
                key.consumerProjection(),
                studentEquivalenceDigest);
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
                  aggregate_version, applied_at, trace_id,
                  retention_effective_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, null,
                        ?, ?, 'dependency-unavailable',
                        'RESPONSIBILITY_RECIPIENT_NOT_EVALUATED',
                        'blocked', ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                  applied_at=excluded.applied_at,
                  trace_id=excluded.trace_id,
                  retention_effective_at=excluded.retention_effective_at
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
                timestamp(appliedAt),
                batch.traceId(),
                timestamp(appliedAt));
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
}
