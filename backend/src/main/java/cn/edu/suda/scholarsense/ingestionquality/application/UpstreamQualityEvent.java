package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.BatchObservationWindow;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityOverallResult;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Frozen Story 2.3 wire evidence; QSHM fields are deliberately absent. */
public record UpstreamQualityEvent(
        UUID eventId,
        String eventSource,
        String eventType,
        String schemaVersion,
        UUID batchId,
        String sourceId,
        long sourceVersion,
        UUID lineageId,
        UUID supersedesBatchId,
        long batchAggregateVersion,
        DataBatchStatus batchStatus,
        UUID snapshotId,
        String snapshotImmutableHash,
        long snapshotAggregateVersion,
        QualityOverallResult snapshotResult,
        String manifestDigest,
        String watermark,
        BatchObservationWindow observationWindow,
        Instant cutoffAt,
        String sourceSchemaVersion,
        String sourceSchemaDigest,
        String qmdpVersion,
        String qmdpDigest,
        String qualityGateVersion,
        String qualityGateDigest,
        Instant effectiveAt,
        Instant snapshotEffectiveAt,
        Instant occurredAt,
        String traceparent,
        String traceId,
        String payloadDigest) {

    public UpstreamQualityEvent {
        eventId = uuidV7(eventId);
        eventSource = text(eventSource, 256);
        eventType = text(eventType, 256);
        schemaVersion = text(schemaVersion, 128);
        batchId = uuidV7(batchId);
        if (sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$")) {
            throw invalid();
        }
        if (sourceVersion < 1 || sourceVersion > 9_007_199_254_740_991L) throw invalid();
        lineageId = uuidV7(lineageId);
        if (supersedesBatchId != null) supersedesBatchId = uuidV7(supersedesBatchId);
        if (batchAggregateVersion < 1) throw invalid();
        batchStatus = Objects.requireNonNull(batchStatus);
        snapshotId = uuidV7(snapshotId);
        snapshotImmutableHash = digest(snapshotImmutableHash);
        if (snapshotAggregateVersion < 1) throw invalid();
        snapshotResult = Objects.requireNonNull(snapshotResult);
        manifestDigest = digest(manifestDigest);
        watermark = text(watermark, 512);
        observationWindow = Objects.requireNonNull(observationWindow);
        cutoffAt = microsecond(cutoffAt);
        sourceSchemaVersion = text(sourceSchemaVersion, 128);
        sourceSchemaDigest = digest(sourceSchemaDigest);
        qmdpVersion = text(qmdpVersion, 128);
        qmdpDigest = digest(qmdpDigest);
        qualityGateVersion = text(qualityGateVersion, 128);
        qualityGateDigest = digest(qualityGateDigest);
        effectiveAt = microsecond(effectiveAt);
        snapshotEffectiveAt = microsecond(snapshotEffectiveAt);
        occurredAt = microsecond(occurredAt);
        if (traceId == null || !traceId.matches("^(?!0{32}$)[0-9a-f]{32}$")) {
            throw invalid();
        }
        if (traceparent == null || !traceparent.matches(
                "^00-" + traceId + "-(?!0{16})[0-9a-f]{16}-0[01]$")) {
            throw invalid();
        }
        payloadDigest = digest(payloadDigest);
    }

    /** Compatibility constructor for callers whose batch and snapshot times were historically equal. */
    public UpstreamQualityEvent(
            UUID eventId,
            String eventSource,
            String eventType,
            String schemaVersion,
            UUID batchId,
            String sourceId,
            long sourceVersion,
            UUID lineageId,
            UUID supersedesBatchId,
            long batchAggregateVersion,
            DataBatchStatus batchStatus,
            UUID snapshotId,
            String snapshotImmutableHash,
            long snapshotAggregateVersion,
            QualityOverallResult snapshotResult,
            String manifestDigest,
            String watermark,
            BatchObservationWindow observationWindow,
            Instant cutoffAt,
            String sourceSchemaVersion,
            String sourceSchemaDigest,
            String qmdpVersion,
            String qmdpDigest,
            String qualityGateVersion,
            String qualityGateDigest,
            Instant effectiveAt,
            Instant occurredAt,
            String traceparent,
            String traceId,
            String payloadDigest) {
        this(eventId, eventSource, eventType, schemaVersion, batchId, sourceId,
                sourceVersion, lineageId, supersedesBatchId, batchAggregateVersion,
                batchStatus, snapshotId, snapshotImmutableHash, snapshotAggregateVersion,
                snapshotResult, manifestDigest, watermark, observationWindow, cutoffAt,
                sourceSchemaVersion, sourceSchemaDigest, qmdpVersion, qmdpDigest,
                qualityGateVersion, qualityGateDigest, effectiveAt, effectiveAt, occurredAt,
                traceparent, traceId, payloadDigest);
    }

    /** Compatibility constructor for frozen 1.x fixtures with a deterministic parent span. */
    public UpstreamQualityEvent(
            UUID eventId,
            String eventSource,
            String eventType,
            String schemaVersion,
            UUID batchId,
            String sourceId,
            long sourceVersion,
            UUID lineageId,
            UUID supersedesBatchId,
            long batchAggregateVersion,
            DataBatchStatus batchStatus,
            UUID snapshotId,
            String snapshotImmutableHash,
            long snapshotAggregateVersion,
            QualityOverallResult snapshotResult,
            String manifestDigest,
            String watermark,
            BatchObservationWindow observationWindow,
            Instant cutoffAt,
            String sourceSchemaVersion,
            String sourceSchemaDigest,
            String qmdpVersion,
            String qmdpDigest,
            String qualityGateVersion,
            String qualityGateDigest,
            Instant effectiveAt,
            Instant snapshotEffectiveAt,
            Instant occurredAt,
            String traceId,
            String payloadDigest) {
        this(eventId, eventSource, eventType, schemaVersion, batchId, sourceId,
                sourceVersion, lineageId, supersedesBatchId, batchAggregateVersion,
                batchStatus, snapshotId, snapshotImmutableHash, snapshotAggregateVersion,
                snapshotResult, manifestDigest, watermark, observationWindow, cutoffAt,
                sourceSchemaVersion, sourceSchemaDigest, qmdpVersion, qmdpDigest,
                qualityGateVersion, qualityGateDigest, effectiveAt, snapshotEffectiveAt,
                occurredAt, legacyTraceparent(traceId), traceId, payloadDigest);
    }

    /** Compatibility constructor for frozen 1.x fixtures with equal batch/snapshot times. */
    public UpstreamQualityEvent(
            UUID eventId,
            String eventSource,
            String eventType,
            String schemaVersion,
            UUID batchId,
            String sourceId,
            long sourceVersion,
            UUID lineageId,
            UUID supersedesBatchId,
            long batchAggregateVersion,
            DataBatchStatus batchStatus,
            UUID snapshotId,
            String snapshotImmutableHash,
            long snapshotAggregateVersion,
            QualityOverallResult snapshotResult,
            String manifestDigest,
            String watermark,
            BatchObservationWindow observationWindow,
            Instant cutoffAt,
            String sourceSchemaVersion,
            String sourceSchemaDigest,
            String qmdpVersion,
            String qmdpDigest,
            String qualityGateVersion,
            String qualityGateDigest,
            Instant effectiveAt,
            Instant occurredAt,
            String traceId,
            String payloadDigest) {
        this(eventId, eventSource, eventType, schemaVersion, batchId, sourceId,
                sourceVersion, lineageId, supersedesBatchId, batchAggregateVersion,
                batchStatus, snapshotId, snapshotImmutableHash, snapshotAggregateVersion,
                snapshotResult, manifestDigest, watermark, observationWindow, cutoffAt,
                sourceSchemaVersion, sourceSchemaDigest, qmdpVersion, qmdpDigest,
                qualityGateVersion, qualityGateDigest, effectiveAt, effectiveAt, occurredAt,
                legacyTraceparent(traceId), traceId, payloadDigest);
    }

    public UpstreamQualityEvent asPublished(UUID newEventId, Instant at, String newDigest) {
        boolean successor = eventType.endsWith(".v2");
        return copy(newEventId, batchId, supersedesBatchId, at, watermark,
                successor ? "scholarsense.ingestion-quality.data-batch.published.v2"
                        : UpstreamQualityEventKind.PUBLISHED.eventType(),
                successor ? "DATA-BATCH-PUBLISHED-2.0.0"
                        : UpstreamQualityEventKind.PUBLISHED.schemaVersion(), 4,
                DataBatchStatus.PUBLISHED, newDigest);
    }

    public UpstreamQualityEvent withEventId(UUID value) {
        return copy(value, batchId, supersedesBatchId, occurredAt, watermark,
                eventType, schemaVersion, batchAggregateVersion, batchStatus, payloadDigest);
    }

    public UpstreamQualityEvent withBatchId(UUID value) {
        return copy(eventId, value, supersedesBatchId, occurredAt, watermark,
                eventType, schemaVersion, batchAggregateVersion, batchStatus, payloadDigest);
    }

    public UpstreamQualityEvent withSupersedesBatchId(UUID value) {
        return copy(eventId, batchId, value, occurredAt, watermark,
                eventType, schemaVersion, batchAggregateVersion, batchStatus, payloadDigest);
    }

    public UpstreamQualityEvent withOccurredAt(Instant value) {
        return copy(eventId, batchId, supersedesBatchId, value, watermark,
                eventType, schemaVersion, batchAggregateVersion, batchStatus, payloadDigest);
    }

    public UpstreamQualityEvent withWatermark(String value) {
        return copy(eventId, batchId, supersedesBatchId, occurredAt, value,
                eventType, schemaVersion, batchAggregateVersion, batchStatus, payloadDigest);
    }

    public UpstreamQualityEvent withPayloadDigest(String value) {
        return copy(eventId, batchId, supersedesBatchId, occurredAt, watermark,
                eventType, schemaVersion, batchAggregateVersion, batchStatus, value);
    }

    private UpstreamQualityEvent copy(
            UUID newEventId,
            UUID newBatchId,
            UUID newSupersedes,
            Instant newOccurredAt,
            String newWatermark,
            String newEventType,
            String newSchema,
            long newAggregateVersion,
            DataBatchStatus newStatus,
            String newPayloadDigest) {
        return new UpstreamQualityEvent(
                newEventId, eventSource, newEventType, newSchema, newBatchId, sourceId,
                sourceVersion, lineageId, newSupersedes, newAggregateVersion, newStatus,
                snapshotId, snapshotImmutableHash, snapshotAggregateVersion, snapshotResult,
                manifestDigest, newWatermark, observationWindow, cutoffAt,
                sourceSchemaVersion, sourceSchemaDigest, qmdpVersion, qmdpDigest,
                qualityGateVersion, qualityGateDigest, effectiveAt, snapshotEffectiveAt,
                newOccurredAt,
                traceparent, traceId, newPayloadDigest);
    }

    private static String legacyTraceparent(String traceId) {
        if (traceId == null || !traceId.matches("(?!0{32})[0-9a-f]{32}")) {
            throw invalid();
        }
        String spanId = traceId.substring(0, 16);
        if (spanId.equals("0".repeat(16))) {
            spanId = traceId.substring(16);
        }
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private static UUID uuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) throw invalid();
        return value;
    }

    private static String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maximum) {
            throw invalid();
        }
        return value;
    }

    private static String digest(String value) {
        if (value == null || !value.matches("^sha256:[0-9a-f]{64}$")) throw invalid();
        return value;
    }

    private static Instant microsecond(Instant value) {
        if (value == null || value.getNano() % 1_000 != 0) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_UPSTREAM_EVENT_INVALID");
    }
}
