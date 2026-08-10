package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.Optional;

public interface DataBatchIdempotencyPort {
    Optional<DataBatchIdempotencyResult> find(DataBatchIdempotencyScope scope, Instant at);
    DataBatchIdempotencyClaim claim(
            DataBatchIdempotencyScope scope, String requestDigest, Instant at);
    void complete(DataBatchIdempotencyResult result);
}
