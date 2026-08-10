package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatch;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import java.util.Objects;

public record ReceiveDataBatchAtomicCommand(
        DataBatch receivedBatch,
        DataBatchAtomicCommitContext commit,
        CanonicalOutboxPayload outbox) {
    public ReceiveDataBatchAtomicCommand {
        Objects.requireNonNull(receivedBatch);
        Objects.requireNonNull(commit);
        Objects.requireNonNull(outbox);
        if (receivedBatch.status() != DataBatchStatus.RECEIVING
                || receivedBatch.aggregateVersion() != 1) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
    }
}
