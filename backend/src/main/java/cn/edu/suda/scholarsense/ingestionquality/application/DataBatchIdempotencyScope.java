package cn.edu.suda.scholarsense.ingestionquality.application;

public record DataBatchIdempotencyScope(
        String tenantId,
        String actorRef,
        DataBatchCommandType commandType,
        String idempotencyKey) {
    public DataBatchIdempotencyScope {
        tenantId = DataBatchCommandRules.text(tenantId, 128);
        actorRef = DataBatchCommandRules.text(actorRef, 256);
        if (commandType == null) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
        idempotencyKey = DataBatchCommandRules.text(idempotencyKey, 128);
    }

    public static DataBatchIdempotencyScope of(
            DataBatchCommandContext context, DataBatchCommandType commandType) {
        return new DataBatchIdempotencyScope(
                context.tenantId(), context.actorRef(), commandType, context.idempotencyKey());
    }

    public String digest() {
        return DataBatchCommandFingerprint.scopeDigest(this);
    }
}
