package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;
import java.util.UUID;

public record PublishDataBatchCommand(
        UUID batchId,
        long expectedAggregateVersion,
        DataBatchCommandContext context) {
    public PublishDataBatchCommand {
        batchId = DataBatchCommandRules.uuidV7(batchId);
        expectedAggregateVersion = DataBatchCommandRules.expectedVersion(expectedAggregateVersion);
        Objects.requireNonNull(context);
    }
}
