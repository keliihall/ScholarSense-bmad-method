package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotQueryCriteria;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotQueryPort;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchObservationWindow;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricBoundary;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricResultStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricUnit;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityOverallResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Online-role adapter; every read is through an exact SECURITY DEFINER routine. */
public final class JdbcQualitySnapshotQueryStore implements QualitySnapshotQueryPort {
    private final JdbcTemplate jdbc;

    public JdbcQualitySnapshotQueryStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public List<QualitySnapshot> findAssessed(QualitySnapshotQueryCriteria criteria) {
        List<UUID> ids = jdbc.query("""
                select snapshot_id
                  from ingestion_quality.iq_find_assessed_quality_snapshot_ids(
                       ?,?,?,?,?,?,?,?,?)
                """, (row, ignored) -> row.getObject(1, UUID.class),
                criteria.sourceId(), criteria.overallResult(), timestamp(criteria.evaluatedFrom()),
                timestamp(criteria.evaluatedTo()), criteria.sortField(), criteria.sortDirection(),
                timestamp(criteria.afterEvaluatedAt()),
                criteria.afterSnapshotId(), criteria.limit());
        if (ids.isEmpty()) return List.of();
        UUID[] page = ids.toArray(UUID[]::new);
        List<SnapshotRow> snapshots = jdbc.query("""
                select *
                  from ingestion_quality.iq_find_assessed_quality_snapshot_page(?::uuid[])
                """, JdbcQualitySnapshotQueryStore::snapshotRow, (Object) page);
        if (snapshots.size() != ids.size()
                || !snapshots.stream().map(SnapshotRow::snapshotId).toList().equals(ids)) {
            throw persistenceInvalid();
        }
        Map<UUID, List<QualityMetricResult>> metrics = pageMap(ids);
        for (MetricRow metric : jdbc.query("""
                select *
                  from ingestion_quality.iq_find_assessed_quality_snapshot_page_metrics(?::uuid[])
                """, JdbcQualitySnapshotQueryStore::metricRow, (Object) page)) {
            requirePage(metrics, metric.snapshotId()).add(metric.metric());
        }
        Map<UUID, List<String>> impacts = pageMap(ids);
        for (ImpactRow impact : jdbc.query("""
                select *
                  from ingestion_quality.iq_find_assessed_quality_snapshot_page_impact_scopes(?::uuid[])
                """, JdbcQualitySnapshotQueryStore::impactRow, (Object) page)) {
            requirePage(impacts, impact.snapshotId()).add(impact.scopeCode());
        }
        return snapshots.stream().map(row -> row.toSnapshot(
                List.copyOf(metrics.get(row.snapshotId())),
                List.copyOf(impacts.get(row.snapshotId())))).toList();
    }

    @Override
    public Optional<QualitySnapshot> findById(UUID snapshotId) {
        Objects.requireNonNull(snapshotId);
        List<SnapshotRow> rows = jdbc.query("""
                select *
                  from ingestion_quality.iq_find_assessed_quality_snapshot(?)
                """, JdbcQualitySnapshotQueryStore::snapshotRow, snapshotId);
        if (rows.isEmpty()) return Optional.empty();
        if (rows.size() != 1) throw persistenceInvalid();
        SnapshotRow row = rows.getFirst();
        List<QualityMetricResult> metrics = jdbc.query("""
                select *
                  from ingestion_quality.iq_find_assessed_quality_snapshot_metrics(?)
                """, JdbcQualitySnapshotQueryStore::metric, snapshotId);
        List<String> impacts = jdbc.query("""
                select scope_code_utf8
                  from ingestion_quality.iq_find_assessed_quality_snapshot_impact_scopes(?)
                """, (scope, ignored) -> DataBatchPersistenceCodec.decodeUtf8(scope.getBytes(1)),
                snapshotId);
        return Optional.of(row.toSnapshot(metrics, impacts));
    }

    private static SnapshotRow snapshotRow(ResultSet row, int ignored) throws SQLException {
        return new SnapshotRow(
                row.getObject("snapshot_id", UUID.class), row.getObject("batch_id", UUID.class),
                row.getString("domain_tag"), row.getString("hash_profile_version"),
                trim(row.getString("hash_profile_digest")), row.getString("source_id"),
                batchStatus(row.getString("assessed_batch_status")),
                overallResult(row.getString("overall_result")),
                new BatchObservationWindow(
                        instant(row, "observation_start_at"),
                        instant(row, "observation_end_at")),
                instant(row, "cutoff_at"),
                DataBatchPersistenceCodec.decodeUtf8(row.getBytes("watermark_utf8")),
                row.getString("source_owner_ref"), row.getString("approval_ref"),
                instant(row, "effective_at"), row.getString("retention_schedule_version"),
                row.getString("qmdp_version"), trim(row.getString("qmdp_digest")),
                row.getString("quality_gate_version"),
                trim(row.getString("quality_gate_digest")),
                row.getString("canonicalization_profile"),
                trim(row.getString("manifest_digest")), row.getString("source_schema_version"),
                trim(row.getString("source_schema_digest")),
                row.getObject("lineage_id", UUID.class),
                row.getObject("supersedes_snapshot_id", UUID.class),
                instant(row, "evaluated_at"), trim(row.getString("trace_id")),
                row.getLong("aggregate_version"), trim(row.getString("immutable_hash")));
    }

