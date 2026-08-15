package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable, PII-free fact derived from sealed quality evidence. */
public record RecoveryObservationFact(
        UUID eventId,
        UUID batchId,
        UUID snapshotId,
        long sourceVersionOrdinal,
        long lineageRevision,
        Stage stage,
        String pairingDigest,
        String watermark,
        Instant occurredAt) {

    public RecoveryObservationFact {
        requireUuidV7(eventId);
        requireUuidV7(batchId);
        requireUuidV7(snapshotId);
        if (sourceVersionOrdinal < 1
                || sourceVersionOrdinal > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || lineageRevision < 0
                || lineageRevision > IngestionQualityDomainRules.MAX_SAFE_VERSION) {
            throw invalid();
        }
        Objects.requireNonNull(stage);
        requireDigest(pairingDigest);
        if (watermark == null || watermark.isBlank() || watermark.length() > 256) {
            throw invalid();
        }
        requireMicrosecond(occurredAt);
    }

    public enum Stage {
        ASSESSED_PASSED,
        PUBLISHED,
        VERIFIED_QUALITY_FAILURE
    }

    static Instant requireMicrosecond(Instant value) {
        if (value == null || value.getNano() % 1_000 != 0) throw invalid();
        return value;
    }

    static void requireDigest(String value) {
        if (value == null || !value.matches("^sha256:[0-9a-f]{64}$")) throw invalid();
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) throw invalid();
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("RECOVERY_OBSERVATION_INVALID");
    }
}
