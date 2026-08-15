package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservation;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationFence;
import java.util.Objects;
import java.util.UUID;

public record RecoveryObservationClaim(
        UUID jobId,
        long leaseGeneration,
        int attemptNumber,
        RecoveryObservation observation,
        RecoveryObservationFence currentFence,
        boolean allAffectedEligibilitiesRecovering) {

    public RecoveryObservationClaim {
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(observation);
        Objects.requireNonNull(currentFence);
        if (leaseGeneration < 1 || attemptNumber < 1 || attemptNumber > 8) {
            throw new IllegalArgumentException("RECOVERY_OBSERVATION_CLAIM_INVALID");
        }
    }
}
