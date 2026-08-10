package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RecordQualityImpactScopeCommand(
        UUID batchId,
        String scopeCode,
        Instant recordedAt) {
    public RecordQualityImpactScopeCommand {
        batchId = DataBatchCommandRules.uuidV7(batchId);
        scopeCode = DataBatchCommandRules.impactScopeCode(scopeCode);
        Objects.requireNonNull(recordedAt);
        if (recordedAt.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
    }
}
