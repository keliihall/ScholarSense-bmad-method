package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;
import java.util.UUID;

public record EvaluateDataBatchCommand(
        UUID batchId,
        long expectedAggregateVersion,
        DataBatchCommandContext context) {
    public EvaluateDataBatchCommand {
        batchId = DataBatchCommandRules.uuidV7(batchId);
        expectedAggregateVersion = DataBatchCommandRules.expectedVersion(expectedAggregateVersion);
        Objects.requireNonNull(context);
    }
}
