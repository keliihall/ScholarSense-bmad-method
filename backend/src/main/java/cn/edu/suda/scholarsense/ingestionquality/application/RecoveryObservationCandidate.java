package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;
import java.util.UUID;

public record RecoveryObservationCandidate(UUID jobId) {
    public RecoveryObservationCandidate {
        Objects.requireNonNull(jobId);
    }
}
