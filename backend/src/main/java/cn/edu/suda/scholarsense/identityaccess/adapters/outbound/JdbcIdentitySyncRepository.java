package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityCheckpoint;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuthorityReferencePort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityLease;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityProjectionFreshness;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityRecordKind;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityRecordState;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySloEvidence;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySloEvidencePort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourceHealth;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncRejection;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncRepository;
import cn.edu.suda.scholarsense.identityaccess.application.NormalizedIdentityBatch;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeAccount;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.identityaccess.domain.EmploymentRoleBinding;
import cn.edu.suda.scholarsense.identityaccess.domain.OrganizationNode;
import cn.edu.suda.scholarsense.identityaccess.domain.OrganizationType;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Parameterized identity-owned SQL. Call apply from the module transaction adapter. */
public final class JdbcIdentitySyncRepository
        implements IdentitySyncRepository, IdentitySloEvidencePort,
                IdentityAuthorityReferencePort {
    private static final Duration SUBJECT_BINDING_READ_WINDOW =
            Duration.ofHours(24);
    private final JdbcTemplate jdbc;

    public JdbcIdentitySyncRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IdentityCheckpoint> checkpoint(CheckpointKey key) {
        List<IdentityCheckpoint> values = jdbc.query("""
                select source_version, source_watermark, aggregate_version,
                       last_successful_at, health, freshness
                  from identity_access.ia_identity_sync_checkpoint
                 where source_id=? and feed_id=? and partition_id=? and consumer_projection=?
                """,
                (rs, row) -> new IdentityCheckpoint(
                        key,
                        rs.getLong("source_version"),
                        rs.getLong("source_watermark"),
                        rs.getLong("aggregate_version"),
                        instant(rs.getTimestamp("last_successful_at")),
                        IdentitySourceHealth.valueOf(rs.getString("health").toUpperCase()),
                        IdentityProjectionFreshness.valueOf(
                                rs.getString("freshness").toUpperCase())),
                key.sourceId(), key.feedId(), key.partitionId(), key.consumerProjection());
        return values.stream().findFirst();
    }

    @Override
    public Optional<String> envelopeDigest(UUID batchId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    select envelope_digest
                      from identity_access.ia_identity_source_archive
                     where batch_id=?
                    """, String.class, batchId));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AuthoritativeAccount> findAccount(
            CheckpointKey key, String externalRefDigest) {
        return jdbc.query("""
                select account_id, source_id, external_ref_digest,
                       subject_binding_token, status, effective_from, effective_to,
                       source_version, aggregate_version
                  from identity_access.ia_authoritative_account_current
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection=? and external_ref_digest=?
                """,
                (rs, row) -> new AuthoritativeAccount(
                        rs.getObject("account_id", UUID.class),
                        rs.getString("source_id"),
                        rs.getString("external_ref_digest"),
                        rs.getString("subject_binding_token"),
                        AuthoritativeStatus.valueOf(
                                rs.getString("status").toUpperCase()),
                        new EffectiveInterval(
                                instant(rs.getTimestamp("effective_from")),
                                instant(rs.getTimestamp("effective_to"))),
                        rs.getLong("source_version"),
                        rs.getLong("aggregate_version")),
                key.sourceId(), key.feedId(), key.partitionId(),
                key.consumerProjection(), externalRefDigest)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<OrganizationNode> findOrganization(
            CheckpointKey key, String externalRefDigest) {
        return jdbc.query("""
                select organization_id, source_id, external_ref_digest,
                       parent_external_ref_digest, display_name, organization_type,
                       status, effective_from, effective_to, source_version,
                       aggregate_version
                  from identity_access.ia_authoritative_organization_current
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection=? and external_ref_digest=?
                """,
                (rs, row) -> organization(rs),
                key.sourceId(), key.feedId(), key.partitionId(),
                key.consumerProjection(), externalRefDigest)
                .stream()
                .findFirst();
    }

    @Override
    public List<OrganizationNode> currentOrganizations(CheckpointKey key) {
        return jdbc.query("""
                select organization_id, source_id, external_ref_digest,
                       parent_external_ref_digest, display_name, organization_type,
                       status, effective_from, effective_to, source_version,
                       aggregate_version
                  from identity_access.ia_authoritative_organization_current
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection=?
                """,
                (rs, row) -> organization(rs),
                key.sourceId(), key.feedId(), key.partitionId(),
                key.consumerProjection());
    }

    @Override
    public boolean currentAccountExists(CheckpointKey key, UUID accountId) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from identity_access.ia_authoritative_account_current
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection=? and account_id=?
                """,
                Integer.class,
                key.sourceId(), key.feedId(), key.partitionId(),
                key.consumerProjection(), accountId);
        return count != null && count == 1;
    }

    @Override
    public boolean currentOrganizationExists(
            CheckpointKey key, UUID organizationId) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from identity_access.ia_authoritative_organization_current
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection=? and organization_id=?
                """,
                Integer.class,
                key.sourceId(), key.feedId(), key.partitionId(),
                key.consumerProjection(), organizationId);
        return count != null && count == 1;
    }

    @Override
    public Optional<IdentityRecordState> currentRecord(
            CheckpointKey key,
            IdentityRecordKind kind,
            String externalRefDigest) {
        return jdbc.query("""
                select source_version, payload_digest
                  from identity_access.ia_identity_source_fact
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection=? and record_kind=?
                   and external_ref_digest=?
                 order by source_version desc, applied_at desc
                 limit 1
                """,
                (rs, row) -> new IdentityRecordState(
                        rs.getLong("source_version"),
                        rs.getString("payload_digest")),
                key.sourceId(), key.feedId(), key.partitionId(),
                key.consumerProjection(), kind.wireName(), externalRefDigest)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<String> currentSubjectBinding(
            CheckpointKey key, String externalRefDigest) {
        return jdbc.query("""
                select subject_binding_token
                  from identity_access.ia_authoritative_account_current
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection=? and external_ref_digest=?
                """,
                (rs, row) -> rs.getString("subject_binding_token"),
                key.sourceId(), key.feedId(), key.partitionId(),
                key.consumerProjection(), externalRefDigest)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<String> currentSubjectBinding(
            CheckpointKey key, UUID accountId) {
        return jdbc.query("""
                select subject_binding_token
                  from identity_access.ia_authoritative_account_current
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection=? and account_id=?
                """,
                (rs, row) -> rs.getString("subject_binding_token"),
                key.sourceId(), key.feedId(), key.partitionId(),
                key.consumerProjection(), accountId)
                .stream()
                .findFirst();
    }

    @Override
    public List<String> currentSubjectBindingsForOrganization(
            CheckpointKey key, UUID organizationId) {
        return jdbc.query("""
                select distinct account.subject_binding_token
                  from identity_access.ia_authoritative_role_current role
                  join identity_access.ia_authoritative_account_current account
                    on account.account_id=role.account_id
                 where role.source_id=? and role.feed_id=? and role.partition_id=?
                   and role.consumer_projection=? and role.organization_id=?
                   and role.status='active' and account.status='active'
                """,
                (rs, row) -> rs.getString("subject_binding_token"),
                key.sourceId(), key.feedId(), key.partitionId(),
                key.consumerProjection(), organizationId);
    }

    @Override
    public void apply(NormalizedIdentityBatch batch, IdentityLease lease, Instant appliedAt) {
        if (!leaseIsCurrent(lease)) {
            throw new IdentitySyncException("IDENTITY_SYNC_FENCING_STALE");
        }
        Instant inboxExpiry = appliedAt.plus(Duration.ofDays(30));
        jdbc.update("""
                insert into identity_access.ia_identity_source_inbox (
                  batch_id, source_id, feed_id, partition_id, schema_version,
                  source_version, from_watermark, to_watermark, envelope_digest,
                  signature_digest, encrypted_payload, encrypted_data_key,
                  encryption_nonce, encryption_key_ref, encryption_key_version,
                  source_visible_at, observed_at, trace_id,
                  retention_effective_at, expires_at, consumer_watermark)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (batch_id) do nothing
                """,
                batch.batchId(), batch.key().sourceId(), batch.key().feedId(),
                batch.key().partitionId(), batch.schemaVersion(), batch.sourceVersion(),
                batch.fromWatermark(), batch.toWatermark(), batch.envelopeDigest(),
                batch.signatureDigest(), batch.encryptedEnvelope(), batch.wrappedDataKey(),
                batch.encryptionNonce(), batch.encryptionKeyRef(),
                batch.encryptionKeyVersion(), timestamp(batch.sourceVisibleAt()),
                timestamp(batch.observedAt()), batch.traceId(), timestamp(appliedAt),
                timestamp(inboxExpiry), batch.toWatermark());

        jdbc.update("""
                insert into identity_access.ia_identity_source_archive (
                  batch_id, source_id, feed_id, partition_id, consumer_projection,
                  schema_version, source_version, from_watermark, to_watermark,
                  mapping_version, mapping_digest, envelope_digest, signature_digest,
                  encrypted_payload, encrypted_data_key, encryption_nonce,
                  encryption_key_ref, encryption_key_version, source_visible_at,
                  observed_at, applied_at, trace_id, retention_effective_at,
                  expires_at, consumer_watermark)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (batch_id) do nothing
                """,
                batch.batchId(), batch.key().sourceId(), batch.key().feedId(),
                batch.key().partitionId(), batch.key().consumerProjection(),
                batch.schemaVersion(), batch.sourceVersion(), batch.fromWatermark(),
                batch.toWatermark(), batch.mappingVersion(), batch.mappingDigest(),
                batch.envelopeDigest(), batch.signatureDigest(),
                batch.encryptedEnvelope(), batch.wrappedDataKey(),
                batch.encryptionNonce(), batch.encryptionKeyRef(),
                batch.encryptionKeyVersion(), timestamp(batch.sourceVisibleAt()),
                timestamp(batch.observedAt()), timestamp(appliedAt), batch.traceId(),
                timestamp(appliedAt),
                timestamp(appliedAt.plus(Duration.ofDays(365))),
                batch.toWatermark());

        long nextAggregate = checkpoint(batch.key())
                .orElseGet(() -> IdentityCheckpoint.initial(batch.key()))
                .aggregateVersion() + 1;
        for (var fact : batch.sourceFacts()) {
            jdbc.update("""
                    insert into identity_access.ia_identity_source_fact (
                      fact_id, batch_id, event_id, source_id, feed_id, partition_id,
                      consumer_projection, record_kind, external_ref_digest, source_version,
                      source_watermark, effective_from, effective_to, payload_digest,
                      aggregate_version, applied_at, trace_id, retention_effective_at,
                      expires_at, consumer_watermark)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (event_id) do nothing
                    """,
                    fact.eventId(), batch.batchId(), fact.eventId(), batch.key().sourceId(),
                    batch.key().feedId(), batch.key().partitionId(),
                    batch.key().consumerProjection(), fact.recordKind().wireName(),
                    fact.externalRefDigest(), fact.sourceVersion(), batch.toWatermark(),
                    timestamp(fact.effectiveInterval().effectiveFrom()),
                    timestamp(fact.effectiveInterval().effectiveTo()), fact.payloadDigest(),
                    nextAggregate, timestamp(appliedAt), batch.traceId(),
                    timestamp(appliedAt), timestamp(appliedAt.plus(Duration.ofDays(365))),
                    batch.toWatermark());
        }
        for (var account : batch.accounts()) {
            jdbc.update("""
                insert into identity_access.ia_authoritative_account_current (
                      account_id, source_id, feed_id, partition_id, consumer_projection,
                      external_ref_digest, subject_binding_token, status, effective_from,
                      effective_to, source_version, source_watermark, aggregate_version,
                      mapping_version, applied_at, trace_id, retention_effective_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (source_id, external_ref_digest) do update set
                      feed_id=excluded.feed_id, partition_id=excluded.partition_id,
                      consumer_projection=excluded.consumer_projection,
                      subject_binding_token=excluded.subject_binding_token,
                      status=excluded.status, effective_from=excluded.effective_from,
                      effective_to=excluded.effective_to, source_version=excluded.source_version,
                      source_watermark=excluded.source_watermark,
                      aggregate_version=excluded.aggregate_version,
                      mapping_version=excluded.mapping_version, applied_at=excluded.applied_at,
                      trace_id=excluded.trace_id
                    where excluded.source_version >
                          identity_access.ia_authoritative_account_current.source_version
                    """,
                    account.accountId(), account.sourceId(), batch.key().feedId(),
                    batch.key().partitionId(), batch.key().consumerProjection(),
                    account.externalRefDigest(), account.subjectBindingToken(),
                    account.status().wireName(),
                    timestamp(account.effectiveInterval().effectiveFrom()),
                    timestamp(account.effectiveInterval().effectiveTo()), account.sourceVersion(),
                    batch.toWatermark(), nextAggregate, batch.mappingVersion(),
                    timestamp(appliedAt), batch.traceId(), timestamp(appliedAt));
            jdbc.update("""
                    update identity_access.ia_authoritative_subject_binding_history
                       set is_current=false,
                           read_approved_until=least(
                             coalesce(read_approved_until, ?), ?),
                           last_seen_at=?
                     where account_id=?
                    """,
                    timestamp(appliedAt.plus(SUBJECT_BINDING_READ_WINDOW)),
                    timestamp(appliedAt.plus(SUBJECT_BINDING_READ_WINDOW)),
                    timestamp(appliedAt), account.accountId());
            for (String binding : account.subjectBindingReadTokens()) {
                boolean currentBinding =
                        binding.equals(account.subjectBindingToken());
                int bound = jdbc.update("""
                        insert into identity_access.ia_authoritative_subject_binding_history (
                          subject_binding_token, account_id, key_version, is_current,
                          source_version, first_seen_at, last_seen_at, read_approved_until,
                          retention_effective_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (subject_binding_token) do update set
                          is_current=excluded.is_current,
                          source_version=greatest(
                            identity_access.ia_authoritative_subject_binding_history.source_version,
                            excluded.source_version),
                          last_seen_at=excluded.last_seen_at,
                          read_approved_until=case
                            when excluded.is_current then null
                            else least(
                              identity_access.ia_authoritative_subject_binding_history.read_approved_until,
                              excluded.read_approved_until)
                          end
                        where identity_access.ia_authoritative_subject_binding_history.account_id
                              = excluded.account_id
                        """,
                        binding,
                        account.accountId(),
                        keyVersion(binding),
                        currentBinding,
                        account.sourceVersion(),
                        timestamp(appliedAt),
                        timestamp(appliedAt),
                        currentBinding
                                ? null
                                : timestamp(appliedAt.plus(
                                        SUBJECT_BINDING_READ_WINDOW)),
                        timestamp(appliedAt));
                if (bound != 1) {
                    throw new IdentitySyncException(
                            "IDENTITY_SUBJECT_BINDING_CONFLICT");
                }
            }
        }
        for (var organization : batch.organizations()) {
            jdbc.update("""
                insert into identity_access.ia_authoritative_organization_current (
                      organization_id, source_id, feed_id, partition_id, consumer_projection,
                      external_ref_digest, parent_external_ref_digest, display_name,
                      organization_type, status, effective_from, effective_to,
                      source_version, source_watermark,
                      aggregate_version, applied_at, trace_id, retention_effective_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (source_id, external_ref_digest) do update set
                      feed_id=excluded.feed_id, partition_id=excluded.partition_id,
                      consumer_projection=excluded.consumer_projection,
                      parent_external_ref_digest=excluded.parent_external_ref_digest,
                      display_name=excluded.display_name,
                      organization_type=excluded.organization_type, status=excluded.status,
                      effective_from=excluded.effective_from, effective_to=excluded.effective_to,
                      source_version=excluded.source_version,
                      source_watermark=excluded.source_watermark,
                      aggregate_version=excluded.aggregate_version, applied_at=excluded.applied_at,
                      trace_id=excluded.trace_id
                    where excluded.source_version >
                          identity_access.ia_authoritative_organization_current.source_version
                    """,
                    organization.organizationId(), organization.sourceId(),
                    batch.key().feedId(), batch.key().partitionId(),
                    batch.key().consumerProjection(), organization.externalRefDigest(),
                    organization.parentExternalRefDigest(),
                    organization.displayName(),
                    organization.organizationType().wireName(), organization.status().wireName(),
                    timestamp(organization.effectiveInterval().effectiveFrom()),
                    timestamp(organization.effectiveInterval().effectiveTo()),
                    organization.sourceVersion(), batch.toWatermark(), nextAggregate,
                    timestamp(appliedAt), batch.traceId(), timestamp(appliedAt));
        }
        for (var role : batch.roleBindings()) {
            appendRoleBindingHistory(batch, role, appliedAt);
            jdbc.update("""
                insert into identity_access.ia_authoritative_role_current (
                      binding_id, source_id, feed_id, partition_id, consumer_projection,
                      account_id, organization_id, external_ref_digest, source_role_code,
                      target_role_id, mapping_version, mapping_digest, status, effective_from,
                      effective_to, source_version, source_watermark, aggregate_version,
                      applied_at, trace_id, retention_effective_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (external_ref_digest) do update set
                      source_id=excluded.source_id, feed_id=excluded.feed_id,
                      partition_id=excluded.partition_id,
                      consumer_projection=excluded.consumer_projection,
                      account_id=excluded.account_id, organization_id=excluded.organization_id,
                      source_role_code=excluded.source_role_code,
                      target_role_id=excluded.target_role_id,
                      mapping_version=excluded.mapping_version,
                      mapping_digest=excluded.mapping_digest, status=excluded.status,
                      effective_from=excluded.effective_from, effective_to=excluded.effective_to,
                      source_version=excluded.source_version,
                      source_watermark=excluded.source_watermark,
                      aggregate_version=excluded.aggregate_version, applied_at=excluded.applied_at,
                      trace_id=excluded.trace_id
                    where excluded.source_version >
                          identity_access.ia_authoritative_role_current.source_version
                    """,
                    role.bindingId(), batch.key().sourceId(), batch.key().feedId(),
                    batch.key().partitionId(), batch.key().consumerProjection(),
                    role.accountId(), role.organizationId(),
                    role.externalRefDigest(), role.sourceRoleCode(), role.targetRole().wireName(),
                    role.mappingVersion(), batch.mappingDigest(), role.status().wireName(),
                    timestamp(role.effectiveInterval().effectiveFrom()),
                    timestamp(role.effectiveInterval().effectiveTo()), role.sourceVersion(),
                    batch.toWatermark(), nextAggregate, timestamp(appliedAt), batch.traceId(),
                    timestamp(appliedAt));
        }
        ensureCheckpointExists(batch.key(), appliedAt, batch.traceId());
        int updated = jdbc.update("""
                update identity_access.ia_identity_sync_checkpoint checkpoint
                   set source_version=?, source_watermark=?, aggregate_version=?,
                       last_successful_at=?, health='healthy', freshness='fresh',
                       updated_at=?, trace_id=?
                 where checkpoint.source_id=? and checkpoint.feed_id=?
                   and checkpoint.partition_id=? and checkpoint.consumer_projection=?
                   and checkpoint.source_watermark=? and checkpoint.aggregate_version=?
                   and exists (
                     select 1 from identity_access.ia_identity_sync_lease lease
                      where lease.source_id=checkpoint.source_id
                        and lease.feed_id=checkpoint.feed_id
                        and lease.partition_id=checkpoint.partition_id
                        and lease.consumer_projection=checkpoint.consumer_projection
                        and lease.job_id=? and lease.attempt_no=?
                        and lease.fencing_token=?
                        and lease.lease_expires_at>clock_timestamp())
                """,
                batch.sourceVersion(), batch.toWatermark(), nextAggregate,
                timestamp(appliedAt), timestamp(appliedAt), batch.traceId(),
                batch.key().sourceId(), batch.key().feedId(), batch.key().partitionId(),
                batch.key().consumerProjection(), batch.fromWatermark(),
                nextAggregate - 1, lease.jobId(), lease.attemptNo(), lease.fencingToken());
        if (updated != 1) {
            throw new IdentitySyncException("IDENTITY_SYNC_FENCING_STALE");
        }
        jdbc.update("""
                update identity_access.ia_identity_replay_request
                   set status='covered', covered_at=?
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection=? and status='requested'
                   and requested_from>=? and requested_to<=?
                """,
                timestamp(appliedAt),
                batch.key().sourceId(), batch.key().feedId(),
                batch.key().partitionId(), batch.key().consumerProjection(),
                batch.fromWatermark() + 1, batch.toWatermark());
    }

    private void appendRoleBindingHistory(
            NormalizedIdentityBatch batch,
            EmploymentRoleBinding role,
            Instant appliedAt) {
        int inserted = jdbc.update("""
                insert into identity_access
                  .ia_authoritative_role_binding_history (
                    source_id, role_external_ref_digest, source_version,
                    binding_id, account_id, organization_id,
                    source_role_code, target_role_id, mapping_version,
                    mapping_digest, status, effective_from, effective_to,
                    recorded_at, trace_id, retention_effective_at,
                    expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict do nothing
                """,
                batch.key().sourceId(),
                role.externalRefDigest(),
                role.sourceVersion(),
                role.bindingId(),
                role.accountId(),
                role.organizationId(),
                role.sourceRoleCode(),
                role.targetRole().wireName(),
                role.mappingVersion(),
                batch.mappingDigest(),
                role.status().wireName(),
                timestamp(role.effectiveInterval().effectiveFrom()),
                timestamp(role.effectiveInterval().effectiveTo()),
                timestamp(appliedAt),
                batch.traceId(),
                timestamp(appliedAt),
                timestamp(appliedAt.plus(Duration.ofDays(2190))));
        if (inserted == 1) {
            return;
        }
        Boolean exactReplay = jdbc.queryForObject("""
                select exists (
                  select 1
                    from identity_access
                      .ia_authoritative_role_binding_history
                   where source_id=?
                     and role_external_ref_digest=?
                     and source_version=?
                     and binding_id=?
                     and account_id=?
                     and organization_id=?
                     and source_role_code=?
                     and target_role_id=?
                     and mapping_version=?
                     and mapping_digest=?
                     and status=?
                     and effective_from=?
                     and effective_to is not distinct from
                         cast(? as timestamptz)
                )
                """,
                Boolean.class,
                batch.key().sourceId(),
                role.externalRefDigest(),
                role.sourceVersion(),
                role.bindingId(),
                role.accountId(),
                role.organizationId(),
                role.sourceRoleCode(),
                role.targetRole().wireName(),
                role.mappingVersion(),
                batch.mappingDigest(),
                role.status().wireName(),
                timestamp(role.effectiveInterval().effectiveFrom()),
                timestamp(role.effectiveInterval().effectiveTo()));
        if (!Boolean.TRUE.equals(exactReplay)) {
            throw new IdentitySyncException(
                    "IDENTITY_ROLE_BINDING_HISTORY_CONFLICT");
        }
    }

    @Override
    public void reject(IdentitySyncRejection rejection) {
        jdbc.update("""
                insert into identity_access.ia_identity_rejected_record (
                  rejection_id, batch_id, source_id, feed_id, partition_id,
                  consumer_projection, source_version, source_watermark, payload_digest,
                  reason_code, replayable, job_id, attempt_no, trace_id, rejected_at,
                  retention_effective_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rejection.rejectionId(), rejection.batchId(), rejection.key().sourceId(),
                rejection.key().feedId(), rejection.key().partitionId(),
                rejection.key().consumerProjection(), rejection.sourceVersion(),
                rejection.sourceWatermark(), rejection.payloadDigest(), rejection.reasonCode(),
                rejection.replayable(), rejection.jobId(), rejection.attemptNo(),
                rejection.traceId(), timestamp(rejection.rejectedAt()),
                timestamp(rejection.rejectedAt()),
                timestamp(rejection.rejectedAt().plus(Duration.ofDays(180))));
    }

    @Override
    public void append(IdentitySloEvidence evidence) {
        jdbc.update("""
                insert into identity_access.ia_identity_slo_evidence (
                  evidence_id, account_id, record_kind, source_id,
                  source_version, source_watermark,
                  aggregate_version, source_visible_at, applied_at,
                  authorization_effective_at, within_fifteen_minutes, late_reason_code,
                  trace_id, retention_effective_at, expires_at)
                values (?, ?, ?, 'SRC-P0-RESPONSIBILITY-001', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (evidence_id) do nothing
                """,
                evidence.evidenceId(), evidence.accountId(),
                evidence.recordKind().wireName(), evidence.sourceVersion(),
                evidence.sourceWatermark(), evidence.aggregateVersion(),
                timestamp(evidence.sourceVisibleAt()), timestamp(evidence.appliedAt()),
                timestamp(evidence.authorizationEffectiveAt()),
                evidence.withinFifteenMinutes(), evidence.lateReasonCode(), evidence.traceId(),
                timestamp(evidence.authorizationEffectiveAt()),
                timestamp(evidence.authorizationEffectiveAt().plus(Duration.ofDays(1095))));
    }

    @Override
    public void compensate(IdentitySloEvidence evidence, String reasonCode) {
        jdbc.update("""
                insert into identity_access.ia_identity_slo_compensation (
                  evidence_id, account_id, record_kind, source_version, source_watermark,
                  aggregate_version, source_visible_at, applied_at,
                  attempted_authorization_effective_at, within_fifteen_minutes,
                  late_reason_code, reason_code, trace_id,
                  requested_at, retention_effective_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (evidence_id) do nothing
                """,
                evidence.evidenceId(), evidence.accountId(),
                evidence.recordKind().wireName(), evidence.sourceVersion(),
                evidence.sourceWatermark(), evidence.aggregateVersion(),
                timestamp(evidence.sourceVisibleAt()), timestamp(evidence.appliedAt()),
                timestamp(evidence.authorizationEffectiveAt()),
                evidence.withinFifteenMinutes(), evidence.lateReasonCode(), reasonCode,
                evidence.traceId(), timestamp(evidence.authorizationEffectiveAt()),
                timestamp(evidence.authorizationEffectiveAt()),
                timestamp(evidence.authorizationEffectiveAt().plus(Duration.ofDays(1095))));
    }

    @Override
    public Optional<IdentitySloEvidence> nextCompensation() {
        List<IdentitySloEvidence> values = jdbc.query("""
                select evidence_id, account_id, record_kind, source_version,
                       source_watermark, aggregate_version, source_visible_at,
                       applied_at, attempted_authorization_effective_at,
                       within_fifteen_minutes, late_reason_code, trace_id
                  from identity_access.ia_identity_slo_compensation
                 where status='pending'
                 order by requested_at, evidence_id
                 limit 1
                """,
                (rs, row) -> new IdentitySloEvidence(
                        rs.getObject("evidence_id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        IdentityRecordKind.valueOf(
                                rs.getString("record_kind")
                                        .replace('-', '_')
                                        .toUpperCase()),
                        rs.getLong("source_version"),
                        rs.getLong("source_watermark"),
                        rs.getLong("aggregate_version"),
                        rs.getTimestamp("source_visible_at").toInstant(),
                        rs.getTimestamp("applied_at").toInstant(),
                        rs.getTimestamp(
                                        "attempted_authorization_effective_at")
                                .toInstant(),
                        rs.getBoolean("within_fifteen_minutes"),
                        rs.getString("late_reason_code"),
                        rs.getString("trace_id")));
        return values.stream().findFirst();
    }

    @Override
    public void completeCompensation(UUID evidenceId, Instant completedAt) {
        jdbc.update("""
                update identity_access.ia_identity_slo_compensation
                   set status='completed', completed_at=?
                 where evidence_id=? and status='pending'
                """,
                timestamp(completedAt), evidenceId);
    }

    @Override
    public boolean leaseIsCurrent(IdentityLease lease) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from identity_access.ia_identity_sync_lease
                 where source_id=? and feed_id=? and partition_id=? and consumer_projection=?
                   and job_id=? and attempt_no=? and fencing_token=?
                   and lease_expires_at>clock_timestamp()
                """,
                Integer.class,
                lease.key().sourceId(), lease.key().feedId(), lease.key().partitionId(),
                lease.key().consumerProjection(), lease.jobId(), lease.attemptNo(),
                lease.fencingToken());
        return count != null && count == 1;
    }

    private void ensureCheckpointExists(CheckpointKey key, Instant now, String traceId) {
        jdbc.update("""
                insert into identity_access.ia_identity_sync_checkpoint (
                  source_id, feed_id, partition_id, consumer_projection,
                  source_version, source_watermark, aggregate_version,
                  health, freshness, updated_at, trace_id, retention_effective_at)
                values (?, ?, ?, ?, 0, 0, 0, 'degraded', 'stale', ?, ?, ?)
                on conflict (source_id, feed_id, partition_id, consumer_projection) do nothing
                """,
                key.sourceId(), key.feedId(), key.partitionId(), key.consumerProjection(),
                timestamp(now), traceId, timestamp(now));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String keyVersion(String binding) {
        int marker = binding.indexOf("_k");
        int end = binding.indexOf('_', marker + 2);
        if (marker < 0 || end < 0) {
            throw new IdentitySyncException(
                    "IDENTITY_SUBJECT_BINDING_TOKEN_INVALID");
        }
        return binding.substring(marker + 1, end);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static OrganizationNode organization(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new OrganizationNode(
                rs.getObject("organization_id", UUID.class),
                rs.getString("source_id"),
                rs.getString("external_ref_digest"),
                rs.getString("parent_external_ref_digest"),
                rs.getString("display_name"),
                OrganizationType.valueOf(
                        rs.getString("organization_type").toUpperCase()),
                AuthoritativeStatus.valueOf(
                        rs.getString("status").toUpperCase()),
                new EffectiveInterval(
                        instant(rs.getTimestamp("effective_from")),
                        instant(rs.getTimestamp("effective_to"))),
                rs.getLong("source_version"),
                rs.getLong("aggregate_version"));
    }
}
