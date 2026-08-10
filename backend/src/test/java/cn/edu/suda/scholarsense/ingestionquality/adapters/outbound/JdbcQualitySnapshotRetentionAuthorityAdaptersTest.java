package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionAttemptIds;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionAuthorityEvidence;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionCandidate;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionResult;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionScopeCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcQualitySnapshotRetentionAuthorityAdaptersTest {
    private static final UUID AUTHORITY_ID =
            UUID.fromString("019d2c7d-4000-7000-8000-000000000210");
    private static final UUID EXECUTION_ID =
            UUID.fromString("019d2c7d-4000-7000-8000-000000000209");
    private static final String TRACE_ID = "22222222222222222222222222222222";
    private static final QualitySnapshotRetentionAttemptIds ATTEMPT =
            new QualitySnapshotRetentionAttemptIds(
                    UUID.fromString("019d2c7d-4000-7000-8000-000000000211"),
                    AUTHORITY_ID);

    @Test
    void authorityPoolCanCallOnlyTheThreeArgumentIngestBoundary() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate(AUTHORITY_ID);
        QualitySnapshotRetentionAuthorityEvidence evidence = evidence();

        UUID result = new JdbcQualitySnapshotRetentionAuthorityIngest(jdbc).ingest(evidence);

        assertEquals(AUTHORITY_ID, result);
        assertEquals(
                "select ingestion_quality."
                        + "iq_ingest_quality_snapshot_retention_authority(?,?,?)",
                jdbc.sql);
        assertEquals(3, jdbc.arguments.length);
        assertEquals(AUTHORITY_ID, jdbc.arguments[0]);
        assertArrayEquals(evidence.payloadUtf8(), (byte[]) jdbc.arguments[1]);
        assertEquals(evidence.payloadDigest(), jdbc.arguments[2]);
    }

    @Test
    void retentionPoolPassesOnlyOwnerCandidateIdsOpaqueAuthorityIdAndInvocationTrace() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate("completed");
        QualitySnapshotRetentionCandidate candidate = candidate();

        QualitySnapshotRetentionResult result =
                new JdbcQualitySnapshotRetentionExecutor(jdbc)
                        .execute(candidate, ATTEMPT, TRACE_ID);

        assertEquals(QualitySnapshotRetentionResult.COMPLETED, result);
        assertEquals(
                "select ingestion_quality."
                        + "iq_execute_quality_snapshot_retention(?,?,?,?,?,?,?)",
                jdbc.sql);
        assertEquals(7, jdbc.arguments.length);
        assertEquals(EXECUTION_ID, jdbc.arguments[0]);
        assertEquals(ATTEMPT.resultEventId(), jdbc.arguments[1]);
        assertEquals(candidate.scope().snapshotId(), jdbc.arguments[2]);
        assertEquals(candidate.scope().snapshotImmutableHash(), jdbc.arguments[3]);
        assertEquals(candidate.scopeDigest(), jdbc.arguments[4]);
        assertEquals(AUTHORITY_ID, jdbc.arguments[5]);
        assertEquals(TRACE_ID, jdbc.arguments[6]);
    }

    @Test
    void databaseFailureOrUnknownOutcomeIsNotReportedAsARetentionResult() {
        CapturingJdbcTemplate failed = new CapturingJdbcTemplate(null);
        failed.failure = new DataAccessResourceFailureException("down");

        IngestionQualityApplicationException unavailable = assertThrows(
                IngestionQualityApplicationException.class,
                () -> new JdbcQualitySnapshotRetentionAuthorityIngest(failed)
                        .ingest(evidence()));
        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", unavailable.code());

        CapturingJdbcTemplate unknown = new CapturingJdbcTemplate("partial");
        IngestionQualityApplicationException invalidResult = assertThrows(
                IngestionQualityApplicationException.class,
                () -> new JdbcQualitySnapshotRetentionExecutor(unknown)
                        .execute(candidate(), ATTEMPT, TRACE_ID));
        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", invalidResult.code());
    }

    private static QualitySnapshotRetentionAuthorityEvidence evidence() {
        return new QualitySnapshotRetentionAuthorityEvidence(
                AUTHORITY_ID,
                "{\"authority\":true}".getBytes(StandardCharsets.UTF_8),
                "a".repeat(64));
    }

    private static QualitySnapshotRetentionCandidate candidate() {
        var scope = new QualitySnapshotRetentionScopeCanonicalizer.Material(
                "QualitySnapshot",
                UUID.fromString("019d2c7d-4000-7000-8000-000000000110"),
                "SRC-P0-STUDENT-001",
                9,
                Instant.parse("2026-08-10T00:00:00Z"),
                Instant.parse("2028-08-10T00:00:00Z"),
                "sha256:" + "b".repeat(64),
                "QUALITY-SNAPSHOT-RETENTION-1.0.0",
                "RS-1.0.0");
        return new QualitySnapshotRetentionCandidate(
                EXECUTION_ID,
                scope,
                QualitySnapshotRetentionScopeCanonicalizer.digest(scope));
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private final Object result;
        private String sql;
        private Object[] arguments;
        private DataAccessResourceFailureException failure;

        private CapturingJdbcTemplate(Object result) {
            this.result = result;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (failure != null) throw failure;
            this.sql = sql;
            this.arguments = args.clone();
            return result == null ? null : requiredType.cast(result);
        }
    }
}
