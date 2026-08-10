package cn.edu.suda.scholarsense.ingestionquality.adapters;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenExecutableQualityPolicyLoader;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityContractAttestation;
import cn.edu.suda.scholarsense.ingestionquality.application.VerifiedQualityContract;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchObservationWindow;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.MetricDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.Operand;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.SourcePolicy;
import cn.edu.suda.scholarsense.ingestionquality.domain.MeasuredQualityInputs;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityAssessment;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricCalculator;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityOverallResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshotCanonicalizer;
import cn.edu.suda.scholarsense.ingestionquality.domain.SealedQualityContractEvidence;
import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditFact;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Shared exact QMDP/QSHM, audit and PIC evidence for V14 PostgreSQL tests. */
final class DataBatchPostgreSqlEvidenceFixtures {
    static final String ASSESSED_EVENT_TYPE =
            "scholarsense.ingestion-quality.data-batch.quality-assessed.v1";
    static final String ASSESSED_EVENT_SCHEMA = "DATA-BATCH-QUALITY-ASSESSED-1.0.0";
    static final String PUBLISHED_EVENT_TYPE =
            "scholarsense.ingestion-quality.data-batch.published.v1";
    static final String PUBLISHED_EVENT_SCHEMA = "DATA-BATCH-PUBLISHED-1.0.0";
    static final VerifiedQualityContract CONTRACT =
            new FrozenExecutableQualityPolicyLoader(
                    Path.of("..").toAbsolutePath().normalize()).loadVerified();
    static final SourcePolicy CARD = CONTRACT.policy().sources().stream()
            .filter(source -> source.sourceId().equals("SRC-P0-CARD-001"))
            .findFirst().orElseThrow();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final QualitySnapshotCanonicalizer QSHM =
            new QualitySnapshotCanonicalizer(CONTRACT.policy(), CONTRACT.hashProfile());
    private static final String MANIFEST_RECORD_COUNT = "manifest-declared-record-count";
    static final Plan PASSING_CARD = passingCard();

    private DataBatchPostgreSqlEvidenceFixtures() {}

    static AuditEnvelope acceptedAudit(
            String login,
            UUID commandId,
            UUID batchId,
            String action,
            long aggregateVersion,
            String traceId,
            String requestDigest,
            Instant occurredAt) {
        Map<String, Object> authorization = new LinkedHashMap<>();
        authorization.put("decision", "allow");
        authorization.put("policyVersion",
                "INGESTION-QUALITY-WORKLOAD-AUTHORIZATION-1.0.0");
        authorization.put("scopeCodes", List.of("QUALITY_WORKLOAD"));
        authorization.put("grantSearchTokens", List.of());
        authorization.put("notApplicableReason", null);
        LocalAuditFact fact = new LocalAuditFact(
                commandId, "LOCAL-AUDIT-FACT-1.0.0", "ingestion-quality",
                ActorType.SERVICE, token("ast", login), List.of("QUALITY_WORKER"),
                authorization, action, "data-batch", token("ost", batchId.toString()),
                "accepted", "INGESTION_QUALITY_COMMAND_ACCEPTED", "DATA_QUALITY",
                "QUALITY_WORKLOAD", occurredAt, occurredAt,
                new TimeSourceProfile(
                        "iq-db-clock", "AUDIT-CLOCK-BINDING-1.0.0", 0,
                        occurredAt.minusSeconds(1), occurredAt.plusSeconds(60),
                        "evidence://signed/ingestion-quality/db-clock"),
                null, "AUDIT-TOKENIZATION-1.0.0", "k1", traceId,
                "data-batch", token("agt", batchId.toString()), aggregateVersion,
                requestDigest.substring("sha256:".length()),
                Map.of("workloadAuthorization",
                        "INGESTION-QUALITY-WORKLOAD-AUTHORIZATION-1.0.0"), "RS-1.0.0");
        LocalAuditOutboxRecord record = new LocalAuditOutboxRecord(
                commandId, commandId,
                "ingestion-quality.local-audit-fact.recorded.v1",
                "LOCAL-AUDIT-OUTBOX-1.0.0", "ingestion-quality", occurredAt, fact);
        String payload = write(record);
        return new AuditEnvelope(payload, canonicalDigest(payload));
    }

