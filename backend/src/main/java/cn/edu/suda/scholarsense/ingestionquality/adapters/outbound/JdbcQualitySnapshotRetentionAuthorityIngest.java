package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionAuthorityEvidence;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionAuthorityIngestPort;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Dedicated consumer-registry-authority pool adapter; it has no retention execute capability. */
public final class JdbcQualitySnapshotRetentionAuthorityIngest
        implements QualitySnapshotRetentionAuthorityIngestPort {
    private final JdbcTemplate jdbc;

    public JdbcQualitySnapshotRetentionAuthorityIngest(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public UUID ingest(QualitySnapshotRetentionAuthorityEvidence evidence) {
        Objects.requireNonNull(evidence);
        try {
            UUID evidenceId = jdbc.queryForObject(
                    "select ingestion_quality."
                            + "iq_ingest_quality_snapshot_retention_authority(?,?,?)",
                    UUID.class,
                    evidence.authorityEvidenceId(),
                    evidence.payloadUtf8(),
                    evidence.payloadDigest());
            if (evidenceId == null) {
                throw new IngestionQualityApplicationException(
                        "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
            }
            return evidenceId;
        } catch (DataAccessException unavailable) {
            throw new IngestionQualityApplicationException(
                    "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", unavailable);
        }
    }
}
