package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Separates observation readiness from the owner command that can finalize it. */
public final class RecoveryFinalizationCandidatePolicy {
    public Optional<Candidate> evaluate(
            UUID recoveryId,
            long expectedGeneration,
            RecoveryObservationDecision observation,
            List<EligibilityFence> eligibilities) {
        Objects.requireNonNull(recoveryId);
        Objects.requireNonNull(observation);
        List<EligibilityFence> ordered = List.copyOf(Objects.requireNonNull(eligibilities))
                .stream().sorted(Comparator.comparing(EligibilityFence::eligibilityId)).toList();
        if (!observation.ready() || expectedGeneration < 1 || ordered.isEmpty()
                || ordered.stream().anyMatch(value ->
                        value.generation() != expectedGeneration
                                || value.status() != QualityEligibilityStatus.RECOVERING)) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(
                recoveryId, expectedGeneration,
                ordered.stream().map(EligibilityFence::eligibilityId).toList(),
                observation.finalWatermark()));
    }

    public record EligibilityFence(
            UUID eligibilityId,
            long generation,
            long aggregateVersion,
            QualityEligibilityStatus status) {
        public EligibilityFence {
            Objects.requireNonNull(eligibilityId);
            Objects.requireNonNull(status);
            if (generation < 1 || aggregateVersion < 1) {
                throw RecoveryObservationFact.invalid();
            }
        }
    }

    public record Candidate(
            UUID recoveryId,
            long generation,
            List<UUID> eligibilityIds,
            String finalObservationWatermark) {
        public Candidate {
            eligibilityIds = List.copyOf(eligibilityIds);
            if (eligibilityIds.isEmpty() || finalObservationWatermark == null) {
                throw RecoveryObservationFact.invalid();
            }
        }
    }
}
