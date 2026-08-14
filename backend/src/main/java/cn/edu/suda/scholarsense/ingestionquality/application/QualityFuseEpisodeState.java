package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;
import java.util.UUID;

/** Locked active episode/task identity for one source/dependency incident generation. */
public record QualityFuseEpisodeState(
        UUID episodeId,
        UUID recoveryTaskId,
        String workItemKey,
        String workItemKeyVersion,
        String sourceId,
        String dependencyId,
        long generation,
        long aggregateVersion) {
    public QualityFuseEpisodeState {
        episodeId = Objects.requireNonNull(episodeId);
        recoveryTaskId = Objects.requireNonNull(recoveryTaskId);
        workItemKey = requireText(workItemKey);
        if (workItemKeyVersion == null || !workItemKeyVersion.matches("^k[1-9][0-9]*$")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_FUSE_EPISODE_INVALID");
        }
        sourceId = requireText(sourceId);
        dependencyId = requireText(dependencyId);
        if (generation < 1 || aggregateVersion < 1) {
            throw new IllegalArgumentException("INGESTION_QUALITY_FUSE_EPISODE_INVALID");
        }
    }

    public String key() {
        return QualityEligibilityProcessingState.episodeKey(sourceId, dependencyId);
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("INGESTION_QUALITY_FUSE_EPISODE_INVALID");
        }
        return value;
    }
}
