package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.BatchManifest;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatch;
import java.util.Objects;
import java.util.UUID;

/** Exact sealed-batch identity returned alongside measured operands. */
public record QualityMeasurementAnchor(
        UUID batchId,
        long sealedAggregateVersion,
        String sourceId,
        BatchManifest manifest,
        UUID lineageId,
        UUID supersedesBatchId) {

    public QualityMeasurementAnchor {
        Objects.requireNonNull(batchId);
        if (sealedAggregateVersion < 1) throw invalid();
        sourceId = required(sourceId);
        Objects.requireNonNull(manifest);
        Objects.requireNonNull(lineageId);
    }

    public static QualityMeasurementAnchor from(DataBatch sealedBatch) {
        Objects.requireNonNull(sealedBatch);
        BatchManifest manifest = Objects.requireNonNull(sealedBatch.manifest());
        return new QualityMeasurementAnchor(
                sealedBatch.batchId(), sealedBatch.aggregateVersion(),
                sealedBatch.identity().sourceId(), manifest, sealedBatch.lineage().lineageId(),
                sealedBatch.lineage().supersedesBatchId());
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw invalid();
        return value;
    }

    private static IngestionQualityApplicationException invalid() {
        return new IngestionQualityApplicationException("INGESTION_QUALITY_EVIDENCE_INVALID");
    }
}
