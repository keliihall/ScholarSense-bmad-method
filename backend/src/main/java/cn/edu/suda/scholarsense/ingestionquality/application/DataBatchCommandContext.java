package cn.edu.suda.scholarsense.ingestionquality.application;

public record DataBatchCommandContext(
        String tenantId,
        String actorRef,
        String idempotencyKey,
        String traceId) {
    public DataBatchCommandContext {
        tenantId = DataBatchCommandRules.text(tenantId, 128);
        actorRef = DataBatchCommandRules.text(actorRef, 256);
        idempotencyKey = DataBatchCommandRules.text(idempotencyKey, 128);
        traceId = DataBatchCommandRules.traceId(traceId);
    }
}
