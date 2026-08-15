package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.shared.observability.CurrentTraceSource;
import cn.edu.suda.scholarsense.shared.observability.ObservationPort;
import cn.edu.suda.scholarsense.shared.observability.SafeObservationAttributes;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContext;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContextCodec;
import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditFact;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Builds the exact V14 audit and PIC canonical payloads from typed application values. */
public final class DataBatchCanonicalOutboxFactory {
    private static final String WORKLOAD_POLICY =
            "INGESTION-QUALITY-WORKLOAD-AUTHORIZATION-1.0.0";
    private static final String ASSESSED_TYPE =
            "scholarsense.ingestion-quality.data-batch.quality-assessed.v1";
    private static final String ASSESSED_SCHEMA = "DATA-BATCH-QUALITY-ASSESSED-1.0.0";
    private static final String PUBLISHED_TYPE =
            "scholarsense.ingestion-quality.data-batch.published.v1";
    private static final String PUBLISHED_SCHEMA = "DATA-BATCH-PUBLISHED-1.0.0";
    private static final String ASSESSED_TYPE_V2 =
            "scholarsense.ingestion-quality.data-batch.quality-assessed.v2";
    private static final String ASSESSED_SCHEMA_V2 = "DATA-BATCH-QUALITY-ASSESSED-2.0.0";
    private static final String PUBLISHED_TYPE_V2 =
            "scholarsense.ingestion-quality.data-batch.published.v2";
    private static final String PUBLISHED_SCHEMA_V2 = "DATA-BATCH-PUBLISHED-2.0.0";

    private final String databaseWorkloadIdentity;
    private final CurrentTraceSource currentTrace;
    private final W3cTraceContextCodec traceCodec;
    private final ObservationPort observations;
    private final boolean successorTraceContext;

    public DataBatchCanonicalOutboxFactory(String databaseWorkloadIdentity) {
        this(databaseWorkloadIdentity, java.util.Optional::empty,
                new W3cTraceContextCodec(), null, false);
    }

    public DataBatchCanonicalOutboxFactory(
            String databaseWorkloadIdentity,
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec traceCodec) {
        this(databaseWorkloadIdentity, currentTrace, traceCodec, true);
    }

    public DataBatchCanonicalOutboxFactory(
            String databaseWorkloadIdentity,
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec traceCodec,
            ObservationPort observations) {
        this(databaseWorkloadIdentity, currentTrace, traceCodec, observations, true);
    }

    private DataBatchCanonicalOutboxFactory(
            String databaseWorkloadIdentity,
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec traceCodec,
            boolean successorTraceContext) {
        this(databaseWorkloadIdentity, currentTrace, traceCodec, null,
                successorTraceContext);
    }

    private DataBatchCanonicalOutboxFactory(
            String databaseWorkloadIdentity,
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec traceCodec,
            ObservationPort observations,
            boolean successorTraceContext) {
        if (databaseWorkloadIdentity == null
                || !databaseWorkloadIdentity.matches("[a-z][a-z0-9_]{2,63}")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_DATABASE_IDENTITY_MISMATCH");
        }
        this.databaseWorkloadIdentity = databaseWorkloadIdentity;
        this.currentTrace = Objects.requireNonNull(currentTrace);
        this.traceCodec = Objects.requireNonNull(traceCodec);
        this.observations = observations;
        this.successorTraceContext = successorTraceContext;
    }

    public CanonicalOutboxPayload create(
            DataBatchCommandType type,
            DataBatchView response,
            DataBatchAtomicCommitContext commit) {
        return create(type, response, null, commit, null);
    }

    public CanonicalOutboxPayload create(
            DataBatchCommandType type,
            DataBatchView response,
            QualitySnapshot qualitySnapshot,
            DataBatchAtomicCommitContext commit) {
        return create(type, response, qualitySnapshot, commit, null);
    }

    public CanonicalOutboxPayload create(
            DataBatchCommandType type,
            DataBatchView response,
            QualitySnapshot qualitySnapshot,
            DataBatchAtomicCommitContext commit,
            PublicationScope publication) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(response);
        Objects.requireNonNull(commit);
        requireBinding(type, response, commit);
        LocalAuditOutboxRecord audit = audit(type, response, commit);
        byte[] auditBytes = DataBatchCanonicalJson.bytes(auditMaterial(audit));
        String auditDigest = sha256Hex(auditBytes);

