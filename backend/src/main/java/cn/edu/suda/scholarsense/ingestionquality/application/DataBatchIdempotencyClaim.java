package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Optional;

public record DataBatchIdempotencyClaim(Status status, Optional<DataBatchIdempotencyResult> result) {
    public enum Status { FRESH, REPLAY, MISMATCH }

    public DataBatchIdempotencyClaim {
        result = result == null ? Optional.empty() : result;
        if (status == null || (status == Status.REPLAY) != result.isPresent()) {
            throw new IllegalArgumentException("INGESTION_QUALITY_IDEMPOTENCY_CLAIM_INVALID");
        }
    }

    public static DataBatchIdempotencyClaim fresh() {
        return new DataBatchIdempotencyClaim(Status.FRESH, Optional.empty());
    }

    public static DataBatchIdempotencyClaim replay(DataBatchIdempotencyResult result) {
        return new DataBatchIdempotencyClaim(Status.REPLAY, Optional.of(result));
    }

    public static DataBatchIdempotencyClaim mismatch() {
        return new DataBatchIdempotencyClaim(Status.MISMATCH, Optional.empty());
    }
}