    static String sealedEvidenceJson() {
        QualityContractAttestation value = CONTRACT.attestation();
        return write(new SealedQualityContractEvidence(
                value.qmdpProfileVersion(), value.qmdpPolicyRawDigest(),
                value.qmdpPolicyCanonicalDigest(), value.qmdpContractLockVersion(),
                value.qmdpContractLockRawDigest(), value.qmdpContractLockCanonicalDigest(),
                value.qmdpAuthorityRef(), value.qmdpApprovalRef(), value.qmdpEffectiveAt(),
                value.qshmProfileVersion(), value.qshmProfileRawDigest(),
                value.qshmProfileCanonicalDigest(), value.qshmContractLockVersion(),
                value.qshmContractLockRawDigest(), value.qshmAuthorityRef(),
                value.qshmApprovalRef(), value.qshmEffectiveAt()));
    }

    static String immutableHash(
            UUID batchId,
            UUID lineageId,
            UUID snapshotId,
            Instant observationStart,
            Instant observationEnd,
            Instant cutoff,
            byte[] watermark,
            List<byte[]> impactScopes,
            String manifestDigest,
            Instant evaluatedAt,
            String traceId) {
        return qualitySnapshot(
                batchId, lineageId, snapshotId, observationStart, observationEnd,
                cutoff, watermark, impactScopes, manifestDigest, evaluatedAt, traceId)
                .immutableHash();
    }

    static QualitySnapshot qualitySnapshot(
            UUID batchId,
            UUID lineageId,
            UUID snapshotId,
            Instant observationStart,
            Instant observationEnd,
            Instant cutoff,
            byte[] watermark,
            List<byte[]> impactScopes,
            String manifestDigest,
            Instant evaluatedAt,
            String traceId) {
        List<String> scopes = impactScopes.stream()
                .map(value -> new String(value, StandardCharsets.UTF_8)).toList();
        QualitySnapshot provisional = new QualitySnapshot(
                "scholarsense.ingestion-quality.quality-snapshot.immutable-hash.v1",
                CONTRACT.attestation().qshmProfileVersion(),
                CONTRACT.attestation().qshmProfileCanonicalDigest(), batchId,
                CARD.sourceId(), DataBatchStatus.QUALITY_PASSED,
                QualityOverallResult.QUALITY_PASSED,
                new BatchObservationWindow(observationStart, observationEnd), cutoff,
                new String(watermark, StandardCharsets.UTF_8),
                PASSING_CARD.assessment().metricResults(), scopes, CARD.owner(),
                CONTRACT.policy().approvalRef(), CONTRACT.policy().effectiveAt(),
                "RS-1.0.0", CONTRACT.policy().profileVersion(),
                CONTRACT.attestation().qmdpPolicyCanonicalDigest(),
                CONTRACT.policy().controlledInputs().qualityGate().version(),
                CONTRACT.policy().controlledInputs().qualityGate().canonicalDigest(),
                CONTRACT.policy().canonicalization().profile(), manifestDigest,
                CARD.schemaBinding().version(), CARD.schemaBinding().canonicalDigest(),
                lineageId, null, snapshotId, evaluatedAt, traceId, 3,
                digest("provisional-quality-snapshot"));
        return new QualitySnapshot(
                provisional.domainTag(), provisional.hashProfileVersion(),
                provisional.hashProfileDigest(), provisional.batchId(),
                provisional.sourceId(), provisional.assessedBatchStatus(),
                provisional.overallResult(), provisional.observationWindow(),
                provisional.cutoffAt(), provisional.watermark(),
                provisional.metricResults(), provisional.impactScopeCodes(),
                provisional.sourceOwnerRef(), provisional.approvalRef(),
                provisional.effectiveAt(), provisional.retentionScheduleVersion(),
                provisional.qualityMetricDecisionProfileVersion(),
                provisional.qualityMetricDecisionProfileDigest(),
                provisional.qualityGateVersion(), provisional.qualityGateDigest(),
                provisional.canonicalizationProfile(), provisional.manifestDigest(),
                provisional.sourceSchemaVersion(), provisional.sourceSchemaDigest(),
                provisional.lineageId(), provisional.supersedesSnapshotId(),
                provisional.snapshotId(), provisional.evaluatedAt(),
                provisional.traceId(), provisional.aggregateVersion(),
                QSHM.immutableHash(provisional));
    }

    static BusinessEnvelope assessedBusiness(
            UUID commandId,
            UUID causationId,
            UUID eventId,
            UUID batchId,
            String traceId,
            Instant occurredAt,
            long sourceVersion,
            Instant batchEffectiveAt,
            QualitySnapshot snapshot) {
        return business(
                commandId, causationId, eventId, batchId, traceId, occurredAt,
                sourceVersion, batchEffectiveAt, snapshot, 3L, "quality-passed",
                null, ASSESSED_EVENT_TYPE, ASSESSED_EVENT_SCHEMA);
    }

