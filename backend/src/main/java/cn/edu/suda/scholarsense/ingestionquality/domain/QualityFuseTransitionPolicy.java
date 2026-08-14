package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.Objects;

/**
 * Applies only the QFTP-1.0.0 latch. Raw formulas remain owned by
 * {@link QualityEligibilityEvaluator}; technical failures must use {@link #technical}.
 */
public final class QualityFuseTransitionPolicy {
    public QualityFuseTransition apply(
            QualityFuseEvaluationProvenance provenance,
            boolean priorFactExists,
            QualityEligibilityStatus priorState,
            QualityEligibilityDecision evaluated,
            boolean verifiedBusinessFailure,
            boolean activeEpisode) {
        Objects.requireNonNull(provenance);
        Objects.requireNonNull(priorState);
        Objects.requireNonNull(evaluated);
        if (verifiedBusinessFailure
                && evaluated.status() != QualityEligibilityStatus.FUSED) {
            throw new IllegalArgumentException("INGESTION_QUALITY_FUSE_TRIGGER_INVALID");
        }

        if (provenance == QualityFuseEvaluationProvenance.BOOTSTRAP_MATERIALIZATION) {
            if (priorFactExists || verifiedBusinessFailure) {
                throw new IllegalArgumentException(
                        "INGESTION_QUALITY_BOOTSTRAP_PROVENANCE_INVALID");
            }
            return transition(
                    priorState, evaluated,
                    evaluated.status() == QualityEligibilityStatus.FUSED
                            ? decision(QualityEligibilityStatus.FUSED,
                                    QualityEligibilityReason.BOOTSTRAP_FUSED, evaluated)
                            : evaluated,
                    QualityFuseEpisodeAction.NONE, QualityFuseTaskAction.NONE, false);
        }
        if (priorState == QualityEligibilityStatus.FUSED) {
            if (!verifiedBusinessFailure) {
                return transition(
                        priorState, evaluated,
                        decision(QualityEligibilityStatus.FUSED,
                                QualityEligibilityReason.FUSE_LATCHED, evaluated),
                        activeEpisode ? QualityFuseEpisodeAction.PRESERVE
                                : QualityFuseEpisodeAction.NONE,
                        activeEpisode ? QualityFuseTaskAction.PRESERVE
                                : QualityFuseTaskAction.NONE, false);
            }
            if (!activeEpisode) {
                return transition(
                        priorState, evaluated, evaluated,
                        QualityFuseEpisodeAction.START, QualityFuseTaskAction.CREATE, true);
            }
            return transition(
                    priorState, evaluated, evaluated,
                    QualityFuseEpisodeAction.UPDATE, QualityFuseTaskAction.UPDATE, true);
        }
        if (priorState == QualityEligibilityStatus.RECOVERING) {
            if (!verifiedBusinessFailure) {
                return transition(
                        priorState, evaluated,
                        decision(QualityEligibilityStatus.RECOVERING,
                                QualityEligibilityReason.RECOVERY_LATCHED, evaluated),
                        activeEpisode ? QualityFuseEpisodeAction.PRESERVE
                                : QualityFuseEpisodeAction.NONE,
                        activeEpisode ? QualityFuseTaskAction.PRESERVE
                                : QualityFuseTaskAction.NONE, false);
            }
            QualityFuseEpisodeAction episodeAction = activeEpisode
                    ? QualityFuseEpisodeAction.UPDATE : QualityFuseEpisodeAction.START;
            QualityFuseTaskAction taskAction = activeEpisode
                    ? QualityFuseTaskAction.UPDATE : QualityFuseTaskAction.CREATE;
            return transition(
                    priorState, evaluated,
                    decision(QualityEligibilityStatus.FUSED,
                            QualityEligibilityReason.RECOVERY_RELAPSED, evaluated),
                    episodeAction, taskAction, true);
        }
        if (verifiedBusinessFailure) {
            if (activeEpisode) {
                return transition(
                        priorState, evaluated, evaluated,
                        QualityFuseEpisodeAction.UPDATE, QualityFuseTaskAction.UPDATE, true);
            }
            return transition(
                    priorState, evaluated, evaluated,
                    QualityFuseEpisodeAction.START, QualityFuseTaskAction.CREATE, true);
        }
        return transition(
                priorState, evaluated, evaluated,
                QualityFuseEpisodeAction.NONE, QualityFuseTaskAction.NONE, false);
    }

    public QualityFuseTransition technical(
            boolean priorFactExists,
            QualityEligibilityStatus priorState,
            QualityEligibilityDecision current) {
        Objects.requireNonNull(current);
        QualityEligibilityDecision applied = priorFactExists
                ? new QualityEligibilityDecision(
                        priorState, current.reason(), current.failedMembers())
                : current;
        return transition(
                priorState, current, applied,
                QualityFuseEpisodeAction.NONE, QualityFuseTaskAction.NONE, false);
    }

    private static QualityEligibilityDecision decision(
            QualityEligibilityStatus status,
            QualityEligibilityReason reason,
            QualityEligibilityDecision source) {
        return new QualityEligibilityDecision(status, reason, source.failedMembers());
    }

    private static QualityFuseTransition transition(
            QualityEligibilityStatus prior,
            QualityEligibilityDecision evaluated,
            QualityEligibilityDecision applied,
            QualityFuseEpisodeAction episode,
            QualityFuseTaskAction task,
            boolean trigger) {
        return new QualityFuseTransition(prior, evaluated, applied, episode, task, trigger);
    }

}
