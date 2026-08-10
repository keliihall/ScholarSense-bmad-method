package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenExecutableQualityPolicyLoader;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchCorrectionReason;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchIdentity;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchLineage;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchManifest;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchObservationWindow;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatch;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.MetricDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.SourcePolicy;
import cn.edu.suda.scholarsense.ingestionquality.domain.MeasuredQualityInputs;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityOverallResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshotCanonicalizer;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Task 2.3 application RED: the calculator/hash implementation is not a persistence boundary.
 * A command may write only after the sealed contract, measurement anchor and direct predecessor
 * have all been bound and the captured contract has been reloaded one final time.
 */
class DataBatchQualityEvaluationServiceTest {

    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
    private static final VerifiedQualityContract CONTRACT =
            new FrozenExecutableQualityPolicyLoader(REPOSITORY).loadVerified();
    private static final String SOURCE_ID = "SRC-P0-STUDENT-001";
    private static final UUID ROOT_BATCH_ID = uuid("019ff300-0000-7000-8000-000000000001");
    private static final UUID SUCCESSOR_BATCH_ID = uuid("019ff300-0000-7000-8000-000000000002");
    private static final UUID THIRD_BATCH_ID = uuid("019ff300-0000-7000-8000-000000000004");
    private static final UUID LINEAGE_ID = uuid("019ff300-0000-7000-8000-000000000003");
    private static final UUID FIRST_SNAPSHOT_ID = uuid("019ff300-0000-7000-8000-000000000101");
    private static final UUID SECOND_SNAPSHOT_ID = uuid("019ff300-0000-7000-8000-000000000102");
    private static final UUID THIRD_SNAPSHOT_ID = uuid("019ff300-0000-7000-8000-000000000103");
    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");
    private static final String ROOT_MANIFEST_DIGEST = "sha256:" + "a".repeat(64);
    private static final String SUCCESSOR_MANIFEST_DIGEST = "sha256:" + "b".repeat(64);
    private static final TrustedTimeSource TIME = () -> new TrustedTime(
            NOW,
            new TimeSourceProfile(
                    "campus-ntp-quality", "AUDIT-CLOCK-BINDING-1.0.0", 7,
                    NOW.minusSeconds(5), NOW.plusSeconds(30),
                    "evidence://signed/clock/quality.json"));

    @Test
    void passAndFailEachCreateOneUniqueSnapshotAndSameReplayNeverDuplicates() {
        Harness passing = new Harness(false);
        passing.receiveAndSealRoot();
        EvaluateDataBatchCommand passCommand = new EvaluateDataBatchCommand(
                ROOT_BATCH_ID, 2, context("evaluate-pass"));

        DataBatchView passed = passing.service.evaluate(passCommand);
        DataBatchView replay = passing.service.evaluate(passCommand);

        assertEquals(DataBatchStatus.QUALITY_PASSED, passed.status());
        assertEquals(passed, replay);
        assertEquals(1, passing.snapshots.insertCalls.get());
        QualitySnapshot passSnapshot = passing.snapshots.byBatch.get(ROOT_BATCH_ID);
        assertEquals(QualityOverallResult.QUALITY_PASSED, passSnapshot.overallResult());
        assertEquals(DataBatchStatus.QUALITY_PASSED, passSnapshot.assessedBatchStatus());
        assertEquals(FIRST_SNAPSHOT_ID, passSnapshot.snapshotId());
        assertTrue(passSnapshot.immutableHash().matches("sha256:[0-9a-f]{64}"));

        Harness failing = new Harness(true);
        failing.receiveAndSealRoot();
        DataBatchView failed = failing.service.evaluate(new EvaluateDataBatchCommand(
                ROOT_BATCH_ID, 2, context("evaluate-fail")));

        assertEquals(DataBatchStatus.QUALITY_FAILED, failed.status());
        assertEquals(1, failing.snapshots.insertCalls.get());
        QualitySnapshot failSnapshot = failing.snapshots.byBatch.get(ROOT_BATCH_ID);
        assertEquals(QualityOverallResult.QUALITY_FAILED, failSnapshot.overallResult());
        assertEquals(DataBatchStatus.QUALITY_FAILED, failSnapshot.assessedBatchStatus());
        assertNotEquals(passSnapshot.immutableHash(), failSnapshot.immutableHash(),
                "business result and metric evidence are included QSHM material");
    }

    @Test
    void evaluateCapturesMeasuresHashesGeneratesUuidRevalidatesThenPerformsTheFirstWrite() {
        Harness harness = new Harness(false);
        harness.receiveAndSealRoot();
        harness.events.clear();
        harness.contractLoads.set(0);

        harness.service.evaluate(new EvaluateDataBatchCommand(
                ROOT_BATCH_ID, 2, context("evaluate-order")));

        assertEquals(List.of(
                "contract-load", "measure", "snapshot-id", "contract-load",
                "batch-save", "snapshot-insert", "audit", "idempotency-complete"),
                harness.events);
        assertEquals(expectedDefinitions(CONTRACT.policy(), SOURCE_ID),
                harness.measurement.lastDefinitions,
                "measurement receives the exact QMDP common-then-source-gate order");
    }

