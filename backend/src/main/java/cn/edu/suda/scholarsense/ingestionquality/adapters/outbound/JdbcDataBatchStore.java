package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchReadPort;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchCommandPrecedence;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchCommandReplayPort;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchIdempotencyResult;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchIdempotencyScope;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchView;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityMeasurement;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityMeasurementAnchor;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityMeasurementPort;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotReadPort;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchCorrectionReason;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchIdentity;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchLineage;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchManifest;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchObservationWindow;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatch;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.MetricDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.MeasuredQualityInputs;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricBoundary;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricResultStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricUnit;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityOverallResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import cn.edu.suda.scholarsense.ingestionquality.domain.SealedQualityContractEvidence;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only aggregate/snapshot hydration and bounded receiving-stage measurement access.
 *
 * <p>Every owner mutation is deliberately excluded from this adapter and is available only via
 * {@link JdbcDataBatchAtomicCommandAdapter}'s compound database functions.
 */
public final class JdbcDataBatchStore
        implements DataBatchReadPort, QualitySnapshotReadPort, QualityMeasurementPort,
        DataBatchCommandReplayPort {
    private static final String BATCH_SELECT = """
            select batch_id, source_id, business_key_utf8, source_version,
                   lineage_id, supersedes_batch_id, correction_reason, effective_at,
                   declared_manifest_digest, status, aggregate_version,
                   record_count, valid_record_count, rejected_record_count,
                   observation_start_at, observation_end_at, cutoff_at, business_timezone,
                   watermark_utf8, source_schema_version, source_schema_digest,
                   data_catalog_version, data_catalog_digest, quality_gate_version,
                   quality_gate_digest, qmdp_version, qmdp_digest, source_occurred_at,
                   scheduled_due_at, lane_id, sealed_contract_evidence::text,
                   received_at, sealed_at, evaluated_at, published_at, trace_id
              from ingestion_quality.iq_data_batch
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcDataBatchStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public Optional<DataBatch> find(UUID batchId) {
        Objects.requireNonNull(batchId);
        return batches(BATCH_SELECT + " where batch_id=?", batchId).stream().findFirst();
    }

    @Override
    public Optional<DataBatch> findByIdentity(BatchIdentity identity) {
        Objects.requireNonNull(identity);
        byte[] businessKey = DataBatchPersistenceCodec.encodeUtf8(identity.businessKey());
        return batches(BATCH_SELECT + """
                 where source_id=? and business_key_digest=?::char(64) and source_version=?
                   and business_key_utf8=?
                """, identity.sourceId(), sha256Hex(businessKey), identity.sourceVersion(),
                businessKey).stream().findFirst();
    }

    @Override
    public Optional<DataBatch> latestForBusinessKey(String sourceId, String businessKey) {
        Objects.requireNonNull(sourceId);
        byte[] encodedKey = DataBatchPersistenceCodec.encodeUtf8(businessKey);
        return batches(BATCH_SELECT + """
                 where source_id=? and business_key_digest=?::char(64) and business_key_utf8=?
                 order by source_version desc, batch_id desc
                 limit 1
                """, sourceId, sha256Hex(encodedKey), encodedKey).stream().findFirst();
    }

    @Override
    public Optional<DataBatch> lineageHead(UUID lineageId) {
        Objects.requireNonNull(lineageId);
        return batches(BATCH_SELECT + """
                 where lineage_id=?
                 order by source_version desc, batch_id desc
                 limit 1
                """, lineageId).stream().findFirst();
    }

    @Override
    public DataBatchCommandPrecedence inspect(
            DataBatchIdempotencyScope scope, String requestDigest) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(requestDigest);
        List<PrecedenceRow> rows = jdbc.query("""
                select disposition, batch_id, response_status,
                       response_aggregate_version, completed_at, expires_at
                  from ingestion_quality.iq_inspect_batch_command_precedence(
                    ?::char(64), ?::varchar(32), ?::char(71))
                """, (row, ignored) -> new PrecedenceRow(
                        row.getString("disposition"),
                        row.getObject("batch_id", UUID.class),
                        row.getString("response_status"),
                        row.getObject("response_aggregate_version", Long.class),
                        instantOrNull(row, "completed_at"),
                        instantOrNull(row, "expires_at")),
                scope.digest(), scope.commandType().name().toLowerCase(java.util.Locale.ROOT),
                requestDigest);
        if (rows.size() != 1) throw persistenceInvalid();
        PrecedenceRow row = rows.getFirst();
        return switch (row.disposition()) {
            case "fresh" -> {
                requireEmpty(row);
                yield DataBatchCommandPrecedence.fresh();
            }
            case "mismatch" -> {
                requireEmpty(row);
                yield DataBatchCommandPrecedence.mismatch();
            }
            case "replay" -> DataBatchCommandPrecedence.replay(
                    replay(scope, requestDigest, row));
            default -> throw persistenceInvalid();
        };
    }

    @Override
    public Optional<QualitySnapshot> findByBatchId(UUID batchId) {
        Objects.requireNonNull(batchId);
        List<SnapshotRow> snapshots = jdbc.query("""
                select snapshot_id, batch_id, domain_tag, hash_profile_version,
                       hash_profile_digest, source_id, assessed_batch_status, overall_result,
                       observation_start_at, observation_end_at, cutoff_at, watermark_utf8,
                       source_owner_ref, approval_ref, effective_at,
                       retention_schedule_version, qmdp_version, qmdp_digest,
                       quality_gate_version, quality_gate_digest, canonicalization_profile,
                       manifest_digest, source_schema_version, source_schema_digest,
                       lineage_id, supersedes_snapshot_id, evaluated_at, trace_id,
                       aggregate_version, immutable_hash
                  from ingestion_quality.iq_quality_snapshot
                 where batch_id=?
                """, (row, ignored) -> snapshotRow(row), batchId);
        if (snapshots.isEmpty()) return Optional.empty();
        if (snapshots.size() != 1) throw persistenceInvalid();

        SnapshotRow row = snapshots.getFirst();
        List<QualityMetricResult> metrics = jdbc.query("""
                select metric_id, formula_id, formula_version, result, applicable,
                       numerator, denominator, value_basis_points, unit, operator,
                       threshold_numerator, threshold_denominator, boundary, reason_code
                  from ingestion_quality.iq_quality_snapshot_metric
                 where snapshot_id=?
                 order by metric_ordinal
                """, (metric, ignored) -> metric(metric), row.snapshotId());
        List<String> impactScopes = jdbc.query("""
                select scope_code_utf8
                  from ingestion_quality.iq_quality_snapshot_impact_scope
                 where snapshot_id=?
                 order by scope_ordinal
                """, (scope, ignored) -> DataBatchPersistenceCodec.decodeUtf8(scope.getBytes(1)),
                row.snapshotId());
        return Optional.of(row.toSnapshot(metrics, impactScopes));
    }

    @Override
    public QualityMeasurement measure(
            DataBatch sealedBatch, List<MetricDefinition> orderedDefinitions) {
        Objects.requireNonNull(sealedBatch);
        List<MetricDefinition> definitions = List.copyOf(orderedDefinitions);
        List<MeasurementRow> rows = jdbc.query("""
                select measurement.formula_id, measurement.formula_ordinal,
                       measurement.applicable, operand.operand_id, operand.operand_value
                  from ingestion_quality.iq_batch_quality_measurement measurement
                  left join ingestion_quality.iq_batch_quality_operand operand
                    on operand.batch_id=measurement.batch_id
                   and operand.formula_id=measurement.formula_id
                 where measurement.batch_id=? and measurement.sealed_at=?
                 order by measurement.formula_ordinal, operand.operand_id
                """, (row, ignored) -> {
                    long operandValue = row.getLong(5);
                    return new MeasurementRow(
                            row.getString(1), row.getInt(2), row.getBoolean(3),
                            row.getString(4), row.wasNull() ? null : BigInteger.valueOf(operandValue));
                },
                sealedBatch.batchId(), Timestamp.from(sealedBatch.sealedAt()));

        LinkedHashMap<String, MeasurementAccumulator> grouped = new LinkedHashMap<>();
        for (MeasurementRow row : rows) {
            MeasurementAccumulator accumulator = grouped.computeIfAbsent(
                    row.formulaId(), ignored -> new MeasurementAccumulator(
                            row.formulaOrdinal(), row.applicable()));
            if (accumulator.formulaOrdinal != row.formulaOrdinal()
                    || accumulator.applicable != row.applicable()) {
                throw persistenceInvalid();
            }
            if (row.operandId() == null) {
                if (row.operandValue() != null) throw persistenceInvalid();
            } else if (row.operandValue() == null
                    || accumulator.operands.put(row.operandId(), row.operandValue()) != null) {
                throw persistenceInvalid();
            }
        }
        if (grouped.size() != definitions.size()) throw persistenceInvalid();

        LinkedHashMap<String, MeasuredQualityInputs> measured = new LinkedHashMap<>();
        for (int ordinal = 0; ordinal < definitions.size(); ordinal++) {
            MetricDefinition definition = definitions.get(ordinal);
            MeasurementAccumulator accumulator = grouped.get(definition.formulaId());
            if (accumulator == null || accumulator.formulaOrdinal != ordinal) {
                throw persistenceInvalid();
            }
            measured.put(definition.formulaId(), new MeasuredQualityInputs(
                    accumulator.applicable, accumulator.operands));
        }
        List<String> impactScopes = jdbc.query("""
                select scope.scope_code_utf8
                  from ingestion_quality.iq_batch_quality_impact_scope scope
                 where scope.batch_id=? and scope.sealed_at=?
                 order by scope.scope_code_utf8
                """, (scope, ignored) ->
                        DataBatchPersistenceCodec.decodeUtf8(scope.getBytes(1)),
                sealedBatch.batchId(), Timestamp.from(sealedBatch.sealedAt()));
        return new QualityMeasurement(
                QualityMeasurementAnchor.from(sealedBatch), measured, impactScopes);
    }

    private List<DataBatch> batches(String query, Object... parameters) {
        return jdbc.query(query, this::batch, parameters);
    }

    private DataBatch batch(ResultSet row, int ignored) throws SQLException {
        BatchIdentity identity = new BatchIdentity(
                row.getString("source_id"),
                DataBatchPersistenceCodec.decodeUtf8(row.getBytes("business_key_utf8")),
                row.getLong("source_version"));
        String correctionReason = row.getString("correction_reason");
        BatchLineage lineage = correctionReason == null
                ? BatchLineage.root(
                        row.getObject("lineage_id", UUID.class),
                        instant(row, "effective_at"))
                : BatchLineage.successor(
                        row.getObject("lineage_id", UUID.class),
                        row.getObject("supersedes_batch_id", UUID.class),
                        BatchCorrectionReason.valueOf(correctionReason),
                        instant(row, "effective_at"));
        DataBatchStatus status = batchStatus(row.getString("status"));
        BatchManifest manifest = status == DataBatchStatus.RECEIVING ? null : new BatchManifest(
                row.getLong("record_count"), row.getLong("valid_record_count"),
                row.getLong("rejected_record_count"),
                new BatchObservationWindow(
                        instant(row, "observation_start_at"),
                        instant(row, "observation_end_at")),
                instant(row, "cutoff_at"), row.getString("business_timezone"),
                DataBatchPersistenceCodec.decodeUtf8(row.getBytes("watermark_utf8")),
                row.getString("source_schema_version"), trim(row.getString("source_schema_digest")),
                row.getString("data_catalog_version"), trim(row.getString("data_catalog_digest")),
                row.getString("quality_gate_version"), trim(row.getString("quality_gate_digest")),
                row.getString("qmdp_version"), trim(row.getString("qmdp_digest")),
                instant(row, "source_occurred_at"), instant(row, "scheduled_due_at"),
                instant(row, "received_at"), row.getString("lane_id"),
                trim(row.getString("declared_manifest_digest")));
        SealedQualityContractEvidence evidence = status == DataBatchStatus.RECEIVING
                ? null : readEvidence(row.getString("sealed_contract_evidence"));
        return DataBatch.restore(
                row.getObject("batch_id", UUID.class), identity, lineage,
                trim(row.getString("declared_manifest_digest")), trim(row.getString("trace_id")),
                status, row.getLong("aggregate_version"), manifest, evidence,
                instant(row, "received_at"), instantOrNull(row, "sealed_at"),
                instantOrNull(row, "evaluated_at"), instantOrNull(row, "published_at"));
    }

    private SealedQualityContractEvidence readEvidence(String value) {
        try {
            return json.readValue(value, SealedQualityContractEvidence.class);
        } catch (JacksonException invalid) {
            throw new IllegalStateException("INGESTION_QUALITY_PERSISTED_JSON_INVALID", invalid);
        }
    }

    private static SnapshotRow snapshotRow(ResultSet row) throws SQLException {
        return new SnapshotRow(
                row.getObject("snapshot_id", UUID.class),
                row.getObject("batch_id", UUID.class), row.getString("domain_tag"),
                row.getString("hash_profile_version"), trim(row.getString("hash_profile_digest")),
                row.getString("source_id"), batchStatus(row.getString("assessed_batch_status")),
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

    private static QualityMetricResult metric(ResultSet row) throws SQLException {
        long basisPoints = row.getLong("value_basis_points");
        BigInteger valueBasisPoints = row.wasNull() ? null : BigInteger.valueOf(basisPoints);
        return new QualityMetricResult(
                row.getString("metric_id"), row.getString("formula_id"),
                row.getString("formula_version"), resultStatus(row.getString("result")),
                row.getBoolean("applicable"), BigInteger.valueOf(row.getLong("numerator")),
                BigInteger.valueOf(row.getLong("denominator")), valueBasisPoints,
                unit(row.getString("unit")), operator(row.getString("operator")),
                BigInteger.valueOf(row.getLong("threshold_numerator")),
                BigInteger.valueOf(row.getLong("threshold_denominator")),
                boundary(row.getString("boundary")), row.getString("reason_code"));
    }

    private static DataBatchView historicalView(
            DataBatch current, DataBatchStatus status, long aggregateVersion) {
        long expectedVersion = switch (status) {
            case RECEIVING -> 1;
            case SEALED -> 2;
            case QUALITY_PASSED, QUALITY_FAILED -> 3;
            case PUBLISHED -> 4;
        };
        if (aggregateVersion != expectedVersion
                || current.aggregateVersion() < aggregateVersion) {
            throw persistenceInvalid();
        }
        return new DataBatchView(
                current.batchId(), current.identity(), current.lineage(),
                current.declaredManifestDigest(), status, aggregateVersion,
                status == DataBatchStatus.RECEIVING ? null : current.manifest(),
                current.receivedAt(),
                aggregateVersion >= 2 ? current.sealedAt() : null,
                aggregateVersion >= 3 ? current.evaluatedAt() : null,
                aggregateVersion >= 4 ? current.publishedAt() : null,
                current.traceId());
    }

    private DataBatchIdempotencyResult replay(
            DataBatchIdempotencyScope scope,
            String requestDigest,
            PrecedenceRow row) {
        if (row.batchId() == null || row.responseStatus() == null
                || row.aggregateVersion() == null || row.completedAt() == null
                || row.expiresAt() == null) {
            throw persistenceInvalid();
        }
        DataBatch current = find(row.batchId()).orElseThrow(
                JdbcDataBatchStore::persistenceInvalid);
        DataBatchView response = historicalView(
                current, batchStatus(row.responseStatus()), row.aggregateVersion());
        return new DataBatchIdempotencyResult(
                scope, requestDigest, response, row.completedAt(), row.expiresAt());
    }

    private static void requireEmpty(PrecedenceRow row) {
        if (row.batchId() != null || row.responseStatus() != null
                || row.aggregateVersion() != null || row.completedAt() != null
                || row.expiresAt() != null) {
            throw persistenceInvalid();
        }
    }

    private static DataBatchStatus batchStatus(String value) {
        return switch (value) {
            case "receiving" -> DataBatchStatus.RECEIVING;
            case "sealed" -> DataBatchStatus.SEALED;
            case "quality-passed" -> DataBatchStatus.QUALITY_PASSED;
            case "quality-failed" -> DataBatchStatus.QUALITY_FAILED;
            case "published" -> DataBatchStatus.PUBLISHED;
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

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        if (value == null) throw persistenceInvalid();
        return value.toInstant();
    }

    private static Instant instantOrNull(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static IllegalStateException persistenceInvalid() {
        return new IllegalStateException("INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID");
    }

    private record MeasurementRow(
            String formulaId,
            int formulaOrdinal,
            boolean applicable,
            String operandId,
            BigInteger operandValue) {}

    private record PrecedenceRow(
            String disposition,
            UUID batchId,
            String responseStatus,
            Long aggregateVersion,
            Instant completedAt,
            Instant expiresAt) {}

    private static final class MeasurementAccumulator {
        private final int formulaOrdinal;
        private final boolean applicable;
        private final LinkedHashMap<String, BigInteger> operands = new LinkedHashMap<>();

        private MeasurementAccumulator(int formulaOrdinal, boolean applicable) {
            this.formulaOrdinal = formulaOrdinal;
            this.applicable = applicable;
        }
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

        private QualitySnapshot toSnapshot(
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
}
