package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshotHashProfile;
import java.util.Objects;

/** A typed QMDP/QSHM pair whose complete controlled chain has been verified. */
public record VerifiedQualityContract(
        ExecutableQualityPolicy policy,
        QualitySnapshotHashProfile hashProfile,
        QualityContractAttestation attestation) {
    public VerifiedQualityContract {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(hashProfile);
        Objects.requireNonNull(attestation);
    }
}
