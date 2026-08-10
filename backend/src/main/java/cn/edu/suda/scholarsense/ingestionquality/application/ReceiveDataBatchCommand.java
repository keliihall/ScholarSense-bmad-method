package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.BatchIdentity;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchLineage;
import java.util.Objects;
import java.util.UUID;

public record ReceiveDataBatchCommand(
        UUID batchId,
        long expectedAggregateVersion,
        BatchIdentity identity,
        BatchLineage lineage,
        String declaredManifestDigest,
        DataBatchCommandContext context) {
    public ReceiveDataBatchCommand {
        batchId = DataBatchCommandRules.uuidV7(batchId);
        if (expectedAggregateVersion != 0) {
            throw new IllegalArgumentException("INGESTION_QUALITY_EXPECTED_VERSION_INVALID");
        }
        Objects.requireNonNull(identity);
        Objects.requireNonNull(lineage);
        declaredManifestDigest = DataBatchCommandRules.digest(declaredManifestDigest);
        Objects.requireNonNull(context);
    }
}
