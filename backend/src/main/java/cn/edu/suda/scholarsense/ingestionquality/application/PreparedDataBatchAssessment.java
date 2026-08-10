package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatch;
import cn.edu.suda.scholarsense.ingestionquality.domain.SealedQualityContractEvidence;
import java.util.Objects;

/** Non-forgeable result that may be persisted only after final contract revalidation. */
public final class PreparedDataBatchAssessment {
    private final DataBatch updatedBatch;
    private final VerifiedQualitySnapshot snapshot;
    private final SealedQualityContractEvidence contractEvidence;

    PreparedDataBatchAssessment(
            DataBatch updatedBatch,
            VerifiedQualitySnapshot snapshot,
            SealedQualityContractEvidence contractEvidence) {
        this.updatedBatch = Objects.requireNonNull(updatedBatch);
        this.snapshot = Objects.requireNonNull(snapshot);
        this.contractEvidence = Objects.requireNonNull(contractEvidence);
    }

    public DataBatch updatedBatch() { return updatedBatch; }

    public VerifiedQualitySnapshot snapshot() { return snapshot; }

    public SealedQualityContractEvidence contractEvidence() { return contractEvidence; }
}
