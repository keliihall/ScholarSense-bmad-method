package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;
import java.util.Optional;

/** Database-linearized precedence observed for an idempotent batch command. */
public record DataBatchCommandPrecedence(
        Status status, Optional<DataBatchIdempotencyResult> replay) {
    public enum Status { FRESH, MISMATCH, REPLAY }

    public DataBatchCommandPrecedence {
        Objects.requireNonNull(status);
        replay = replay == null ? Optional.empty() : replay;
        if ((status == Status.REPLAY) != replay.isPresent()) {
            throw new IllegalArgumentException(
                    "INGESTION_QUALITY_IDEMPOTENCY_PRECEDENCE_INVALID");
        }
    }

    public static DataBatchCommandPrecedence fresh() {
        return new DataBatchCommandPrecedence(Status.FRESH, Optional.empty());
    }

    public static DataBatchCommandPrecedence mismatch() {
        return new DataBatchCommandPrecedence(Status.MISMATCH, Optional.empty());
    }

    public static DataBatchCommandPrecedence replay(DataBatchIdempotencyResult result) {
        return new DataBatchCommandPrecedence(Status.REPLAY, Optional.of(result));
    }
}
