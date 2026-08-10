package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Typed PIC-1.0.0 business envelope identity and aggregate binding. */
public record DataBatchBusinessOutboxEvent(
        UUID eventId,
        UUID commandId,
        UUID causationId,
        UUID batchId,
        long aggregateVersion,
        String eventType,
        String schemaVersion,
        String traceId,
        Instant occurredAt,
        DataBatchView batch,
        QualitySnapshot qualitySnapshot) {
    public DataBatchBusinessOutboxEvent {
        eventId = DataBatchCommandRules.uuidV7(eventId);
        commandId = DataBatchCommandRules.uuidV7(commandId);
        causationId = DataBatchCommandRules.uuidV7(causationId);
        batchId = DataBatchCommandRules.uuidV7(batchId);
        aggregateVersion = DataBatchCommandRules.expectedVersion(aggregateVersion);
        eventType = DataBatchCommandRules.text(eventType, 256);
        schemaVersion = DataBatchCommandRules.text(schemaVersion, 128);
        traceId = DataBatchCommandRules.traceId(traceId);
        Objects.requireNonNull(occurredAt);
        batch = Objects.requireNonNull(batch);
        qualitySnapshot = Objects.requireNonNull(qualitySnapshot);
        if (occurredAt.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
        if (!batch.batchId().equals(batchId)
                || batch.aggregateVersion() != aggregateVersion
                || !qualitySnapshot.batchId().equals(batchId)
                || !qualitySnapshot.sourceId().equals(batch.identity().sourceId())
                || !qualitySnapshot.lineageId().equals(batch.lineage().lineageId())
                || !qualitySnapshot.manifestDigest().equals(batch.manifest().manifestDigest())) {
            throw new IllegalArgumentException("INGESTION_QUALITY_CANONICAL_PAYLOAD_INVALID");
        }
    }
}