        DataBatchBusinessOutboxEvent business = business(
                type, response, qualitySnapshot, commit);
        String producerTraceparent = business == null
                ? null
                : publication == null
                        ? traceparentFor(business.traceId())
                        : publication.traceparentFor(business.traceId());
        byte[] businessBytes = business == null
                ? null : DataBatchCanonicalJson.bytes(
                        businessMaterial(business, producerTraceparent));
        return new CanonicalOutboxPayload(
                audit, auditBytes, auditDigest, business, businessBytes,
                businessBytes == null ? null : sha256Hex(businessBytes));
    }

    private LocalAuditOutboxRecord audit(
            DataBatchCommandType type,
            DataBatchView response,
            DataBatchAtomicCommitContext commit) {
        UUID commandId = commit.commandId();
        Instant at = commit.occurredAt().instant();
        Map<String, Object> authorization = new LinkedHashMap<>();
        authorization.put("decision", "allow");
        authorization.put("policyVersion", WORKLOAD_POLICY);
        authorization.put("scopeCodes", List.of("QUALITY_WORKLOAD"));
        authorization.put("grantSearchTokens", List.of());
        authorization.put("notApplicableReason", null);
        LocalAuditFact fact = new LocalAuditFact(
                commandId, "LOCAL-AUDIT-FACT-1.0.0", "ingestion-quality",
                ActorType.SERVICE, token("ast", databaseWorkloadIdentity),
                List.of("QUALITY_WORKER"), authorization, type.action(), "data-batch",
                token("ost", response.batchId().toString()), "accepted",
                "INGESTION_QUALITY_COMMAND_ACCEPTED", "DATA_QUALITY", "QUALITY_WORKLOAD",
                at, at, commit.occurredAt().profile(), null,
                "AUDIT-TOKENIZATION-1.0.0", "k1", commit.traceId(), "data-batch",
                token("agt", response.batchId().toString()), response.aggregateVersion(),
                commit.requestDigest().substring("sha256:".length()),
                Map.of("workloadAuthorization", WORKLOAD_POLICY), "RS-1.0.0");
        return LocalAuditOutboxRecord.forFact(commandId, fact, at);
    }

    private DataBatchBusinessOutboxEvent business(
            DataBatchCommandType type,
            DataBatchView response,
            QualitySnapshot qualitySnapshot,
            DataBatchAtomicCommitContext commit) {
        DataBatchCommandType causeType;
        long causeVersion;
        Instant causeAt;
        String eventType;
        String schema;
        if (type == DataBatchCommandType.EVALUATE) {
            causeType = DataBatchCommandType.SEAL;
            causeVersion = 2;
            causeAt = response.sealedAt();
            eventType = eventTypeFor(type);
            schema = schemaFor(type);
        } else if (type == DataBatchCommandType.PUBLISH) {
            causeType = DataBatchCommandType.EVALUATE;
            causeVersion = 3;
            causeAt = response.evaluatedAt();
            eventType = eventTypeFor(type);
            schema = schemaFor(type);
        } else {
            return null;
        }
        if (qualitySnapshot == null) {
            throw new IllegalArgumentException("INGESTION_QUALITY_CANONICAL_PAYLOAD_INVALID");
        }
        UUID causationId = DataBatchOwnerIds.commandId(
                response.batchId(), causeType, causeVersion, causeAt);
        UUID eventId = DataBatchOwnerIds.businessEventId(
                response.batchId(), type, response.aggregateVersion(),
                commit.occurredAt().instant());
        return new DataBatchBusinessOutboxEvent(
                eventId, commit.commandId(), causationId, response.batchId(),
                response.aggregateVersion(), eventType, schema, commit.traceId(),
                commit.occurredAt().instant(), response, qualitySnapshot);
    }

    String eventTypeFor(DataBatchCommandType type) {
        return switch (type) {
            case EVALUATE -> successorTraceContext ? ASSESSED_TYPE_V2 : ASSESSED_TYPE;
            case PUBLISH -> successorTraceContext ? PUBLISHED_TYPE_V2 : PUBLISHED_TYPE;
            default -> throw new IllegalArgumentException(
                    "INGESTION_QUALITY_CANONICAL_PAYLOAD_INVALID");
        };
    }

    String schemaFor(DataBatchCommandType type) {
        return switch (type) {
            case EVALUATE -> successorTraceContext ? ASSESSED_SCHEMA_V2 : ASSESSED_SCHEMA;
            case PUBLISH -> successorTraceContext ? PUBLISHED_SCHEMA_V2 : PUBLISHED_SCHEMA;
            default -> throw new IllegalArgumentException(
                    "INGESTION_QUALITY_CANONICAL_PAYLOAD_INVALID");
        };
    }

    private static Map<String, Object> auditMaterial(LocalAuditOutboxRecord record) {
        LocalAuditFact fact = record.fact();
        Map<String, Object> time = timeMaterial(fact.timeSourceProfile());
        Map<String, Object> factValue = new LinkedHashMap<>();
        factValue.put("auditId", fact.auditId().toString());
        factValue.put("schemaVersion", fact.schemaVersion());
        factValue.put("producerModule", fact.producerModule());
        factValue.put("actorType", fact.actorType().name());
        factValue.put("actorSearchToken", fact.actorSearchToken());
        factValue.put("roleIds", fact.roleIds());
        factValue.put("authorizationContext", fact.authorizationContext());
        factValue.put("action", fact.action());
        factValue.put("objectType", fact.objectType());
        factValue.put("objectSearchToken", fact.objectSearchToken());
        factValue.put("outcome", fact.outcome());
        factValue.put("reasonCode", fact.reasonCode());
        factValue.put("purpose", fact.purpose());
        factValue.put("projectionScope", fact.projectionScope());
        factValue.put("occurredAt", fact.occurredAt().toString());
        factValue.put("recordedAt", fact.recordedAt().toString());
        factValue.put("timeSourceProfile", time);
        factValue.put("sourceIpSearchToken", fact.sourceIpSearchToken());
        factValue.put("tokenizationProfileVersion", fact.tokenizationProfileVersion());
        factValue.put("keyVersion", fact.keyVersion());
        factValue.put("traceId", fact.traceId());
        factValue.put("aggregateType", fact.aggregateType());
        factValue.put("aggregateIdSearchToken", fact.aggregateIdSearchToken());
        factValue.put("aggregateVersion", fact.aggregateVersion());
        factValue.put("idempotencyKeyDigest", fact.idempotencyKeyDigest());
        factValue.put("policyVersions", fact.policyVersions());
        factValue.put("retentionScheduleVersion", fact.retentionScheduleVersion());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", record.eventId().toString());
        envelope.put("auditId", record.auditId().toString());
        envelope.put("eventType", record.eventType());
        envelope.put("schemaVersion", record.schemaVersion());
        envelope.put("producer", record.producer());
        envelope.put("createdAt", record.createdAt().toString());
        envelope.put("fact", factValue);
        return envelope;
    }

    private static Map<String, Object> timeMaterial(TimeSourceProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceId", profile.sourceId());
        value.put("profileVersion", profile.profileVersion());
        value.put("offsetMs", profile.offsetMs());
        value.put("observedAt", profile.observedAt().toString());
        value.put("freshUntil", profile.freshUntil().toString());
        value.put("evidenceRef", profile.evidenceRef());
        return value;
    }

    private Map<String, Object> businessMaterial(
            DataBatchBusinessOutboxEvent event, String producerTraceparent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("aggregateId", event.batchId().toString());
        data.put("aggregateType", "data-batch");
        data.put("aggregateVersion", event.aggregateVersion());
        data.put("causationId", event.causationId().toString());
        data.put("contractVersion", "PIC-1.0.0");
        data.put("correlationId", event.commandId().toString());
        data.put("eventId", event.eventId().toString());
        data.put("occurredAt", event.occurredAt().toString());
        data.put("producer", "ingestion-quality");
        data.put("runtimeEvidenceClaim", "none");
        data.put("schemaVersion", event.schemaVersion());
        data.put("traceId", event.traceId());
        data.put("batch", batchMaterial(event.batch()));
        data.put("qualitySnapshot", snapshotMaterial(event.qualitySnapshot()));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("data", data);
        envelope.put("datacontenttype", "application/json");
        envelope.put("id", event.eventId().toString());
        envelope.put("source", "urn:scholarsense:ingestion-quality");
        envelope.put("specversion", "1.0");
        envelope.put("subject", "data-batch/" + event.batchId());
        envelope.put("time", event.occurredAt().toString());
        envelope.put("traceparent", producerTraceparent);
        envelope.put("type", event.eventType());
        return envelope;
    }

    static String traceparent(String traceId) {
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")
                || traceId.matches("0{32}")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_CANONICAL_PAYLOAD_INVALID");
        }
        String spanId = traceId.substring(0, 16);
        if (spanId.matches("0{16}")) spanId = traceId.substring(16);
        return "00-" + traceId + "-" + spanId + "-01";
    }

    String traceparentFor(String traceId) {
        if (!successorTraceContext) return traceparent(traceId);
        return traceCodec.format(traceCodec.child(parent(traceId)));
    }

    public PublicationScope startPublication(String traceId) {
        W3cTraceContext parent = parent(traceId);
        W3cTraceContext producer = traceCodec.child(parent);
        ObservationPort.ObservationScope observation = null;
        if (observations != null) {
            try {
                observation = observations.start(
                        "outbox.publish",
                        ObservationPort.ObservationKind.PRODUCER,
                        SafeObservationAttributes.create()
                                .low("module", "ingestion-quality")
                                .low("operation", "outbox.publish"),
                        parent);
                producer = observation.context();
            } catch (RuntimeException telemetryUnavailable) {
                observation = null;
            }
        }
        return new PublicationScope(
                producer.traceId(), traceCodec.format(producer), observation);
    }

    private W3cTraceContext parent(String traceId) {
        if (traceId == null || !traceId.matches("(?!0{32})[0-9a-f]{32}")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_CANONICAL_PAYLOAD_INVALID");
        }
        return currentTrace.current()
                .filter(context -> context.traceId().equals(traceId))
                .orElseGet(() -> traceCodec.resume(traceId, false));
    }

    /** Keeps the real PRODUCER span open until revalidation and owner commit resolve. */
    public static final class PublicationScope implements AutoCloseable {
        private final String traceId;
        private final String traceparent;
        private final ObservationPort.ObservationScope observation;
        private boolean completed;
        private boolean closed;

        private PublicationScope(
                String traceId,
                String traceparent,
                ObservationPort.ObservationScope observation) {
            this.traceId = traceId;
            this.traceparent = traceparent;
            this.observation = observation;
        }

        public String traceparent() {
            return traceparent;
        }

        String traceparentFor(String expectedTraceId) {
            if (!traceId.equals(expectedTraceId)) {
                throw new IllegalArgumentException(
                        "INGESTION_QUALITY_CANONICAL_PAYLOAD_INVALID");
            }
            return traceparent;
        }

        public void complete(boolean replay) {
            if (completed) return;
            completed = true;
            outcome(replay ? "duplicate" : "success");
        }

        public void fail(String outcome, RuntimeException failure) {
            Objects.requireNonNull(failure);
            if (completed) return;
            completed = true;
            outcome(outcome);
            if (observation != null) {
                try {
                    observation.error(failure);
                } catch (RuntimeException ignoredTelemetryFailure) {
                    // Telemetry cannot change owner transaction semantics.
                }
            }
        }

        private void outcome(String value) {
            if (observation != null) {
                try {
                    observation.outcome(value);
                } catch (RuntimeException ignoredTelemetryFailure) {
                    // Telemetry cannot change owner transaction semantics.
                }
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (!completed) outcome("failure");
            if (observation != null) {
                try {
                    observation.close();
                } catch (RuntimeException ignoredTelemetryFailure) {
                    // A failed exporter cannot change the owner transaction result.
                }
            }
        }
    }

    private static Map<String, Object> batchMaterial(DataBatchView batch) {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("startAt", batch.manifest().observationWindow().startAt().toString());
        window.put("endAt", batch.manifest().observationWindow().endAt().toString());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("batchId", batch.batchId().toString());
        value.put("sourceId", batch.identity().sourceId());
        value.put("sourceVersion", batch.identity().sourceVersion());
        value.put("status", batch.status().wireValue());
        value.put("aggregateVersion", batch.aggregateVersion());
        value.put("manifestDigest", batch.manifest().manifestDigest());
        value.put("observationWindow", window);
        value.put("cutoffAt", batch.manifest().cutoffAt().toString());
        value.put("watermark", batch.manifest().watermark());
        value.put("sourceSchemaVersion", batch.manifest().sourceSchemaVersion());
        value.put("sourceSchemaDigest", batch.manifest().sourceSchemaDigest());
        value.put("qualityMetricDecisionProfileVersion",
                batch.manifest().qualityMetricDecisionProfileVersion());
        value.put("qualityMetricDecisionProfileDigest",
                batch.manifest().qualityMetricDecisionProfileDigest());
        value.put("qualityGateVersion", batch.manifest().qualityGateVersion());
        value.put("qualityGateDigest", batch.manifest().qualityGateDigest());
        value.put("lineageId", batch.lineage().lineageId().toString());
        value.put("supersedesBatchId", batch.lineage().supersedesBatchId() == null
                ? null : batch.lineage().supersedesBatchId().toString());
        value.put("effectiveAt", batch.lineage().effectiveAt().toString());
        value.put("evaluatedAt", batch.evaluatedAt().toString());
        value.put("publishedAt", batch.publishedAt() == null
                ? null : batch.publishedAt().toString());
        return value;
    }

    private static Map<String, Object> snapshotMaterial(QualitySnapshot snapshot) {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("startAt", snapshot.observationWindow().startAt().toString());
        window.put("endAt", snapshot.observationWindow().endAt().toString());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("snapshotId", snapshot.snapshotId().toString());
        value.put("batchId", snapshot.batchId().toString());
        value.put("sourceId", snapshot.sourceId());
        value.put("assessedBatchStatus", snapshot.assessedBatchStatus().wireValue());
        value.put("overallResult", snapshot.overallResult().wireValue());
        value.put("observationWindow", window);
        value.put("cutoffAt", snapshot.cutoffAt().toString());
        value.put("evaluatedAt", snapshot.evaluatedAt().toString());
        value.put("watermark", snapshot.watermark());
        value.put("metricResults", snapshot.metricResults().stream()
                .map(DataBatchCanonicalOutboxFactory::metricMaterial).toList());
        value.put("impactScopeCodes", snapshot.impactScopeCodes());
        value.put("sourceOwnerRef", snapshot.sourceOwnerRef());
        value.put("approvalRef", snapshot.approvalRef());
        value.put("effectiveAt", snapshot.effectiveAt().toString());
        value.put("retentionScheduleVersion", snapshot.retentionScheduleVersion());
        value.put("qualityMetricDecisionProfileVersion",
                snapshot.qualityMetricDecisionProfileVersion());
        value.put("qualityMetricDecisionProfileDigest",
                snapshot.qualityMetricDecisionProfileDigest());
        value.put("qualityGateVersion", snapshot.qualityGateVersion());
        value.put("qualityGateDigest", snapshot.qualityGateDigest());
        value.put("canonicalizationProfile", snapshot.canonicalizationProfile());
        value.put("manifestDigest", snapshot.manifestDigest());
        value.put("sourceSchemaVersion", snapshot.sourceSchemaVersion());
        value.put("sourceSchemaDigest", snapshot.sourceSchemaDigest());
        value.put("immutableHash", snapshot.immutableHash());
        value.put("traceId", snapshot.traceId());
        value.put("lineageId", snapshot.lineageId().toString());
        value.put("supersedesSnapshotId", snapshot.supersedesSnapshotId() == null
                ? null : snapshot.supersedesSnapshotId().toString());
        value.put("aggregateVersion", snapshot.aggregateVersion());
        return value;
    }

    private static Map<String, Object> metricMaterial(QualityMetricResult metric) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("metricId", metric.metricId());
        value.put("formulaId", metric.formulaId());
        value.put("formulaVersion", metric.formulaVersion());
        value.put("result", metric.result().wireValue());
        value.put("applicable", metric.applicable());
        value.put("numerator", metric.numerator());
        value.put("denominator", metric.denominator());
        value.put("valueBasisPoints", metric.valueBasisPoints());
        value.put("unit", metric.unit().wireValue());
        value.put("operator", metric.operator().wireValue());
        value.put("thresholdNumerator", metric.thresholdNumerator());
        value.put("thresholdDenominator", metric.thresholdDenominator());
        value.put("boundary", metric.boundary().wireValue());
        value.put("reasonCode", metric.reasonCode());
        return value;
    }

    private static void requireBinding(
            DataBatchCommandType type,
            DataBatchView response,
            DataBatchAtomicCommitContext commit) {
        Instant expected = switch (type) {
            case RECEIVE -> null;
            case SEAL -> response.sealedAt();
            case EVALUATE -> response.evaluatedAt();
            case PUBLISH -> response.publishedAt();
        };
        boolean timeBound = type == DataBatchCommandType.RECEIVE
                ? !commit.occurredAt().instant().isBefore(response.receivedAt())
                : commit.occurredAt().instant().equals(expected);
        if (!timeBound
                || commit.idempotencyScope().commandType() != type) {
            throw new IllegalArgumentException("INGESTION_QUALITY_CANONICAL_PAYLOAD_INVALID");
        }
    }

    private static String token(String domain, String value) {
        return domain + "_v1_k1_" + sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
