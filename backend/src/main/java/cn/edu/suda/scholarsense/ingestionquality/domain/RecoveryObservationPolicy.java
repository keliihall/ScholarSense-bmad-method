package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Pure QOFP-1.0.0 evaluator. Finalization remains a separate owner command. */
public final class RecoveryObservationPolicy {
    public RecoveryObservationDecision evaluate(
            RecoveryObservation observation,
            RecoveryObservationFence current,
            boolean allAffectedEligibilitiesRecovering,
            Instant trustedNow) {
        Objects.requireNonNull(observation);
        Objects.requireNonNull(current);
        RecoveryObservationFact.requireMicrosecond(trustedNow);
        if (trustedNow.isBefore(observation.recoveringStartedAt())) {
            throw RecoveryObservationFact.invalid();
        }
        Duration observedDuration = Duration.between(
                observation.recoveringStartedAt(), trustedNow);
        int required = observation.sourceClass().requiredConsecutivePassedBatches();
        Duration requiredDuration = observation.sourceClass().observationDuration();

        if (!allAffectedEligibilitiesRecovering
                || !observation.policyVersion().equals(current.policyVersion())
                || !observation.policyDigest().equals(current.policyDigest())
                || !observation.memberSetDigest().equals(current.memberSetDigest())
                || !observation.watermarksDigest().equals(current.watermarksDigest())) {
            return decision(RecoveryObservationStatus.POLICY_DRIFT,
                    RecoveryObservationReason.CURRENT_FACT_DRIFT, 0, required,
                    observedDuration, requiredDuration, null, false);
        }
        if (observation.facts().stream().anyMatch(fact ->
                fact.stage() == RecoveryObservationFact.Stage.VERIFIED_QUALITY_FAILURE)
                || observation.evidence().values().contains(
                        RecoveryObservationEvidenceStatus.VERIFIED_FAILED)) {
            return decision(RecoveryObservationStatus.RELAPSED,
                    RecoveryObservationReason.VERIFIED_QUALITY_FAILURE, 0, required,
                    observedDuration, requiredDuration, null, true);
        }
        if (observation.evidence().values().contains(
                RecoveryObservationEvidenceStatus.UNAVAILABLE)) {
            return decision(RecoveryObservationStatus.DEPENDENCY_UNAVAILABLE,
                    RecoveryObservationReason.TECHNICAL_UNAVAILABLE, 0, required,
                    observedDuration, requiredDuration, null, false);
        }
        if (observation.evidence().values().contains(
                RecoveryObservationEvidenceStatus.UNKNOWN)) {
            return decision(RecoveryObservationStatus.NOT_READY,
                    RecoveryObservationReason.EVIDENCE_UNKNOWN, 0, required,
                    observedDuration, requiredDuration, null, false);
        }

        PairResult pairs = qualify(observation.facts());
        if (pairs.reason != null) {
            return decision(RecoveryObservationStatus.NOT_READY, pairs.reason,
                    pairs.count, required, observedDuration, requiredDuration,
                    pairs.watermark, false);
        }
        if (pairs.count == 0) {
            return decision(RecoveryObservationStatus.NOT_READY,
                    RecoveryObservationReason.NO_DATA, 0, required,
                    observedDuration, requiredDuration, null, false);
        }
        if (pairs.count < required) {
            return decision(RecoveryObservationStatus.NOT_READY,
                    RecoveryObservationReason.CONSECUTIVE_BATCHES_INSUFFICIENT,
                    pairs.count, required, observedDuration, requiredDuration,
                    pairs.watermark, false);
        }
        if (observedDuration.compareTo(requiredDuration) < 0) {
            return decision(RecoveryObservationStatus.NOT_READY,
                    RecoveryObservationReason.DURATION_INCOMPLETE,
                    pairs.count, required, observedDuration, requiredDuration,
                    pairs.watermark, false);
        }
        return decision(RecoveryObservationStatus.READY,
                RecoveryObservationReason.READY, pairs.count, required,
                observedDuration, requiredDuration, pairs.watermark, false);
    }

    private static PairResult qualify(List<RecoveryObservationFact> facts) {
        Map<Long, List<RecoveryObservationFact>> byOrdinal = new TreeMap<>();
        for (RecoveryObservationFact fact : facts) {
            if (fact.stage() != RecoveryObservationFact.Stage.VERIFIED_QUALITY_FAILURE) {
                byOrdinal.computeIfAbsent(fact.sourceVersionOrdinal(), ignored ->
                        new ArrayList<>()).add(fact);
            }
        }
        long previous = -1;
        int count = 0;
        String watermark = null;
        for (Map.Entry<Long, List<RecoveryObservationFact>> entry : byOrdinal.entrySet()) {
            if (previous != -1 && entry.getKey() != previous + 1) {
                return new PairResult(count, watermark,
                        RecoveryObservationReason.SEQUENCE_GAP);
            }
            List<RecoveryObservationFact> pair = entry.getValue();
            if (pair.size() != 2) {
                return new PairResult(count, watermark,
                        RecoveryObservationReason.POISONED_PAIR);
            }
            RecoveryObservationFact assessed = pair.stream()
                    .filter(value -> value.stage()
                            == RecoveryObservationFact.Stage.ASSESSED_PASSED)
                    .findFirst().orElse(null);
            RecoveryObservationFact published = pair.stream()
                    .filter(value -> value.stage() == RecoveryObservationFact.Stage.PUBLISHED)
                    .findFirst().orElse(null);
            if (assessed == null || published == null
                    || !assessed.batchId().equals(published.batchId())
                    || !assessed.snapshotId().equals(published.snapshotId())
                    || assessed.lineageRevision() != published.lineageRevision()
                    || !assessed.pairingDigest().equals(published.pairingDigest())
                    || published.occurredAt().isBefore(assessed.occurredAt())) {
                return new PairResult(count, watermark,
                        RecoveryObservationReason.POISONED_PAIR);
            }
            count++;
            watermark = published.watermark();
            previous = entry.getKey();
        }
        return new PairResult(count, watermark, null);
    }

    private static RecoveryObservationDecision decision(
            RecoveryObservationStatus status,
            RecoveryObservationReason reason,
            int count,
            int required,
            Duration observed,
            Duration target,
            String watermark,
            boolean failure) {
        return new RecoveryObservationDecision(
                status, reason, count, required, observed, target, watermark, failure);
    }

    private record PairResult(
            int count, String watermark, RecoveryObservationReason reason) {}
}