    @Test
    void contractRaceAfterHashAndUuidFailsBeforeAnySnapshotAuditOrIdempotencyWrite()
            throws Exception {
        Harness harness = new Harness(false);
        harness.receiveAndSealRoot();
        int auditsBefore = harness.ports.audits.size();
        int idempotencyBefore = harness.ports.idempotency.size();
        harness.events.clear();
        harness.contractLoads.set(0);
        harness.driftContractWhenIdGenerated = true;

        IngestionQualityApplicationException failure = assertThrows(
                IngestionQualityApplicationException.class,
                () -> harness.service.evaluate(new EvaluateDataBatchCommand(
                        ROOT_BATCH_ID, 2, context("evaluate-contract-race"))));

        assertEquals("INGESTION_QUALITY_CONTRACT_INVALID", failure.code());
        assertEquals(List.of(
                "contract-load", "measure", "snapshot-id", "contract-load"),
                harness.events);
        assertTrue(harness.snapshots.byBatch.isEmpty());
        assertEquals(auditsBefore, harness.ports.audits.size());
        assertEquals(idempotencyBefore, harness.ports.idempotency.size());
        assertSealed(harness);
    }

    @Test
    void zeroDenominatorIsTechnicalAndLeavesTheBatchSealedWithoutAnyCompletedWrite() {
        Harness harness = new Harness(false);
        harness.receiveAndSealRoot();
        harness.measurement.zeroFirstDenominator = true;
        int auditsBefore = harness.ports.audits.size();
        int idempotencyBefore = harness.ports.idempotency.size();

        IngestionQualityApplicationException failure = assertThrows(
                IngestionQualityApplicationException.class,
                () -> harness.service.evaluate(new EvaluateDataBatchCommand(
                        ROOT_BATCH_ID, 2, context("evaluate-zero-denominator"))));

        assertEquals("QUALITY_POLICY_ZERO_DENOMINATOR", failure.code());
        assertEquals(List.of("contract-load", "measure"),
                harness.events.subList(harness.events.size() - 2, harness.events.size()));
        assertTrue(harness.snapshots.byBatch.isEmpty());
        assertEquals(0, harness.snapshotIds.get());
        assertEquals(auditsBefore, harness.ports.audits.size());
        assertEquals(idempotencyBefore, harness.ports.idempotency.size());
        assertSealed(harness);
    }

    @Test
    void anInexactMeasurementAnchorIsATechnicalFailureBeforeSnapshotConstructionOrWrites()
            throws Exception {
        Harness harness = new Harness(false);
        harness.receiveAndSealRoot();
        int auditsBefore = harness.ports.audits.size();
        int idempotencyBefore = harness.ports.idempotency.size();
        harness.measurement.driftAnchor = true;
        harness.events.clear();

        IngestionQualityApplicationException failure = assertThrows(
                IngestionQualityApplicationException.class,
                () -> harness.service.evaluate(new EvaluateDataBatchCommand(
                        ROOT_BATCH_ID, 2, context("evaluate-anchor-drift"))));

        assertEquals("INGESTION_QUALITY_EVIDENCE_INVALID", failure.code());
        assertEquals(List.of("contract-load", "measure"), harness.events);
        assertTrue(harness.snapshots.byBatch.isEmpty());
        assertEquals(0, harness.snapshotIds.get());
        assertEquals(auditsBefore, harness.ports.audits.size());
        assertEquals(idempotencyBefore, harness.ports.idempotency.size());
        assertSealed(harness);
    }

    @Test
    void aSuccessorUsesOnlyTheSnapshotOfItsDirectSupersededBatch() {
        Harness harness = new Harness(false);
        harness.receiveAndSealRoot();
        harness.service.evaluate(new EvaluateDataBatchCommand(
                ROOT_BATCH_ID, 2, context("evaluate-root")));
        QualitySnapshot rootSnapshot = harness.snapshots.byBatch.get(ROOT_BATCH_ID);
        harness.receiveAndSealSuccessor();
        harness.events.clear();

        DataBatchView assessed = harness.service.evaluate(new EvaluateDataBatchCommand(
                SUCCESSOR_BATCH_ID, 2, context("evaluate-successor")));

        assertEquals(DataBatchStatus.QUALITY_PASSED, assessed.status());
        QualitySnapshot successor = harness.snapshots.byBatch.get(SUCCESSOR_BATCH_ID);
        assertEquals(rootSnapshot.snapshotId(), successor.supersedesSnapshotId());
        assertEquals(List.of(ROOT_BATCH_ID), harness.snapshots.predecessorLookups);
        assertTrue(harness.events.indexOf("predecessor-lookup")
                < harness.events.indexOf("snapshot-id"));
    }

    @Test
    void missingOrWrongDirectPredecessorNeverCreatesAReplacementSnapshot() {
        Harness missing = preparedSuccessorHarness();
        missing.snapshots.byBatch.remove(ROOT_BATCH_ID);
        int missingAuditBefore = missing.ports.audits.size();

        IngestionQualityApplicationException absent = assertThrows(
                IngestionQualityApplicationException.class,
                () -> missing.service.evaluate(new EvaluateDataBatchCommand(
                        SUCCESSOR_BATCH_ID, 2, context("evaluate-missing-predecessor"))));

        assertEquals("INGESTION_QUALITY_PREDECESSOR_SNAPSHOT_INVALID", absent.code());
        assertFalse(missing.snapshots.byBatch.containsKey(SUCCESSOR_BATCH_ID));
        assertEquals(missingAuditBefore, missing.ports.audits.size());
        assertSealed(missing, SUCCESSOR_BATCH_ID);

        Harness wrong = preparedSuccessorHarness();
        QualitySnapshot predecessor = wrong.snapshots.byBatch.get(ROOT_BATCH_ID);
        wrong.snapshots.byBatch.put(ROOT_BATCH_ID, copyWithBatchId(
                predecessor, uuid("019ff300-0000-7000-8000-000000000099")));
        int wrongAuditBefore = wrong.ports.audits.size();

        IngestionQualityApplicationException mismatched = assertThrows(
                IngestionQualityApplicationException.class,
                () -> wrong.service.evaluate(new EvaluateDataBatchCommand(
                        SUCCESSOR_BATCH_ID, 2, context("evaluate-wrong-predecessor"))));

        assertEquals("INGESTION_QUALITY_PREDECESSOR_SNAPSHOT_INVALID", mismatched.code());
        assertFalse(wrong.snapshots.byBatch.containsKey(SUCCESSOR_BATCH_ID));
        assertEquals(wrongAuditBefore, wrong.ports.audits.size());
        assertSealed(wrong, SUCCESSOR_BATCH_ID);
    }