    private static QualityMetricResult metric(ResultSet row, int ignored) throws SQLException {
        long basisPoints = row.getLong("value_basis_points");
        BigInteger optionalBasisPoints = row.wasNull() ? null : BigInteger.valueOf(basisPoints);
        return new QualityMetricResult(
                row.getString("metric_id"), row.getString("formula_id"),
                row.getString("formula_version"), resultStatus(row.getString("result")),
                row.getBoolean("applicable"), BigInteger.valueOf(row.getLong("numerator")),
                BigInteger.valueOf(row.getLong("denominator")), optionalBasisPoints,
                unit(row.getString("unit")), operator(row.getString("operator")),
                BigInteger.valueOf(row.getLong("threshold_numerator")),
                BigInteger.valueOf(row.getLong("threshold_denominator")),
                boundary(row.getString("boundary")), row.getString("reason_code"));
    }

    private static MetricRow metricRow(ResultSet row, int ignored) throws SQLException {
        return new MetricRow(row.getObject("snapshot_id", UUID.class), metric(row, ignored));
    }

    private static ImpactRow impactRow(ResultSet row, int ignored) throws SQLException {
        return new ImpactRow(
                row.getObject("snapshot_id", UUID.class),
                DataBatchPersistenceCodec.decodeUtf8(row.getBytes("scope_code_utf8")));
    }

    private static <T> Map<UUID, List<T>> pageMap(List<UUID> ids) {
        Map<UUID, List<T>> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, new ArrayList<>()));
        return result;
    }

    private static <T> List<T> requirePage(Map<UUID, List<T>> page, UUID snapshotId) {
        List<T> values = page.get(snapshotId);
        if (values == null) throw persistenceInvalid();
        return values;
    }

    private static DataBatchStatus batchStatus(String value) {
        return switch (value) {
            case "quality-passed" -> DataBatchStatus.QUALITY_PASSED;
            case "quality-failed" -> DataBatchStatus.QUALITY_FAILED;
            default -> throw persistenceInvalid();
        };
    }

    private static QualityOverallResult overallResult(String value) {
        return switch (value) {
            case "quality-passed" -> QualityOverallResult.QUALITY_PASSED;
            case "quality-failed" -> QualityOverallResult.QUALITY_FAILED;
            default -> throw persistenceInvalid();
        };
    }

    private static QualityMetricResultStatus resultStatus(String value) {
        return switch (value) {
            case "passed" -> QualityMetricResultStatus.PASSED;
            case "failed" -> QualityMetricResultStatus.FAILED;
            case "not-applicable" -> QualityMetricResultStatus.NOT_APPLICABLE;
            default -> throw persistenceInvalid();
        };
    }

    private static QualityMetricUnit unit(String value) {
        return switch (value) {
            case "basis-point" -> QualityMetricUnit.BASIS_POINT;
            case "count" -> QualityMetricUnit.COUNT;
            case "millisecond" -> QualityMetricUnit.MILLISECOND;
            case "member-count" -> QualityMetricUnit.MEMBER_COUNT;
            default -> throw persistenceInvalid();
        };
    }

    private static QualityMetricOperator operator(String value) {
        return switch (value) {
            case ">=" -> QualityMetricOperator.GREATER_THAN_OR_EQUAL;
            case "<=" -> QualityMetricOperator.LESS_THAN_OR_EQUAL;
            case "=" -> QualityMetricOperator.EQUAL;
            default -> throw persistenceInvalid();
        };
    }

    private static QualityMetricBoundary boundary(String value) {
        if (!"inclusive".equals(value)) throw persistenceInvalid();
        return QualityMetricBoundary.INCLUSIVE;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        if (value == null) throw persistenceInvalid();
        return value.toInstant();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static IllegalStateException persistenceInvalid() {
        return new IllegalStateException("INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID");
    }

    private record SnapshotRow(
            UUID snapshotId,
            UUID batchId,
            String domainTag,
            String hashProfileVersion,
            String hashProfileDigest,
            String sourceId,
            DataBatchStatus assessedBatchStatus,
            QualityOverallResult overallResult,
            BatchObservationWindow observationWindow,
            Instant cutoffAt,
            String watermark,
            String sourceOwnerRef,
            String approvalRef,
            Instant effectiveAt,
            String retentionScheduleVersion,
            String qmdpVersion,
            String qmdpDigest,
            String qualityGateVersion,
            String qualityGateDigest,
            String canonicalizationProfile,
            String manifestDigest,
            String sourceSchemaVersion,
            String sourceSchemaDigest,
            UUID lineageId,
            UUID supersedesSnapshotId,
            Instant evaluatedAt,
            String traceId,
            long aggregateVersion,
            String immutableHash) {
        QualitySnapshot toSnapshot(
                List<QualityMetricResult> metrics, List<String> impactScopes) {
            return new QualitySnapshot(
                    domainTag, hashProfileVersion, hashProfileDigest, batchId, sourceId,
                    assessedBatchStatus, overallResult, observationWindow, cutoffAt, watermark,
                    metrics, impactScopes, sourceOwnerRef, approvalRef, effectiveAt,
                    retentionScheduleVersion, qmdpVersion, qmdpDigest, qualityGateVersion,
                    qualityGateDigest, canonicalizationProfile, manifestDigest,
                    sourceSchemaVersion, sourceSchemaDigest, lineageId, supersedesSnapshotId,
                    snapshotId, evaluatedAt, traceId, aggregateVersion, immutableHash);
        }
    }

    private record MetricRow(UUID snapshotId, QualityMetricResult metric) {}

    private record ImpactRow(UUID snapshotId, String scopeCode) {}
}
