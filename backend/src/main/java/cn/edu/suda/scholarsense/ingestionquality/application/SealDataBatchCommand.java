package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.BatchManifest;
import java.util.Objects;
import java.util.UUID;

public record SealDataBatchCommand(
        UUID batchId,
        long expectedAggregateVersion,
        BatchManifest manifest,
        DataBatchCommandContext context) {
    public SealDataBatchCommand {
        batchId = DataBatchCommandRules.uuidV7(batchId);
        expectedAggregateVersion = DataBatchCommandRules.expectedVersion(expectedAggregateVersion);
        Objects.requireNonNull(manifest);
        Objects.requireNonNull(context);
    }
}
