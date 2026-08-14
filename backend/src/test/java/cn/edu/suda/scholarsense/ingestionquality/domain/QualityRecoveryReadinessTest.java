package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoveryReadiness.DependencyState;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoveryReadiness.EvidenceAvailability;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoveryReadiness.MissingEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoveryReadiness.RequiredMemberSet;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoveryReadiness.ThresholdOperator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QualityRecoveryReadinessTest {
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String OTHER_DIGEST = "sha256:" + "b".repeat(64);
    private static final Instant TRUSTED_NOW = Instant.parse("2026-08-12T00:00:00Z");
    private static final Instant LKG = Instant.parse("2026-07-01T00:00:00Z");
    private final QualityRecoveryReadiness evaluator = new QualityRecoveryReadiness();

    @Test
    void streamingUsesInclusiveThreeBatchBoundaryAndDailyUsesTwo() {
        assertFalse(decision(QualityRecoverySourceClass.STREAMING, batches(2), passingSample(101, 100)).readyForD4());
        assertTrue(decision(QualityRecoverySourceClass.STREAMING, batches(3), passingSample(101, 100)).readyForD4());
        assertTrue(decision(QualityRecoverySourceClass.STREAMING, batches(4), passingSample(101, 100)).readyForD4());

        assertFalse(decision(QualityRecoverySourceClass.DAILY_BATCH, batches(1), passingSample(101, 100)).readyForD4());
        assertTrue(decision(QualityRecoverySourceClass.DAILY_BATCH, batches(2), passingSample(101, 100)).readyForD4());
        assertTrue(decision(QualityRecoverySourceClass.DAILY_BATCH, batches(3), passingSample(101, 100)).readyForD4());
    }

    @Test
    void batchEvidenceRequiresTheExactAssessedPassedThenPublishedPair() {
        RecoveryBatchEvidence valid = batch(1);
        assertTrue(valid.exactAssessedPassedToPublishedPair());

        assertFalse(copy(valid, RecoveryBatchEvidence.AssessmentStatus.FAILED,
                valid.publishedBatchId(), valid.publishedSnapshotId(), DIGEST, 4)
                .exactAssessedPassedToPublishedPair());
        assertFalse(copy(valid, RecoveryBatchEvidence.AssessmentStatus.PASSED,
                uuidV7(900), valid.publishedSnapshotId(), DIGEST, 4)
                .exactAssessedPassedToPublishedPair());
        assertFalse(copy(valid, RecoveryBatchEvidence.AssessmentStatus.PASSED,
                valid.publishedBatchId(), uuidV7(901), DIGEST, 4)
                .exactAssessedPassedToPublishedPair());
        assertFalse(copy(valid, RecoveryBatchEvidence.AssessmentStatus.PASSED,
                valid.publishedBatchId(), valid.publishedSnapshotId(), OTHER_DIGEST, 4)
                .exactAssessedPassedToPublishedPair());
        assertFalse(copy(valid, RecoveryBatchEvidence.AssessmentStatus.PASSED,
                valid.publishedBatchId(), valid.publishedSnapshotId(), DIGEST, 6)
                .exactAssessedPassedToPublishedPair());
    }

    @Test
    void consecutiveCountUsesOnlyBusinessSequenceAndRejectsGapsOrAmbiguousCorrections() {
        List<RecoveryBatchEvidence> deliberatelyTransportUnordered =
                List.of(batch(3), batch(1), batch(2));
        assertEquals(3,
                RecoveryBatchEvidence.consecutivePassedPublishedCount(
                        deliberatelyTransportUnordered));

        assertEquals(2,
                RecoveryBatchEvidence.consecutivePassedPublishedCount(
                        List.of(batch(1), batch(3), batch(4))));

        RecoveryBatchEvidence correction = withLineageRevision(batch(3), 1);
        assertEquals(2,
                RecoveryBatchEvidence.consecutivePassedPublishedCount(
                        List.of(batch(2), batch(3), correction)));
        assertEquals(0,
                RecoveryBatchEvidence.consecutivePassedPublishedCount(
                        List.of(batch(2), withLineageRevision(batch(3), 1))));
        assertEquals(0,
                RecoveryBatchEvidence.consecutivePassedPublishedCount(
                        List.of(batch(1), withSource(batch(2), "SRC-P0-CARD-001"))));
    }

    @Test
    void newestBusinessTailNotAnOlderHistoricalRunControlsTheCount() {
        assertEquals(1,
                RecoveryBatchEvidence.consecutivePassedPublishedCount(
                        List.of(batch(1), batch(2), batch(3), batch(5))));
    }

    @Test
    void anOlderFailedCanonicalBatchBreaksAtThatPointButDoesNotEraseTheNewestTail() {
        RecoveryBatchEvidence failed = copy(
                batch(2), RecoveryBatchEvidence.AssessmentStatus.FAILED,
                batch(2).publishedBatchId(), batch(2).publishedSnapshotId(), DIGEST, 4);

        assertEquals(3,
                RecoveryBatchEvidence.consecutivePassedPublishedCount(
                        List.of(batch(1), failed, batch(4), batch(5), batch(6))));
    }

    @Test
    void adjacentAggregateVersionsAreAcceptedWithoutFreezingStory23VersionNumbers() {
        RecoveryBatchEvidence source = batch(1);
        RecoveryBatchEvidence laterAdditivePair = new RecoveryBatchEvidence(
                source.sourceId(), source.sourceVersionOrdinal(), source.lineageRevision(),
                source.assessedBatchId(), source.assessedSnapshotId(),
                source.assessedSnapshotImmutableHash(), source.assessmentStatus(), 7,
                source.publishedBatchId(), source.publishedSnapshotId(),
                source.publishedSnapshotImmutableHash(), source.publicationStatus(), 8);

        assertTrue(laterAdditivePair.exactAssessedPassedToPublishedPair());
    }

    @Test
    void backfillStartsAtTheLaterOfLastKnownGoodAndTrustedNowMinusNinetyDays() {
        Instant lookback = TRUSTED_NOW.minusSeconds(90L * 24 * 60 * 60);

        assertEquals(LKG, QualityRecoveryReadiness.backfillStart(policy(), LKG, TRUSTED_NOW));
        assertEquals(lookback,
                QualityRecoveryReadiness.backfillStart(
                        policy(), Instant.parse("2026-01-01T00:00:00Z"), TRUSTED_NOW));
    }

    @Test
    void reconciliationRequiresAvailableFullCoverageExactCountsAndZeroMismatch() {
        assertTrue(reconciliation(RecoveryReconciliationSummary.Coverage.FULL, 10, 10, 0)
                .qualified(policy()));
        assertFalse(reconciliation(RecoveryReconciliationSummary.Coverage.PARTIAL, 10, 10, 0)
                .qualified(policy()));
        assertFalse(reconciliation(RecoveryReconciliationSummary.Coverage.FULL, 10, 9, 0)
                .qualified(policy()));
        assertFalse(reconciliation(RecoveryReconciliationSummary.Coverage.FULL, 10, 10, 1)
                .qualified(policy()));
        assertFalse(new RecoveryReconciliationSummary(
                RecoveryReconciliationSummary.SUMMARY_VERSION,
                RecoveryReconciliationSummary.Availability.UNAVAILABLE,
                RecoveryReconciliationSummary.Coverage.FULL, 10, 10, 0).qualified(policy()));
    }

    @Test
    void sampleTakesAllBelowOneHundredAndAtLeastOneHundredAboveIt() {
        assertTrue(passingSample(99, 99).qualified(policy()));
        assertFalse(passingSample(99, 98).qualified(policy()));
        assertFalse(passingSample(101, 99).qualified(policy()));
        assertTrue(passingSample(101, 100).qualified(policy()));
        assertFalse(new RecoverySampleSummary(
                RecoverySampleSummary.SUMMARY_VERSION,
                "RECOVERY-SAMPLE-PROVIDER-1.0.0",
                RecoverySampleSummary.ProviderAvailability.AVAILABLE,
                true, 100, 100, 1).qualified(policy()));
    }

    @Test
    void qualityThresholdIsInclusiveAndOneScaledUnitBelowFails() {
        assertFalse(decisionWithQuality(9_998, 9_999, ThresholdOperator.GREATER_THAN_OR_EQUAL)
                .readyForD4());
        assertTrue(decisionWithQuality(9_999, 9_999, ThresholdOperator.GREATER_THAN_OR_EQUAL)
                .readyForD4());
        assertTrue(decisionWithQuality(10_000, 9_999, ThresholdOperator.GREATER_THAN_OR_EQUAL)
                .readyForD4());
    }

    @Test
    void requiredDependencyUnknownUnavailableOrNotEligibleFailsClosed() {
        for (var dependency : List.of(
                dependency(true, DependencyState.UNKNOWN, EvidenceAvailability.AVAILABLE),
                dependency(true, DependencyState.ELIGIBLE, EvidenceAvailability.UNAVAILABLE),
                dependency(true, DependencyState.FUSED, EvidenceAvailability.AVAILABLE))) {
            var input = input(QualityRecoverySourceClass.STREAMING, batches(3), quality(10_000, 9_999),
                    true, List.of(dependency), passingSample(101, 100));
            var decision = evaluator.evaluate(input);

            assertFalse(decision.readyForD4());
            assertTrue(decision.missingEvidence().contains(
                    MissingEvidence.REQUIRED_DEPENDENCY_NOT_ELIGIBLE));
        }

        var optionalUnavailable = input(QualityRecoverySourceClass.STREAMING, batches(3),
                quality(10_000, 9_999), true,
                List.of(dependency(false, DependencyState.UNKNOWN,
                        EvidenceAvailability.UNAVAILABLE)), passingSample(101, 100));
        assertTrue(evaluator.evaluate(optionalUnavailable).readyForD4());
    }

    @Test
    void unknownPolicyDigestSourceClassAndProviderNeverPass() {
        var wrongDigest = input(QualityRecoverySourceClass.STREAMING, batches(3),
                quality(10_000, 9_999), true, List.of(passingDependency()),
                passingSample(101, 100));
        wrongDigest = new QualityRecoveryReadiness.Input(
                wrongDigest.policy(), OTHER_DIGEST,
                wrongDigest.sourceClass(), wrongDigest.batchEvidence(), wrongDigest.qualityGate(),
                wrongDigest.requiredMemberSet(), wrongDigest.expectedMemberSetDigest(),
                wrongDigest.dependencies(),
                wrongDigest.lastKnownGoodWatermark(), wrongDigest.trustedNow(),
                wrongDigest.backfillCompleted(), wrongDigest.reconciliation(), wrongDigest.sample());

        var unknownSource = input(null, batches(3),
                quality(10_000, 9_999), true, List.of(passingDependency()),
                passingSample(101, 100));
        var unavailableProvider = input(QualityRecoverySourceClass.STREAMING, batches(3),
                quality(10_000, 9_999), true, List.of(passingDependency()),
                new RecoverySampleSummary(
                        RecoverySampleSummary.SUMMARY_VERSION,
                        "RECOVERY-SAMPLE-PROVIDER-1.0.0",
                        RecoverySampleSummary.ProviderAvailability.NOT_INSTALLED,
                        true, 101, 100, 0));
        var unknownProviderVersion = input(QualityRecoverySourceClass.STREAMING, batches(3),
                quality(10_000, 9_999), true, List.of(passingDependency()),
                new RecoverySampleSummary(
                        "QUALITY-RECOVERY-SAMPLE-SUMMARY-9.0.0",
                        "RECOVERY-SAMPLE-PROVIDER-9.0.0",
                        RecoverySampleSummary.ProviderAvailability.AVAILABLE,
                        true, 101, 100, 0));
        var unapprovedCompatibleProviderVersion = input(
                QualityRecoverySourceClass.STREAMING, batches(3),
                quality(10_000, 9_999), true, List.of(passingDependency()),
                new RecoverySampleSummary(
                        RecoverySampleSummary.SUMMARY_VERSION,
                        "RECOVERY-SAMPLE-PROVIDER-1.1.0",
                        RecoverySampleSummary.ProviderAvailability.AVAILABLE,
                        true, 101, 100, 0));

        assertFalse(evaluator.evaluate(wrongDigest).readyForD4());
        assertFalse(evaluator.evaluate(unknownSource).readyForD4());
        assertFalse(evaluator.evaluate(unavailableProvider).readyForD4());
        assertFalse(evaluator.evaluate(unknownProviderVersion).readyForD4());
        assertFalse(evaluator.evaluate(unapprovedCompatibleProviderVersion).readyForD4());
        assertTrue(evaluator.evaluate(wrongDigest).missingEvidence()
                .contains(MissingEvidence.VERSION_OR_DIGEST_UNKNOWN));
        assertTrue(evaluator.evaluate(unavailableProvider).missingEvidence()
                .contains(MissingEvidence.SAMPLE_PROVIDER_UNAVAILABLE));
    }

    @Test
    void missingBackfillReconciliationSampleAndMemberSetAreReportedTogether() {
        var input = new QualityRecoveryReadiness.Input(
                policy(), QualityRecoveryPolicy.RAW_DIGEST,
                QualityRecoverySourceClass.STREAMING, batches(3), quality(10_000, 9_999),
                null, DIGEST, List.of(), null, null, false,
                null, null);

        var decision = evaluator.evaluate(input);

        assertFalse(decision.readyForD4());
        assertNull(decision.backfillStart());
        assertTrue(decision.missingEvidence().containsAll(List.of(
                MissingEvidence.BACKFILL_INCOMPLETE,
                MissingEvidence.RECONCILIATION_MISMATCH,
                MissingEvidence.SAMPLE_PROVIDER_UNAVAILABLE,
                MissingEvidence.REQUIRED_DEPENDENCY_NOT_ELIGIBLE)));
    }

    @Test
    void nonMicrosecondTrustedTimeAndUnknownReconciliationVersionFailClosed() {
        var base = input(QualityRecoverySourceClass.STREAMING, batches(3),
                quality(10_000, 9_999), true, List.of(passingDependency()),
                passingSample(101, 100));
        var invalid = new QualityRecoveryReadiness.Input(
                base.policy(), base.policyDigest(), base.sourceClass(), base.batchEvidence(),
                base.qualityGate(), base.requiredMemberSet(), base.expectedMemberSetDigest(),
                base.dependencies(),
                base.lastKnownGoodWatermark(), TRUSTED_NOW.plusNanos(1), true,
                new RecoveryReconciliationSummary(
                        "QUALITY-RECOVERY-RECONCILIATION-SUMMARY-9.0.0",
                        RecoveryReconciliationSummary.Availability.AVAILABLE,
                        RecoveryReconciliationSummary.Coverage.FULL, 10, 10, 0),
                base.sample());

        var decision = evaluator.evaluate(invalid);

        assertFalse(decision.readyForD4());
        assertTrue(decision.missingEvidence().contains(MissingEvidence.BACKFILL_INCOMPLETE));
        assertTrue(decision.missingEvidence().contains(MissingEvidence.VERSION_OR_DIGEST_UNKNOWN));
    }

    @Test
    void omittedRequiredDependencyCannotMasqueradeAsAnExactMemberSet() {
        var input = new QualityRecoveryReadiness.Input(
                policy(), QualityRecoveryPolicy.RAW_DIGEST,
                QualityRecoverySourceClass.STREAMING, batches(3), quality(10_000, 9_999),
                new RequiredMemberSet(
                        DIGEST, java.util.Set.of("DEP-P0-CAMPUS-ACCESS-001")),
                DIGEST, List.of(), LKG, TRUSTED_NOW, true,
                reconciliation(RecoveryReconciliationSummary.Coverage.FULL, 10, 10, 0),
                passingSample(101, 100));

        var decision = evaluator.evaluate(input);

        assertFalse(decision.readyForD4());
        assertTrue(decision.missingEvidence()
                .contains(MissingEvidence.REQUIRED_DEPENDENCY_NOT_ELIGIBLE));
    }

    private QualityRecoveryReadiness.Decision decision(
            QualityRecoverySourceClass sourceClass,
            List<RecoveryBatchEvidence> batches,
            RecoverySampleSummary sample) {
        return evaluator.evaluate(input(sourceClass, batches, quality(10_000, 9_999),
                true, List.of(passingDependency()), sample));
    }

    private QualityRecoveryReadiness.Decision decisionWithQuality(
            long actual,
            long threshold,
            ThresholdOperator operator) {
        var evidence = new QualityRecoveryReadiness.QualityGateEvidence(
                QualityRecoveryReadiness.QUALITY_GATE_VERSION, DIGEST, DIGEST,
                EvidenceAvailability.AVAILABLE, true, actual, threshold, operator);
        return evaluator.evaluate(input(QualityRecoverySourceClass.STREAMING, batches(3), evidence,
                true, List.of(passingDependency()), passingSample(101, 100)));
    }

    private static QualityRecoveryReadiness.Input input(
            QualityRecoverySourceClass sourceClass,
            List<RecoveryBatchEvidence> batches,
            QualityRecoveryReadiness.QualityGateEvidence quality,
            boolean memberSetKnown,
            List<QualityRecoveryReadiness.RequiredDependencyEvidence> dependencies,
            RecoverySampleSummary sample) {
        return new QualityRecoveryReadiness.Input(
                policy(), QualityRecoveryPolicy.RAW_DIGEST,
                sourceClass, batches, quality,
                memberSetKnown ? memberSet(dependencies) : null, DIGEST, dependencies,
                LKG, TRUSTED_NOW, true,
                reconciliation(RecoveryReconciliationSummary.Coverage.FULL, 10, 10, 0),
                sample);
    }

    private static RequiredMemberSet memberSet(
            List<QualityRecoveryReadiness.RequiredDependencyEvidence> dependencies) {
        return new RequiredMemberSet(
                DIGEST,
                dependencies.stream()
                        .filter(QualityRecoveryReadiness.RequiredDependencyEvidence::required)
                        .map(QualityRecoveryReadiness.RequiredDependencyEvidence::dependencyId)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private static QualityRecoveryPolicy policy() {
        return new QualityRecoveryPolicy(
                QualityRecoveryPolicy.VERSION,
                QualityRecoveryPolicy.RAW_DIGEST,
                List.of("DEC-012", "G-03", "AD-5", "AD-23", "DCC-1.1.0", "QG-1.0.0", "RC-1.0.0"),
                new QualityRecoveryPolicy.SourceClassBinding(
                        "ingestion-quality", "approved-explicit-source-class-registry", "reject"),
                java.util.Map.of(
                        QualityRecoverySourceClass.STREAMING,
                        new QualityRecoveryPolicy.SourceClassRule(3, java.time.Duration.ofMinutes(60)),
                        QualityRecoverySourceClass.DAILY_BATCH,
                        new QualityRecoveryPolicy.SourceClassRule(2, java.time.Duration.ofHours(24))),
                new QualityRecoveryPolicy.BatchQualification(
                        "assessed-passed-then-exact-published",
                        List.of("sourceId", "sourceVersionOrdinal", "lineageRevision"),
                        java.util.Set.of("eventId", "occurredAt", "watermark", "batchId"), true),
                new QualityRecoveryPolicy.Backfill(
                        java.time.Duration.ofDays(90),
                        "max(lastKnownGoodWatermark,trustedNow-minus-P90D)", true),
                new QualityRecoveryPolicy.Reconciliation("full", 0),
                new QualityRecoveryPolicy.Sampling("subject-window", true, 100, true, 0, true),
                new QualityRecoveryPolicy.QualityGate(
                        "QG-1.0.0", true, "use-approved-inclusive-operator",
                        "all-current-required-members-eligible"),
                new QualityRecoveryPolicy.ImpactPreview(
                        true, List.of("already-expired-history-only",
                                "currently-potentially-actionable",
                                "expected-to-expire-before-observation-completes"),
                        "2.5c", false),
                new QualityRecoveryPolicy.FailureFallback(
                        "remain-fused", java.time.Duration.ofHours(24), false),
                "contract-only");
    }

    private static QualityRecoveryReadiness.QualityGateEvidence quality(
            long actual,
            long threshold) {
        return new QualityRecoveryReadiness.QualityGateEvidence(
                QualityRecoveryReadiness.QUALITY_GATE_VERSION, DIGEST, DIGEST,
                EvidenceAvailability.AVAILABLE, true, actual, threshold,
                ThresholdOperator.GREATER_THAN_OR_EQUAL);
    }

    private static QualityRecoveryReadiness.RequiredDependencyEvidence passingDependency() {
        return dependency(true, DependencyState.ELIGIBLE, EvidenceAvailability.AVAILABLE);
    }

    private static QualityRecoveryReadiness.RequiredDependencyEvidence dependency(
            boolean required,
            DependencyState state,
            EvidenceAvailability availability) {
        return new QualityRecoveryReadiness.RequiredDependencyEvidence(
                "DEP-P0-CAMPUS-ACCESS-001", required, state, availability, 1, DIGEST);
    }

    private static RecoveryReconciliationSummary reconciliation(
            RecoveryReconciliationSummary.Coverage coverage,
            long expected,
            long actual,
            long mismatches) {
        return new RecoveryReconciliationSummary(
                RecoveryReconciliationSummary.SUMMARY_VERSION,
                RecoveryReconciliationSummary.Availability.AVAILABLE,
                coverage, expected, actual, mismatches);
    }

    private static RecoverySampleSummary passingSample(long population, long selected) {
        return new RecoverySampleSummary(
                RecoverySampleSummary.SUMMARY_VERSION,
                "RECOVERY-SAMPLE-PROVIDER-1.0.0",
                RecoverySampleSummary.ProviderAvailability.AVAILABLE,
                true, population, selected, 0);
    }

    private static List<RecoveryBatchEvidence> batches(int count) {
        var batches = new ArrayList<RecoveryBatchEvidence>();
        for (int ordinal = 1; ordinal <= count; ordinal++) batches.add(batch(ordinal));
        return List.copyOf(batches);
    }

    private static RecoveryBatchEvidence batch(long ordinal) {
        UUID batchId = uuidV7(ordinal * 10);
        UUID snapshotId = uuidV7(ordinal * 10 + 1);
        return new RecoveryBatchEvidence(
                "SRC-P0-ACCOMMODATION-001", ordinal, 0,
                batchId, snapshotId, DIGEST,
                RecoveryBatchEvidence.AssessmentStatus.PASSED, 3,
                batchId, snapshotId, DIGEST,
                RecoveryBatchEvidence.PublicationStatus.PUBLISHED, 4);
    }

    private static RecoveryBatchEvidence copy(
            RecoveryBatchEvidence source,
            RecoveryBatchEvidence.AssessmentStatus assessedStatus,
            UUID publishedBatchId,
            UUID publishedSnapshotId,
            String publishedDigest,
            long publishedVersion) {
        return new RecoveryBatchEvidence(
                source.sourceId(), source.sourceVersionOrdinal(), source.lineageRevision(),
                source.assessedBatchId(), source.assessedSnapshotId(),
                source.assessedSnapshotImmutableHash(), assessedStatus,
                source.assessedAggregateVersion(), publishedBatchId, publishedSnapshotId,
                publishedDigest, RecoveryBatchEvidence.PublicationStatus.PUBLISHED,
                publishedVersion);
    }

    private static RecoveryBatchEvidence withLineageRevision(
            RecoveryBatchEvidence source,
            long lineageRevision) {
        return new RecoveryBatchEvidence(
                source.sourceId(), source.sourceVersionOrdinal(), lineageRevision,
                source.assessedBatchId(), source.assessedSnapshotId(),
                source.assessedSnapshotImmutableHash(), source.assessmentStatus(),
                source.assessedAggregateVersion(), source.publishedBatchId(),
                source.publishedSnapshotId(), source.publishedSnapshotImmutableHash(),
                source.publicationStatus(), source.publishedAggregateVersion());
    }

    private static RecoveryBatchEvidence withSource(
            RecoveryBatchEvidence source,
            String sourceId) {
        return new RecoveryBatchEvidence(
                sourceId, source.sourceVersionOrdinal(), source.lineageRevision(),
                source.assessedBatchId(), source.assessedSnapshotId(),
                source.assessedSnapshotImmutableHash(), source.assessmentStatus(),
                source.assessedAggregateVersion(), source.publishedBatchId(),
                source.publishedSnapshotId(), source.publishedSnapshotImmutableHash(),
                source.publicationStatus(), source.publishedAggregateVersion());
    }

    private static UUID uuidV7(long suffix) {
        return UUID.fromString("018f0000-0000-7000-8000-" + String.format("%012d", suffix));
    }
}