    static BusinessEnvelope publishedBusiness(
            UUID commandId,
            UUID causationId,
            UUID eventId,
            UUID batchId,
            String traceId,
            Instant occurredAt,
            long sourceVersion,
            Instant batchEffectiveAt,
            QualitySnapshot snapshot) {
        return business(
                commandId, causationId, eventId, batchId, traceId, occurredAt,
                sourceVersion, batchEffectiveAt, snapshot, 4L, "published",
                occurredAt, PUBLISHED_EVENT_TYPE, PUBLISHED_EVENT_SCHEMA);
    }

    private static BusinessEnvelope business(
            UUID commandId,
            UUID causationId,
            UUID eventId,
            UUID batchId,
            String traceId,
            Instant occurredAt,
            long sourceVersion,
            Instant batchEffectiveAt,
            QualitySnapshot snapshot,
            long aggregateVersion,
            String status,
            Instant publishedAt,
            String eventType,
            String schemaVersion) {
        Map<String, Object> data = new TreeMap<>();
        data.put("aggregateId", batchId.toString());
        data.put("aggregateType", "data-batch");
        data.put("aggregateVersion", aggregateVersion);
        data.put("causationId", causationId.toString());
        data.put("contractVersion", "PIC-1.0.0");
        data.put("correlationId", commandId.toString());
        data.put("eventId", eventId.toString());
        data.put("occurredAt", occurredAt.toString());
        data.put("producer", "ingestion-quality");
        data.put("runtimeEvidenceClaim", "none");
        data.put("schemaVersion", schemaVersion);
        data.put("traceId", traceId);
        data.put("batch", batchMaterial(
                snapshot, sourceVersion, batchEffectiveAt, aggregateVersion,
                status, publishedAt));
        data.put("qualitySnapshot", snapshotMaterial(snapshot));
        Map<String, Object> envelope = new TreeMap<>();
        envelope.put("data", data);
        envelope.put("datacontenttype", "application/json");
        envelope.put("id", eventId.toString());
        envelope.put("source", "urn:scholarsense:ingestion-quality");
        envelope.put("specversion", "1.0");
        envelope.put("subject", "data-batch/" + batchId);
        envelope.put("time", occurredAt.toString());
        String spanId = traceId.substring(0, 16);
        if (spanId.equals("0".repeat(16))) spanId = traceId.substring(16);
        envelope.put("traceparent", "00-" + traceId + "-" + spanId + "-01");
        envelope.put("type", eventType);
        byte[] payload = canonicalBytes(write(envelope));
        return new BusinessEnvelope(payload, sha256(payload));
    }

    private static Map<String, Object> batchMaterial(
            QualitySnapshot snapshot,
            long sourceVersion,
            Instant batchEffectiveAt,
            long aggregateVersion,
            String status,
            Instant publishedAt) {
        Map<String, Object> window = new TreeMap<>();
        window.put("startAt", snapshot.observationWindow().startAt().toString());
        window.put("endAt", snapshot.observationWindow().endAt().toString());
        Map<String, Object> value = new TreeMap<>();
        value.put("batchId", snapshot.batchId().toString());
        value.put("sourceId", snapshot.sourceId());
        value.put("sourceVersion", sourceVersion);
        value.put("status", status);
        value.put("aggregateVersion", aggregateVersion);
        value.put("manifestDigest", snapshot.manifestDigest());
        value.put("observationWindow", window);
        value.put("cutoffAt", snapshot.cutoffAt().toString());
        value.put("watermark", snapshot.watermark());
        value.put("sourceSchemaVersion", snapshot.sourceSchemaVersion());
        value.put("sourceSchemaDigest", snapshot.sourceSchemaDigest());
        value.put("qualityMetricDecisionProfileVersion",
                snapshot.qualityMetricDecisionProfileVersion());
        value.put("qualityMetricDecisionProfileDigest",
                snapshot.qualityMetricDecisionProfileDigest());
        value.put("qualityGateVersion", snapshot.qualityGateVersion());
        value.put("qualityGateDigest", snapshot.qualityGateDigest());
        value.put("lineageId", snapshot.lineageId().toString());
        value.put("supersedesBatchId", null);
        value.put("effectiveAt", batchEffectiveAt.toString());
        value.put("evaluatedAt", snapshot.evaluatedAt().toString());
        value.put("publishedAt", publishedAt == null ? null : publishedAt.toString());
        return value;
    }

    private static Map<String, Object> snapshotMaterial(QualitySnapshot snapshot) {
        Map<String, Object> window = new TreeMap<>();
        window.put("startAt", snapshot.observationWindow().startAt().toString());
        window.put("endAt", snapshot.observationWindow().endAt().toString());
        Map<String, Object> value = new TreeMap<>();
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
                .map(DataBatchPostgreSqlEvidenceFixtures::metricMaterial).toList());
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
        Map<String, Object> value = new TreeMap<>();
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

