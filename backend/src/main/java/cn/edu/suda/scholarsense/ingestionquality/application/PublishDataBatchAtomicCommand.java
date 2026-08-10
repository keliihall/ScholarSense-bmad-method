package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatch;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import java.util.Objects;

public record PublishDataBatchAtomicCommand(
        long expectedAggregateVersion,
        DataBatch publishedBatch,
        DataBatchAtomicCommitContext commit,
        CanonicalOutboxPayload outbox) {
    public PublishDataBatchAtomicCommand {
        expectedAggregateVersion = DataBatchCommandRules.expectedVersion(
                expectedAggregateVersion);
        Objects.requireNonNull(publishedBatch);
        Objects.requireNonNull(commit);
        Objects.requireNonNull(outbox);
        if (publishedBatch.status() != DataBatchStatus.PUBLISHED
                || publishedBatch.aggregateVersion() != expectedAggregateVersion + 1) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
    }
}
