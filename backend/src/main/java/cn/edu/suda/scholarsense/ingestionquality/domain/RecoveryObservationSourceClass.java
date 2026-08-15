package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Duration;

public enum RecoveryObservationSourceClass {
    STREAMING(3, Duration.ofMinutes(60), "PT60M"),
    DAILY_BATCH(2, Duration.ofDays(1), "P1D");

    private final int requiredConsecutivePassedBatches;
    private final Duration observationDuration;
    private final String canonicalDuration;

    RecoveryObservationSourceClass(
            int requiredConsecutivePassedBatches,
            Duration observationDuration,
            String canonicalDuration) {
        this.requiredConsecutivePassedBatches = requiredConsecutivePassedBatches;
        this.observationDuration = observationDuration;
        this.canonicalDuration = canonicalDuration;
    }

    public int requiredConsecutivePassedBatches() {
        return requiredConsecutivePassedBatches;
    }

    public Duration observationDuration() {
        return observationDuration;
    }

    public String canonicalDuration() {
        return canonicalDuration;
    }
}
