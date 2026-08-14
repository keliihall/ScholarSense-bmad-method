package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.Objects;

/** Pure result of applying prior-state latch semantics to a raw composition decision. */
public record QualityFuseTransition(
        QualityEligibilityStatus priorState,
        QualityEligibilityDecision evaluated,
        QualityEligibilityDecision applied,
        QualityFuseEpisodeAction episodeAction,
        QualityFuseTaskAction taskAction,
        boolean businessFailureTrigger) {

    public QualityFuseTransition {
        priorState = Objects.requireNonNull(priorState);
        evaluated = Objects.requireNonNull(evaluated);
        applied = Objects.requireNonNull(applied);
        episodeAction = Objects.requireNonNull(episodeAction);
        taskAction = Objects.requireNonNull(taskAction);
        if (taskAction == QualityFuseTaskAction.CREATE
                && episodeAction != QualityFuseEpisodeAction.START) {
            throw new IllegalArgumentException("INGESTION_QUALITY_FUSE_TRANSITION_INVALID");
        }
        if (businessFailureTrigger
                && evaluated.status() != QualityEligibilityStatus.FUSED) {
            throw new IllegalArgumentException("INGESTION_QUALITY_FUSE_TRANSITION_INVALID");
        }
    }
}
