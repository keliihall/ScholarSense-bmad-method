package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionCandidate;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionCandidatePort;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionScopeCanonicalizer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads one due candidate only through the retention owner's bounded SECURITY DEFINER function. */
public final class JdbcQualitySnapshotRetentionCandidateFinder
        implements QualitySnapshotRetentionCandidatePort {
    private static final String SQL =
            "select * from ingestion_quality.iq_find_next_due_quality_snapshot_retention()";

    private final JdbcTemplate jdbc;

    public JdbcQualitySnapshotRetentionCandidateFinder(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Optional<QualitySnapshotRetentionCandidate> findNextDue() {
        try {
            List<QualitySnapshotRetentionCandidate> candidates = jdbc.query(SQL, (row, ignored) -> {
                var material = new QualitySnapshotRetentionScopeCanonicalizer.Material(
                        QualitySnapshotRetentionScopeCanonicalizer.OBJECT_TYPE,
                        row.getObject("snapshot_id", java.util.UUID.class),
                        row.getString("source_id"),
                        row.getLong("snapshot_aggregate_version"),
                        row.getTimestamp("evaluated_at").toInstant(),
                        row.getTimestamp("retention_due_at").toInstant(),
                        row.getString("snapshot_immutable_hash").strip(),
                        row.getString("retention_policy_version"),
                        row.getString("retention_schedule_version"));
                return new QualitySnapshotRetentionCandidate(
                        row.getObject("execution_id", java.util.UUID.class),
                        material,
                        row.getString("retention_scope_digest").strip());
            });
            if (candidates.size() > 1) throw unavailable();
            return candidates.stream().findFirst();
        } catch (DataAccessException unavailable) {
            throw new IngestionQualityApplicationException(
                    "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", unavailable);
        }
    }

    private static IngestionQualityApplicationException unavailable() {
        return new IngestionQualityApplicationException(
                "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
    }
}
