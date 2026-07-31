package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface ResponsibilitySloEvidencePort {
    void append(ResponsibilitySloEvidence evidence);

    default void compensate(
            ResponsibilitySloEvidence evidence,
            String reasonCode) {}

    default Optional<ResponsibilitySloEvidence>
            nextCompensation() {
        return Optional.empty();
    }

    default void completeCompensation(
            UUID evidenceId, Instant completedAt) {}

    default ResponsibilitySloWindow rollingThirtyDays(
            Instant now) {
        return new ResponsibilitySloWindow(0, 0);
    }
}
