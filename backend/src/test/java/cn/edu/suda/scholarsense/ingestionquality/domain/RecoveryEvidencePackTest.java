package cn.edu.suda.scholarsense.ingestionquality.domain;

import static cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryVersionBindingsTest.completeBindings;
import static cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryVersionBindingsTest.digest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryEvidencePack.BackfillEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryEvidencePack.BackfillStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryEvidencePack.BatchEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryEvidencePack.MissingEvidenceCode;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryEvidencePack.QualityEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryEvidencePack.ReadinessDecision;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryEvidencePack.ReconciliationEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryEvidencePack.RecoveryObjectBinding;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryEvidencePack.SampleEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryEvidencePack.SampleProviderAvailability;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryEvidencePack.StratumSummary;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryVersionBindings.Authority;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryVersionBindings.VersionDigestBinding;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryEvidencePackTest {
    private static final Instant STARTED = Instant.parse("2026-08-12T00:00:00.000000Z");
    private static final Instant COMPLETED = Instant.parse("2026-08-12T00:10:00.000000Z");

    @Test
    void readyPackIsAClosedSelfContainedImmutableDigestAndCountSnapshot() {
        ArrayList<MissingEvidenceCode> mutableCodes = new ArrayList<>();
        RecoveryEvidencePack pack = pack(
                QualityRecoverySourceClass.STREAMING, streamingRule(), 3,
                sample(99, 99, 0),
                ReadinessDecision.READY_FOR_D4, mutableCodes);
        mutableCodes.add(MissingEvidenceCode.SAMPLE_MISMATCH);

        assertEquals(List.of(), pack.missingEvidenceCodes());
        assertThrows(UnsupportedOperationException.class,
                () -> pack.missingEvidenceCodes().add(MissingEvidenceCode.SAMPLE_MISMATCH));
        assertEquals(12, pack.versionBindings().versions().size());
        assertEquals(digest('f'), pack.versionBindings().watermarksDigest());
        assertEquals("QUALITY-RECOVERY-EVIDENCE-PACK-1.0.0", pack.schemaVersion());
        assertTrue(List.of(RecoveryEvidencePack.class.getRecordComponents()).stream()
                .map(component -> component.getName().toLowerCase())
                .noneMatch(name -> name.contains("actor") || name.contains("principal")
                        || name.contains("student") || name.contains("reason")
                        || name.contains("note") || name.contains("text")));
    }

    @Test
    void sampleBoundaryUsesAllWhenFewerThanOneHundredAndAtLeastOneHundredOtherwise() {
        pack(QualityRecoverySourceClass.STREAMING, streamingRule(), 3,
                sample(99, 99, 0),
                ReadinessDecision.READY_FOR_D4, List.of());
        assertThrows(IllegalArgumentException.class, () -> pack(
                QualityRecoverySourceClass.STREAMING, streamingRule(), 3,
                sample(99, 98, 0),
                ReadinessDecision.READY_FOR_D4, List.of()));
        assertThrows(IllegalArgumentException.class, () -> pack(
                QualityRecoverySourceClass.STREAMING, streamingRule(), 3,
                sample(101, 99, 0),
                ReadinessDecision.READY_FOR_D4, List.of()));
        pack(QualityRecoverySourceClass.STREAMING, streamingRule(), 3,
                sample(101, 100, 0),
                ReadinessDecision.READY_FOR_D4, List.of());
    }

    @Test
    void exactReadinessDecisionAndMismatchFailuresCannotMasqueradeAsReady() {
        assertThrows(IllegalArgumentException.class, () -> pack(
                QualityRecoverySourceClass.STREAMING, streamingRule(), 2,
                sample(100, 100, 0),
                ReadinessDecision.READY_FOR_D4, List.of()));
        assertThrows(IllegalArgumentException.class, () -> pack(
                QualityRecoverySourceClass.DAILY_BATCH, dailyRule(), 1,
                sample(100, 100, 0),
                ReadinessDecision.READY_FOR_D4, List.of()));
        assertThrows(IllegalArgumentException.class, () -> pack(
                QualityRecoverySourceClass.STREAMING, streamingRule(), 3,
                sample(100, 100, 1),
                ReadinessDecision.READY_FOR_D4, List.of()));

        RecoveryEvidencePack notReady = pack(
                QualityRecoverySourceClass.STREAMING, streamingRule(), 2,
                sample(100, 100, 0),
                ReadinessDecision.NOT_READY,
                List.of(MissingEvidenceCode.CONSECUTIVE_BATCHES_INSUFFICIENT));
        assertEquals(ReadinessDecision.NOT_READY, notReady.readinessDecision());
    }

    @Test
    void packAcceptsOnlyTheExactUpstreamReadinessSnapshot() {
        QualityRecoveryReadiness.Decision ready = new QualityRecoveryReadiness.Decision(
                true, 3, 3, STARTED, java.util.Set.of());
        RecoveryEvidencePack pack = RecoveryEvidencePack.create(
                uuid(5), 1, objectBinding(), bindings(),
                QualityRecoverySourceClass.STREAMING, RecoveryVersionBindingsTest.policy(), quality(),
                ready, digest('7'), backfill(), reconciliation(), sample(100, 100, 0),
                digest('8'), STARTED, COMPLETED, digest('9'));

        assertEquals(ReadinessDecision.READY_FOR_D4, pack.readinessDecision());
        assertEquals(3, pack.batchEvidence().requiredConsecutivePassedBatches());
        assertEquals(3, pack.batchEvidence().actualConsecutivePassedBatches());

        QualityRecoveryReadiness.Decision notReady = new QualityRecoveryReadiness.Decision(
                false, 3, 2, STARTED,
                java.util.Set.of(QualityRecoveryReadiness.MissingEvidence
                        .CONSECUTIVE_BATCHES_INSUFFICIENT));
        RecoveryEvidencePack failed = RecoveryEvidencePack.create(
                uuid(5), 1, objectBinding(), bindings(),
                QualityRecoverySourceClass.STREAMING, RecoveryVersionBindingsTest.policy(), quality(),
                notReady, digest('7'), backfill(), reconciliation(), sample(100, 100, 0),
                digest('8'), STARTED, COMPLETED, digest('9'));

        assertEquals(ReadinessDecision.NOT_READY, failed.readinessDecision());
        assertEquals(List.of(MissingEvidenceCode.CONSECUTIVE_BATCHES_INSUFFICIENT),
                failed.missingEvidenceCodes());
    }

    @Test
    void policyEvidenceCanOnlyComeFromTheExactDigestBoundQrp() {
        List<VersionDigestBinding> drifted = new ArrayList<>(completeBindings());
        drifted.set(0, new VersionDigestBinding(
                Authority.QUALITY_RECOVERY_POLICY, "QRP-1.0.0", digest('c')));
        RecoveryVersionBindings wrongPolicyDigest =
                RecoveryVersionBindingsTest.bindings(drifted);

        assertThrows(IllegalArgumentException.class,
                () -> RecoveryVersionBindings.ResolvedPolicyEvidence.from(
                        RecoveryVersionBindingsTest.policy(),
                        QualityRecoverySourceClass.STREAMING, wrongPolicyDigest));
    }

    @Test
    void missingCodesMustExactlyCoverDerivableEvidenceFailures() {
        assertThrows(IllegalArgumentException.class, () -> pack(
                QualityRecoverySourceClass.STREAMING, streamingRule(), 2,
                sample(100, 100, 0),
                ReadinessDecision.NOT_READY,
                List.of(MissingEvidenceCode.SAMPLE_MISMATCH)));
        assertThrows(IllegalArgumentException.class, () -> pack(
                QualityRecoverySourceClass.STREAMING, streamingRule(), 3,
                sample(100, 100, 0),
                ReadinessDecision.NOT_READY, List.of()));
        assertThrows(IllegalArgumentException.class, () -> pack(
                QualityRecoverySourceClass.STREAMING, streamingRule(), 2,
                sample(100, 100, 0), ReadinessDecision.NOT_READY,
                List.of(MissingEvidenceCode.CONSECUTIVE_BATCHES_INSUFFICIENT,
                        MissingEvidenceCode.SAMPLE_MISMATCH)));
    }

    @Test
    void objectVersionsAndAllTimesAreBoundAtMicrosecondPrecision() {
        RecoveryObjectBinding wrongVersion = new RecoveryObjectBinding(
                uuid(1), uuid(2), uuid(3), uuid(4), 4,
                "SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001", 99, 12, 13);
        assertThrows(IllegalArgumentException.class, () -> new RecoveryEvidencePack(
                uuid(5), 1, wrongVersion, bindings(), resolved(),
                QualityRecoverySourceClass.STREAMING,
                quality(), new BatchEvidence(3, 3, digest('7')),
                backfill(), reconciliation(), sample(100, 100, 0), digest('8'),
                ReadinessDecision.READY_FOR_D4, List.of(), STARTED, COMPLETED, digest('9')));

        assertThrows(IllegalArgumentException.class, () -> new ReconciliationEvidence(
                "full", 100, 100, 0, digest('4'), COMPLETED.plusNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> new RecoveryEvidencePack(
                uuid(5), 1, objectBinding(), bindings(), resolved(),
                QualityRecoverySourceClass.STREAMING,
                quality(), new BatchEvidence(3, 3, digest('7')),
                backfill(), reconciliation(), sample(100, 100, 0), digest('8'),
                ReadinessDecision.READY_FOR_D4, List.of(), COMPLETED, STARTED, digest('9')));
    }

    @Test
    void strataAreSelfContainedSortedAndMustReconcileToOverallCounts() {
        SampleEvidence twoStrata = new SampleEvidence(
                "RECOVERY-SAMPLE-PROVIDER-1.0.0", SampleProviderAvailability.AVAILABLE,
                true, digest('1'), 100, 100,
                List.of(
                        new StratumSummary("SECOND", 40, 40, 0, digest('2')),
                        new StratumSummary("FIRST", 60, 60, 0, digest('3'))),
                digest('4'), digest('6'), digest('6'), 0, COMPLETED);
        RecoveryEvidencePack pack = pack(
                QualityRecoverySourceClass.STREAMING, streamingRule(), 3,
                twoStrata, ReadinessDecision.READY_FOR_D4, List.of());

        assertEquals(List.of("FIRST", "SECOND"), pack.sampleEvidence().strata().stream()
                .map(StratumSummary::stratumCode).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> pack.sampleEvidence().strata().clear());

        SampleEvidence mismatchedTotals = new SampleEvidence(
                "RECOVERY-SAMPLE-PROVIDER-1.0.0", SampleProviderAvailability.AVAILABLE,
                true, digest('1'), 100, 100,
                List.of(new StratumSummary("ONLY", 99, 99, 0, digest('2'))),
                digest('4'), digest('6'), digest('6'), 0, COMPLETED);
        assertThrows(IllegalArgumentException.class, () -> pack(
                QualityRecoverySourceClass.STREAMING, streamingRule(), 3,
                mismatchedTotals, ReadinessDecision.READY_FOR_D4, List.of()));
    }

    private static RecoveryEvidencePack pack(
            QualityRecoverySourceClass sourceClass,
            QualityRecoveryPolicy.SourceClassRule sourceClassRule,
            int actualBatches,
            SampleEvidence sample,
            ReadinessDecision decision,
            List<MissingEvidenceCode> missing) {
        return new RecoveryEvidencePack(
                uuid(5), 1, objectBinding(), bindings(),
                RecoveryVersionBindings.ResolvedPolicyEvidence.from(
                        RecoveryVersionBindingsTest.policy(), sourceClass, bindings()), sourceClass,
                quality(), new BatchEvidence(
                        sourceClassRule.consecutivePassedBatches(), actualBatches, digest('7')),
                backfill(), reconciliation(), sample, digest('8'), decision, missing,
                STARTED, COMPLETED, digest('9'));
    }

    private static RecoveryVersionBindings bindings() {
        return RecoveryVersionBindingsTest.bindings(completeBindings());
    }

    private static RecoveryObjectBinding objectBinding() {
        return new RecoveryObjectBinding(
                uuid(1), uuid(2), uuid(3), uuid(4), 4,
                "SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001", 11, 12, 13);
    }

    private static QualityEvidence quality() {
        return new QualityEvidence(
                digest('1'), digest('2'), digest('3'), true, digest('4'), digest('5'));
    }

    private static BackfillEvidence backfill() {
        return new BackfillEvidence(
                digest('1'), STARTED, digest('2'), digest('3'), 90, digest('4'),
                BackfillStatus.SUCCEEDED);
    }

    private static ReconciliationEvidence reconciliation() {
        return new ReconciliationEvidence("full", 100, 100, 0, digest('4'), COMPLETED);
    }

    private static SampleEvidence sample(int population, int selected, int mismatch) {
        String actual = mismatch == 0 ? digest('6') : digest('7');
        return new SampleEvidence(
                "RECOVERY-SAMPLE-PROVIDER-1.0.0", SampleProviderAvailability.AVAILABLE,
                true, digest('1'), population, selected,
                List.of(new StratumSummary(
                        "ALL", population, selected, mismatch, digest('3'))),
                digest('2'), digest('6'), actual,
                mismatch, COMPLETED);
    }

    private static QualityRecoveryPolicy.SourceClassRule streamingRule() {
        return new QualityRecoveryPolicy.SourceClassRule(3, Duration.ofMinutes(60));
    }

    private static QualityRecoveryPolicy.SourceClassRule dailyRule() {
        return new QualityRecoveryPolicy.SourceClassRule(2, Duration.ofDays(1));
    }

    private static RecoveryVersionBindings.ResolvedPolicyEvidence resolved() {
        return RecoveryVersionBindings.ResolvedPolicyEvidence.from(
                RecoveryVersionBindingsTest.policy(),
                QualityRecoverySourceClass.STREAMING, bindings());
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("01914d3e-8a7b-7c1d-8abc-1234567890a" + suffix);
    }
}
