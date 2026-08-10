package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AppendNormalizedFactCommand(
        UUID batchId,
        String recordId,
        String sourceId,
        String businessKey,
        long sourceVersion,
        String sourceSchemaVersion,
        String sourceSchemaDigest,
        UUID lineageId,
        String contentDigest,
        Instant acceptedAt) {
    public AppendNormalizedFactCommand {
        batchId = DataBatchCommandRules.uuidV7(batchId);
        recordId = DataBatchCommandRules.text(recordId, 1024);
        sourceId = DataBatchCommandRules.text(sourceId, 128);
        businessKey = DataBatchCommandRules.text(businessKey, 1024);
        if (sourceVersion < 1) throw invalid();
        sourceSchemaVersion = DataBatchCommandRules.text(sourceSchemaVersion, 128);
        sourceSchemaDigest = DataBatchCommandRules.digest(sourceSchemaDigest);
        lineageId = DataBatchCommandRules.uuidV7(lineageId);
        contentDigest = DataBatchCommandRules.digest(contentDigest);
        Objects.requireNonNull(acceptedAt);
        if (acceptedAt.getNano() % 1_000 != 0) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
    }
}