    @Test
    void publishRejectsAnyContractDriftFromTheAttestationFrozenAtSeal() throws Exception {
        Harness harness = new Harness(false);
        harness.receiveAndSealRoot();
        harness.service.evaluate(new EvaluateDataBatchCommand(
                ROOT_BATCH_ID, 2, context("evaluate-before-publish")));
        int auditsBefore = harness.ports.audits.size();
        int idempotencyBefore = harness.ports.idempotency.size();
        harness.contract.current = driftContract(CONTRACT, "qshmApprovalRef");

        IngestionQualityApplicationException failure = assertThrows(
                IngestionQualityApplicationException.class,
                () -> harness.service.publish(new PublishDataBatchCommand(
                        ROOT_BATCH_ID, 3, context("publish-contract-drift"))));

        assertEquals("INGESTION_QUALITY_CONTRACT_INVALID", failure.code());
        assertEquals(DataBatchStatus.QUALITY_PASSED,
                harness.ports.byId.get(ROOT_BATCH_ID).status());
        assertEquals(auditsBefore, harness.ports.audits.size());
        assertEquals(idempotencyBefore, harness.ports.idempotency.size());
    }

    @Test
    void snapshotKeepsTheEvaluateTraceWhileASeparatePublishTraceRemainsValid() {
        Harness harness = new Harness(false);
        harness.receiveAndSealRoot();
        String evaluateTrace = "11112222333344445555666677778888";
        String publishTrace = "88887777666655554444333322221111";

        harness.service.evaluate(new EvaluateDataBatchCommand(
                ROOT_BATCH_ID, 2, context("evaluate-distinct-trace", evaluateTrace)));
        DataBatchView published = harness.service.publish(new PublishDataBatchCommand(
                ROOT_BATCH_ID, 3, context("publish-distinct-trace", publishTrace)));

        assertEquals(DataBatchStatus.PUBLISHED, published.status());
        assertEquals(evaluateTrace, harness.snapshots.byBatch.get(ROOT_BATCH_ID).traceId());
    }

