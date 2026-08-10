package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionAttemptIds;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionCandidate;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionExecutionPort;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionResult;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Dedicated retention-executor pool adapter; authority payload bytes never enter this boundary. */
public final class JdbcQualitySnapshotRetentionExecutor
        implements QualitySnapshotRetentionExecutionPort {
    private final JdbcTemplate jdbc;

    public JdbcQualitySnapshotRetentionExecutor(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public QualitySnapshotRetentionResult execute(
            QualitySnapshotRetentionCandidate candidate,
            QualitySnapshotRetentionAttemptIds attempt,
            String requestedTraceId) {
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(attempt);
        Objects.requireNonNull(requestedTraceId);
        try {
            String result = jdbc.queryForObject(
                    "select ingestion_quality."
                            + "iq_execute_quality_snapshot_retention(?,?,?,?,?,?,?)",
                    String.class,
                    candidate.executionId(),
                    attempt.resultEventId(),
                    candidate.scope().snapshotId(),
                    candidate.scope().snapshotImmutableHash(),
                    candidate.scopeDigest(),
                    attempt.authorityEvidenceId(),
                    requestedTraceId);
            return QualitySnapshotRetentionResult.fromDatabase(result);
        } catch (DataAccessException unavailable) {
            throw new IngestionQualityApplicationException(
                    "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", unavailable);
        }
    }
}
