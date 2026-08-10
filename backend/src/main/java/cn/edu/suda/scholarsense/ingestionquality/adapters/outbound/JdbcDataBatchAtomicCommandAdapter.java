package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationDomain;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizedValue;
import cn.edu.suda.scholarsense.ingestionquality.application.AppendNormalizedFactCommand;
import cn.edu.suda.scholarsense.ingestionquality.application.CanonicalOutboxPayload;
import cn.edu.suda.scholarsense.ingestionquality.application.CommitDataBatchQualityEvaluationCommand;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchAtomicCommandPort;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchAtomicCommandResult;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchAtomicCommitContext;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchStagingPort;
import cn.edu.suda.scholarsense.ingestionquality.application.PublishDataBatchAtomicCommand;
import cn.edu.suda.scholarsense.ingestionquality.application.ReceiveDataBatchAtomicCommand;
import cn.edu.suda.scholarsense.ingestionquality.application.RecordQualityImpactScopeCommand;
import cn.edu.suda.scholarsense.ingestionquality.application.RecordQualityMeasurementCommand;
import cn.edu.suda.scholarsense.ingestionquality.application.SealDataBatchAtomicCommand;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchManifest;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatch;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** The quality-worker's exclusive Java boundary to V14 compound owner commands. */
public final class JdbcDataBatchAtomicCommandAdapter
        implements DataBatchAtomicCommandPort, DataBatchStagingPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JdbcDataBatchStore store;
    private final AuditTokenizationPort tokenization;

    public JdbcDataBatchAtomicCommandAdapter(
            JdbcTemplate jdbc,
            ObjectMapper json,
            JdbcDataBatchStore store,
            AuditTokenizationPort tokenization) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
        this.store = Objects.requireNonNull(store);
        this.tokenization = Objects.requireNonNull(tokenization);
    }

    @Override
    public DataBatchAtomicCommandResult receive(ReceiveDataBatchAtomicCommand command) {
        Objects.requireNonNull(command);
        DataBatch batch = command.receivedBatch();
        var identity = batch.identity();
        var lineage = batch.lineage();
        DataBatchAtomicCommitContext commit = command.commit();
        if (!batch.traceId().equals(commit.traceId())) throw persistenceInvalid();
        CanonicalOutboxPayload outbox = command.outbox();
        Object[] returned = owner(() -> jdbc.queryForObject("""
                select batch_id, disposition
                  from ingestion_quality.iq_receive_data_batch(
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, (row, ignored) -> new Object[] {
                        row.getObject("batch_id", UUID.class), row.getString("disposition")
                },
                commit.commandId(), batch.batchId(), identity.sourceId(),
                DataBatchPersistenceCodec.encodeUtf8(identity.businessKey()),
                identity.sourceVersion(), lineage.lineageId(), lineage.supersedesBatchId(),
                lineage.reasonCode() == null ? null : lineage.reasonCode().name(),
                timestamp(lineage.effectiveAt()), batch.declaredManifestDigest(),
                timestamp(batch.receivedAt()), commit.traceId(), commit.scopeDigest(),
                commit.requestDigest(), auditText(outbox), outbox.auditDigest()));
        if (returned == null || returned.length != 2 || !(returned[0] instanceof UUID batchId)
                || !(returned[1] instanceof String disposition)) {
            throw persistenceInvalid();
        }
        DataBatchAtomicCommandResult result = result(disposition, commit);
        if (!result.response().batchId().equals(batchId)) throw persistenceInvalid();
        return result;
    }

    @Override
    public boolean appendNormalizedFact(AppendNormalizedFactCommand command) {
        Objects.requireNonNull(command);
        return Boolean.TRUE.equals(owner(() -> jdbc.queryForObject("""
                select ingestion_quality.iq_append_normalized_fact(
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Boolean.class,
                command.batchId(), DataBatchPersistenceCodec.encodeUtf8(command.recordId()),
                command.sourceId(), DataBatchPersistenceCodec.encodeUtf8(command.businessKey()),
                command.sourceVersion(), command.sourceSchemaVersion(),
                command.sourceSchemaDigest(), command.lineageId(), command.contentDigest(),
                timestamp(command.acceptedAt()))));
    }

    @Override
    public boolean recordQualityMeasurement(RecordQualityMeasurementCommand command) {
        Objects.requireNonNull(command);
        return Boolean.TRUE.equals(owner(() -> jdbc.queryForObject("""
                select ingestion_quality.iq_record_batch_quality_measurement(
                  ?, ?, ?, ?, ?::jsonb, ?, ?)
                """, Boolean.class,
                command.batchId(), command.formulaId(), command.formulaOrdinal(),
                command.applicable(), write(command.operands()), command.evidenceDigest(),
                timestamp(command.recordedAt()))));
    }

    @Override
    public boolean recordQualityImpactScope(RecordQualityImpactScopeCommand command) {
        Objects.requireNonNull(command);
        return Boolean.TRUE.equals(owner(() -> jdbc.queryForObject("""
                select ingestion_quality.iq_record_batch_quality_impact_scope(?, ?, ?)
                """, Boolean.class,
                command.batchId(), DataBatchPersistenceCodec.encodeUtf8(command.scopeCode()),
                timestamp(command.recordedAt()))));
    }

    @Override
    public DataBatchAtomicCommandResult seal(SealDataBatchAtomicCommand command) {
        Objects.requireNonNull(command);
        DataBatch batch = command.sealedBatch();
        BatchManifest manifest = batch.manifest();
        DataBatchAtomicCommitContext commit = command.commit();
        CanonicalOutboxPayload outbox = command.outbox();
        boolean accepted = Boolean.TRUE.equals(owner(() -> jdbc.queryForObject("""
                select ingestion_quality.iq_seal_data_batch(
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                  ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?)
                """, Boolean.class,
                commit.commandId(), batch.batchId(), command.expectedAggregateVersion(),
                manifest.recordCount(), manifest.validRecordCount(),
                manifest.rejectedRecordCount(),
                timestamp(manifest.observationWindow().startAt()),
                timestamp(manifest.observationWindow().endAt()),
                timestamp(manifest.cutoffAt()), manifest.timezone(),
                DataBatchPersistenceCodec.encodeUtf8(manifest.watermark()),
                manifest.sourceSchemaVersion(), manifest.sourceSchemaDigest(),
                manifest.dataCatalogVersion(), manifest.dataCatalogDigest(),
                manifest.qualityGateVersion(), manifest.qualityGateDigest(),
                manifest.qualityMetricDecisionProfileVersion(),
                manifest.qualityMetricDecisionProfileDigest(),
                timestamp(manifest.sourceOccurredAt()), timestamp(manifest.scheduledDueAt()),
                timestamp(manifest.receivedAt()), manifest.laneId(), manifest.manifestDigest(),
                write(command.contractEvidence()), timestamp(batch.sealedAt()),
                commit.traceId(), commit.scopeDigest(), commit.requestDigest(), auditText(outbox),
                outbox.auditDigest())));
        return result(accepted, commit);
    }

    @Override
    public DataBatchAtomicCommandResult commitQualityEvaluation(
            CommitDataBatchQualityEvaluationCommand command) {
        Objects.requireNonNull(command);
        QualitySnapshot snapshot = command.assessment().snapshot().value();
        DataBatchAtomicCommitContext commit = command.commit();
        if (!snapshot.traceId().equals(commit.traceId())) throw persistenceInvalid();
        CanonicalOutboxPayload outbox = command.outbox();
        var business = outbox.businessEvent().orElseThrow(
                JdbcDataBatchAtomicCommandAdapter::persistenceInvalid);
        byte[] businessBytes = outbox.businessUtf8().orElseThrow(
                JdbcDataBatchAtomicCommandAdapter::persistenceInvalid);
        String businessDigest = outbox.businessDigest().orElseThrow(
                JdbcDataBatchAtomicCommandAdapter::persistenceInvalid);
        AuditTokenizedValue objectToken = tokenization.tokenize(
                AuditTokenizationDomain.OBJECT, snapshot.snapshotId().toString());
        AuditTokenizedValue aggregateToken = tokenization.tokenize(
                AuditTokenizationDomain.AGGREGATE, snapshot.snapshotId().toString());
        if (!objectToken.profileVersion().equals(aggregateToken.profileVersion())
                || !objectToken.keyVersion().equals(aggregateToken.keyVersion())) {
            throw persistenceInvalid();
        }
        boolean accepted = Boolean.TRUE.equals(owner(() -> jdbc.queryForObject("""
                select ingestion_quality.iq_commit_batch_quality_evaluation(
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?,
                  ?::jsonb, ?, ?, ?, ?, ?, ?)
                """, Boolean.class,
                commit.commandId(), snapshot.batchId(), command.expectedAggregateVersion(),
                snapshot.snapshotId(), objectToken.value(), aggregateToken.value(),
                snapshot.overallResult().wireValue(),
                snapshot.sourceOwnerRef(), timestamp(snapshot.evaluatedAt()),
                commit.traceId(), snapshot.immutableHash(), command.retentionScopeDigest(),
                writeMetrics(snapshot.metricResults()), commit.scopeDigest(),
                commit.requestDigest(), auditText(outbox), outbox.auditDigest(),
                business.eventId(), business.eventType(), business.schemaVersion(),
                businessBytes, businessDigest)));
        return result(accepted, commit);
    }

    @Override
    public DataBatchAtomicCommandResult publish(PublishDataBatchAtomicCommand command) {
        Objects.requireNonNull(command);
        DataBatch batch = command.publishedBatch();
        DataBatchAtomicCommitContext commit = command.commit();
        CanonicalOutboxPayload outbox = command.outbox();
        var business = outbox.businessEvent().orElseThrow(
                JdbcDataBatchAtomicCommandAdapter::persistenceInvalid);
        byte[] businessBytes = outbox.businessUtf8().orElseThrow(
                JdbcDataBatchAtomicCommandAdapter::persistenceInvalid);
        String businessDigest = outbox.businessDigest().orElseThrow(
                JdbcDataBatchAtomicCommandAdapter::persistenceInvalid);
        boolean accepted = Boolean.TRUE.equals(owner(() -> jdbc.queryForObject("""
                select ingestion_quality.iq_publish_data_batch(
                  ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                """, Boolean.class,
                commit.commandId(), batch.batchId(), command.expectedAggregateVersion(),
                timestamp(batch.publishedAt()), commit.traceId(), commit.scopeDigest(),
                commit.requestDigest(), auditText(outbox), outbox.auditDigest(),
                business.eventId(), business.eventType(), business.schemaVersion(),
                businessBytes, businessDigest)));
        return result(accepted, commit);
    }

    private DataBatchAtomicCommandResult result(
            boolean accepted, DataBatchAtomicCommitContext commit) {
        return result(accepted ? "accepted" : "replay", commit);
    }

    private DataBatchAtomicCommandResult result(
            String disposition, DataBatchAtomicCommitContext commit) {
        DataBatchAtomicCommandResult.Status status = switch (disposition) {
            case "accepted" -> DataBatchAtomicCommandResult.Status.ACCEPTED;
            case "replay" -> DataBatchAtomicCommandResult.Status.REPLAY;
            default -> throw persistenceInvalid();
        };
        var completed = store.inspect(
                commit.idempotencyScope(), commit.requestDigest()).replay()
                .orElseThrow(JdbcDataBatchAtomicCommandAdapter::persistenceInvalid);
        if (!completed.requestDigest().equals(commit.requestDigest())) {
            throw persistenceInvalid();
        }
        return new DataBatchAtomicCommandResult(status, completed.response());
    }

    private <T> T owner(Supplier<T> command) {
        try {
            return command.get();
        } catch (DataAccessException failure) {
            throw DataBatchJdbcFailures.translate(failure);
        }
    }

    private String writeMetrics(List<QualityMetricResult> values) {
        List<Map<String, Object>> encoded = new ArrayList<>(values.size());
        for (QualityMetricResult metric : values) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("metricId", metric.metricId());
            item.put("formulaId", metric.formulaId());
            item.put("formulaVersion", metric.formulaVersion());
            item.put("result", metric.result().wireValue());
            item.put("applicable", metric.applicable());
            item.put("numerator", metric.numerator());
            item.put("denominator", metric.denominator());
            item.put("valueBasisPoints", metric.valueBasisPoints());
            item.put("unit", metric.unit().wireValue());
            item.put("operator", metric.operator().wireValue());
            item.put("thresholdNumerator", metric.thresholdNumerator());
            item.put("thresholdDenominator", metric.thresholdDenominator());
            item.put("boundary", metric.boundary().wireValue());
            item.put("reasonCode", metric.reasonCode());
            encoded.add(item);
        }
        return write(encoded);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException invalid) {
            throw new IllegalArgumentException(
                    "INGESTION_QUALITY_PERSISTENCE_JSON_INVALID", invalid);
        }
    }

    private static String auditText(CanonicalOutboxPayload outbox) {
        return new String(outbox.auditUtf8(), StandardCharsets.UTF_8);
    }

    private static Timestamp timestamp(Instant value) {
        Instant required = Objects.requireNonNull(value);
        if (required.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("INGESTION_QUALITY_TIME_PRECISION_INVALID");
        }
        return Timestamp.from(required);
    }

    private static IllegalStateException persistenceInvalid() {
        return new IllegalStateException("INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID");
    }
}
