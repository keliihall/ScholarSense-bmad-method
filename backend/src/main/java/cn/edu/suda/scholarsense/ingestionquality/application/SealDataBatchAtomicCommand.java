package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatch;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.SealedQualityContractEvidence;
import java.util.Objects;

public record SealDataBatchAtomicCommand(
        long expectedAggregateVersion,
        DataBatch sealedBatch,
        SealedQualityContractEvidence contractEvidence,
        DataBatchAtomicCommitContext commit,
        CanonicalOutboxPayload outbox) {
    public SealDataBatchAtomicCommand {
        expectedAggregateVersion = DataBatchCommandRules.expectedVersion(
                expectedAggregateVersion);
        Objects.requireNonNull(sealedBatch);
        Objects.requireNonNull(contractEvidence);
        Objects.requireNonNull(commit);
        Objects.requireNonNull(outbox);
        if (sealedBatch.status() != DataBatchStatus.SEALED
                || sealedBatch.aggregateVersion() != expectedAggregateVersion + 1
                || !contractEvidence.equals(sealedBatch.sealedQualityContractEvidence())) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
    }
}
