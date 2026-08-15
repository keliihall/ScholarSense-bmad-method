package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable observation aggregate. Its clock starts at the committed recovering transition. */
public record RecoveryObservation(
        UUID recoveryId,
        long generation,
        String sourceId,
        RecoveryObservationSourceClass sourceClass,
        Instant recoveringStartedAt,
        String policyVersion,
        String policyDigest,
        String memberSetDigest,
        String watermarksDigest,
        List<RecoveryObservationFact> facts,
        RecoveryObservationEvidence evidence) {

    public RecoveryObservation {
        if (recoveryId == null || recoveryId.version() != 7 || recoveryId.variant() != 2
                || generation < 1
                || generation > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || sourceId == null
                || !sourceId.matches("^SRC-P[01]-[A-Z0-9-]{3,48}$")
                || !"QRP-1.0.0".equals(policyVersion)) {
            throw RecoveryObservationFact.invalid();
        }
        Objects.requireNonNull(sourceClass);
        RecoveryObservationFact.requireMicrosecond(recoveringStartedAt);
        RecoveryObservationFact.requireDigest(policyDigest);
        RecoveryObservationFact.requireDigest(memberSetDigest);
        RecoveryObservationFact.requireDigest(watermarksDigest);
        facts = List.copyOf(Objects.requireNonNull(facts)).stream()
                .sorted(Comparator.comparingLong(RecoveryObservationFact::sourceVersionOrdinal)
                        .thenComparingLong(RecoveryObservationFact::lineageRevision)
                        .thenComparing(RecoveryObservationFact::occurredAt)
                        .thenComparing(value -> value.stage().ordinal()))
                .toList();
        if (facts.size() > 10_000
                || facts.stream().anyMatch(value -> value.occurredAt().isBefore(
                        recoveringStartedAt))) {
            throw RecoveryObservationFact.invalid();
        }
        Objects.requireNonNull(evidence);
    }
}
