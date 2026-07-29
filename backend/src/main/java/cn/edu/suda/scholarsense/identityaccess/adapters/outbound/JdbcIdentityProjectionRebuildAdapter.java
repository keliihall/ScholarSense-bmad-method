package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityArchivedEnvelope;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityProjectionFreshness;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityProjectionRebuildPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourceHealth;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncJob;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncJobStatus;
import cn.edu.suda.scholarsense.identityaccess.application.NormalizedIdentityBatch;
import cn.edu.suda.scholarsense.identityaccess.application.UuidV7;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL archive reader and atomic current-projection replacement target. */
public final class JdbcIdentityProjectionRebuildAdapter
        implements IdentityProjectionRebuildPort {
    private final JdbcTemplate jdbc;
    private final JdbcIdentitySyncRepository repository;
    private final JdbcIdentitySyncJobAdapter jobs;

    public JdbcIdentityProjectionRebuildAdapter(
            JdbcTemplate jdbc,
            JdbcIdentitySyncRepository repository,
            JdbcIdentitySyncJobAdapter jobs) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.jobs = jobs;
    }

    @Override
    public List<IdentityArchivedEnvelope> loadArchives(CheckpointKey key) {
        return jdbc.query("""
                select batch_id, schema_version, source_version, from_watermark,
                       to_watermark, source_visible_at, observed_at, applied_at,
                       trace_id, mapping_version, mapping_digest, envelope_digest,
                       signature_digest, encrypted_payload, encrypted_data_key,
                       encryption_nonce, encryption_key_ref, encryption_key_version
                  from identity_access.ia_identity_source_archive
                 where source_id=? and feed_id=? and partition_id=?
                   and consumer_projection=?
                 order by from_watermark, to_watermark, batch_id
                """,
                (rs, row) -> new IdentityArchivedEnvelope(
                        rs.getObject("batch_id", UUID.class),
                        key,
                        rs.getString("schema_version"),
                        rs.getLong("source_version"),
                        rs.getLong("from_watermark"),
                        rs.getLong("to_watermark"),
                        rs.getTimestamp("source_visible_at").toInstant(),
                        rs.getTimestamp("observed_at").toInstant(),
                        rs.getTimestamp("applied_at").toInstant(),
                        rs.getString("trace_id"),
                        rs.getString("mapping_version"),
                        rs.getString("mapping_digest"),
                        rs.getString("envelope_digest"),
                        rs.getString("signature_digest"),
                        new EncryptedSecret(
                                rs.getBytes("encrypted_payload"),
                                rs.getBytes("encrypted_data_key"),
                                rs.getString("encryption_key_ref"),
                                rs.getString("encryption_key_version"),
                                rs.getBytes("encryption_nonce"))),
                key.sourceId(), key.feedId(), key.partitionId(),
                key.consumerProjection());
    }

    @Override
    public void replaceCurrentProjection(
            CheckpointKey key,
            List<NormalizedIdentityBatch> batches,
            String traceId,
            Instant rebuiltAt) {
        if (batches.isEmpty()) {
            throw new IdentitySyncException("IDENTITY_REBUILD_ARCHIVE_INCOMPLETE");
        }
        UUID jobId = UUID.fromString(UuidV7.generate(rebuiltAt));
        IdentitySyncJob job = new IdentitySyncJob(
                jobId,
                key,
                IdentitySyncJobStatus.QUEUED,
                IdentitySourceHealth.DEGRADED,
                IdentityProjectionFreshness.STALE,
                rebuiltAt,
                null,
                0,
                rebuiltAt,
                1,
                0,
                null,
                traceId);
        try {
            jobs.enqueue(job);
            var attempt = jobs.start(jobId, "projection-rebuild", rebuiltAt)
                    .orElseThrow(() -> new IdentitySyncException(
                            "IDENTITY_REBUILD_BUSY"));
            jdbc.update("""
                    delete from identity_access.ia_authoritative_role_current
                     where source_id=? and feed_id=? and partition_id=?
                       and consumer_projection=?
                    """,
                    key.sourceId(), key.feedId(), key.partitionId(),
                    key.consumerProjection());
            jdbc.update("""
                    delete from identity_access.ia_authoritative_subject_binding_history
                     where account_id in (
                       select account_id
                         from identity_access.ia_authoritative_account_current
                        where source_id=? and feed_id=? and partition_id=?
                          and consumer_projection=?)
                    """,
                    key.sourceId(), key.feedId(), key.partitionId(),
                    key.consumerProjection());
            jdbc.update("""
                    delete from identity_access.ia_authoritative_account_current
                     where source_id=? and feed_id=? and partition_id=?
                       and consumer_projection=?
                    """,
                    key.sourceId(), key.feedId(), key.partitionId(),
                    key.consumerProjection());
            jdbc.update("""
                    delete from identity_access.ia_authoritative_organization_current
                     where source_id=? and feed_id=? and partition_id=?
                       and consumer_projection=?
                    """,
                    key.sourceId(), key.feedId(), key.partitionId(),
                    key.consumerProjection());
            jdbc.update("""
                    update identity_access.ia_identity_sync_checkpoint
                       set source_version=0, source_watermark=0,
                           aggregate_version=0, last_successful_at=null,
                           health='degraded', freshness='stale',
                           updated_at=?, trace_id=?
                     where source_id=? and feed_id=? and partition_id=?
                       and consumer_projection=?
                    """,
                    timestamp(rebuiltAt), traceId, key.sourceId(), key.feedId(),
                    key.partitionId(), key.consumerProjection());
            for (NormalizedIdentityBatch batch : batches) {
                repository.apply(batch, attempt.lease(), batch.observedAt());
            }
            NormalizedIdentityBatch last = batches.getLast();
            IdentitySyncJob completed = attempt.job().transitionTo(
                    IdentitySyncJobStatus.SUCCEEDED,
                    IdentitySourceHealth.HEALTHY,
                    IdentityProjectionFreshness.FRESH,
                    rebuiltAt,
                    null,
                    null,
                    last.toWatermark());
            jobs.save(attempt, completed, rebuiltAt);
        } catch (DataAccessException unavailable) {
            throw new IdentitySyncException(
                    "IDENTITY_REBUILD_PERSISTENCE_UNAVAILABLE", unavailable);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
