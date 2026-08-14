package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilitySnapshotEvidence;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilitySnapshotLookupPort;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseFormulaBoundaryEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchObservationWindow;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityOverallResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exact batch + snapshot + immutable-hash lookup through the owner routine. */
public final class JdbcQualityEligibilitySnapshotLookupStore
        implements QualityEligibilitySnapshotLookupPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcQualityEligibilitySnapshotLookupStore(JdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper());
    }

    public JdbcQualityEligibilitySnapshotLookupStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public Optional<QualityEligibilitySnapshotEvidence> findExact(
            UUID batchId, UUID snapshotId, String immutableHash) {
        List<QualityEligibilitySnapshotEvidence> rows = jdbc.query("""
                select * from ingestion_quality.iq_find_quality_snapshot_evidence_v2(?,?,?)
                """, this::map,
                Objects.requireNonNull(batchId), Objects.requireNonNull(snapshotId),
                Objects.requireNonNull(immutableHash));
        if (rows.size() > 1) throw persistenceInvalid();
        return rows.stream().findFirst();
    }

    private QualityEligibilitySnapshotEvidence map(ResultSet row, int ignored)
            throws SQLException {
        return new QualityEligibilitySnapshotEvidence(
                row.getObject("snapshot_id", UUID.class),
                row.getObject("batch_id", UUID.class), row.getString("source_id"),
                overallResult(row.getString("overall_result")),
                new BatchObservationWindow(
                        row.getTimestamp("observation_start_at").toInstant(),
                        row.getTimestamp("observation_end_at").toInstant()),
                row.getTimestamp("cutoff_at").toInstant(),
                DataBatchPersistenceCodec.decodeUtf8(row.getBytes("watermark_utf8")),
                trim(row.getString("manifest_digest")),
                row.getString("source_schema_version"),
                trim(row.getString("source_schema_digest")),
                row.getString("qmdp_version"), trim(row.getString("qmdp_digest")),
                row.getString("quality_gate_version"),
                trim(row.getString("quality_gate_digest")),
                row.getString("qshm_version"), trim(row.getString("qshm_digest")),
                row.getObject("lineage_id", UUID.class),
                row.getTimestamp("effective_at").toInstant(),
                trim(row.getString("immutable_hash")),
                formulaEvidence(row.getString("formula_evidence")));
    }

    private List<QualityFuseFormulaBoundaryEvidence> formulaEvidence(String value) {
        try {
            JsonNode root = json.readTree(Objects.requireNonNull(value));
            if (!root.isArray()) throw persistenceInvalid();
            java.util.ArrayList<QualityFuseFormulaBoundaryEvidence> result =
                    new java.util.ArrayList<>();
            for (JsonNode item : root) {
                result.add(new QualityFuseFormulaBoundaryEvidence(
                        text(item, "metricId"), text(item, "formulaId"),
                        text(item, "formulaVersion"), text(item, "result"),
                        bool(item, "applicable"), number(item, "numerator"),
                        number(item, "denominator"), nullableNumber(item, "valueBasisPoints"),
                        text(item, "unit"), text(item, "operator"),
                        number(item, "thresholdNumerator"),
                        number(item, "thresholdDenominator"), text(item, "boundary"),
                        nullableBoolean(item, "comparisonResult")));
            }
            return List.copyOf(result);
        } catch (RuntimeException invalid) {
            throw persistenceInvalid();
        }
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) throw persistenceInvalid();
        return value.stringValue();
    }

    private static boolean bool(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) throw persistenceInvalid();
        return value.booleanValue();
    }

    private static long number(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw persistenceInvalid();
        }
        return value.longValue();
    }

    private static Long nullableNumber(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value == null || value.isNull() ? null : number(parent, field);
    }

    private static Boolean nullableBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isBoolean()) throw persistenceInvalid();
        return value.booleanValue();
    }

    private static QualityOverallResult overallResult(String value) {
        return switch (value) {
            case "quality-passed" -> QualityOverallResult.QUALITY_PASSED;
            case "quality-failed" -> QualityOverallResult.QUALITY_FAILED;
            default -> throw persistenceInvalid();
        };
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static IllegalStateException persistenceInvalid() {
        return new IllegalStateException("INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID");
    }
}
