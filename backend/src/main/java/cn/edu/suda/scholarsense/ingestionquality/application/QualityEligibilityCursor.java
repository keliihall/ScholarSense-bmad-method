package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;
import java.util.UUID;

public record QualityEligibilityCursor(
        String sourceId,
        String dependencyId,
        long sourceVersion,
        UUID lineageId,
        long lineageRevision,
        UUID batchId,
        QualityEligibilityCursorStage stage,
        boolean paused,
        long aggregateVersion) {

    public QualityEligibilityCursor {
        if (sourceId == null || dependencyId == null || sourceVersion < 1
                || lineageRevision < 0 || aggregateVersion < 1) {
            throw new IllegalArgumentException("INGESTION_QUALITY_CURSOR_INVALID");
        }
        lineageId = Objects.requireNonNull(lineageId);
        batchId = Objects.requireNonNull(batchId);
        stage = Objects.requireNonNull(stage);
    }

    public QualityEligibilityCursor pause() {
        return new QualityEligibilityCursor(
                sourceId, dependencyId, sourceVersion, lineageId, lineageRevision,
                batchId, stage, true, aggregateVersion + 1);
    }
}
