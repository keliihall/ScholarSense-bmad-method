package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryObservationPolicyTest {
    private static final UUID RECOVERY_ID = uuid("018f34c0-9b80-7a11-8abc-0123456789ab");
    private static final Instant START = Instant.parse("2026-08-14T00:00:00Z");
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String OTHER_DIGEST = "sha256:" + "b".repeat(64);
    private final RecoveryObservationPolicy policy = new RecoveryObservationPolicy();

    @Test
    void streamingRequiresThreeStrictPairsAndFullSixtyMinutesInclusively() {
        RecoveryObservation observation = observation(
                RecoveryObservationSourceClass.STREAMING, pairs(3), passed());

        RecoveryObservationDecision early = policy.evaluate(
                observation, current(), true, START.plusSeconds(3600).minusNanos(1_000));
        RecoveryObservationDecision boundary = policy.evaluate(
                observation, current(), true, START.plusSeconds(3600));
        RecoveryObservationDecision later = policy.evaluate(
                observation, current(), true, START.plusSeconds(3600).plusNanos(1_000));

        assertEquals(RecoveryObservationStatus.NOT_READY, early.status());
        assertEquals(RecoveryObservationReason.DURATION_INCOMPLETE, early.reason());
        assertEquals(RecoveryObservationStatus.READY, boundary.status());
        assertEquals(3, boundary.consecutivePassedBatches());
        assertEquals(RecoveryObservationStatus.READY, later.status());
    }

    @Test
    void dailyRequiresTwoPairsAndOneDayWhileThreeAlsoPasses() {
        RecoveryObservation two = observation(
                RecoveryObservationSourceClass.DAILY_BATCH, pairs(2), passed());
        RecoveryObservation three = observation(
                RecoveryObservationSourceClass.DAILY_BATCH, pairs(3), passed());

        assertEquals(RecoveryObservationStatus.NOT_READY, policy.evaluate(
                observation(RecoveryObservationSourceClass.DAILY_BATCH, pairs(1), passed()),
                current(), true, START.plusSeconds(86_400)).status());
        assertEquals(RecoveryObservationStatus.READY, policy.evaluate(
                two, current(), true, START.plusSeconds(86_400)).status());
        assertEquals(RecoveryObservationStatus.READY, policy.evaluate(
                three, current(), true, START.plusSeconds(86_400).plusSeconds(1)).status());
    }

    @Test
    void noDataGapPoisonAndUnknownEvidenceNeverPass() {
        RecoveryObservation none = observation(
                RecoveryObservationSourceClass.STREAMING, List.of(), passed());
        List<RecoveryObservationFact> gapFacts = new ArrayList<>(pairs(2));
        gapFacts.addAll(pair(4));
        List<RecoveryObservationFact> poisonFacts = new ArrayList<>(pairs(2));
        RecoveryObservationFact original = pair(3).get(1);
        poisonFacts.add(pair(3).get(0));
        poisonFacts.add(new RecoveryObservationFact(
                original.eventId(), original.batchId(), original.snapshotId(),
                original.sourceVersionOrdinal(), original.lineageRevision(),
                original.stage(), OTHER_DIGEST, original.watermark(), original.occurredAt()));

        assertEquals(RecoveryObservationReason.NO_DATA, policy.evaluate(
                none, current(), true, START.plusSeconds(3600)).reason());
        assertEquals(RecoveryObservationReason.SEQUENCE_GAP, policy.evaluate(
                observation(RecoveryObservationSourceClass.STREAMING, gapFacts, passed()),
                current(), true, START.plusSeconds(3600)).reason());
        assertEquals(RecoveryObservationReason.POISONED_PAIR, policy.evaluate(
                observation(RecoveryObservationSourceClass.STREAMING, poisonFacts, passed()),
                current(), true, START.plusSeconds(3600)).reason());
        assertEquals(RecoveryObservationReason.EVIDENCE_UNKNOWN, policy.evaluate(
                observation(RecoveryObservationSourceClass.STREAMING, pairs(3),
                        new RecoveryObservationEvidence(
                                RecoveryObservationEvidenceStatus.UNKNOWN,
                                RecoveryObservationEvidenceStatus.PASSED,
                                RecoveryObservationEvidenceStatus.PASSED,
                                RecoveryObservationEvidenceStatus.PASSED)),
                current(), true, START.plusSeconds(3600)).reason());
    }

    @Test
    void technicalUnavailableIsRetryableAndNeverBusinessRelapse() {
        RecoveryObservation observation = observation(
                RecoveryObservationSourceClass.STREAMING, pairs(3),
                new RecoveryObservationEvidence(
                        RecoveryObservationEvidenceStatus.PASSED,
                        RecoveryObservationEvidenceStatus.UNAVAILABLE,
                        RecoveryObservationEvidenceStatus.PASSED,
                        RecoveryObservationEvidenceStatus.PASSED));

        RecoveryObservationDecision decision = policy.evaluate(
                observation, current(), true, START.plusSeconds(3600));

        assertEquals(RecoveryObservationStatus.DEPENDENCY_UNAVAILABLE, decision.status());
        assertEquals(RecoveryObservationReason.TECHNICAL_UNAVAILABLE, decision.reason());
        assertFalse(decision.verifiedBusinessFailure());
    }

    @Test
    void verifiedFailureWinsAfterAReadyLookingObservation() {
        List<RecoveryObservationFact> facts = new ArrayList<>(pairs(3));
        facts.add(failure(4));
        RecoveryObservationDecision decision = policy.evaluate(
                observation(RecoveryObservationSourceClass.STREAMING, facts, passed()),
                current(), true, START.plusSeconds(3600));

        assertEquals(RecoveryObservationStatus.RELAPSED, decision.status());
        assertEquals(RecoveryObservationReason.VERIFIED_QUALITY_FAILURE, decision.reason());
        assertTrue(decision.verifiedBusinessFailure());
    }

    @Test
    void policyMemberWatermarkOrAllRecoveringDriftFailsClosed() {
        RecoveryObservation observation = observation(
                RecoveryObservationSourceClass.STREAMING, pairs(3), passed());

        assertEquals(RecoveryObservationStatus.POLICY_DRIFT, policy.evaluate(
                observation,
                new RecoveryObservationFence("QRP-1.0.0", OTHER_DIGEST, DIGEST, DIGEST),
                true, START.plusSeconds(3600)).status());
        assertEquals(RecoveryObservationStatus.POLICY_DRIFT, policy.evaluate(
                observation,
                new RecoveryObservationFence("QRP-1.0.0", DIGEST, OTHER_DIGEST, DIGEST),
                true, START.plusSeconds(3600)).status());
        assertEquals(RecoveryObservationStatus.POLICY_DRIFT, policy.evaluate(
                observation, current(), false, START.plusSeconds(3600)).status());
    }

    @Test
    void observationStartsAtOwnerCommittedRecoveringTimeAndRejectsNonMicroseconds() {
        assertThrows(IllegalArgumentException.class, () -> new RecoveryObservation(
                RECOVERY_ID, 1, "SRC-P0-CAMPUS-ACCESS-001",
                RecoveryObservationSourceClass.STREAMING, START.plusNanos(1),
                "QRP-1.0.0", DIGEST, DIGEST, DIGEST, List.of(), passed()));
        assertThrows(IllegalArgumentException.class, () -> policy.evaluate(
                observation(RecoveryObservationSourceClass.STREAMING, pairs(3), passed()),
                current(), true, START.plusSeconds(3600).plusNanos(1)));
    }

    @Test
    void finalCandidateExistsOnlyForReadyAllRecoveringSameGeneration() {
        RecoveryObservationDecision ready = policy.evaluate(
                observation(RecoveryObservationSourceClass.STREAMING, pairs(3), passed()),
                current(), true, START.plusSeconds(3600));
        RecoveryFinalizationCandidatePolicy finalization =
                new RecoveryFinalizationCandidatePolicy();
        List<RecoveryFinalizationCandidatePolicy.EligibilityFence> good = List.of(
                fence(1, QualityEligibilityStatus.RECOVERING),
                fence(1, QualityEligibilityStatus.RECOVERING));

        assertTrue(finalization.evaluate(RECOVERY_ID, 1, ready, good).isPresent());
        assertTrue(finalization.evaluate(RECOVERY_ID, 1, ready, List.of(
                fence(1, QualityEligibilityStatus.RECOVERING),
                fence(2, QualityEligibilityStatus.RECOVERING))).isEmpty());
        assertTrue(finalization.evaluate(RECOVERY_ID, 1, ready, List.of(
                fence(1, QualityEligibilityStatus.FUSED))).isEmpty());
    }

    @Test
    void deliveredRecoveryReasonRemainsReadable() {
        assertEquals(
                QualityEligibilityReason.RECOVERY_VALIDATION_APPROVED,
                QualityEligibilityReason.valueOf("RECOVERY_VALIDATION_APPROVED"));
    }

    private static RecoveryObservation observation(
            RecoveryObservationSourceClass sourceClass,
            List<RecoveryObservationFact> facts,
            RecoveryObservationEvidence evidence) {
        return new RecoveryObservation(
                RECOVERY_ID, 1, "SRC-P0-CAMPUS-ACCESS-001", sourceClass, START,
                "QRP-1.0.0", DIGEST, DIGEST, DIGEST, facts, evidence);
    }

    private static RecoveryObservationFence current() {
        return new RecoveryObservationFence("QRP-1.0.0", DIGEST, DIGEST, DIGEST);
    }

    private static RecoveryObservationEvidence passed() {
        return new RecoveryObservationEvidence(
                RecoveryObservationEvidenceStatus.PASSED,
                RecoveryObservationEvidenceStatus.PASSED,
                RecoveryObservationEvidenceStatus.PASSED,
                RecoveryObservationEvidenceStatus.PASSED);
    }

    private static List<RecoveryObservationFact> pairs(int count) {
        ArrayList<RecoveryObservationFact> facts = new ArrayList<>();
        for (int index = 1; index <= count; index++) facts.addAll(pair(index));
        return facts;
    }

    private static List<RecoveryObservationFact> pair(int ordinal) {
        UUID batch = uuid("018f34c0-9b8" + ordinal + "-7a11-8abc-0123456789ab");
        UUID snapshot = uuid("018f34c0-9c8" + ordinal + "-7a11-8abc-0123456789ab");
        return List.of(
                fact(ordinal, batch, snapshot, RecoveryObservationFact.Stage.ASSESSED_PASSED),
                fact(ordinal, batch, snapshot, RecoveryObservationFact.Stage.PUBLISHED));
    }

    private static RecoveryObservationFact failure(int ordinal) {
        return fact(
                ordinal,
                uuid("018f34c0-9d8" + ordinal + "-7a11-8abc-0123456789ab"),
                uuid("018f34c0-9e8" + ordinal + "-7a11-8abc-0123456789ab"),
                RecoveryObservationFact.Stage.VERIFIED_QUALITY_FAILURE);
    }

    private static RecoveryObservationFact fact(
            int ordinal,
            UUID batch,
            UUID snapshot,
            RecoveryObservationFact.Stage stage) {
        return new RecoveryObservationFact(
                uuid("018f34c0-9f8" + ordinal + "-7a11-8abc-0123456789ab"),
                batch, snapshot, ordinal, 0, stage, DIGEST, "wm-" + ordinal,
                START.plusSeconds(ordinal * 60L));
    }

    private static RecoveryFinalizationCandidatePolicy.EligibilityFence fence(
            long generation, QualityEligibilityStatus status) {
        return new RecoveryFinalizationCandidatePolicy.EligibilityFence(
                uuid("018f34c0-9a80-7a11-8abc-0123456789ab"), generation, 3, status);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
