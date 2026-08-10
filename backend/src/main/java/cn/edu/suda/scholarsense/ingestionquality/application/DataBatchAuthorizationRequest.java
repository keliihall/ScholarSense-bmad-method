package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.UUID;

public record DataBatchAuthorizationRequest(
        DataBatchCommandContext context,
        DataBatchCommandType commandType,
        String sourceId,
        UUID batchId,
        long aggregateVersion) {
}
