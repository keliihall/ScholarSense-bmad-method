package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.UpstreamQualityEvent;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchObservationWindow;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityOverallResult;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strict anti-corruption decoder for the frozen Story 2.3 event wire contract. */
public final class StrictDataBatchQualityEventDecoder {
    private static final Set<String> ROOT_KEYS = Set.of(
            "data", "datacontenttype", "id", "source", "specversion", "subject",
            "time", "traceparent", "type");
    private static final Set<String> DATA_KEYS = Set.of(
            "aggregateId", "aggregateType", "aggregateVersion", "causationId",
            "contractVersion", "correlationId", "eventId", "occurredAt", "producer",
            "qualitySnapshot", "runtimeEvidenceClaim", "schemaVersion", "traceId", "batch");
    private static final Set<String> BATCH_KEYS = Set.of(
            "batchId", "sourceId", "sourceVersion", "status", "aggregateVersion",
            "manifestDigest", "observationWindow", "cutoffAt", "watermark",
            "sourceSchemaVersion", "sourceSchemaDigest",
            "qualityMetricDecisionProfileVersion", "qualityMetricDecisionProfileDigest",
            "qualityGateVersion", "qualityGateDigest", "lineageId", "supersedesBatchId",
            "effectiveAt", "evaluatedAt", "publishedAt");
    private static final Set<String> SNAPSHOT_KEYS = Set.of(
            "snapshotId", "batchId", "sourceId", "assessedBatchStatus", "overallResult",
            "observationWindow", "cutoffAt", "evaluatedAt", "watermark", "metricResults",
            "impactScopeCodes", "sourceOwnerRef", "approvalRef", "effectiveAt",
            "retentionScheduleVersion", "qualityMetricDecisionProfileVersion",
            "qualityMetricDecisionProfileDigest", "qualityGateVersion", "qualityGateDigest",
            "canonicalizationProfile", "manifestDigest", "sourceSchemaVersion",
            "sourceSchemaDigest", "immutableHash", "traceId", "lineageId",
            "supersedesSnapshotId", "aggregateVersion");
    private static final Set<String> WINDOW_KEYS = Set.of("startAt", "endAt");

    private static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    public UpstreamQualityEvent decode(byte[] payload) {
        if (payload == null || payload.length == 0) throw invalid();
        try {
            JsonNode root = MAPPER.readTree(payload);
            requireObjectWithExactKeys(root, ROOT_KEYS);
            JsonNode data = requiredObject(root, "data");
            JsonNode batch = requiredObject(data, "batch");
            JsonNode snapshot = requiredObject(data, "qualitySnapshot");
            JsonNode window = requiredObject(batch, "observationWindow");
            JsonNode snapshotWindow = requiredObject(snapshot, "observationWindow");
            requireObjectWithExactKeys(data, DATA_KEYS);
            requireObjectWithExactKeys(batch, BATCH_KEYS);
            requireObjectWithExactKeys(snapshot, SNAPSHOT_KEYS);
            requireObjectWithExactKeys(window, WINDOW_KEYS);
            requireObjectWithExactKeys(snapshotWindow, WINDOW_KEYS);

            UUID batchId = uuid(batch, "batchId");
            String sourceId = text(batch, "sourceId");
            verifyCrossBindings(root, data, batch, snapshot, batchId, sourceId);

            return new UpstreamQualityEvent(
                    uuid(data, "eventId"),
                    text(root, "source"),
                    text(root, "type"),
                    text(data, "schemaVersion"),
                    batchId,
                    sourceId,
                    integer(batch, "sourceVersion"),
                    uuid(batch, "lineageId"),
                    nullableUuid(batch, "supersedesBatchId"),
                    integer(batch, "aggregateVersion"),
                    batchStatus(text(batch, "status")),
                    uuid(snapshot, "snapshotId"),
                    text(snapshot, "immutableHash"),
                    integer(snapshot, "aggregateVersion"),
                    snapshotResult(text(snapshot, "overallResult")),
                    text(batch, "manifestDigest"),
                    text(batch, "watermark"),
                    new BatchObservationWindow(
                            instant(window, "startAt"), instant(window, "endAt")),
                    instant(batch, "cutoffAt"),
                    text(batch, "sourceSchemaVersion"),
                    text(batch, "sourceSchemaDigest"),
                    text(batch, "qualityMetricDecisionProfileVersion"),
                    text(batch, "qualityMetricDecisionProfileDigest"),
                    text(batch, "qualityGateVersion"),
                    text(batch, "qualityGateDigest"),
                    instant(batch, "effectiveAt"),
                    instant(data, "occurredAt"),
                    text(data, "traceId"),
                    digest(payload));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) throw invalid();
        return value;
    }