    @Test
    void snapshotWritesRequireANonForgeablePolicyVerifiedValue() throws Exception {
        assertThrows(
                NoSuchMethodException.class,
                () -> QualitySnapshotRepository.class.getMethod("insert", QualitySnapshot.class));
        assertEquals(
                void.class,
                QualitySnapshotRepository.class
                        .getMethod("insert", VerifiedQualitySnapshot.class)
                        .getReturnType());
        assertTrue(Modifier.isFinal(VerifiedQualitySnapshot.class.getModifiers()));
        assertTrue(Arrays.stream(VerifiedQualitySnapshot.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
    }

    @Test
    void measurementAnchorContainsTheWholeFrozenManifestNotOnlyItsClaimedDigest() {
        Harness harness = new Harness(false);
        harness.receiveAndSealRoot();
        DataBatch sealed = harness.ports.byId.get(ROOT_BATCH_ID);
        BatchManifest original = sealed.manifest();
        BatchManifest sameDigestDifferentEvidence = new BatchManifest(
                original.recordCount(), original.validRecordCount() - 1,
                original.rejectedRecordCount() + 1, original.observationWindow(),
                original.cutoffAt(), original.timezone(), original.watermark(),
                original.sourceSchemaVersion(), original.sourceSchemaDigest(),
                original.dataCatalogVersion(), original.dataCatalogDigest(),
                original.qualityGateVersion(), original.qualityGateDigest(),
                original.qualityMetricDecisionProfileVersion(),
                original.qualityMetricDecisionProfileDigest(), original.sourceOccurredAt(),
                original.scheduledDueAt(), original.receivedAt(), original.laneId(),
                original.manifestDigest());
        DataBatch drifted = DataBatch.receiving(
                        uuid("019ff300-0000-7000-8000-000000000009"), sealed.identity(),
                        BatchLineage.root(
                                uuid("019ff300-0000-7000-8000-000000000019"), NOW),
                        original.manifestDigest(), NOW, sealed.traceId())
                .seal(sameDigestDifferentEvidence, sealed.sealedQualityContractEvidence(), NOW);

        assertNotEquals(
                QualityMeasurementAnchor.from(sealed),
                QualityMeasurementAnchor.from(drifted));
        assertEquals(
                List.of(
                        "batchId", "sealedAggregateVersion", "sourceId", "manifest",
                        "lineageId", "supersedesBatchId"),
                Arrays.stream(QualityMeasurementAnchor.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList());
    }

    @Test
    void publishRejectsARehashedSnapshotWhoseIncludedManifestEvidenceWasMixed() {
        Harness harness = new Harness(false);
        harness.receiveAndSealRoot();
        harness.service.evaluate(new EvaluateDataBatchCommand(
                ROOT_BATCH_ID, 2, context("evaluate-before-snapshot-mix")));
        QualitySnapshot original = harness.snapshots.byBatch.get(ROOT_BATCH_ID);
        QualitySnapshotCanonicalizer canonicalizer = new QualitySnapshotCanonicalizer(
                CONTRACT.policy(), CONTRACT.hashProfile());
        QualitySnapshot changed = copyWithManifestEvidence(
                original, original.watermark() + "-other", "sha256:" + "c".repeat(64),
                "sha256:" + "1".repeat(64));
        changed = copyWithManifestEvidence(
                changed, changed.watermark(), changed.manifestDigest(),
                canonicalizer.immutableHash(changed));
        harness.snapshots.byBatch.put(ROOT_BATCH_ID, changed);

        IngestionQualityApplicationException failure = assertThrows(
                IngestionQualityApplicationException.class,
                () -> harness.service.publish(new PublishDataBatchCommand(
                        ROOT_BATCH_ID, 3, context("publish-snapshot-mix"))));

        assertEquals("INGESTION_QUALITY_CONTRACT_INVALID", failure.code());
        assertEquals(DataBatchStatus.QUALITY_PASSED,
                harness.ports.byId.get(ROOT_BATCH_ID).status());
    }

    @Test
    void successorRejectsARehashedButMixedDirectPredecessorSnapshot() {
        Harness harness = new Harness(false);
        harness.receiveAndSealRoot();
        harness.service.evaluate(new EvaluateDataBatchCommand(
                ROOT_BATCH_ID, 2, context("evaluate-predecessor-before-mix")));
        QualitySnapshot original = harness.snapshots.byBatch.get(ROOT_BATCH_ID);
        QualitySnapshotCanonicalizer canonicalizer = new QualitySnapshotCanonicalizer(
                CONTRACT.policy(), CONTRACT.hashProfile());
        QualitySnapshot changed = copyWithManifestEvidence(
                original, original.watermark() + "-mixed", original.manifestDigest(),
                "sha256:" + "1".repeat(64));
        changed = copyWithManifestEvidence(
                changed, changed.watermark(), changed.manifestDigest(),
                canonicalizer.immutableHash(changed));
        harness.snapshots.byBatch.put(ROOT_BATCH_ID, changed);
        harness.receiveAndSealSuccessor();

        IngestionQualityApplicationException failure = assertThrows(
                IngestionQualityApplicationException.class,
                () -> harness.service.evaluate(new EvaluateDataBatchCommand(
                        SUCCESSOR_BATCH_ID, 2, context("evaluate-mixed-predecessor"))));

        assertEquals("INGESTION_QUALITY_PREDECESSOR_SNAPSHOT_INVALID", failure.code());
        assertFalse(harness.snapshots.byBatch.containsKey(SUCCESSOR_BATCH_ID));
        assertSealed(harness, SUCCESSOR_BATCH_ID);
    }

    @Test
    void thirdGenerationNeedsOnlyItsDirectPredecessorAfterAnOlderAncestorWasRetainedAway() {
        Harness harness = new Harness(false);
        harness.receiveAndSealRoot();
        harness.service.evaluate(new EvaluateDataBatchCommand(
                ROOT_BATCH_ID, 2, context("evaluate-generation-one")));
        harness.receiveAndSealSuccessor();
        harness.service.evaluate(new EvaluateDataBatchCommand(
                SUCCESSOR_BATCH_ID, 2, context("evaluate-generation-two")));
        harness.snapshots.byBatch.remove(ROOT_BATCH_ID);
        harness.ports.byId.remove(ROOT_BATCH_ID);
        harness.receiveAndSealThirdSuccessor();
        harness.snapshots.predecessorLookups.clear();

        DataBatchView assessed = harness.service.evaluate(new EvaluateDataBatchCommand(
                THIRD_BATCH_ID, 2, context("evaluate-generation-three")));

        assertEquals(DataBatchStatus.QUALITY_PASSED, assessed.status());
        assertEquals(
                harness.snapshots.byBatch.get(SUCCESSOR_BATCH_ID).snapshotId(),
                harness.snapshots.byBatch.get(THIRD_BATCH_ID).supersedesSnapshotId());
        assertEquals(List.of(SUCCESSOR_BATCH_ID), harness.snapshots.predecessorLookups);
    }

    @Test
    void directPredecessorLookupKeyMustEqualBothReturnedOwnerIdentities() {
        Harness harness = new Harness(false);
        harness.receiveAndSealRoot();
        harness.service.evaluate(new EvaluateDataBatchCommand(
                ROOT_BATCH_ID, 2, context("evaluate-before-key-substitution")));
        harness.receiveAndSealSuccessor();
        UUID otherBatchId = uuid("019ff300-0000-7000-8000-000000000099");
        DataBatch originalBatch = harness.ports.byId.get(ROOT_BATCH_ID);
        DataBatch otherBatch = DataBatch.receiving(
                        otherBatchId, originalBatch.identity(),
                        BatchLineage.root(LINEAGE_ID, NOW), ROOT_MANIFEST_DIGEST, NOW,
                        originalBatch.traceId())
                .seal(originalBatch.manifest(),
                        originalBatch.sealedQualityContractEvidence(), NOW)
                .recordQualityResult(true, NOW);
        QualitySnapshotCanonicalizer canonicalizer = new QualitySnapshotCanonicalizer(
                CONTRACT.policy(), CONTRACT.hashProfile());
        QualitySnapshot otherSnapshot = copyWithBatchId(
                harness.snapshots.byBatch.get(ROOT_BATCH_ID), otherBatchId);
        otherSnapshot = copyWithManifestEvidence(
                otherSnapshot, otherSnapshot.watermark(), otherSnapshot.manifestDigest(),
                canonicalizer.immutableHash(otherSnapshot));
        harness.ports.byId.put(ROOT_BATCH_ID, otherBatch);
        harness.snapshots.byBatch.put(ROOT_BATCH_ID, otherSnapshot);

        IngestionQualityApplicationException failure = assertThrows(
                IngestionQualityApplicationException.class,
                () -> harness.service.evaluate(new EvaluateDataBatchCommand(
                        SUCCESSOR_BATCH_ID, 2, context("evaluate-key-substitution"))));

        assertEquals("INGESTION_QUALITY_PREDECESSOR_SNAPSHOT_INVALID", failure.code());
        assertFalse(harness.snapshots.byBatch.containsKey(SUCCESSOR_BATCH_ID));
        assertSealed(harness, SUCCESSOR_BATCH_ID);
    }

    private static Harness preparedSuccessorHarness() {
        Harness harness = new Harness(false);
        harness.receiveAndSealRoot();
        harness.service.evaluate(new EvaluateDataBatchCommand(
                ROOT_BATCH_ID, 2, context("evaluate-predecessor")));
        harness.receiveAndSealSuccessor();
        return harness;
    }

    private static void assertSealed(Harness harness) {
        assertSealed(harness, ROOT_BATCH_ID);
    }

    private static void assertSealed(Harness harness, UUID batchId) {
        DataBatch batch = harness.ports.byId.get(batchId);
        assertEquals(DataBatchStatus.SEALED, batch.status());
        assertEquals(2, batch.aggregateVersion());
    }

    private static List<MetricDefinition> expectedDefinitions(
            ExecutableQualityPolicy policy, String sourceId) {
        SourcePolicy source = source(policy, sourceId);
        List<MetricDefinition> result = new ArrayList<>();
        for (MetricDefinition metric : policy.commonMetrics()) {
            if (source.applicableCommonMetricIds().contains(metric.metricId())) result.add(metric);
        }
        result.addAll(source.sourceGates());
        return List.copyOf(result);
    }

    private static SourcePolicy source(ExecutableQualityPolicy policy, String sourceId) {
        return policy.sources().stream()
                .filter(candidate -> candidate.sourceId().equals(sourceId))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, MeasuredQualityInputs> measurements(
            List<MetricDefinition> definitions,
            boolean failFirst,
            boolean zeroFirstDenominator) {
        LinkedHashMap<String, MeasuredQualityInputs> result = new LinkedHashMap<>();
        for (int index = 0; index < definitions.size(); index++) {
            MetricDefinition definition = definitions.get(index);
            LinkedHashMap<String, BigInteger> operands = new LinkedHashMap<>();
            if (definition.calculation().numerator().operandId() != null) {
                operands.put(
                        definition.calculation().numerator().operandId(),
                        failFirst && index == 0
                                ? BigInteger.ZERO
                                : BigInteger.valueOf(definition.thresholdNumerator()));
            }
            if (definition.calculation().denominator().operandId() != null) {
                operands.put(
                        definition.calculation().denominator().operandId(),
                        zeroFirstDenominator && index == 0
                                ? BigInteger.ZERO
                                : BigInteger.valueOf(definition.thresholdDenominator()));
            }
            result.put(definition.formulaId(), new MeasuredQualityInputs(true, operands));
        }
        return Map.copyOf(result);
    }

    private static QualitySnapshot copyWithBatchId(QualitySnapshot value, UUID batchId) {
        return new QualitySnapshot(
                value.domainTag(), value.hashProfileVersion(), value.hashProfileDigest(), batchId,
                value.sourceId(), value.assessedBatchStatus(), value.overallResult(),
                value.observationWindow(), value.cutoffAt(), value.watermark(),
                value.metricResults(), value.impactScopeCodes(), value.sourceOwnerRef(),
                value.approvalRef(), value.effectiveAt(), value.retentionScheduleVersion(),
                value.qualityMetricDecisionProfileVersion(),
                value.qualityMetricDecisionProfileDigest(), value.qualityGateVersion(),
                value.qualityGateDigest(), value.canonicalizationProfile(),
                value.manifestDigest(), value.sourceSchemaVersion(), value.sourceSchemaDigest(),
                value.lineageId(), value.supersedesSnapshotId(), value.snapshotId(),
                value.evaluatedAt(), value.traceId(), value.aggregateVersion(),
                value.immutableHash());
    }

    private static QualitySnapshot copyWithManifestEvidence(
            QualitySnapshot value,
            String watermark,
            String manifestDigest,
            String immutableHash) {
        return new QualitySnapshot(
                value.domainTag(), value.hashProfileVersion(), value.hashProfileDigest(),
                value.batchId(), value.sourceId(), value.assessedBatchStatus(),
                value.overallResult(), value.observationWindow(), value.cutoffAt(), watermark,
                value.metricResults(), value.impactScopeCodes(), value.sourceOwnerRef(),
                value.approvalRef(), value.effectiveAt(), value.retentionScheduleVersion(),
                value.qualityMetricDecisionProfileVersion(),
                value.qualityMetricDecisionProfileDigest(), value.qualityGateVersion(),
                value.qualityGateDigest(), value.canonicalizationProfile(), manifestDigest,
                value.sourceSchemaVersion(), value.sourceSchemaDigest(), value.lineageId(),
                value.supersedesSnapshotId(), value.snapshotId(), value.evaluatedAt(),
                value.traceId(), value.aggregateVersion(), immutableHash);
    }

    private static VerifiedQualityContract driftContract(
            VerifiedQualityContract original, String componentName) throws Exception {
        QualityContractAttestation attestation = original.attestation();
        RecordComponent[] components = QualityContractAttestation.class.getRecordComponents();
        Class<?>[] parameterTypes = Arrays.stream(components)
                .map(RecordComponent::getType)
                .toArray(Class<?>[]::new);
        Object[] values = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            values[index] = components[index].getAccessor().invoke(attestation);
            if (components[index].getName().equals(componentName)) {
                values[index] = values[index] instanceof Instant instant
                        ? instant.plusSeconds(1)
                        : values[index] + "-drift";
            }
        }
        QualityContractAttestation drifted = (QualityContractAttestation)
                QualityContractAttestation.class.getDeclaredConstructor(parameterTypes)
                        .newInstance(values);
        return new VerifiedQualityContract(original.policy(), original.hashProfile(), drifted);
    }

    private static BatchManifest manifest(String digest) {
        SourcePolicy source = source(CONTRACT.policy(), SOURCE_ID);
        Instant cutoffAt = NOW.plusSeconds(1);
        return new BatchManifest(
                10, 10, 0,
                new BatchObservationWindow(cutoffAt.minusSeconds(720L * 3_600L), cutoffAt),
                cutoffAt, "Asia/Shanghai", "sha256:" + "8".repeat(64),
                source.schemaBinding().version(), source.schemaBinding().canonicalDigest(),
                CONTRACT.policy().controlledInputs().dataCatalog().version(),
                CONTRACT.policy().controlledInputs().dataCatalog().canonicalDigest(),
                CONTRACT.policy().controlledInputs().qualityGate().version(),
                CONTRACT.policy().controlledInputs().qualityGate().canonicalDigest(),
                CONTRACT.policy().profileVersion(),
                CONTRACT.attestation().qmdpPolicyCanonicalDigest(),
                NOW.minusSeconds(1), NOW.plusSeconds(60), NOW,
                source.freshnessLanes().getFirst().laneId(), digest);
    }

    private static DataBatchCommandContext context(String key) {
        return context(key, "00112233445566778899aabbccddeeff");
    }

    private static DataBatchCommandContext context(String key, String traceId) {
        return new DataBatchCommandContext(
                "tenant-a", "quality-worker-a", key,
                traceId);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static final class Harness {
        private final List<String> events = new ArrayList<>();
        private final MutableContractPort contract = new MutableContractPort(events);
        private final AtomicInteger contractLoads = contract.loads;
        private final MemorySnapshots snapshots = new MemorySnapshots(events);
        private final MemoryPorts ports = new MemoryPorts(events);
        private final Measurement measurement;
        private final AtomicInteger snapshotIds = new AtomicInteger();
        private boolean driftContractWhenIdGenerated;
        private final DataBatchCommandService service;

        private Harness(boolean failFirstMetric) {
            measurement = new Measurement(events, failFirstMetric);
            ExecutableQualityPolicyGuard guard = new ExecutableQualityPolicyGuard(contract);
            QualitySnapshotIdPort ids = evaluatedAt -> {
                events.add("snapshot-id");
                int index = snapshotIds.getAndIncrement();
                if (driftContractWhenIdGenerated) {
                    try {
                        contract.current = driftContract(CONTRACT, "qmdpPolicyCanonicalDigest");
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }
                return switch (index) {
                    case 0 -> FIRST_SNAPSHOT_ID;
                    case 1 -> SECOND_SNAPSHOT_ID;
                    case 2 -> THIRD_SNAPSHOT_ID;
                    default -> throw new AssertionError("unexpected snapshot id request");
                };
            };
            DataBatchQualityEvaluationService evaluation =
                    new DataBatchQualityEvaluationService(
                            guard, measurement, ports, snapshots, ids);
            ports.snapshots = snapshots;
            service = new DataBatchCommandService(
                    ports, snapshots, ports, ports, evaluation, guard, ports,
                    DataBatchWorkloadAuthorizationTestFixture.guard("quality-worker-a"),
                    new DataBatchCanonicalOutboxFactory("scholarsense_iq_test_worker"), TIME);
        }

        private void receiveAndSealRoot() {
            service.receive(new ReceiveDataBatchCommand(
                    ROOT_BATCH_ID, 0,
                    new BatchIdentity(SOURCE_ID, "student-status:2026-08-09", 7),
                    BatchLineage.root(LINEAGE_ID, NOW), ROOT_MANIFEST_DIGEST,
                    context("receive-root")));
            service.seal(new SealDataBatchCommand(
                    ROOT_BATCH_ID, 1, manifest(ROOT_MANIFEST_DIGEST),
                    context("seal-root")));
        }

        private void receiveAndSealSuccessor() {
            service.receive(new ReceiveDataBatchCommand(
                    SUCCESSOR_BATCH_ID, 0,
                    new BatchIdentity(SOURCE_ID, "student-status:2026-08-09", 8),
                    BatchLineage.successor(
                            LINEAGE_ID, ROOT_BATCH_ID, BatchCorrectionReason.LATE_ARRIVAL,
                            NOW.plusSeconds(1)),
                    SUCCESSOR_MANIFEST_DIGEST, context("receive-successor")));
            service.seal(new SealDataBatchCommand(
                    SUCCESSOR_BATCH_ID, 1, manifest(SUCCESSOR_MANIFEST_DIGEST),
                    context("seal-successor")));
        }

        private void receiveAndSealThirdSuccessor() {
            String digest = "sha256:" + "d".repeat(64);
            service.receive(new ReceiveDataBatchCommand(
                    THIRD_BATCH_ID, 0,
                    new BatchIdentity(SOURCE_ID, "student-status:2026-08-09", 9),
                    BatchLineage.successor(
                            LINEAGE_ID, SUCCESSOR_BATCH_ID,
                            BatchCorrectionReason.SOURCE_CORRECTION, NOW.plusSeconds(2)),
                    digest, context("receive-third-successor")));
            service.seal(new SealDataBatchCommand(
                    THIRD_BATCH_ID, 1, manifest(digest), context("seal-third-successor")));
        }
    }

    private static final class MutableContractPort implements ExecutableQualityPolicyPort {
        private final List<String> events;
        private final AtomicInteger loads = new AtomicInteger();
        private VerifiedQualityContract current = CONTRACT;

        private MutableContractPort(List<String> events) {
            this.events = events;
        }

        @Override
        public VerifiedQualityContract loadVerified() {
            loads.incrementAndGet();
            events.add("contract-load");
            return current;
        }
    }

    private static final class Measurement implements QualityMeasurementPort {
        private final List<String> events;
        private final boolean failFirst;
        private boolean driftAnchor;
        private boolean zeroFirstDenominator;
        private List<MetricDefinition> lastDefinitions = List.of();

        private Measurement(List<String> events, boolean failFirst) {
            this.events = events;
            this.failFirst = failFirst;
        }

        @Override
        public QualityMeasurement measure(
                DataBatch sealedBatch, List<MetricDefinition> orderedDefinitions) {
            events.add("measure");
            lastDefinitions = List.copyOf(orderedDefinitions);
            QualityMeasurementAnchor anchor = QualityMeasurementAnchor.from(sealedBatch);
            if (driftAnchor) anchor = driftAnchor(anchor);
            return new QualityMeasurement(
                    anchor,
                    measurements(lastDefinitions, failFirst, zeroFirstDenominator),
                    List.of("student-status"));
        }

        private static QualityMeasurementAnchor driftAnchor(QualityMeasurementAnchor original) {
            try {
                RecordComponent[] components = QualityMeasurementAnchor.class.getRecordComponents();
                Class<?>[] parameterTypes = Arrays.stream(components)
                        .map(RecordComponent::getType)
                        .toArray(Class<?>[]::new);
                Object[] values = new Object[components.length];
                boolean changed = false;
                for (int index = 0; index < components.length; index++) {
                    values[index] = components[index].getAccessor().invoke(original);
                    if (!changed && values[index] instanceof String text) {
                        values[index] = text + "-drift";
                        changed = true;
                    }
                }
                if (!changed) throw new AssertionError("anchor has no string binding to mutate");
                return (QualityMeasurementAnchor) QualityMeasurementAnchor.class
                        .getDeclaredConstructor(parameterTypes).newInstance(values);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class MemorySnapshots implements QualitySnapshotRepository {
        private final List<String> events;
        private final Map<UUID, QualitySnapshot> byBatch = new LinkedHashMap<>();
        private final List<UUID> predecessorLookups = new ArrayList<>();
        private final AtomicInteger insertCalls = new AtomicInteger();

        private MemorySnapshots(List<String> events) {
            this.events = events;
        }

        @Override
        public Optional<QualitySnapshot> findByBatchId(UUID batchId) {
            events.add("predecessor-lookup");
            predecessorLookups.add(batchId);
            return Optional.ofNullable(byBatch.get(batchId));
        }

        @Override
        public void insert(VerifiedQualitySnapshot verified) {
            QualitySnapshot snapshot = verified.value();
            events.add("snapshot-insert");
            if (byBatch.putIfAbsent(snapshot.batchId(), snapshot) != null) {
                throw new IllegalStateException("duplicate snapshot for batch");
            }
            insertCalls.incrementAndGet();
        }
    }

    private static final class MemoryPorts implements
            DataBatchRepository,
            DataBatchIdempotencyPort,
            DataBatchTransactionPort,
            DataBatchAuthorizationPort,
            DataBatchAuditPort,
            DataBatchCommandReplayPort,
            DataBatchAtomicCommandPort {
        private final List<String> events;
        private final Map<UUID, DataBatch> byId = new ConcurrentHashMap<>();
        private final Map<DataBatchIdempotencyScope, DataBatchIdempotencyResult> idempotency =
                new ConcurrentHashMap<>();
        private final List<DataBatchAuditEvent> audits =
                Collections.synchronizedList(new ArrayList<>());
        private MemorySnapshots snapshots;

        private MemoryPorts(List<String> events) {
            this.events = events;
        }

        @Override
        public Optional<DataBatch> find(UUID batchId) {
            return Optional.ofNullable(byId.get(batchId));
        }

        @Override
        public Optional<DataBatch> findByIdentity(BatchIdentity identity) {
            return byId.values().stream()
                    .filter(batch -> batch.identity().equals(identity)).findFirst();
        }

        @Override
        public Optional<DataBatch> latestForBusinessKey(String sourceId, String businessKey) {
            return byId.values().stream()
                    .filter(batch -> batch.identity().sourceId().equals(sourceId)
                            && batch.identity().businessKey().equals(businessKey))
                    .max(java.util.Comparator.comparingLong(
                            batch -> batch.identity().sourceVersion()));
        }

        @Override
        public Optional<DataBatch> lineageHead(UUID lineageId) {
            return byId.values().stream()
                    .filter(batch -> batch.lineage().lineageId().equals(lineageId))
                    .max(java.util.Comparator.comparingLong(
                            batch -> batch.identity().sourceVersion()));
        }

        @Override
        public void insert(DataBatch batch) {
            if (byId.putIfAbsent(batch.batchId(), batch) != null) {
                throw new DataBatchVersionConflictException(
                        byId.get(batch.batchId()).aggregateVersion());
            }
        }

        @Override
        public void save(DataBatch batch, long expectedVersion) {
            byId.compute(batch.batchId(), (ignored, current) -> {
                if (current == null || current.aggregateVersion() != expectedVersion) {
                    throw new DataBatchVersionConflictException(
                            current == null ? 0 : current.aggregateVersion());
                }
                return batch;
            });
            events.add("batch-save");
        }

        @Override
        public Optional<DataBatchIdempotencyResult> find(
                DataBatchIdempotencyScope scope, Instant at) {
            DataBatchIdempotencyResult result = idempotency.get(scope);
            return result == null || !at.isBefore(result.expiresAt())
                    ? Optional.empty() : Optional.of(result);
        }

        @Override
        public DataBatchCommandPrecedence inspect(
                DataBatchIdempotencyScope scope, String requestDigest) {
            DataBatchIdempotencyResult existing = idempotency.get(scope);
            if (existing == null || !NOW.isBefore(existing.expiresAt())) {
                return DataBatchCommandPrecedence.fresh();
            }
            return existing.requestDigest().equals(requestDigest)
                    ? DataBatchCommandPrecedence.replay(existing)
                    : DataBatchCommandPrecedence.mismatch();
        }

        @Override
        public DataBatchAtomicCommandResult receive(ReceiveDataBatchAtomicCommand command) {
            return atomic(command.commit(), () -> {
                DataBatch value = findByIdentity(command.receivedBatch().identity())
                        .orElse(command.receivedBatch());
                if (!byId.containsKey(value.batchId())) insert(value);
                return DataBatchView.from(value);
            });
        }

        @Override
        public DataBatchAtomicCommandResult seal(SealDataBatchAtomicCommand command) {
            return atomic(command.commit(), () -> {
                save(command.sealedBatch(), command.expectedAggregateVersion());
                return DataBatchView.from(command.sealedBatch());
            });
        }

        @Override
        public DataBatchAtomicCommandResult commitQualityEvaluation(
                CommitDataBatchQualityEvaluationCommand command) {
            return atomic(command.commit(), () -> {
                save(command.assessment().updatedBatch(), command.expectedAggregateVersion());
                snapshots.insert(command.assessment().snapshot());
                return DataBatchView.from(command.assessment().updatedBatch());
            });
        }

        @Override
        public DataBatchAtomicCommandResult publish(PublishDataBatchAtomicCommand command) {
            return atomic(command.commit(), () -> {
                save(command.publishedBatch(), command.expectedAggregateVersion());
                return DataBatchView.from(command.publishedBatch());
            });
        }

        private DataBatchAtomicCommandResult atomic(
                DataBatchAtomicCommitContext commit,
                java.util.function.Supplier<DataBatchView> fresh) {
            DataBatchIdempotencyResult existing = idempotency.get(commit.idempotencyScope());
            if (existing != null
                    && commit.occurredAt().instant().isBefore(existing.expiresAt())) {
                if (!existing.requestDigest().equals(commit.requestDigest())) {
                    throw new IngestionQualityApplicationException(
                            "INGESTION_QUALITY_IDEMPOTENCY_MISMATCH");
                }
                return new DataBatchAtomicCommandResult(
                        DataBatchAtomicCommandResult.Status.REPLAY, existing.response());
            }
            DataBatchView response = fresh.get();
            append(new DataBatchAuditEvent(
                    commit.idempotencyScope().commandType().action(), "accepted",
                    response.batchId(), response.aggregateVersion(),
                    commit.idempotencyScope().actorRef(), commit.traceId(),
                    commit.occurredAt().instant(), commit.occurredAt().profile(),
                    commit.requestDigest()));
            complete(new DataBatchIdempotencyResult(
                    commit.idempotencyScope(), commit.requestDigest(), response,
                    commit.occurredAt().instant(),
                    commit.occurredAt().instant().plus(DataBatchCommandService.IDEMPOTENCY_RETENTION)));
            return new DataBatchAtomicCommandResult(
                    DataBatchAtomicCommandResult.Status.ACCEPTED, response);
        }

        @Override
        public DataBatchIdempotencyClaim claim(
                DataBatchIdempotencyScope scope, String requestDigest, Instant at) {
            DataBatchIdempotencyResult existing = idempotency.get(scope);
            if (existing == null || !at.isBefore(existing.expiresAt())) {
                return DataBatchIdempotencyClaim.fresh();
            }
            return existing.requestDigest().equals(requestDigest)
                    ? DataBatchIdempotencyClaim.replay(existing)
                    : DataBatchIdempotencyClaim.mismatch();
        }

        @Override
        public void complete(DataBatchIdempotencyResult result) {
            idempotency.put(result.scope(), result);
            events.add("idempotency-complete");
        }

        @Override
        public DataBatchView execute(java.util.function.Supplier<DataBatchView> work) {
            return work.get();
        }

        @Override
        public DataBatchAuthorizationDecision authorize(DataBatchAuthorizationRequest request) {
            return DataBatchAuthorizationDecision.ALLOW;
        }

        @Override
        public void requireHealthy(String traceId, Instant at) {
            // healthy
        }

        @Override
        public void append(DataBatchAuditEvent event) {
            audits.add(event);
            events.add("audit");
        }
    }
}
