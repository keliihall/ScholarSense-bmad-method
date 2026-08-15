package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Owner-transaction progress projected only from strict paired business sequence facts. */
public record RecoveryObservationProgressState(
        UUID recoveryId,
        long generation,
        String sourceId,
        String dependencyId,
        Instant recoveringStartedAt,
        long lastSourceVersionOrdinal,
        long lastLineageRevision,
        int consecutivePassedBatches,
        String watermark,
        Instant lastObservedAt,
        RecoveryObservationProgressStatus status,
        long aggregateVersion) {

    public RecoveryObservationProgressState {
        if (recoveryId == null || recoveryId.version() != 7 || recoveryId.variant() != 2
                || generation < 1
                || sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z0-9-]{3,48}$")
                || dependencyId == null
                || !dependencyId.matches("^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$")
                || !microsecond(recoveringStartedAt)
                || lastSourceVersionOrdinal < 0 || lastLineageRevision < 0
                || consecutivePassedBatches < 0 || !microsecond(lastObservedAt)
                || lastObservedAt.isBefore(recoveringStartedAt)
                || aggregateVersion < 0) {
            throw invalid();
        }
        Objects.requireNonNull(status);
        if (lastSourceVersionOrdinal == 0
                != (consecutivePassedBatches == 0 && watermark == null
                    && aggregateVersion == 0)) {
            throw invalid();
        }
        if (watermark != null && (watermark.isBlank() || watermark.length() > 256)) {
            throw invalid();
        }
    }

    public static RecoveryObservationProgressState start(
            UUID recoveryId,
            long generation,
            String sourceId,
            String dependencyId,
            Instant recoveringStartedAt) {
        return new RecoveryObservationProgressState(
                recoveryId, generation, sourceId, dependencyId, recoveringStartedAt,
                0, 0, 0, null, recoveringStartedAt,
                RecoveryObservationProgressStatus.OBSERVING, 0);
    }

    public RecoveryObservationProgressState passed(
            long sourceVersionOrdinal,
            long lineageRevision,
            String nextWatermark,
            Instant observedAt) {
        boolean correction = validateNext(
                sourceVersionOrdinal, lineageRevision, nextWatermark, observedAt);
        if (status != RecoveryObservationProgressStatus.OBSERVING
                && status != RecoveryObservationProgressStatus.READY) throw invalid();
        return new RecoveryObservationProgressState(
                recoveryId, generation, sourceId, dependencyId, recoveringStartedAt,
                sourceVersionOrdinal, lineageRevision,
                consecutivePassedBatches + (correction ? 0 : 1),
                nextWatermark, observedAt, RecoveryObservationProgressStatus.OBSERVING,
                aggregateVersion + 1);
    }

    public RecoveryObservationProgressState relapsed(
            long sourceVersionOrdinal,
            long lineageRevision,
            String nextWatermark,
            Instant observedAt) {
        validateNext(sourceVersionOrdinal, lineageRevision, nextWatermark, observedAt);
        if (status != RecoveryObservationProgressStatus.OBSERVING
                && status != RecoveryObservationProgressStatus.READY) throw invalid();
        return new RecoveryObservationProgressState(
                recoveryId, generation, sourceId, dependencyId, recoveringStartedAt,
                sourceVersionOrdinal, lineageRevision, consecutivePassedBatches,
                nextWatermark, observedAt, RecoveryObservationProgressStatus.RELAPSED,
                aggregateVersion + 1);
    }

    public String key() {
        return QualityEligibilityProcessingState.episodeKey(sourceId, dependencyId);
    }

    private boolean validateNext(
            long sourceVersionOrdinal,
            long lineageRevision,
            String nextWatermark,
            Instant observedAt) {
        boolean directCorrection = lastSourceVersionOrdinal != 0
                && sourceVersionOrdinal == lastSourceVersionOrdinal
                && lineageRevision > lastLineageRevision;
        boolean nextOrdinal = lastSourceVersionOrdinal == 0
                || sourceVersionOrdinal == lastSourceVersionOrdinal + 1;
        if (sourceVersionOrdinal < 1 || (!nextOrdinal && !directCorrection)
                || lineageRevision < 0
                || !microsecond(observedAt)
                || !observedAt.isAfter(lastObservedAt)
                || nextWatermark == null || nextWatermark.isBlank()
                || nextWatermark.length() > 256) {
            throw invalid();
        }
        return directCorrection;
    }

    private static boolean microsecond(Instant value) {
        return value != null && value.getNano() % 1_000 == 0;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("RECOVERY_OBSERVATION_PROGRESS_INVALID");
    }
}
