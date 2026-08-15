package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyQualityState;
import java.util.List;
import java.util.Objects;

public record QualityEligibilityMutation(
        QualityEligibilityProcessingOutcome outcome,
        QualityEligibilityCursor cursor,
        PendingQualityPair pendingPair,
        DependencyQualityState dependencyState,
        List<RuleEligibilityDecision> decisions,
        QualityEligibilityBackfillRequest backfillRequest,
        QualityEligibilityQuarantine quarantine,
        QualityEligibilitySnapshotEvidence snapshotEvidence,
        QualityFuseTaskPlan fuseTaskPlan,
        RecoveryObservationProgressState recoveryObservationProgress) {

    public QualityEligibilityMutation {
        outcome = Objects.requireNonNull(outcome);
        decisions = List.copyOf(Objects.requireNonNull(decisions));
    }

    public QualityEligibilityMutation(
            QualityEligibilityProcessingOutcome outcome,
            QualityEligibilityCursor cursor,
            PendingQualityPair pendingPair,
            DependencyQualityState dependencyState,
            List<RuleEligibilityDecision> decisions,
            QualityEligibilityBackfillRequest backfillRequest,
            QualityEligibilityQuarantine quarantine,
            QualityEligibilitySnapshotEvidence snapshotEvidence,
            QualityFuseTaskPlan fuseTaskPlan) {
        this(outcome, cursor, pendingPair, dependencyState, decisions,
                backfillRequest, quarantine, snapshotEvidence, fuseTaskPlan, null);
    }

    public QualityEligibilityMutation(
            QualityEligibilityProcessingOutcome outcome,
            QualityEligibilityCursor cursor,
            PendingQualityPair pendingPair,
            DependencyQualityState dependencyState,
            List<RuleEligibilityDecision> decisions,
            QualityEligibilityBackfillRequest backfillRequest,
            QualityEligibilityQuarantine quarantine,
            QualityEligibilitySnapshotEvidence snapshotEvidence) {
        this(outcome, cursor, pendingPair, dependencyState, decisions,
                backfillRequest, quarantine, snapshotEvidence, null, null);
    }

    public static QualityEligibilityMutation noChange(
            QualityEligibilityProcessingOutcome outcome,
            QualityEligibilityProcessingState state) {
        return new QualityEligibilityMutation(
                outcome, state.cursor(), state.pendingPair(), null, List.of(),
                null, null, null,
                state.currentInbox() == null ? null : state.currentInbox().fuseTaskPlan(),
                null);
    }
}
