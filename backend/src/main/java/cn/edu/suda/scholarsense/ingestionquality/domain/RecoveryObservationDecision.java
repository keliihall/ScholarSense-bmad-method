package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Duration;
import java.util.Objects;

public record RecoveryObservationDecision(
        RecoveryObservationStatus status,
        RecoveryObservationReason reason,
        int consecutivePassedBatches,
        int requiredConsecutivePassedBatches,
        Duration observedDuration,
        Duration requiredDuration,
        String finalWatermark,
        boolean verifiedBusinessFailure) {

    public RecoveryObservationDecision {
        Objects.requireNonNull(status);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(observedDuration);
        Objects.requireNonNull(requiredDuration);
        if (consecutivePassedBatches < 0 || requiredConsecutivePassedBatches < 1
                || observedDuration.isNegative() || requiredDuration.isNegative()
                || (status == RecoveryObservationStatus.RELAPSED)
                    != verifiedBusinessFailure
                || (status == RecoveryObservationStatus.READY && finalWatermark == null)) {
            throw RecoveryObservationFact.invalid();
        }
    }

    public boolean ready() {
        return status == RecoveryObservationStatus.READY;
    }
}
