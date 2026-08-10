package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.BatchIdentity;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchLineage;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchManifest;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatch;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import java.time.Instant;
import java.util.UUID;

public record DataBatchView(
        UUID batchId,
        BatchIdentity identity,
        BatchLineage lineage,
        String declaredManifestDigest,
        DataBatchStatus status,
        long aggregateVersion,
        BatchManifest manifest,
        Instant receivedAt,
        Instant sealedAt,
        Instant evaluatedAt,
        Instant publishedAt,
        String traceId) {
    public static DataBatchView from(DataBatch batch) {
        return new DataBatchView(
                batch.batchId(), batch.identity(), batch.lineage(),
                batch.declaredManifestDigest(), batch.status(), batch.aggregateVersion(),
                batch.manifest(), batch.receivedAt(), batch.sealedAt(), batch.evaluatedAt(),
                batch.publishedAt(), batch.traceId());
    }
}
