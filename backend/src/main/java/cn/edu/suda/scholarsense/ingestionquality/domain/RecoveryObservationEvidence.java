package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.List;
import java.util.Objects;

public record RecoveryObservationEvidence(
        RecoveryObservationEvidenceStatus requiredMembers,
        RecoveryObservationEvidenceStatus reconciliation,
        RecoveryObservationEvidenceStatus sample,
        RecoveryObservationEvidenceStatus sloFreshness) {

    public RecoveryObservationEvidence {
        Objects.requireNonNull(requiredMembers);
        Objects.requireNonNull(reconciliation);
        Objects.requireNonNull(sample);
        Objects.requireNonNull(sloFreshness);
    }

    public List<RecoveryObservationEvidenceStatus> values() {
        return List.of(requiredMembers, reconciliation, sample, sloFreshness);
    }
}
