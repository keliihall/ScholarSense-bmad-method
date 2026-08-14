package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class QualityFuseTransitionPolicyTest {
    private final QualityFuseTransitionPolicy policy = new QualityFuseTransitionPolicy();

    @Test
    void eligibleFailureStartsEpisodeAndTask() {
        QualityFuseTransition result = policy.apply(
                QualityFuseEvaluationProvenance.REALTIME_ASSESSMENT,
                true, QualityEligibilityStatus.ELIGIBLE, failed(), true, false);

        assertEquals(QualityEligibilityStatus.FUSED, result.applied().status());
        assertEquals(QualityFuseEpisodeAction.START, result.episodeAction());
        assertEquals(QualityFuseTaskAction.CREATE, result.taskAction());
        assertTrue(result.businessFailureTrigger());
    }

    @Test
    void firstRealtimeFailureStartsEpisodeEvenWithoutPriorEligibilityFact() {
        QualityFuseTransition result = policy.apply(
                QualityFuseEvaluationProvenance.REALTIME_ASSESSMENT,
                false, QualityEligibilityStatus.MISSING, failed(), true, false);

        assertEquals(QualityEligibilityStatus.FUSED, result.applied().status());
        assertEquals(QualityFuseEpisodeAction.START, result.episodeAction());
        assertEquals(QualityFuseTaskAction.CREATE, result.taskAction());
        assertTrue(result.businessFailureTrigger());
    }

    @Test
    void fusedPassAndFailureStayLatchedOnOriginalEpisode() {
        QualityFuseTransition pass = policy.apply(
                QualityFuseEvaluationProvenance.REALTIME_ASSESSMENT,
                true, QualityEligibilityStatus.FUSED, passed(), false, true);
        QualityFuseTransition failure = policy.apply(
                QualityFuseEvaluationProvenance.REALTIME_ASSESSMENT,
                true, QualityEligibilityStatus.FUSED, failed(), true, true);

        assertEquals(QualityEligibilityStatus.FUSED, pass.applied().status());
        assertEquals(QualityEligibilityReason.FUSE_LATCHED, pass.applied().reason());
        assertEquals(QualityFuseEpisodeAction.PRESERVE, pass.episodeAction());
        assertEquals(QualityFuseTaskAction.PRESERVE, pass.taskAction());
        assertEquals(QualityEligibilityStatus.FUSED, failure.applied().status());
        assertEquals(QualityFuseEpisodeAction.UPDATE, failure.episodeAction());
        assertEquals(QualityFuseTaskAction.UPDATE, failure.taskAction());
    }

    @Test
    void recoveringPassStaysRecoveringAndFailureRelapsesToFused() {
        QualityFuseTransition pass = policy.apply(
                QualityFuseEvaluationProvenance.REALTIME_ASSESSMENT,
                true, QualityEligibilityStatus.RECOVERING, passed(), false, true);
        QualityFuseTransition failure = policy.apply(
                QualityFuseEvaluationProvenance.REALTIME_ASSESSMENT,
                true, QualityEligibilityStatus.RECOVERING, failed(), true, true);

        assertEquals(QualityEligibilityStatus.RECOVERING, pass.applied().status());
        assertEquals(QualityEligibilityReason.RECOVERY_LATCHED, pass.applied().reason());
        assertEquals(QualityEligibilityStatus.FUSED, failure.applied().status());
        assertEquals(QualityEligibilityReason.RECOVERY_RELAPSED, failure.applied().reason());
    }

    @Test
    void bootstrapFuseAndTechnicalFailureNeverCreateIncidentTask() {
        QualityFuseTransition bootstrap = policy.apply(
                QualityFuseEvaluationProvenance.BOOTSTRAP_MATERIALIZATION,
                false, QualityEligibilityStatus.MISSING, failed(), false, false);
        QualityFuseTransition technical = policy.technical(
                true, QualityEligibilityStatus.ELIGIBLE, passed());

        assertEquals(QualityEligibilityStatus.FUSED, bootstrap.applied().status());
        assertEquals(QualityEligibilityReason.BOOTSTRAP_FUSED, bootstrap.applied().reason());
        assertEquals(QualityFuseEpisodeAction.NONE, bootstrap.episodeAction());
        assertEquals(QualityFuseTaskAction.NONE, bootstrap.taskAction());
        assertFalse(bootstrap.businessFailureTrigger());
        assertEquals(QualityEligibilityStatus.ELIGIBLE, technical.applied().status());
        assertEquals(QualityFuseTaskAction.NONE, technical.taskAction());
    }

    private static QualityEligibilityDecision failed() {
        return new QualityEligibilityDecision(
                QualityEligibilityStatus.FUSED,
                QualityEligibilityReason.REQUIRED_MEMBER_FUSED,
                List.of("DEP-P0-CAMPUS-ACCESS-001"));
    }

    private static QualityEligibilityDecision passed() {
        return new QualityEligibilityDecision(
                QualityEligibilityStatus.ELIGIBLE,
                QualityEligibilityReason.ALL_REQUIRED_ELIGIBLE,
                List.of());
    }
}