    static String metricsWithFirstBasisPoints(long basisPoints) {
        ArrayNode metrics = (ArrayNode) read(PASSING_CARD.metricsJson()).deepCopy();
        ((ObjectNode) metrics.get(0)).put("valueBasisPoints", basisPoints);
        return write(metrics);
    }

    static String json(Object value) {
        return write(value);
    }

    static String evidenceDigest(String encodedJson) {
        return "sha256:" + canonicalDigest(encodedJson);
    }

    static String digest(String value) {
        return "sha256:" + sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Plan passingCard() {
        List<MetricDefinition> definitions =
                QualityMetricCalculator.orderedDefinitions(CONTRACT.policy(), CARD.sourceId());
        Map<String, MeasuredQualityInputs> inputs = new LinkedHashMap<>();
        for (MetricDefinition definition : definitions) {
            Map<String, BigInteger> operands = new LinkedHashMap<>();
            boolean manifestBound = MANIFEST_RECORD_COUNT.equals(
                    definition.calculation().denominator().operandId());
            measured(operands, definition.calculation().numerator(),
                    manifestBound
                            ? BigInteger.ONE
                            : BigInteger.valueOf(definition.thresholdNumerator()));
            measured(operands, definition.calculation().denominator(),
                    manifestBound
                            ? BigInteger.ONE
                            : BigInteger.valueOf(definition.thresholdDenominator()));
            inputs.put(definition.formulaId(), new MeasuredQualityInputs(true, operands));
        }
        QualityAssessment assessment =
                new QualityMetricCalculator().assess(CONTRACT.policy(), CARD.sourceId(), inputs);
        return new Plan(definitions, Map.copyOf(inputs), assessment,
                metricJson(assessment.metricResults()));
    }

    private static void measured(
            Map<String, BigInteger> target, Operand operand, BigInteger value) {
        if (operand.operandId() == null) return;
        BigInteger prior = target.putIfAbsent(operand.operandId(), value);
        if (prior != null && !prior.equals(value)) throw new IllegalStateException();
    }

    private static String metricJson(List<QualityMetricResult> results) {
        ArrayNode metrics = JSON.createArrayNode();
        for (QualityMetricResult metric : results) {
            ObjectNode value = JSON.createObjectNode();
            value.put("metricId", metric.metricId());
            value.put("formulaId", metric.formulaId());
            value.put("formulaVersion", metric.formulaVersion());
            value.put("result", metric.result().wireValue());
            value.put("applicable", metric.applicable());
            value.put("numerator", metric.numerator());
            value.put("denominator", metric.denominator());
            if (metric.valueBasisPoints() == null) value.putNull("valueBasisPoints");
            else value.put("valueBasisPoints", metric.valueBasisPoints());
            value.put("unit", metric.unit().wireValue());
            value.put("operator", metric.operator().wireValue());
            value.put("thresholdNumerator", metric.thresholdNumerator());
            value.put("thresholdDenominator", metric.thresholdDenominator());
            value.put("boundary", metric.boundary().wireValue());
            value.putNull("reasonCode");
            metrics.add(value);
        }
        return write(metrics);
    }

    private static String token(String prefix, String value) {
        return prefix + "_v1_k1_" + sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String canonicalDigest(String encoded) {
        return sha256(canonicalBytes(encoded));
    }

    private static byte[] canonicalBytes(String encoded) {
        return writeBytes(canonicalValue(read(encoded)));
    }

    private static Object canonicalValue(JsonNode value) {
        if (value.isNull()) return null;
        if (value.isTextual()) return value.asText();
        if (value.isBoolean()) return value.asBoolean();
        if (value.isIntegralNumber()) return value.bigIntegerValue();
        if (value.isArray()) {
            List<Object> result = new ArrayList<>();
            value.forEach(item -> result.add(canonicalValue(item)));
            return result;
        }
        if (value.isObject()) {
            Map<String, Object> result = new TreeMap<>();
            value.forEachEntry((name, item) -> result.put(name, canonicalValue(item)));
            return result;
        }
        throw new IllegalArgumentException("non-canonical JSON scalar");
    }

    private static JsonNode read(String value) {
        try {
            return JSON.readTree(value);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException(failure);
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException(failure);
        }
    }

    private static byte[] writeBytes(Object value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException(failure);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record AuditEnvelope(String payload, String digest) {}

    record BusinessEnvelope(byte[] payload, String digest) {
        BusinessEnvelope { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }

    record Plan(
            List<MetricDefinition> definitions,
            Map<String, MeasuredQualityInputs> inputs,
            QualityAssessment assessment,
            String metricsJson) {}
}