    private static void verifyCrossBindings(
            JsonNode root,
            JsonNode data,
            JsonNode batch,
            JsonNode snapshot,
            UUID batchId,
            String sourceId) {
        UUID eventId = uuid(data, "eventId");
        UUID aggregateId = uuid(data, "aggregateId");
        uuid(data, "causationId");
        uuid(data, "correlationId");
        if (!eventId.equals(uuid(root, "id"))
                || !aggregateId.equals(batchId)
                || !batchId.equals(uuid(snapshot, "batchId"))
                || !sourceId.equals(text(snapshot, "sourceId"))) {
            throw invalid();
        }

        requireText(root, "specversion", "1.0");
        requireText(root, "datacontenttype", "application/json");
        requireText(root, "source", "urn:scholarsense:ingestion-quality");
        requireText(data, "aggregateType", "data-batch");
        requireText(data, "contractVersion", "PIC-1.0.0");
        requireText(data, "producer", "ingestion-quality");
        requireText(data, "runtimeEvidenceClaim", "none");
        if (!text(root, "subject").equals("data-batch/" + aggregateId)
                || !text(root, "time").equals(text(data, "occurredAt"))
                || !text(root, "traceparent").equals(traceparent(text(data, "traceId")))) {
            throw invalid();
        }
        instant(root, "time");

        for (String field : Set.of(
                "sourceId", "manifestDigest", "observationWindow", "cutoffAt", "watermark",
                "sourceSchemaVersion", "sourceSchemaDigest",
                "qualityMetricDecisionProfileVersion",
                "qualityMetricDecisionProfileDigest", "qualityGateVersion",
                "qualityGateDigest", "lineageId", "effectiveAt", "evaluatedAt")) {
            requireEqual(batch, field, snapshot, field);
        }

        String eventType = text(root, "type");
        String schemaVersion = text(data, "schemaVersion");
        long dataVersion = integer(data, "aggregateVersion");
        long batchVersion = integer(batch, "aggregateVersion");
        long snapshotVersion = integer(snapshot, "aggregateVersion");
        String batchStatus = text(batch, "status");
        String assessedStatus = text(snapshot, "assessedBatchStatus");
        String overallResult = text(snapshot, "overallResult");
        JsonNode publishedAt = batch.get("publishedAt");
        if ("scholarsense.ingestion-quality.data-batch.quality-assessed.v1".equals(eventType)) {
            if (!"DATA-BATCH-QUALITY-ASSESSED-1.0.0".equals(schemaVersion)
                    || dataVersion != 3 || batchVersion != 3 || snapshotVersion != 3
                    || !Set.of("quality-passed", "quality-failed").contains(batchStatus)
                    || publishedAt == null || !publishedAt.isNull()
                    || !batchStatus.equals(assessedStatus)
                    || !batchStatus.equals(overallResult)) {
                throw invalid();
            }
            return;
        }
        if (!"scholarsense.ingestion-quality.data-batch.published.v1".equals(eventType)
                || !"DATA-BATCH-PUBLISHED-1.0.0".equals(schemaVersion)
                || dataVersion != 4 || batchVersion != 4 || snapshotVersion != 3
                || !"published".equals(batchStatus)
                || !"quality-passed".equals(assessedStatus)
                || !"quality-passed".equals(overallResult)
                || publishedAt == null || !publishedAt.isTextual()
                || !publishedAt.stringValue().equals(text(data, "occurredAt"))) {
            throw invalid();
        }
    }

    private static void requireText(JsonNode parent, String field, String expected) {
        if (!expected.equals(text(parent, field))) throw invalid();
    }

    private static void requireEqual(
            JsonNode left, String leftField, JsonNode right, String rightField) {
        JsonNode leftValue = left.get(leftField);
        JsonNode rightValue = right.get(rightField);
        if (leftValue == null || !leftValue.equals(rightValue)) throw invalid();
    }

    private static String traceparent(String traceId) {
        if (!traceId.matches("^(?!0{32}$)[0-9a-f]{32}$")) throw invalid();
        String spanId = traceId.substring(0, 16);
        if ("0".repeat(16).equals(spanId)) spanId = traceId.substring(16);
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private static void requireObjectWithExactKeys(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject()) throw invalid();
        Set<String> actual = new java.util.HashSet<>();
        actual.addAll(value.propertyNames());
        if (!actual.equals(expected)) throw invalid();
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.stringValue().isBlank()) throw invalid();
        return value.stringValue();
    }

    private static long integer(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        return value.longValue();
    }

    private static UUID uuid(JsonNode parent, String field) {
        try {
            return UUID.fromString(text(parent, field));
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static UUID nullableUuid(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        return uuid(parent, field);
    }

    private static Instant instant(JsonNode parent, String field) {
        try {
            return Instant.parse(text(parent, field));
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static DataBatchStatus batchStatus(String value) {
        for (DataBatchStatus status : DataBatchStatus.values()) {
            if (status.wireValue().equals(value)) return status;
        }
        throw invalid();
    }

    private static QualityOverallResult snapshotResult(String value) {
        for (QualityOverallResult result : QualityOverallResult.values()) {
            if (result.wireValue().equals(value)) return result;
        }
        throw invalid();
    }

    private static String digest(byte[] payload) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_UPSTREAM_EVENT_INVALID");
    }
}
